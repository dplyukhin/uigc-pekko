package org.apache.pekko.uigc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{PostStop, Signal}
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.uigc.actor.typed._
import org.apache.pekko.uigc.actor.typed.scaladsl._
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

object RandomSpec {
  val MAX_ACTORS = 10000
  val PING_FREQUENCY: FiniteDuration = 1.millis
  val SpawnCounter: AtomicInteger = new AtomicInteger()
  val TerminateCounter: CountDownLatch = new CountDownLatch(MAX_ACTORS)

  sealed trait Msg extends Message

  final case class Link(ref: ActorRef[Msg]) extends Msg {
    def refs: Seq[ActorRef[Msg]] = Seq(ref)
  }

  final case class Ping() extends Msg {
    def refs: Seq[Nothing] = Seq()
  }
}

/** This spec spawns a lot of actors in a random configuration and then waits until they have all
  * been collected. If the GC is unsound, this is likely to throw an exception or to log dead letters.
  * If the GC is incomplete, the test will time out.
  */
class RandomSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import RandomSpec._

  private class RandomActor(
      context: ActorContext[Msg],
      timers: TimerScheduler[Msg]
  ) extends AbstractBehavior[Msg](context) {

    private def isRoot: Boolean = timers != null

    private var acquaintances: Set[ActorRef[Msg]] = Set()

    override def onMessage(msg: Msg): Behavior[Msg] =
      msg match {
        case Link(ref) =>
          acquaintances += ref
          doSomeActions()
          this

        case Ping() =>
          doSomeActions()
          this
      }

    private def doSomeActions(): Unit = {
      if (SpawnCounter.get() >= MAX_ACTORS && isRoot) {
        // Root actor stops taking actions after all actors have been spawned.
        if (acquaintances.nonEmpty) {
          println(s"Spawned $MAX_ACTORS actors. Root actor releasing all acquaintances...")
          //context.release(acquaintances)
          acquaintances = Set()
          System.gc()
        }
      }
      else {
        // Either this actor is not the root, or there are still actors to spawn.
        // 1. Non-root actors should always do something when it gets a message.
        // 2. If the actor is the root, it should take take actions until all actors have been spawned.
        doSomething()
        doSomething()
      }
    }

    private def doSomething(): Unit = {
      val p = Random.nextDouble()
      if (p < 0.2) {
        val count = SpawnCounter.incrementAndGet()
        if (count <= MAX_ACTORS) {
          acquaintances += context.spawnAnonymous(RandomActor())
        }
      } else if (p < 0.4 && acquaintances.nonEmpty) {
        val owner = randomItem(acquaintances)
        val target = randomItem(acquaintances)
        owner ! Link(context.createRef(target, owner))
      } else if (p < 0.6 && acquaintances.nonEmpty) {
        val actor = randomItem(acquaintances)
        acquaintances = acquaintances - actor
        //context.release(actor)
      } else if (p < 0.8 && acquaintances.nonEmpty) {
        randomItem(acquaintances) ! Ping()
      }
    }

    /** Pick a random item from the set, assuming the set is nonempty. */
    private def randomItem[T](items: Set[T]): T = {
      val i = Random.nextInt(items.size)
      items.view.slice(i, i + 1).head
    }

    override def onSignal: PartialFunction[Signal, Behavior[Msg]] = {
      case PostStop =>
        TerminateCounter.countDown()
        println(TerminateCounter.getCount.toString + " remaining!")
        this
    }
  }

  object RandomActor {
    def createRoot(): unmanaged.Behavior[Msg] =
      Behaviors.withTimers[Msg] { timers =>
        Behaviors.setupRoot { context =>
          timers.startTimerAtFixedRate((), Ping(), PING_FREQUENCY)
          new RandomActor(context, timers)
        }
      }

    def apply(): ActorFactory[Msg] =
      Behaviors.setup(context => new RandomActor(context, null))
  }

  // Here's the test!
  "GC" must {
    testKit.spawn(RandomActor.createRoot(), "root")

    "eventually detect all garbage" in {
      TerminateCounter.await()
    }
  }

}
