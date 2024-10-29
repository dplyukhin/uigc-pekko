package org.apache.pekko.uigc.engines

import org.apache.pekko.actor
import org.apache.pekko.actor.{Address, ExtendedActorSystem, Extension}
import org.apache.pekko.remote.artery.{InboundEnvelope, ObjectPool, OutboundEnvelope, ReusableOutboundEnvelope}
import org.apache.pekko.stream.stage.{GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{FlowShape, Inlet, Outlet}
import org.apache.pekko.uigc.{interfaces => uigc}

object Engine {
  sealed trait TerminationDecision
  case object ShouldStop extends TerminationDecision
  case object ShouldContinue extends TerminationDecision
  case object Unhandled extends TerminationDecision
}

/** A GC engine is a collection of hooks and datatypes, used by the UIGC API. */
trait Engine extends Extension {
  type ActorRefImpl <: uigc.ActorRef
  type GCMessageImpl[T] <: uigc.GCMessage[T]
  type SpawnInfoImpl <: uigc.SpawnInfo
  type StateImpl <: uigc.State

  /** Transform a message from a non-GC actor so that it can be understood by a GC actor.
    * Necessarily, the recipient is a root actor.
    */
  final def rootMessage[T](payload: T, refs: Iterable[uigc.ActorRef]): uigc.GCMessage[T] =
    rootMessageImpl[T](payload, refs.asInstanceOf[Iterable[ActorRefImpl]])

  def rootMessageImpl[T](payload: T, refs: Iterable[ActorRefImpl]): GCMessageImpl[T]

  /** Produces SpawnInfo indicating to the actor that it is a root actor.
    */
  final def rootSpawnInfo(): uigc.SpawnInfo =
    rootSpawnInfoImpl()

  def rootSpawnInfoImpl(): SpawnInfoImpl

  /** Compute the initial GC state of a managed actor.
    */
  final def initState(context: actor.ActorContext, spawnInfo: uigc.SpawnInfo): uigc.State =
    initStateImpl(context, spawnInfo.asInstanceOf[SpawnInfoImpl])

  def initStateImpl(context: actor.ActorContext, spawnInfo: SpawnInfoImpl): StateImpl

  /** Get a refob owned by this actor, pointing to itself.
    */
  final def getSelfRef(state: uigc.State, context: actor.ActorContext): uigc.ActorRef =
    getSelfRefImpl(state.asInstanceOf[StateImpl], context)

  def getSelfRefImpl(state: StateImpl, context: actor.ActorContext): ActorRefImpl

  /** Spawn a managed actor. */
  final def spawn(factory: uigc.SpawnInfo => actor.ActorRef, state: uigc.State, ctx: actor.ActorContext ): uigc.ActorRef =
    spawnImpl(factory, state.asInstanceOf[StateImpl], ctx)

  def spawnImpl(factory: SpawnInfoImpl => actor.ActorRef, state: StateImpl, ctx: actor.ActorContext): ActorRefImpl

  /** Send a message to a managed actor. */
  def sendMessage(ref: uigc.ActorRef, msg: Any, refs: Iterable[uigc.ActorRef], state: uigc.State, ctx: actor.ActorContext): Unit =
    sendMessageImpl(
      ref.asInstanceOf[ActorRefImpl],
      msg,
      refs.asInstanceOf[Iterable[ActorRefImpl]],
      state.asInstanceOf[StateImpl],
      ctx
    )

  def sendMessageImpl(ref: ActorRefImpl, msg: Any, refs: Iterable[ActorRefImpl], state: StateImpl, ctx: actor.ActorContext) : Unit

  def preMessage[T](msg: uigc.GCMessage[T], state: uigc.State, ctx: actor.ActorContext): Option[T] =
    preMessageImpl(msg.asInstanceOf[GCMessageImpl[T]], state.asInstanceOf[StateImpl], ctx)

  def preMessageImpl[T](msg: GCMessageImpl[T], state: StateImpl, ctx: actor.ActorContext): Option[T]

  def postMessage[T](msg: uigc.GCMessage[T], state: uigc.State, ctx: actor.ActorContext): Engine.TerminationDecision =
    postMessageImpl(msg.asInstanceOf[GCMessageImpl[T]], state.asInstanceOf[StateImpl], ctx)

  def postMessageImpl[T](msg: GCMessageImpl[T], state: StateImpl, ctx: actor.ActorContext): Engine.TerminationDecision

  def preSignal(signal: Any, state: uigc.State, ctx: actor.ActorContext): Unit =
    preSignalImpl(signal, state.asInstanceOf[StateImpl], ctx)

  def preSignalImpl(signal: Any, state: StateImpl, ctx: actor.ActorContext): Unit

  def postSignal(signal: Any, state: uigc.State, ctx: actor.ActorContext): Engine.TerminationDecision =
    postSignalImpl(signal, state.asInstanceOf[StateImpl], ctx)

  def postSignalImpl(signal: Any, state: StateImpl, ctx: actor.ActorContext): Engine.TerminationDecision

  def createRef(target: uigc.ActorRef, owner: uigc.ActorRef, state: uigc.State, ctx: actor.ActorContext): uigc.ActorRef =
    createRefImpl(target.asInstanceOf[ActorRefImpl], owner.asInstanceOf[ActorRefImpl], state.asInstanceOf[StateImpl], ctx)

  def createRefImpl(target: ActorRefImpl, owner: ActorRefImpl, state: StateImpl, ctx: actor.ActorContext): ActorRefImpl

  def deactivate(releasing: uigc.ActorRef, state: uigc.State, ctx: actor.ActorContext): Unit =
    deactivateImpl(releasing.asInstanceOf[ActorRefImpl], state.asInstanceOf[StateImpl], ctx)

  def deactivateImpl(releasing: ActorRefImpl, state: StateImpl, ctx: actor.ActorContext): Unit

  def spawnIngress(
      in: Inlet[InboundEnvelope],
      out: Outlet[InboundEnvelope],
      shape: FlowShape[InboundEnvelope, InboundEnvelope],
      system: ExtendedActorSystem,
      adjacent: Address
  ): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val msg = grab(in)
            push(out, msg)
          }
        }
      )
      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit =
            pull(in)
        }
      )
    }

  def spawnEgress(
      in: Inlet[OutboundEnvelope],
      out: Outlet[OutboundEnvelope],
      shape: FlowShape[OutboundEnvelope, OutboundEnvelope],
      system: ExtendedActorSystem,
      adjacent: Address,
      outboundObjectPool: ObjectPool[ReusableOutboundEnvelope]
  ): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val msg = grab(in)
            push(out, msg)
          }
        }
      )
      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit =
            pull(in)
        }
      )
    }
}
