package org.apache.pekko.uigc.engines.crgc

import org.apache.pekko.actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.uigc.{interfaces => uigc}

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

class RefInfo(
     var target: actor.ActorRef = null,
     /**
     * This field is read by actors that create new refobs and written-to by
     * the GC. Adding @volatile makes it more likely that the parent actor will
     * get the GC's version of the shadow. But it's also okay if the parent actor
     * reads a stale value of this field. We can remove @volatile if it worsens
     * performance.
     */
     @volatile private[pekko] var targetShadow: Shadow = null
) extends uigc.RefInfo with Serializable {

  override def ref: ActorRef = target

  private var _createdRefs: java.util.HashMap[RefInfo, Int] = new java.util.HashMap()
  private var _sendCount: Int = 0
  private var _hasBeenUpdated: Boolean = false

  def sendCount: Int = _sendCount

  def canIncSendCount: Boolean =
    _sendCount < Int.MaxValue

  /** Returns whether the refinfo needs to be added to the state */
  def incSendCount(): Boolean = {
    val old = _hasBeenUpdated
    _hasBeenUpdated = true
    _sendCount += 1
    !old
  }

  /** `createdRefs(a)` is the number of references to `a` that have been sent to this actor. */
  def createdRefs: java.util.HashMap[RefInfo, Int] = _createdRefs

  /** Returns whether the refinfo needs to be added to the state */
  def addCreatedRefPointingTo(target: RefInfo): Boolean = {
    val old = _hasBeenUpdated
    _hasBeenUpdated = true
    if (_createdRefs == null) {
      _createdRefs = new java.util.HashMap(4)
    }
    val count = _createdRefs.getOrDefault(target, 0)
    _createdRefs.put(target, count + 1)
    !old
  }

  def reset(): Unit = {
    _hasBeenUpdated = false
    _sendCount = 0
    _createdRefs = null
  }

  override def equals(that: Any): Boolean =
    that match {
      case that: RefInfo => this.target == that.target
      case _              => false
    }

  override def hashCode(): Int = target.hashCode()

  // SpawnInfo is serialized by setting the Shadow field to None.
  @throws(classOf[IOException])
  private def writeObject(out: ObjectOutputStream): Unit =
    out.writeObject(target)

  @throws(classOf[IOException])
  private def readObject(in: ObjectInputStream): Unit = {
    this.target = in.readObject().asInstanceOf[actor.ActorRef]
    this.targetShadow = null
  }
}
