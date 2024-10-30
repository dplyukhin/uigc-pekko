package org.apache.pekko.uigc.actor.typed;

import org.apache.pekko.uigc.interfaces.RefInfo;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public class PhantomActorRef extends PhantomReference<ActorRef<?>> {

    RefInfo refInfo;
    boolean deactivated = false;

    public PhantomActorRef(ActorRef<?> actorRef, ReferenceQueue<? super ActorRef<?>> queue) {
        super(actorRef, queue);
        refInfo = actorRef.refInfo();
    }

    public RefInfo refInfo() {
        return refInfo;
    }

    public void deactivate() {
        deactivated = true;
    }

    public boolean isDeactivated() {
        return deactivated;
    }

}
