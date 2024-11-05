package org.apache.pekko.uigc.actor

import org.apache.pekko.uigc.interfaces.{GCMessage, SpawnInfo}
import org.apache.pekko.uigc.actor.typed.scaladsl.{ActorContext, Behaviors}

package object typed {

  /** Convenience object that re-exports the unmanaged typed API. */
  object unmanaged {
    import org.apache.pekko.actor.typed
    import org.apache.pekko.actor.typed.scaladsl
    type ActorRef[-T] = typed.ActorRef[T]
    type Behavior[T] = typed.Behavior[T]
    type ActorContext[T] = scaladsl.ActorContext[T]
    val Behaviors: scaladsl.Behaviors.type = scaladsl.Behaviors
  }

  type Behavior[T] = unmanaged.Behavior[GCMessage[T]]
  type ActorFactory[T] = SpawnInfo => Behavior[T]
  type ActorName = unmanaged.ActorRef[Nothing]

  object RemoteSpawner {
    trait Command[T] extends Serializable
    case class Spawn[T](
        factory: String,
        info: SpawnInfo,
        replyTo: unmanaged.ActorRef[unmanaged.ActorRef[GCMessage[T]]]
    ) extends Command[T]

    def apply[T <: Message](
        factories: Map[String, ActorContext[T] => Behavior[T]]
    ): unmanaged.Behavior[Command[T]] =
      unmanaged.Behaviors.receive { (ctx, msg) =>
        msg match {
          case Spawn(key, info, replyTo) =>
            val refob = ctx.spawnAnonymous(Behaviors.setup(factories(key))(info))
            replyTo ! refob
            unmanaged.Behaviors.same
        }
      }
  }
}
