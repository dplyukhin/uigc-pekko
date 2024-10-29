package org.apache.pekko.uigc.interfaces

import org.apache.pekko.actor

class ActorRef(var ref: actor.ActorRef) extends Comparable[ActorRef] with Serializable {
  /**
   * Returns the path for this actor (from this actor up to the root actor).
   */
  def path: actor.ActorPath = ref.path

  /**
   * Scala API: Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * Unlike unmanaged actor refs, the sender is not implicitly attached to the message.
   * <p/>
   */
  def !(message: Any): Unit =
    ref.tell(message, actor.Actor.noSender)

  override def compareTo(o: ActorRef): Int = ref.compareTo(o.ref)
}
