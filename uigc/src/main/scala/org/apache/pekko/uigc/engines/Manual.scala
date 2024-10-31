package org.apache.pekko.uigc.engines

import org.apache.pekko.actor
import org.apache.pekko.uigc.{interfaces => uigc}

object Manual {
  trait SpawnInfo extends uigc.SpawnInfo

  case class GCMessage[+T](payload: T, refs: Iterable[WrappedActorRef]) extends uigc.GCMessage[T]

  case class WrappedActorRef(target: actor.ActorRef) extends uigc.RefInfo(target)

  case object Info extends SpawnInfo

  class State(val selfRef: WrappedActorRef) extends uigc.State
}

class Manual extends Engine {
  import Manual._

  override type ActorRefImpl = Manual.WrappedActorRef
  override type GCMessageImpl[T] = Manual.GCMessage[T]
  override type SpawnInfoImpl = Manual.SpawnInfo
  override type StateImpl = Manual.State

  /** Transform a message from a non-GC actor so that it can be understood by a GC actor.
    * Necessarily, the recipient is a root actor.
    */
  override def rootMessageImpl[T](payload: T, refs: Iterable[ActorRefImpl]): GCMessage[T] =
    GCMessage(payload, refs)

  /** Produces SpawnInfo indicating to the actor that it is a root actor.
    */
  def rootSpawnInfoImpl(): SpawnInfo = Info

  override def initStateImpl(context: actor.ActorContext, spawnInfo: SpawnInfo): State =
    new State(WrappedActorRef(context.self))

  override def getSelfRefInfoImpl(
      state: State,
      context: actor.ActorContext
  ): WrappedActorRef =
    state.selfRef

  override def spawnImpl(
      factory: SpawnInfo => actor.ActorRef,
      state: State,
      ctx: actor.ActorContext
  ): WrappedActorRef =
    WrappedActorRef(factory(Info))

  override def preMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Option[T] =
    Some(msg.payload)

  override def postMessageImpl[T](
      msg: GCMessage[T],
      state: State,
      ctx: actor.ActorContext
  ): Engine.TerminationDecision =
    Engine.ShouldContinue

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

  override def createRefImpl(
      target: WrappedActorRef,
      owner: WrappedActorRef,
      state: State,
      ctx: actor.ActorContext
  ): WrappedActorRef =
    WrappedActorRef(target.target)

  override def deactivateImpl(
      releasing: WrappedActorRef,
      state: State,
      ctx: actor.ActorContext
  ): Unit = ()

  override def sendMessageImpl(
      ref: WrappedActorRef,
      msg: Any,
      refs: Iterable[WrappedActorRef],
      state: State,
      ctx: actor.ActorContext
  ): Unit =
    ref.target ! GCMessage(msg, refs)

}
