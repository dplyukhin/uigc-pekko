package edu.illinois.osl.uigc

import org.apache.pekko.actor.typed
import edu.illinois.osl.uigc.interfaces.GCMessage

object implicits {
  implicit class ManagedActorRefCast[T](ref: typed.ActorRef[GCMessage[T]]) {

    /** Casts an Akka [[typed.ActorRef]], pointing to a root managed actor, into a managed
      * [[ActorRef]].
      */
    def toManaged[S](implicit ctx: ActorContext[S]): ActorRef[T] =
      ctx.engine.toRootRefob(ref)
  }
}
