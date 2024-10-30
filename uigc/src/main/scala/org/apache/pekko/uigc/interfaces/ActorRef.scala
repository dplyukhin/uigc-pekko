package org.apache.pekko.uigc.interfaces

import org.apache.pekko.actor
import org.apache.pekko.uigc.engines.Engine

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
  def tell(msg: Any, refs: Iterable[ActorRef], engine: Engine, state: State, ctx: actor.ActorContext): Unit =
    engine.sendMessage(this, msg, refs, state, ctx)

  override def compareTo(o: ActorRef): Int = ref.compareTo(o.ref)
}
