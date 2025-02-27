package org.apache.pekko.uigc.actor.typed.scaladsl

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.BehaviorInterceptor.ReceiveTarget
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.{BehaviorInterceptor, TypedActorContext, scaladsl}
import org.apache.pekko.uigc.UIGC
import org.apache.pekko.uigc.interfaces.{GCMessage, SpawnInfo}
import org.apache.pekko.uigc.actor.typed._

import scala.reflect.ClassTag

object Behaviors {

  /** Sets up a garbage-collected actor. The [[ActorFactory]] instance produced by this method can
    * only be used by *GC-aware* actors.
    */
  def setup[T <: Message](factory: ActorContext[T] => Behavior[T]): ActorFactory[T] =
    (info: SpawnInfo) =>
      scaladsl.Behaviors.setup(context => factory(new ActorContext(context, info)))

  private class RootAdapter[T <: Message](implicit
      interceptMessageClassTag: ClassTag[T]
  ) extends BehaviorInterceptor[T, GCMessage[T]] {
    def aroundReceive(
        ctx: TypedActorContext[T],
        msg: T,
        target: ReceiveTarget[GCMessage[T]]
    ): Behavior[T] = {
      val rootMsg = UIGC(ctx.asScala.system).rootMessage(msg, msg.refs.map(_.refInfo))
      target.apply(ctx, rootMsg)
    }
  }

  /** Sets up a root actor. Root actors are GC-aware actors that act as "entry points" to a
    * garbage-collected subsystem. Although root actors themselves must be terminated manually,
    * their descendants will all be terminated automatically---as long as those descendants only
    * interact with other GC-aware actors.
    */
  def setupRoot[T <: Message](
      factory: ActorContext[T] => Behavior[T]
  )(implicit classTag: ClassTag[T]): typed.Behavior[T] = {

    val b: Behavior[T] = scaladsl.Behaviors.setup(context =>
      factory(new ActorContext(context, UIGC(context.system).rootSpawnInfo()))
    )

    scaladsl.Behaviors.intercept(() => new RootAdapter[T]())(b)
  }

  /** Allows an actor to schedule messages to itself, like [[scaladsl.Behaviors.withTimers]].
    * However, this API is only allowed for root actors---see [[setupRoot]].
    */
  def withTimers[T <: Message](factory: TimerScheduler[T] => typed.Behavior[T]): typed.Behavior[T] =
    scaladsl.Behaviors.withTimers(factory)

  /** Returns a behavior that releases all its references, ignores all messages, and waits to be
    * terminated by the garbage collector.
    */
  def stopped[T <: Message](context: ActorContext[T]): Behavior[T] = scaladsl.Behaviors.stopped
}
