package org.apache.pekko.uigc.engines.wrc

import org.apache.pekko.actor
import org.apache.pekko.actor.ExtendedActorSystem
import com.typesafe.config.Config
import org.apache.pekko.actor.typed
import org.apache.pekko.uigc.engines.Engine
import org.apache.pekko.uigc.engines.wrc.jfr.ActorBlockedEvent
import org.apache.pekko.uigc.{interfaces => uigc}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable

object WRC {
  type Name = actor.ActorRef

  private val RC_INC: Long = 255

  case class RefInfo(target: actor.ActorRef) extends uigc.RefInfo {
    override def ref: actor.ActorRef = target
  }

  ////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////// MESSAGES /////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////

  trait GCMessage[+T] extends uigc.GCMessage[T]

  case class AppMsg[T](payload: T, refs: Iterable[RefInfo], isSelfMsg: Boolean)
      extends GCMessage[T]

  private case class DecMsg(weight: Long) extends GCMessage[Nothing] {
    override def refs: Iterable[RefInfo] = Nil
  }

  private case object IncMsg extends GCMessage[Nothing] {
    override def refs: Iterable[RefInfo] = Nil
  }

  ////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////// STATE //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////

  class State(val self: RefInfo, val kind: SpawnInfo) extends uigc.State {
    val actorMap: mutable.HashMap[Name, Pair] = mutable.HashMap()
    var rc: Long = RC_INC
    var pendingSelfMessages: Long = 0
    var hasSentBLK: Boolean = false

    // We keep the data below just for metrics.
    var appMsgCount: Int = 0
    var ctrlMsgCount: Int = 0
  }

  class Pair(
    var numRefs: Long = 0,
    var weight: Long = 0
  )

  sealed trait SpawnInfo extends uigc.SpawnInfo
  private case object IsRoot extends SpawnInfo
  private case object NonRoot extends SpawnInfo

}

class WRC(system: ExtendedActorSystem) extends Engine {
  import WRC._

  override type GCMessageImpl[+T] = WRC.GCMessage[T]
  override type ActorRefImpl = WRC.RefInfo
  override type SpawnInfoImpl = WRC.SpawnInfo
  override type StateImpl = WRC.State

  val config: Config = system.settings.config

  /** Transform a message from a non-GC actor so that it can be understood by a GC actor.
    * Necessarily, the recipient is a root actor.
    */
  override def rootMessageImpl[T](payload: T, refs: Iterable[RefInfo]): GCMessage[T] =
    AppMsg(payload, refs, isSelfMsg = false)

  /** Produces SpawnInfo indicating to the actor that it is a root actor.
    */
  override def rootSpawnInfoImpl(): SpawnInfo = IsRoot

  override def initStateImpl(
      context: actor.ActorContext,
      spawnInfo: SpawnInfo
  ): State = {
    val state = new State(RefInfo(context.self), spawnInfo)
    val pair = new Pair(numRefs = 1, weight = RC_INC)
    state.actorMap(context.self) = pair

    def onBlock(): Unit = {
      // Record metrics.
      val event = new ActorBlockedEvent()
      event.appMsgCount = state.appMsgCount
      event.ctrlMsgCount = state.ctrlMsgCount
      state.appMsgCount = 0
      state.ctrlMsgCount = 0
      event.commit()
    }

    context.queue.onFinishedProcessingHook = onBlock

    state
  }

  override def getSelfRefInfoImpl(
      state: State,
      context: actor.ActorContext
  ): RefInfo =
    state.self

  override def spawnImpl(
      factory: SpawnInfo => actor.ActorRef,
      state: State,
      ctx: actor.ActorContext
  ): RefInfo = {
    val actorRef = factory(NonRoot)
    ctx.watch(actorRef)
    val pair = new Pair(numRefs = 1, weight = RC_INC)
    state.actorMap(actorRef) = pair
    RefInfo(actorRef)
  }

  private def unblocked(state: State, ctx: actor.ActorContext): Unit = {
  }

  override def preMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Option[T] = msg match {
    case AppMsg(payload, refs, isSelfMsg) =>
      unblocked(state, ctx)
      state.appMsgCount += 1
      if (isSelfMsg) {
        state.pendingSelfMessages -= 1
      }
      val it = refs.iterator
      while (it.hasNext) {
        val ref = it.next()
        val pair = state.actorMap.getOrElseUpdate(ref.target, new Pair())
        pair.numRefs = pair.numRefs + 1
        pair.weight = pair.weight + 1
      }
      Some(payload)
    case DecMsg(weight) =>
      unblocked(state, ctx)
      state.ctrlMsgCount += 1
      state.rc = state.rc - weight
      None
    case IncMsg =>
      unblocked(state, ctx)
      state.ctrlMsgCount += 1
      state.rc = state.rc + RC_INC
      None
  }

  override def postMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Engine.TerminationDecision =
    tryTerminate(state, ctx)

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
    signal match {
      case _: typed.Terminated =>
        tryTerminate(state, ctx)
      case _ =>
        Engine.Unhandled
    }

  private def tryTerminate(
      state: State,
      ctx: actor.ActorContext
  ): Engine.TerminationDecision =
    if (
      state.kind == NonRoot && state.rc == 0 && state.pendingSelfMessages == 0
    ) {
      // At this point, we know the actor is quiescent, so it's safe to deactivate all references.
      // But if the actor has children, we can't actually stop it yet; stopping an actor causes children
      // to stop, and some of those children might not be garbage.
      deactivateAll(state, ctx)
      if (ctx.children.isEmpty)
        Engine.ShouldStop
      else {
        Engine.ShouldContinue
      }
    } else {
      Engine.ShouldContinue
    }

  private def deactivateAll(state: State, ctx: actor.ActorContext): Unit = {
    for ((target, pair) <- state.actorMap) {
      if (target != ctx.self) {
        target ! DecMsg(pair.weight)
      }
    }
    state.actorMap.clear()
  }

  override def createRefImpl(
                              target: RefInfo,
                              owner: RefInfo,
                              state: State,
                              ctx: actor.ActorContext
  ): RefInfo =
    if (target.target == ctx.self) {
      state.rc += 1
      RefInfo(target.target)
    } else {
      val pair = state.actorMap(target.target)
      if (pair.weight <= 1) {
        pair.weight = pair.weight + RC_INC - 1
        target.target ! IncMsg
      } else {
        pair.weight -= 1
      }
      RefInfo(target.target)
    }

  override def deactivateImpl(
                               ref: RefInfo,
                               state: State,
                               ctx: actor.ActorContext
  ): Unit = {
    if (ref.target == ctx.self) {
      state.rc -= 1
    } else {
      val pair = state.actorMap(ref.target)
      if (pair.numRefs <= 1) {
        ref.target ! DecMsg(pair.weight)
        state.actorMap.remove(ref.target)
      } else {
        pair.numRefs -= 1
      }
    }
  }

  override def sendMessageImpl(
                                ref: RefInfo,
                                msg: Any,
                                refs: Iterable[RefInfo],
                                state: State,
                                ctx: actor.ActorContext
  ): Unit = {
    val isSelfMsg = ref.target == state.self.target
    if (isSelfMsg) {
      state.pendingSelfMessages += 1
    }
    ref.target ! AppMsg(msg, refs, isSelfMsg)
  }

}
