package edu.illinois.osl.uigc.engines.mac

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.{ActorRef, Signal, Terminated}
import com.typesafe.config.Config
import edu.illinois.osl.uigc.engines.Engine
import edu.illinois.osl.uigc.engines.mac.jfr.ActorBlockedEvent
import edu.illinois.osl.uigc.interfaces

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable

object MAC {
  type Name = ActorRef[GCMessage[Nothing]]

  private val RC_INC: Long = 255

  case class Refob[-T](target: ActorRef[GCMessage[T]]) extends interfaces.Refob[T] {
    override def typedActorRef: ActorRef[interfaces.GCMessage[T]] =
      target.asInstanceOf[ActorRef[interfaces.GCMessage[T]]]
  }

  ////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////// MESSAGES /////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////

  trait GCMessage[+T] extends interfaces.GCMessage[T]

  case class AppMsg[T](payload: T, refs: Iterable[Refob[Nothing]], isSelfMsg: Boolean)
      extends GCMessage[T]

  private case class DecMsg(weight: Long) extends GCMessage[Nothing] {
    override def refs: Iterable[Refob[Nothing]] = Nil
  }

  private case object IncMsg extends GCMessage[Nothing] {
    override def refs: Iterable[Refob[Nothing]] = Nil
  }

  /**
   * If the cycle detector perceives an actor to be in a cycle, the cycle detector sends
   * the actor this message.
   * @param token a unique identifier for the cycle perceived by the cycle detector
   */
  case class CNF(token: Int) extends GCMessage[Nothing] {
    override def refs: Iterable[Refob[Nothing]] = Nil
  }

  ////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////// STATE //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////

  class State(val self: Refob[Nothing], val kind: SpawnInfo) extends interfaces.State {
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

  sealed trait SpawnInfo extends interfaces.SpawnInfo
  private case object IsRoot extends SpawnInfo
  private case object NonRoot extends SpawnInfo

}

class MAC(system: ExtendedActorSystem) extends Engine {
  import MAC._

  override type GCMessageImpl[+T] = MAC.GCMessage[T]
  override type RefobImpl[-T] = MAC.Refob[T]
  override type SpawnInfoImpl = MAC.SpawnInfo
  override type StateImpl = MAC.State


  val config: Config = system.settings.config
  private val cycleDetectionEnabled: Boolean =
    config.getBoolean("uigc.mac.cycle-detection")

  val Queue: ConcurrentLinkedQueue[CycleDetector.CycleDetectionProtocol] = new ConcurrentLinkedQueue()

  val bookkeeper: org.apache.pekko.actor.ActorRef = {
    if (cycleDetectionEnabled)
      system.systemActorOf(
        org.apache.pekko.actor.Props[CycleDetector]().withDispatcher("my-pinned-dispatcher"),
        "CycleDetector"
      )
    else null
  }


  /** Transform a message from a non-GC actor so that it can be understood by a GC actor.
    * Necessarily, the recipient is a root actor.
    */
  override def rootMessageImpl[T](payload: T, refs: Iterable[Refob[Nothing]]): GCMessage[T] =
    AppMsg(payload, refs, isSelfMsg = false)

  /** Produces SpawnInfo indicating to the actor that it is a root actor.
    */
  override def rootSpawnInfoImpl(): SpawnInfo = IsRoot

  override def toRefobImpl[T](ref: ActorRef[GCMessage[T]]): Refob[T] =
    Refob(ref)

  override def initStateImpl[T](
      context: ActorContext[GCMessage[T]],
      spawnInfo: SpawnInfo
  ): State = {
    val state = new State(Refob(context.self), spawnInfo)
    val pair = new Pair(numRefs = 1, weight = RC_INC)
    state.actorMap(context.self) = pair

    def onBlock(): Unit = {
      if (cycleDetectionEnabled && !state.hasSentBLK) {
        // Copy the names and weights of state.actorMap into an array.
        // Then send it in a BLK message to the cycle detector.
        val array = new Array[(Name, Long)](state.actorMap.size)
        var i = 0
        for ((name, pair) <- state.actorMap) {
          array(i) = (name, pair.weight)
          i += 1
        }
        Queue.add(CycleDetector.BLK(context.self.classicRef, array))
        state.hasSentBLK = true
      }
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

  override def getSelfRefImpl[T](
      state: State,
      context: ActorContext[GCMessage[T]]
  ): Refob[T] =
    state.self.asInstanceOf[Refob[T]]

  override def spawnImpl[S, T](
      factory: SpawnInfo => ActorRef[GCMessage[S]],
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Refob[S] = {
    val actorRef = factory(NonRoot)
    ctx.watch(actorRef)
    val pair = new Pair(numRefs = 1, weight = RC_INC)
    state.actorMap(actorRef) = pair
    val refob = Refob(actorRef)
    refob
  }

  private def unblocked[T](state: State, value: ActorContext[GCMessage[T]]): Unit = {
    if (cycleDetectionEnabled && state.hasSentBLK) {
      state.hasSentBLK = false
      Queue.add(CycleDetector.UNB(value.self.classicRef))
    }
  }

  override def onMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: ActorContext[GCMessage[T]]
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
    case CNF(token) =>
      state.ctrlMsgCount += 1
      if (cycleDetectionEnabled && state.hasSentBLK) {
        Queue.add(CycleDetector.ACK(ctx.self.classicRef, token))
      }
      None
  }

  override def onIdleImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Engine.TerminationDecision =
    tryTerminate(state, ctx)

  override def preSignalImpl[T](
      signal: Signal,
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Unit = ()

  override def postSignalImpl[T](
      signal: Signal,
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Engine.TerminationDecision =
    signal match {
      case signal: Terminated =>
        tryTerminate(state, ctx)
      case signal =>
        Engine.Unhandled
    }

  def tryTerminate[T](
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Engine.TerminationDecision =
    if (
      state.kind == NonRoot && state.rc == 0 && state.pendingSelfMessages == 0 && ctx.children.isEmpty
    )
      Engine.ShouldStop
    else
      Engine.ShouldContinue

  override def createRefImpl[S, T](
      target: Refob[S],
      owner: Refob[Nothing],
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Refob[S] =
    if (target.target == ctx.self) {
      state.rc += 1
      Refob(target.target)
    } else {
      val pair = state.actorMap(target.target)
      if (pair.weight <= 1) {
        pair.weight = pair.weight + RC_INC - 1
        target.target ! IncMsg
      } else {
        pair.weight -= 1
      }
      Refob(target.target)
    }

  override def releaseImpl[S, T](
      releasing: Iterable[Refob[S]],
      state: State,
      ctx: ActorContext[GCMessage[T]]
  ): Unit = {
    val it = releasing.iterator
    while (it.hasNext) {
      val ref = it.next()
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
  }

  override def sendMessageImpl[T, S](
      ref: Refob[T],
      msg: T,
      refs: Iterable[Refob[Nothing]],
      state: State,
      ctx: ActorContext[GCMessage[S]]
  ): Unit = {
    val isSelfMsg = ref.target == state.self.target
    if (isSelfMsg) {
      state.pendingSelfMessages += 1
    }
    ref.target ! AppMsg(msg, refs, isSelfMsg)
  }

}
