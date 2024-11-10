package org.apache.pekko.uigc.engines.crgc

import org.apache.pekko.actor.{Actor, ActorRef, ActorSelection, Address, RootActorPath, Timers}
import org.apache.pekko.cluster.ClusterEvent.{CurrentClusterState, MemberRemoved, MemberUp}
import org.apache.pekko.cluster.{Cluster, Member, MemberStatus}
import org.apache.pekko.uigc.UIGC
import org.apache.pekko.uigc.engines.crgc.jfr.{MergingDeltaGraphs, MergingIngressEntries, ProcessingEntries}

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IterableHasAsJava

object LocalGC {
  trait Msg

  /** Message an ingress actor sends to its local GC when it finalizes an entry. */
  case class LocalIngressEntry(entry: IngressEntry) extends Msg

  /** Bit of a hack. The ingress actor sends its local GC a hook to run when the adjacent node is
    * removed.
    */
  case class NewIngressActor(adjacentAddress: Address, finalizeAndSendEntry: () => Unit) extends Msg

  /** Message sent from the garbage collector [[sender]], summarizing the entries it received
    * recently.
    */
  private case class DeltaMsg(seqnum: Int, graph: DeltaGraph, sender: ActorRef)
      extends Msg
      with Serializable

  /** Message sent to a garbage collector, asking it to forward the given [[msg]] to the egress
    * actor at the given location.
    */
  private case class ForwardToEgress(location: (Address, Address), msg: Gateway.Msg) extends Msg

  /** Message in which a garbage collector broadcasts local ingress entries to all other collectors.
    */
  private case class RemoteIngressEntry(msg: IngressEntry) extends Msg

  /** Message produced by a timer, asking the garbage collector to scan its queue of incoming
    * entries.
    */
  private case object ProcessEntries extends Msg

  /** Message produced by a timer, asking the garbage collector to search the shadow graph
   * for garbage.
   */
  private case object TraceGraph extends Msg

  /** Message produced by a timer, asking the garbage collector to start a wave. */
  private case object StartWave extends Msg
}

class LocalGC extends Actor with Timers {
  import LocalGC._

  private val engine = UIGC(context.system).asInstanceOf[CRGC]

  private val thisAddress: Address =
    if (engine.crgcConfig.numNodes > 1) Cluster(context.system).selfMember.address else null
  private val shadowGraph = new ShadowGraph(engine.crgcConfig)
  private var remoteGCs: Map[Address, ActorSelection] = Map()
  private var undoLogs: Map[Address, UndoLog] = Map()
  private var downedGCs: Set[Address] = Set()
  //private var undoneGCs: Set[Address] = Set()
  private var ingressHooks: Map[Address, () => Unit] = Map()
  private var totalEntries: Int = 0
  // private val testGraph = new ShadowGraph()
  private var deltaGraphID: Int = 0
  private var deltaGraph = DeltaGraph.initialize(thisAddress, engine.crgcConfig)


  // Statistics
  private var wakeupCount = 0

  if (engine.crgcConfig.numNodes == 1) {
    start()
  } else {
    Cluster(context.system).subscribe(self, classOf[MemberUp])
    Cluster(context.system).subscribe(self, classOf[MemberRemoved])
    println("Waiting for other bookkeepers to join...")
  }

  override def receive: Receive = {
    case MemberUp(member) =>
      addMember(member)

    case MemberRemoved(member, previousStatus) =>
      removeMember(member)

    case state: CurrentClusterState =>
      state.members.filter(_.status == MemberStatus.Up).foreach(addMember)
      state.members.filter(_.status == MemberStatus.Removed).foreach(addMember)

    case NewIngressActor(addr, hook) =>
      ingressHooks = ingressHooks + (addr -> hook)

    case ForwardToEgress((sender, receiver), msg) =>
      if (sender == thisAddress && remoteGCs.contains(receiver)) {
        // println(s"GC sending $msg to ${remoteGCs(receiver)} at $receiver")
        remoteGCs(receiver) ! msg
      } else {
        if (remoteGCs.contains(sender))
          remoteGCs(sender) ! ForwardToEgress((sender, receiver), msg)
      }

    case LocalIngressEntry(entry) =>
      // println(s"GC got local ingress entry (${entry.egressAddress},${entry.ingressAddress}) ${entry.id}")
      for ((addr, gc) <- remoteGCs; if addr != entry.egressAddress)
        // Tell each remote GC, except the one that is adjacent to this entry, about the entry.
        gc ! RemoteIngressEntry(entry)

      val metrics = new MergingIngressEntries()
      metrics.begin()
      metrics.sender = thisAddress.toString

      mergeIngressEntry(entry)

      metrics.commit()

    case RemoteIngressEntry(entry) =>
      // println(s"GC got remote ingress entry (${entry.egressAddress},${entry.ingressAddress}) ${entry.id}")
      val metrics = new MergingIngressEntries()
      metrics.begin()
      metrics.sender = entry.ingressAddress.toString

      mergeIngressEntry(entry)

      metrics.commit()

    case DeltaMsg(id, delta, replyTo) =>
      // println(s"GC ${id} deltas from $replyTo")
      if (remoteGCs.contains(delta.address)) {
        val metrics = new MergingDeltaGraphs()
        metrics.begin()
        metrics.sender = delta.address.toString

        // Only merge shadow graphs from nodes that have not yet been removed.
        shadowGraph.mergeDelta(delta)
        undoLogs(delta.address).mergeDeltaGraph(delta)

        metrics.commit()
      }
    // var i = 0
    // while (i < delta.entries.size()) {
    //  testGraph.mergeRemoteEntry(delta.entries.get(i))
    //  i += 1;
    // }
    // shadowGraph.assertEquals(testGraph)

    case TraceGraph =>
      shadowGraph.trace(true)

    case ProcessEntries =>
      wakeupCount += 1
      // println("Bookkeeper woke up!")
      val entryProcessingStats = new ProcessingEntries()
      entryProcessingStats.begin()

      entryProcessingStats.nanosToProcess = System.nanoTime()
      var count = 0
      var deltaCount = 0
      for (queue <- CRGC.EntryQueues) {
        var entry: Entry = queue.poll()
        while (entry != null) {
          count += 1
          shadowGraph.mergeEntry(entry)
          // testGraph.mergeEntry(entry)
          // shadowGraph.assertEquals(testGraph)

          if (engine.crgcConfig.numNodes > 1) {
            deltaGraph.mergeEntry(entry)
            if (deltaGraph.isFull) {
              deltaCount += 1
              finalizeDeltaGraph()
            }
          }

          // Try and get another one
          entry = queue.poll()
        }
        // Done processing entries in this queue. Try the next queue.
      }
      entryProcessingStats.numEntries = count
      entryProcessingStats.nanosToProcess = System.nanoTime() - entryProcessingStats.nanosToProcess

      totalEntries += count

      //if (wakeupCount % 100 == 0)
      //  shadowGraph.investigateLiveSet()
      // shadowGraph.assertEquals(testGraph)

      //println(s"Bookkeeper processed $count entries in ${entryProcessingStats.nanosToProcess/1000} microseconds")
      entryProcessingStats.commit()

    case StartWave =>
      shadowGraph.startWave()
  }

  private def finalizeDeltaGraph(): Unit = {
    for (gc <- remoteGCs.values)
      gc ! DeltaMsg(deltaGraphID, deltaGraph, context.self)
    deltaGraphID += 1
    deltaGraph = DeltaGraph.initialize(thisAddress, engine.crgcConfig)
  }

  private def addMember(member: Member): Unit =
    if (member != Cluster(context.system).selfMember) {
      val addr = member.address
      val gc = context.actorSelection(RootActorPath(addr) / "system" / "Bookkeeper")
      println(s"${context.self} connected to $gc on $addr")
      remoteGCs = remoteGCs + (addr -> gc)
      if (!undoLogs.contains(addr))
        undoLogs = undoLogs + (addr -> new UndoLog(addr))
      if (remoteGCs.size + 1 == engine.crgcConfig.numNodes) {
        start()
      }
    }

  private def start(): Unit = {
    // Start processing entries
    timers.startTimerWithFixedDelay(ProcessEntries, ProcessEntries, engine.crgcConfig.entryProcessingFrequency.millis)
    timers.startTimerWithFixedDelay(TraceGraph, TraceGraph, engine.crgcConfig.tracingFrequency.millis)
    // Start triggering GC waves
    if (engine.crgcConfig.CollectionStyle == CRGC.Wave) {
      timers.startTimerWithFixedDelay(StartWave, StartWave, engine.crgcConfig.waveFrequency.millis)
    }
    // Start asking egress actors to finalize entries
    for ((addr, _) <- remoteGCs)
      timers.startTimerWithFixedDelay(
        (Egress.FinalizeEgressEntry, addr),
        ForwardToEgress((thisAddress, addr), Egress.FinalizeEgressEntry),
        10.millis
      )
    println("Bookkeeper started!")
  }

  private def removeMember(member: Member): Unit =
    if (member != Cluster(context.system).selfMember) {
      val addr = member.address
      println(s"GC detected that $member on $addr has been removed.")
      downedGCs = downedGCs + addr

      val count = shadowGraph.investigateRemotelyHeldActors(addr)
      println(s"$member is preventing $count actors from being collected.")

      // Ask the member's ingress actor to finalize its entry.
      ingressHooks(addr)()

      remoteGCs = remoteGCs - addr
      ingressHooks = ingressHooks - addr
      timers.cancel((Egress.FinalizeEgressEntry, addr))
    }

  private def mergeIngressEntry(entry: IngressEntry): Unit = {
    val addr = entry.egressAddress
    if (!undoLogs.contains(addr)) {
      undoLogs = undoLogs + (addr -> new UndoLog(addr))
    }
    undoLogs(addr).mergeIngressEntry(entry)
    if (entry.isFinal) {
      // println(s"GC got final ingress entry for (${entry.egressAddress},${entry.ingressAddress})")
      // If the undo log for this node has now been finalized by every node in remoteGCs, we can undo it.
      if (
        undoLogs(addr).finalizedBy.contains(thisAddress) &&
        undoLogs(addr).finalizedBy.containsAll(remoteGCs.keys.asJavaCollection)
      ) {
        println(
          s"Undo log for $addr is ready! It contains ${undoLogs(addr).admitted.size()} fields."
        )

        shadowGraph.mergeUndoLog(undoLogs(addr))
        shadowGraph.trace(true)
        // val remaining = shadowGraph.investigateRemotelyHeldActors(addr)
        // println(s"Now $addr prevents $remaining from being collected.")
      }
    }
  }

  override def postStop(): Unit = {
    println(
      s"Bookkeeper stopped! Read $totalEntries entries, produced $deltaGraphID delta-graphs, " +
        s"and discovered ${shadowGraph.totalActorsSeen} actors."
    )
    for (addr <- downedGCs) {
      val count = shadowGraph.investigateRemotelyHeldActors(addr)
      println(s"Address $addr is preventing $count actors from being collected.")
    }
    // shadowGraph.investigateLiveSet()
    timers.cancel(ProcessEntries)
    timers.cancel(TraceGraph)
  }
}
