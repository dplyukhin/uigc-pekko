package org.apache.pekko.uigc.engines.drl

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.uigc.interfaces

/** An opaque and globally unique token.
  */
case class Token(ref: Name, n: Int)

case class Refob[-T](
    token: Option[Token],
    owner: Option[ActorRef[GCMessage[Nothing]]],
    target: ActorRef[GCMessage[T]]
) extends interfaces.Refob[T] {
  override def typedActorRef: ActorRef[interfaces.GCMessage[T]] =
    target.asInstanceOf[ActorRef[interfaces.GCMessage[T]]]
}
