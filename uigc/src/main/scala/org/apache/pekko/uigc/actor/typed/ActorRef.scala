package org.apache.pekko.uigc.actor.typed

import org.apache.pekko.actor.{ActorPath, typed}
import org.apache.pekko.uigc.{interfaces => uigc}

class ActorRef[-T] private[pekko] (private[pekko] val ref: uigc.ActorRef) {

  /**
   * Send a message to the Actor referenced by this ActorRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: T): Unit = ref ! msg

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
  def unsafeUpcast[U >: T]: ActorRef[U] = this.asInstanceOf[ActorRef[U]]

  /**
   * The hierarchical path name of the referenced Actor. The lifecycle of the
   * ActorRef is fully contained within the lifecycle of the [[pekko.actor.ActorPath]]
   * and more than one Actor instance can exist with the same path at different
   * points in time, but not concurrently.
   */
  def path: ActorPath = ref.path

  def compareTo(o: ActorRef[_]): Int = ref.compareTo(o.ref)
}
