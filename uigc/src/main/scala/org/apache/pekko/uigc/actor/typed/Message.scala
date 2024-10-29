package org.apache.pekko.uigc.actor.typed

trait Message {
  def refs: Iterable[ActorRef[_]]
}

trait NoRefs extends Message {
  override def refs: Seq[Nothing] = Nil
}