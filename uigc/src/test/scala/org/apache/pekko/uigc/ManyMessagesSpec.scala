package org.apache.pekko.uigc

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.{PostStop, Signal, Behavior => AkkaBehavior}
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.uigc.actor.typed.scaladsl._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.{Duration, DurationInt}


object ManyMessagesSpec {
  val NUM_MESSAGES: Int = 4 * Short.MaxValue

  sealed trait Msg extends Message

  final case object Ping extends Msg with NoRefs
  final case object Terminated extends Msg with NoRefs
  final case object DoneSendingMessages extends Msg with NoRefs
  final case object DoneReceivingMessages extends Msg with NoRefs
  final case class NewAcquaintance(actorB: ActorRef[Msg]) extends Msg {
    override def refs: Iterable[ActorRef[Nothing]] = Some(actorB)
  }
}

class ManyMessagesSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import ManyMessagesSpec._

  val probeA: TestProbe[Msg] = testKit.createTestProbe[Msg]()
  val probeB: TestProbe[Msg] = testKit.createTestProbe[Msg]()

  // This test spawns two actors A and B, and asks A to send a lot of messages to B.
  // The test is useful for bounds-checking CRGC's State and Entry classes.
  "Actors" must {
    "be collected after they're done working." in {
      val root = testKit.spawn(Root(), "root")
      root ! Ping
      probeA.expectMessage(5.seconds, DoneSendingMessages)
      probeB.expectMessage(5.seconds, DoneReceivingMessages)
      probeA.expectMessage(5.seconds, Terminated)
      probeB.expectMessage(5.seconds, Terminated)
    }
  }


  object Root {
    def apply(): AkkaBehavior[Msg] =
      Behaviors.withTimers[Msg] { timers =>
        // Root actor needs to wake up periodically, or else it'll never detect its references
        // have become garbage.
        Behaviors.setupRoot { context =>
          timers.startTimerAtFixedRate((), Ping, 100.millis)
          new Root(context)
        }
      }
  }
  class Root(context: ActorContext[Msg]) extends AbstractBehavior[Msg](context) {
    {
      // Create a scope so these actors are not fields of the class, so they
      // should get garbage collected.
      val actorA: ActorRef[Msg] = context.spawn(ActorA(), "actorA")
      val actorB: ActorRef[Msg] = context.spawn(ActorB(), "actorB")
      actorA ! NewAcquaintance(context.createRef(actorB, actorA))
    }

    override def onMessage(msg: Msg): Behavior[Msg] = msg match {
      case Ping =>
        // Run the GC manually, since it won't get triggered otherwise!
        System.gc()
        this
      case _ => this
    }
  }

  object ActorA {
    def apply(): ActorFactory[Msg] = {
      Behaviors.setup(context => new ActorA(context))
    }
  }
  class ActorA(context: ActorContext[Msg]) extends AbstractBehavior[Msg](context) {
    override def onMessage(msg: Msg): Behavior[Msg] = {
      msg match {
        case NewAcquaintance(actorB) =>
          for (i <- 1 to NUM_MESSAGES)
            actorB ! Ping
          probeA ! DoneSendingMessages
          this
        case _ =>
          this
      }
    }

    override def onSignal: PartialFunction[Signal, Behavior[Msg]] = {
      case PostStop =>
        probeA.ref ! Terminated
        this
    }
  }

  object ActorB {
    def apply(): ActorFactory[Msg] = {
      Behaviors.setup(context => new ActorB(context))
    }
  }
  class ActorB(context: ActorContext[Msg]) extends AbstractBehavior[Msg](context) {
    private var count = 0
    override def onMessage(msg: Msg): Behavior[Msg] = {
      msg match {
        case Ping =>
          count += 1
          if (count == NUM_MESSAGES) {
            probeB.ref ! DoneReceivingMessages
          }
          this
        case _ =>
          this
      }
    }
    override def onSignal: PartialFunction[Signal, Behavior[Msg]] = {
      case PostStop =>
        probeB.ref ! Terminated
        this
    }
  }
}
