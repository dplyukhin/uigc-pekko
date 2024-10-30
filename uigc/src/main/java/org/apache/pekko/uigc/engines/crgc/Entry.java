package org.apache.pekko.uigc.engines.crgc;

import java.util.Arrays;

public class Entry {
    public RefInfo self;
    public RefInfo[] createdOwners;
    public RefInfo[] createdTargets;
    public RefInfo[] spawnedActors;
    public RefInfo[] updatedRefs;
    public short[] updatedInfos;
    public short recvCount;
    public boolean isBusy;
    public boolean isRoot;

    public Entry(Context context) {
        self           = null;
        createdOwners  = new RefInfo[context.EntryFieldSize];
        createdTargets = new RefInfo[context.EntryFieldSize];
        spawnedActors  = new RefInfo[context.EntryFieldSize];
        updatedRefs    = new RefInfo[context.EntryFieldSize];
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
