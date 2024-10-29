package org.apache.pekko.uigc.actor.typed

import org.apache.pekko.actor.typed.{ExtensibleBehavior, Signal, TypedActorContext, scaladsl}
import org.apache.pekko.uigc.engines.Engine
import org.apache.pekko.uigc.interfaces.GCMessage
import org.apache.pekko.uigc.actor.typed.scaladsl.ActorContext

abstract class AbstractBehavior[T](context: ActorContext[T])
    extends ExtensibleBehavior[GCMessage[T]] {

  implicit val _context: ActorContext[T] = context

  // User API
  def onMessage(msg: T): Behavior[T]
  def onSignal: PartialFunction[Signal, Behavior[T]] = PartialFunction.empty

  override final def receive(
      ctx: TypedActorContext[GCMessage[T]],
      msg: GCMessage[T]
  ): Behavior[T] = {
    val appMsg = context.engine.preMessage(msg, context.state, context.typedContext.classicActorContext)

    val result = appMsg match {
      case Some(msg) => onMessage(msg)
      case None      => scaladsl.Behaviors.same[GCMessage[T]]
    }

    context.engine.postMessage(msg, context.state, context.typedContext.classicActorContext) match {
      case _: Engine.ShouldStop.type     => scaladsl.Behaviors.stopped
      case _: Engine.ShouldContinue.type => result
      case _: Engine.Unhandled.type      => result
    }
  }

  override final def receiveSignal(
      ctx: TypedActorContext[GCMessage[T]],
      msg: Signal
  ): Behavior[T] = {
    context.engine.preSignal(msg, context.state, context.typedContext.classicActorContext)

    val result =
      onSignal.applyOrElse(
        msg,
        { case _ => scaladsl.Behaviors.unhandled }: PartialFunction[Signal, Behavior[T]]
      )

    context.engine.postSignal(msg, context.state, context.typedContext.classicActorContext) match {
      case _: Engine.Unhandled.type  => result
      case _: Engine.ShouldStop.type => scaladsl.Behaviors.stopped
      case _: Engine.ShouldContinue.type =>
        if (result == scaladsl.Behaviors.unhandled)
          scaladsl.Behaviors.same
        else
          result
    }
  }

}
