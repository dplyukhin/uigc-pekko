package org.apache.pekko.uigc.engines.crgc;

import org.apache.pekko.actor.ActorRef;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public class PhantomActorRef extends PhantomReference<WrappedActorRef> {

    ActorRef target;
    volatile Shadow targetShadow;
    boolean deactivated = false;

    public PhantomActorRef(WrappedActorRef wrappedActorRef, ReferenceQueue<? super WrappedActorRef> queue) {
        super(wrappedActorRef, queue);
        target = wrappedActorRef.target();
        targetShadow = wrappedActorRef.targetShadow();
    }

    public ActorRef target() {
        return target;
    }

    public void deactivate() {
        deactivated = true;
    }

    public boolean isDeactivated() {
        return deactivated;
    }

    public Shadow targetShadow() {
        return targetShadow;
    }
}
