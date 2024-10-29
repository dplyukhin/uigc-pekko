package org.apache.pekko.uigc.actor.typed.scaladsl

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import org.apache.pekko.uigc.engines.Engine
import org.apache.pekko.uigc.UIGC
import org.apache.pekko.uigc.interfaces.{GCMessage, SpawnInfo, State}
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

/** A version of [[scaladsl.ActorContext]] used by garbage-collected actors. Provides methods for
  * spawning garbage-collected actors, creating new references, and releasing references. Also
  * stores GC-related local state of the actor. By keeping GC state in the [[ActorContext]],
  * garbage-collected actors can safely change their behavior by passing their [[ActorContext]] to
  * the behavior they take on.
  */
class ActorContext[T](
    val typedContext: scaladsl.ActorContext[GCMessage[T]],
    val spawnInfo: SpawnInfo
) {

  private[pekko] val engine: Engine = UIGC(typedContext.system)

  private[pekko] val state: State = engine.initState(typedContext.classicActorContext, spawnInfo)

  val self: ActorRef[T] = {
    val ref = engine.getSelfRef(state, typedContext.classicActorContext)
    new ActorRef[T](ref)
  }

  def system: ActorSystem[Nothing] = ActorSystem(typedContext.system)

  /** Spawn a new named actor into the GC system.
    *
    * @param factory
    *   The behavior factory for the spawned actor.
    * @param name
    *   The name of the spawned actor.
    * @tparam S
    *   The type of application-level messages to be handled by the new actor.
    * @return
    *   An [[ActorRef]] for the spawned actor.
    */
  def spawn[S](factory: ActorFactory[S], name: String): ActorRef[S] = {
    val ref = engine.spawn(
      info => typedContext.spawn(factory(info), name).classicRef,
      state,
      typedContext.classicActorContext
    )
    new ActorRef[S](ref)
  }

  def spawnRemote[S](
      factory: String,
      location: unmanaged.ActorRef[RemoteSpawner.Command[S]]
  ): ActorRef[S] = {
    implicit val system: typed.ActorSystem[Nothing] = typedContext.system
    implicit val timeout: Timeout = Timeout(1.minute)

    def spawnIt(info: SpawnInfo): unmanaged.ActorRef[GCMessage[S]] = {
      val f: Future[unmanaged.ActorRef[GCMessage[S]]] =
        location.ask((ref: unmanaged.ActorRef[unmanaged.ActorRef[GCMessage[S]]]) =>
          RemoteSpawner.Spawn(factory, info, ref)
        )

      Await.result[unmanaged.ActorRef[GCMessage[S]]](f, Duration.Inf)
    }

    val ref = engine.spawn(info => spawnIt(info).classicRef, state, typedContext.classicActorContext)
    new ActorRef[S](ref)
  }

  /** Spawn a new anonymous actor into the GC system.
    *
    * @param factory
    *   The behavior factory for the spawned actor.
    * @tparam S
    *   The type of application-level messages to be handled by the new actor.
    * @return
    *   An [[ActorRef]] for the spawned actor.
    */
  def spawnAnonymous[S](factory: ActorFactory[S]): ActorRef[S] = {
    val ref = engine.spawn(
      info => typedContext.spawnAnonymous(factory(info)).classicRef,
      state,
      typedContext.classicActorContext
    )
    new ActorRef[S](ref)
  }

  /** Creates a reference to an actor to be sent to another actor and adds it to the created
    * collection. e.g. A has x: A->B and y: A->C. A could create z: B->C using y and send it to B
    * along x.
    *
    * @param target
    *   The [[ActorRef]] the created reference points to.
    * @param owner
    *   The [[ActorRef]] that will receive the created reference.
    * @tparam S
    *   The type that the actor handles.
    * @return
    *   The created reference.
    */
  def createRef[S](target: ActorRef[S], owner: ActorRef[Nothing]): ActorRef[S] = {
    val ref = engine.createRef(target.ref, owner.ref, state, typedContext.classicActorContext)
    new ActorRef[S](ref)
  }

}
