package org.apache.pekko.uigc

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.{PostStop, Signal, Behavior => AkkaBehavior}
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.uigc.actor.typed.scaladsl._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt


object SelfMessagingSpec {
  sealed trait SelfRefMsg extends Message

  final case object Ping extends SelfRefMsg with NoRefs
  final case class Countdown(n: Int) extends SelfRefMsg with NoRefs
  final case class SelfRefTestInit(n: Int) extends SelfRefMsg with NoRefs
  final case class SelfRefTerminated(n: Int) extends SelfRefMsg with NoRefs
}

class SelfMessagingSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import SelfMessagingSpec._

  val probe: TestProbe[SelfRefMsg] = testKit.createTestProbe[SelfRefMsg]()

  // In this test, the receptionist actor A spawns an actor B, tells it to
  // count down from a large number `n`, and releases it. It's very likely
  // that at some point, B will have no inverse acquaintances but it will
  // still have a nonempty mailqueue containing messages to itself. That
  // actor shouldn't terminate until its mail queue is empty.
  "Isolated actors" must {
    val actorA = testKit.spawn(ActorA(), "actorA")
    "not self-terminate when self-messages are in transit" in {
      val n = 10000
      actorA ! SelfRefTestInit(n)
      probe.expectMessage(SelfRefTerminated(n))
    }
  }


  object ActorA {
    def apply(): AkkaBehavior[SelfRefMsg] = {
      Behaviors.withTimers[SelfRefMsg] { timers =>
        // Root actor needs to wake up periodically, or else it'll never detect its references
        // have become garbage.
        Behaviors.setupRoot { context =>
          timers.startTimerAtFixedRate((), Ping, 100.millis)
          new ActorA(context)
        }
      }
    }
  }
  class ActorA(context: ActorContext[SelfRefMsg]) extends AbstractBehavior[SelfRefMsg](context) {

    override def onMessage(msg: SelfRefMsg): Behavior[SelfRefMsg] = {
      msg match {
        case SelfRefTestInit(n) =>
          val actorB: ActorRef[SelfRefMsg] = context.spawn(ActorB(), "actorB")
          actorB ! Countdown(n)
          this
        case _ =>
          // Force GC so the JVM detects the reference to actorB is garbage.
          System.gc()
          this
      }
    }
  }

  object ActorB {
    def apply(): ActorFactory[SelfRefMsg] = {
      Behaviors.setup(context => new ActorB(context))
    }
  }
  class ActorB(context: ActorContext[SelfRefMsg]) extends AbstractBehavior[SelfRefMsg](context) {
    private var count = 0
    override def onMessage(msg: SelfRefMsg): Behavior[SelfRefMsg] = {
      msg match {
        case Countdown(n) =>
          if (n > 0) {
            context.self ! Countdown(n - 1)
            count += 1
          }
          this
        case _ =>
          this
      }
    }
    override def onSignal: PartialFunction[Signal, Behavior[SelfRefMsg]] = {
      case PostStop =>
        probe.ref ! SelfRefTerminated(count)
        this
    }
  }
}
