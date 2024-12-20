package org.apache.pekko.uigc.engines.mac

import org.apache.pekko.actor.{Actor, ActorRef, Timers}
import org.apache.pekko.uigc.UIGC
import org.apache.pekko.uigc.engines.mac.jfr.ProcessingMessages

import scala.concurrent.duration.DurationInt

object CycleDetector {
  /**
   * Message produced by a timer, asking the garbage collector to scan its queue of incoming
   * entries.
   */
  private case object Wakeup

  trait CycleDetectionProtocol

  /**
   * Message sent by a garbage-collected actor to the cycle detector, indicating that the actor
   * has blocked.
   * @param sender the actor that has blocked
   */
  case class BLK(sender: ActorRef, actorMap: Array[(MAC.Name, Long)]) extends CycleDetectionProtocol

  /**
   * Message sent by a garbage-collected actor to the cycle detector after BLK, if the actor receives
   * an application message.
   */
   case class UNB(sender: ActorRef) extends CycleDetectionProtocol

  /**
   * If an actor receives [[MAC.CNF]] and it hasn't received any messages since sending out a BLK
   * message, it sends this message to the cycle detector.
   *
   * @param sender the actor that received the CNF message
   * @param token the token received in the CNF message
   */
  case class ACK(sender: ActorRef, token: Int) extends CycleDetectionProtocol

}

class CycleDetector extends Actor with Timers {
  import CycleDetector._

  private val engine = UIGC(context.system).asInstanceOf[MAC]
  private var totalEntries: Int = 0

  timers.startTimerWithFixedDelay(Wakeup, Wakeup, 50.millis)
  println("Cycle detector started!")

  override def receive: PartialFunction[Any, Unit] = {

    case Wakeup =>
      // println("Cycle detector woke up!")
      val metrics = new ProcessingMessages()
      metrics.begin()

      val queue = engine.Queue
      var count = 0
      var deltaCount = 0
      var msg: CycleDetectionProtocol = queue.poll()
      var actorsToConfirm = Set.empty[ActorRef]
      while (msg != null) {
        count += 1

        msg match {
          case BLK(sender, actorMap) =>
            actorsToConfirm += sender
          case UNB(sender) =>
            actorsToConfirm -= sender
          case ACK(sender, token) =>
            // Confirmed that the actor is blocked, do nothing.
        }

        // Try and get another one
        msg = queue.poll()
      }

      for (apparentlyBlockedActor <- actorsToConfirm) {
        apparentlyBlockedActor ! MAC.CNF(0)
      }
      // println(s"Cycle detector processed $count entries, sent ${actorsToConfirm.size} confirmation requests.")

      metrics.numMessages = count
      metrics.commit()

      totalEntries += count

  }

  override def postStop(): Unit = {
    println(
      s"Cycle detector stopped! Read $totalEntries entries."
    )
    timers.cancel(Wakeup)
  }
}
