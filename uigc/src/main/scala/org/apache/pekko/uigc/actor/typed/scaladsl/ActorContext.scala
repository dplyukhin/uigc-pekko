package org.apache.pekko.uigc.actor.typed.scaladsl

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import org.apache.pekko.uigc.engines.Engine
import org.apache.pekko.uigc.UIGC
import org.apache.pekko.uigc.interfaces.{GCMessage, SpawnInfo, State}
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.util.Timeout

import java.lang.ref.ReferenceQueue
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

/** A version of [[scaladsl.ActorContext]] used by garbage-collected actors. Provides methods for
  * spawning garbage-collected actors, creating new references, and releasing references. Also
  * stores GC-related local state of the actor. By keeping GC state in the [[ActorContext]],
  * garbage-collected actors can safely change their behavior by passing their [[ActorContext]] to
  * the behavior they take on.
  */
class ActorContext[T <: Message](
    val typedContext: scaladsl.ActorContext[GCMessage[T]],
    val spawnInfo: SpawnInfo
) {

  private[pekko] val engine: Engine = UIGC(typedContext.system)
  private[pekko] val state: State = engine.initState(typedContext.classicActorContext, spawnInfo)
  private[pekko] val phantomBuffer: PhantomBuffer = new PhantomBuffer()
  private[pekko] val phantomQueue: ReferenceQueue[ActorRef[_]] = new ReferenceQueue()

  val self: ActorRef[T] = {
    val refInfo = engine.getSelfRefInfo(state, typedContext.classicActorContext)
    new ActorRef[T](typedContext.self.classicRef, refInfo)
  }

  val name: ActorName = typedContext.self

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
  def spawn[S <: Message](factory: ActorFactory[S], name: String): ActorRef[S] = {
    val refInfo = engine.spawn(
      info => typedContext.spawn(factory(info), name).classicRef,
      state,
      typedContext.classicActorContext
    )
    val ref = new ActorRef[S](refInfo.ref, refInfo)
    registerPhantom(ref)
    ref
  }

  def spawnRemote[S <: Message](
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

    val refInfo = engine.spawn(info => spawnIt(info).classicRef, state, typedContext.classicActorContext)
    val ref = new ActorRef[S](refInfo.ref, refInfo)
    registerPhantom(ref)
    ref
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
  def spawnAnonymous[S <: Message](factory: ActorFactory[S]): ActorRef[S] = {
    val refInfo = engine.spawn(
      info => typedContext.spawnAnonymous(factory(info)).classicRef,
      state,
      typedContext.classicActorContext
    )
    val ref = new ActorRef[S](refInfo.ref, refInfo)
    registerPhantom(ref)
    ref
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
  def createRef[S <: Message](target: ActorRef[S], owner: ActorRef[Nothing]): ActorRef[S] = {
    val refInfo = engine.createRef(target.refInfo, owner.refInfo, state, typedContext.classicActorContext)
    // No need to register phantom references that are going to be sent to another actor.
    new ActorRef[S](target.ref, refInfo)
  }

  /**
   * When actor A gets a reference x to actor B, the former needs to register a phantom
   * that points to x. When x is garbage collected, the phantom will be added to [[phantomQueue]].
   * @param ref A reference this actor has just acquired.
   */
  private[pekko] def registerPhantom(ref: ActorRef[_]): Unit = {
    val phantom = new PhantomActorRef(ref, phantomQueue)
    phantomBuffer.add(phantom)
  }

  /**
   * Check for references that have been garbage collected, and deactivate them by calling
   * [[Engine.deactivate]].
   */
  private[pekko] def checkForGarbageReferences(): Unit = {
    // Deactivate all references in the phantom queue and then clear them from the buffer.
    var numDeactivated = 0
    var x = phantomQueue.poll()
    while (x != null) {
      numDeactivated += 1
      val phantom = x.asInstanceOf[PhantomActorRef]
      phantom.deactivate()
      engine.deactivate(phantom.refInfo, state, typedContext.classicActorContext)
      x = phantomQueue.poll()
    }
    if (numDeactivated > 0) {
      phantomBuffer.cull()
    }
  }

}
