package org.apache.pekko.uigc.engines.crgc;

import java.util.Arrays;

public class Entry {
    public WrappedActorRef<?> self;
    public WrappedActorRef<?>[] createdOwners;
    public WrappedActorRef<?>[] createdTargets;
    public WrappedActorRef<?>[] spawnedActors;
    public WrappedActorRef<?>[] updatedRefs;
    public short[] updatedInfos;
    public short recvCount;
    public boolean isBusy;
    public boolean isRoot;

    public Entry(Context context) {
        self           = null;
        createdOwners  = new WrappedActorRef<?>[context.EntryFieldSize];
        createdTargets = new WrappedActorRef<?>[context.EntryFieldSize];
        spawnedActors  = new WrappedActorRef<?>[context.EntryFieldSize];
        updatedRefs    = new WrappedActorRef<?>[context.EntryFieldSize];
        updatedInfos   = new short[context.EntryFieldSize];
        isBusy         = false;
        isRoot         = false;
    }

    public void clean() {
        self = null;
        Arrays.fill(createdOwners, null);
        Arrays.fill(createdTargets, null);
        Arrays.fill(spawnedActors, null);
        Arrays.fill(updatedRefs, null);
        Arrays.fill(updatedInfos, (short) 0);
        isBusy = false;
        isRoot = false;
    }
}
