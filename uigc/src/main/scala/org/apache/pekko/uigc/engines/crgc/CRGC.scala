package org.apache.pekko.uigc.engines.crgc

import org.apache.pekko.actor
import org.apache.pekko.actor.{Address, ExtendedActorSystem}
import org.apache.pekko.remote.artery.{InboundEnvelope, ObjectPool, OutboundEnvelope, ReusableOutboundEnvelope}
import org.apache.pekko.stream.stage.GraphStageLogic
import org.apache.pekko.stream.{FlowShape, Inlet, Outlet}
import com.typesafe.config.Config
import org.apache.pekko.uigc.engines.crgc.jfr.EntrySendEvent
import org.apache.pekko.uigc.engines.{Engine, crgc}
import org.apache.pekko.uigc.{interfaces => uigc}

import java.util.concurrent.ConcurrentLinkedQueue

object CRGC {

  val EntryPool: ConcurrentLinkedQueue[Entry] = new ConcurrentLinkedQueue[Entry]()

  trait CollectionStyle

  class SpawnInfo(
      var creator: Option[WrappedActorRef]
  ) extends uigc.SpawnInfo with Serializable

  case object Wave extends CollectionStyle

  private case object OnBlock extends CollectionStyle

  private case object OnIdle extends CollectionStyle

}

class CRGC(system: ExtendedActorSystem) extends Engine {
  import CRGC._

  override type GCMessageImpl[+T] = crgc.GCMessage[T]
  override type ActorRefImpl = crgc.WrappedActorRef
  override type SpawnInfoImpl = SpawnInfo
  override type StateImpl = crgc.State

  val config: Config = system.settings.config
  val collectionStyle: CollectionStyle =
    config.getString("uigc.crgc.collection-style") match {
      case "wave"     => Wave
      case "on-block" => OnBlock
      case "on-idle"  => OnIdle
    }
  val crgcContext = new Context(config)

  // This could be split into multiple queues if contention becomes high
  val Queue: ConcurrentLinkedQueue[Entry] = new ConcurrentLinkedQueue[Entry]()

  val bookkeeper: org.apache.pekko.actor.ActorRef =
    system.systemActorOf(
      org.apache.pekko.actor.Props[LocalGC]().withDispatcher("my-pinned-dispatcher"),
      "Bookkeeper"
    )

  override def rootMessageImpl[T](payload: T, refs: Iterable[WrappedActorRef]): GCMessage[T] =
    AppMsg(payload, refs)

  override def rootSpawnInfoImpl(): SpawnInfo =
    new SpawnInfo(None)

  override def initStateImpl(
      context: actor.ActorContext,
      spawnInfo: SpawnInfo
  ): State = {
    val self = context.self
    val selfRefob = new WrappedActorRef(self, targetShadow = null)
    val state = new State(selfRefob, crgcContext)
    state.recordNewRefob(selfRefob, selfRefob)
    spawnInfo.creator match {
      case Some(creator) =>
        state.recordNewRefob(creator, selfRefob)
      case None =>
        state.markAsRoot()
    }

    def onBlock(): Unit =
      sendEntry(state, isBusy=false)

    if (collectionStyle == OnBlock)
      context.queue.onFinishedProcessingHook = onBlock
    if ((collectionStyle == Wave && state.isRoot) || collectionStyle == OnIdle)
      sendEntry(state, isBusy=false)
    state
  }

  override def getSelfRefImpl(
      state: State,
      context: actor.ActorContext
  ): WrappedActorRef =
    state.self.asInstanceOf[WrappedActorRef]

  override def spawnImpl(
      factory: SpawnInfo => actor.ActorRef,
      state: State,
      ctx: actor.ActorContext
  ): WrappedActorRef = {
    val child = factory(new SpawnInfo(Some(state.self)))
    val ref = new WrappedActorRef(child, null)
    // NB: "onCreate" is only updated at the child, not the parent.
    if (!state.canRecordNewActor)
      sendEntry(state, isBusy=true)
    state.recordNewActor(ref)
    ref
  }

  override def preMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Option[T] =
    msg match {
      case AppMsg(payload, _) =>
        if (!state.canRecordMessageReceived)
          sendEntry(state, isBusy=true)
        state.recordMessageReceived()
        Some(payload)
      case _ =>
        None
    }

  override def postMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Engine.TerminationDecision =
    msg match {
      case StopMsg =>
        Engine.ShouldStop
      case WaveMsg =>
        sendEntry(state, isBusy=false)
        val it = ctx.children.iterator
        while (it.hasNext) {
          val child = it.next()
          child.tell(WaveMsg)
        }
        Engine.ShouldContinue
      case _ =>
        if (collectionStyle == OnIdle)
          sendEntry(state, isBusy=false)
        Engine.ShouldContinue
    }

  override def createRefImpl(
                                    target: WrappedActorRef,
                                    owner: WrappedActorRef,
                                    state: State,
                                    ctx: actor.ActorContext
  ): WrappedActorRef = {
    val ref = new WrappedActorRef(target.target, target.targetShadow)
    if (!state.canRecordNewRefob)
      sendEntry(state, isBusy=true)
    state.recordNewRefob(owner, target)
    ref
  }

  override def releaseImpl(
                                  ref: WrappedActorRef,
                                  state: State,
                                  ctx: actor.ActorContext
  ): Unit = {
    if (!state.canRecordUpdatedRefob(ref))
      sendEntry(state, isBusy=true)
    ref.deactivate()
    state.recordUpdatedRefob(ref)
  }

  private def sendEntry(
      state: State,
      isBusy: Boolean
  ): Unit = {
    val metrics = new EntrySendEvent()
    metrics.begin()
    var entry = CRGC.EntryPool.poll()
    if (entry == null) {
      entry = new Entry(crgcContext)
      metrics.allocatedMemory = true
    }
    state.flushToEntry(isBusy, entry)
    Queue.add(entry)
    metrics.commit()
  }

  override def preSignalImpl(
      signal: Any,
      state: State,
      ctx: actor.ActorContext
  ): Unit = ()

  override def postSignalImpl(
      signal: Any,
      state: State,
      ctx: actor.ActorContext
  ): Engine.TerminationDecision =
    Engine.Unhandled

  override def sendMessageImpl(
                                      ref: WrappedActorRef,
                                      msg: Any,
                                      refs: Iterable[WrappedActorRef],
                                      state: State,
                                      ctx: actor.ActorContext
  ): Unit = {
    if (!ref.canIncSendCount || !state.canRecordUpdatedRefob(ref))
      sendEntry(state, isBusy=true)
    ref.incSendCount()
    state.recordUpdatedRefob(ref)

    ref.target ! AppMsg(msg, refs)
  }

  override def spawnEgress(
      in: Inlet[OutboundEnvelope],
      out: Outlet[OutboundEnvelope],
      shape: FlowShape[OutboundEnvelope, OutboundEnvelope],
      system: ExtendedActorSystem,
      adjacent: Address,
      outboundObjectPool: ObjectPool[ReusableOutboundEnvelope]
  ): GraphStageLogic =
    new Egress(in, out, shape, system, adjacent, outboundObjectPool)

  override def spawnIngress(
      in: Inlet[InboundEnvelope],
      out: Outlet[InboundEnvelope],
      shape: FlowShape[InboundEnvelope, InboundEnvelope],
      system: ExtendedActorSystem,
      adjacent: Address
  ): GraphStageLogic =
    new MultiIngress(in, out, shape, system, adjacent)

}
