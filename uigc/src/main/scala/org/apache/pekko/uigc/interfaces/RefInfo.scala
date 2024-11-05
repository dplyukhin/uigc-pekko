package org.apache.pekko.uigc.interfaces

import org.apache.pekko.actor

trait RefInfo {
  def ref: actor.ActorRef
}
