package org.apache.pekko.uigc.engines.crgc

import org.apache.pekko.uigc.{interfaces => uigc}

sealed trait GCMessage[+T] extends uigc.GCMessage[T]

final case class AppMsg[+T](
    payload: T,
    refs: Iterable[WrappedActorRef]
) extends GCMessage[T] {
  var windowID: Int = -1
  // This field is set by the egress if the message gets sent to another node.
}

case object StopMsg extends GCMessage[Any] {
  override def refs: Iterable[WrappedActorRef] = Nil
}

case object WaveMsg extends GCMessage[Any] {
  override def refs: Iterable[WrappedActorRef] = Nil
}
