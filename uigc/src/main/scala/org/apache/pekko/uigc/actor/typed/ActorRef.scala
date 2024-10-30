package org.apache.pekko.uigc.actor.typed

import org.apache.pekko.actor.ActorPath
import org.apache.pekko.actor.typed.internal.adapter.ActorRefAdapter
import org.apache.pekko.uigc.actor.typed.scaladsl.ActorContext
import org.apache.pekko.uigc.{interfaces => uigc}

import scala.annotation.unchecked.uncheckedVariance

class ActorRef[-T <: Message] private[pekko] (private[pekko] val ref: uigc.ActorRef) {

  def name: ActorName = ActorRefAdapter(ref.ref)

  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: T, ctx: ActorContext[_]): Unit = this.!(msg)(ctx)

  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def !(msg: T)(implicit ctx: ActorContext[_]): Unit =
    ref.tell(msg, msg.refs.map(_.ref), ctx.engine, ctx.state, ctx.typedContext.classicActorContext)

  /**
   * Narrow the type of this `ActorRef`, which is always a safe operation.
   */
  def narrow[U <: T]: ActorRef[U] = this

  /**
   * Unsafe utility method for widening the type accepted by this ActorRef;
   * provided to avoid having to use `asInstanceOf` on the full reference type,
   * which would unfortunately also work on non-ActorRefs. Use it with caution,it may cause a [[java.lang.ClassCastException]] when you send a message
   * to the widened actorRef.
   */
  def unsafeUpcast[U >: T @uncheckedVariance <: Message]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  /**
   * The hierarchical path name of the referenced Actor. The lifecycle of the
   * ActorRef is fully contained within the lifecycle of the [[ActorPath]]
   * and more than one Actor instance can exist with the same path at different
   * points in time, but not concurrently.
   */
  def path: ActorPath = ref.path

  def compareTo(o: ActorRef[_]): Int = ref.compareTo(o.ref)
}
