package edu.illinois.osl.uigc.streams

import org.apache.pekko.actor.{Address, ExtendedActorSystem}
import org.apache.pekko.remote.artery.InboundEnvelope
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import edu.illinois.osl.uigc.UIGC

class Ingress(system: ExtendedActorSystem, adjacentSystem: Address)
    extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {

  val in: Inlet[InboundEnvelope] = Inlet("Artery.Ingress.in")
  val out: Outlet[InboundEnvelope] = Outlet("Artery.Ingress.out")
  val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    UIGC(system).spawnIngress(in, out, shape, system, adjacentSystem)
}
