package org.apache.pekko.uigc

import org.apache.pekko.actor.{ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, ExtensionId, ExtensionIdProvider}
import org.apache.pekko.uigc.engines.crgc.CRGC
import org.apache.pekko.uigc.engines.wrc.WRC
import org.apache.pekko.uigc.engines.{Engine, Manual}

/** The UIGC system extension. */
object UIGC extends ExtensionId[Engine] with ExtensionIdProvider {
  override def lookup: UIGC.type = UIGC

  def createExtension(system: ExtendedActorSystem): Engine = {
    val config = system.settings.config
    config.getString("uigc.engine") match {
      case "crgc"   => new CRGC(system)
      case "wrc"    => new WRC(system)
      case "manual" => new Manual
    }
  }

  override def get(system: ActorSystem): Engine = super.get(system)

  override def get(system: ClassicActorSystemProvider): Engine = super.get(system)
}
