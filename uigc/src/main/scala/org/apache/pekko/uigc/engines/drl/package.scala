package org.apache.pekko.uigc.engines

import org.apache.pekko.actor.typed.ActorRef

package object drl {
  type Name = ActorRef[drl.GCMessage[Nothing]]
  type Ref = drl.Refob[Nothing]
}
