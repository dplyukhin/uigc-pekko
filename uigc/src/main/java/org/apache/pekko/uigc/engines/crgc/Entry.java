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
    public int threadPoolID;
    public boolean isBusy;
    public boolean isRoot;

    public Entry(CrgcConfig crgcConfig, int threadPoolID) {
        this.self           = null;
        this.createdOwners  = new RefInfo[crgcConfig.EntryFieldSize];
        this.createdTargets = new RefInfo[crgcConfig.EntryFieldSize];
        this.spawnedActors  = new RefInfo[crgcConfig.EntryFieldSize];
        this.updatedRefs    = new RefInfo[crgcConfig.EntryFieldSize];
        this.updatedInfos   = new short[crgcConfig.EntryFieldSize];
        this.recvCount      = 0;
        this.threadPoolID   = threadPoolID;
        this.isBusy         = false;
        this.isRoot         = false;
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

    @Override
    public String toString() {
        return "Entry{" +
                "\nself=" + self +
                "\n, createdOwners=" + Arrays.toString(createdOwners) +
                "\n, createdTargets=" + Arrays.toString(createdTargets) +
                "\n, spawnedActors=" + Arrays.toString(spawnedActors) +
                "\n, updatedRefs=" + Arrays.toString(updatedRefs) +
                "\n, updatedInfos=" + Arrays.toString(updatedInfos) +
                "\n, recvCount=" + recvCount +
                "\n, threadPoolID=" + threadPoolID +
                "\n, isBusy=" + isBusy +
                "\n, isRoot=" + isRoot +
                "\n}";
    }
}
