package org.apache.pekko.uigc

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.{PostStop, Signal}
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.uigc.actor.typed.scaladsl._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class SimpleActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  sealed trait TestMessage extends Message
  case object Ping extends TestMessage with NoRefs
  case object Init extends TestMessage with NoRefs
  case class SendC(msg: TestMessage) extends TestMessage with NoRefs
  case class SendB(msg: TestMessage) extends TestMessage with NoRefs
  case object TellBAboutC extends TestMessage with NoRefs
  case object ReleaseC extends TestMessage with NoRefs
  case object ReleaseB extends TestMessage with NoRefs
  case object Hello extends TestMessage with NoRefs
  case class Spawned() extends TestMessage with NoRefs
  case object Terminated extends TestMessage with NoRefs
  case class GetRef(ref: ActorRef[TestMessage]) extends TestMessage with Message {
    override def refs: Iterable[ActorRef[Nothing]] = Iterable(ref)
  }

  val probe: TestProbe[TestMessage] = testKit.createTestProbe[TestMessage]()

  "GC Actors" must {
    val actorA = testKit.spawn(ActorA(), "actorA")

    "be able to spawn actors" in {
      actorA ! Init
      probe.expectMessageType[Spawned]
      probe.expectMessageType[Spawned]
    }
    "be able to send messages" in {
      actorA ! SendC(Hello)
      probe.expectMessage(Hello)
    }
    "be able to share references" in {
      actorA ! TellBAboutC
      actorA ! SendB(SendC(Hello))
      probe.expectMessage(Hello)
    }
    "not terminate when some owners still exist" in {
      actorA ! ReleaseC
      probe.expectNoMessage()
    }
    "be able to send messages after other owners have released" in {
      actorA ! SendB(SendC(Hello))
      probe.expectMessage(Hello)
    }
    "terminate after all references have been released" in {
      actorA ! ReleaseB
      probe.expectMessage(Terminated)
      probe.expectMessage(Terminated)
    }
  }

  object ActorA {
    def apply(): unmanaged.Behavior[TestMessage] =
      Behaviors.withTimers[TestMessage] { timers =>
        // Root actor needs to wake up periodically, or else it'll never detect its references
        // have become garbage.
        Behaviors.setupRoot { context =>
          timers.startTimerAtFixedRate((), Ping, 100.millis)
          new ActorA(context)
        }
      }
  }
  object ActorB {
    def apply(): ActorFactory[TestMessage] = {
      Behaviors.setup(context => new ActorB(context))
    }
  }
  object ActorC {
    def apply(): ActorFactory[TestMessage] = {
      Behaviors.setup(context => new ActorC(context))
    }
  }

  class ActorA(context: ActorContext[TestMessage]) extends AbstractBehavior[TestMessage](context) {
    var actorB: ActorRef[TestMessage] = _
    var actorC: ActorRef[TestMessage] = _
    override def onMessage(msg: TestMessage): Behavior[TestMessage] = {
      msg match {
        case Init =>
          actorB = context.spawn(ActorB(), "actorB")
          actorC = context.spawn(ActorC(), "actorC")
          this
        case SendC(msg) =>
          actorC ! msg
          this
        case SendB(msg) =>
          actorB ! msg
          this
        case TellBAboutC =>
          val refToShare = context.createRef(actorC, actorB)
          actorB ! GetRef(refToShare)
          this
        case ReleaseC =>
          actorC = null
          this
        case ReleaseB =>
          actorB = null
          this
        case _ =>
          System.gc()
          this
      }
    }
  }
  class ActorB(context: ActorContext[TestMessage]) extends AbstractBehavior[TestMessage](context) {
    var actorC: ActorRef[TestMessage]= _
    probe.ref ! Spawned()
    override def onMessage(msg: TestMessage): Behavior[TestMessage] = {
      msg match {
        case GetRef(ref) =>
          actorC = ref
          this
        case SendC(msg) =>
          actorC ! msg
          this
        case ReleaseC =>
          actorC = null
          this
        case _ => this
      }
    }
    override def onSignal: PartialFunction[Signal, Behavior[TestMessage]] = {
      case PostStop =>
        probe.ref ! Terminated
        this
    }
  }
  class ActorC(context: ActorContext[TestMessage]) extends AbstractBehavior[TestMessage](context) {
    probe.ref ! Spawned()
    override def onMessage(msg: TestMessage): Behavior[TestMessage] = {
      msg match {
        case Hello =>
          probe.ref ! Hello
          this
        case _ => this
      }
    }
    override def onSignal: PartialFunction[Signal, Behavior[TestMessage]] = {
      case PostStop =>
        probe.ref ! Terminated
        this
    }
  }
}
