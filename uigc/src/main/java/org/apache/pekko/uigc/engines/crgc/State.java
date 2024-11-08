package org.apache.pekko.uigc.engines.crgc;

import org.apache.pekko.uigc.engines.crgc.jfr.EntryFlushEvent;

public class State implements org.apache.pekko.uigc.interfaces.State {

    /** This actor's ref to itself */
    RefInfo self;
    /** Tracks references created by this actor */
    RefInfo[] createdOwners;
    RefInfo[] createdTargets;
    /** Tracks actors spawned by this actor */
    RefInfo[] spawnedActors;
    /** Tracks all the refobs that have been updated in this entry period */
    RefInfo[] updatedRefobs;
    /** Where in the array to insert the next "created" ref */
    int createdIdx;
    /** Where in the array to insert the next "spawned" ref */
    int spawnedIdx;
    /** Where in the array to insert the next "updated" refob */
    int updatedIdx;
    /** Tracks how many messages are received using each reference. */
    short recvCount;
    /** True iff the actor is a root (i.e. manually collected) */
    boolean isRoot;
    /** True if the GC has asked this actor to stop */
    boolean stopRequested;
    CrgcConfig crgcConfig;

    public static int CREATED_ACTORS_FULL = 0;
    public static int SPAWNED_ACTORS_FULL = 1;
    public static int UPDATED_REFOBS_FULL = 2;
    public static int RECV_COUNT_FULL = 3;
    public static int BLOCKED = 4;
    public static int IDLE = 5;
    public static int WAVE = 6;

    public State(RefInfo self, CrgcConfig crgcConfig) {
        this.self = self;
        this.crgcConfig = crgcConfig;
        this.createdOwners = new RefInfo[crgcConfig.EntryFieldSize];
        this.createdTargets = new RefInfo[crgcConfig.EntryFieldSize];
        this.spawnedActors = new RefInfo[crgcConfig.EntryFieldSize];
        this.updatedRefobs = new RefInfo[crgcConfig.EntryFieldSize];
        this.createdIdx = 0;
        this.spawnedIdx = 0;
        this.updatedIdx = 0;
        this.recvCount = (short) 0;
        this.isRoot = false;
        this.stopRequested = false;
    }

    public void markAsRoot() {
        this.isRoot = true;
    }

    public boolean canRecordNewRefob() {
        return createdIdx < crgcConfig.EntryFieldSize;
    }

    public void recordNewRefob(RefInfo owner, RefInfo target) {
        assert(canRecordNewRefob());
        int i = createdIdx++;
        createdOwners[i] = owner;
        createdTargets[i] = target;
    }

    public boolean canRecordNewActor() {
        return spawnedIdx < crgcConfig.EntryFieldSize;
    }

    public void recordNewActor(RefInfo child) {
        assert(canRecordNewActor());
        spawnedActors[spawnedIdx++] = child;
    }

    public boolean canRecordUpdatedRefob(RefInfo refob) {
        return refob.hasBeenRecorded() || updatedIdx < crgcConfig.EntryFieldSize;
    }

    public void recordUpdatedRefob(RefInfo refob) {
        assert(canRecordUpdatedRefob(refob));
        if (refob.hasBeenRecorded())
            return;
        refob.setHasBeenRecorded();
        updatedRefobs[updatedIdx++] = refob;
    }

    public boolean canRecordMessageReceived() {
        return recvCount < Short.MAX_VALUE;
    }

    public void recordMessageReceived() {
        assert(canRecordMessageReceived());
        recvCount++;
    }

    public void flushToEntry(boolean isBusy, Entry entry, int reason) {
        EntryFlushEvent metrics = new EntryFlushEvent();
        metrics.recvCount = recvCount;
        //System.out.println(self.ref() + " flushing because " + reason);

        entry.self = self;
        entry.isBusy = isBusy;
        entry.isRoot = isRoot;

        for (int i = 0; i < createdIdx; i++) {
            entry.createdOwners[i] = this.createdOwners[i];
            entry.createdTargets[i] = this.createdTargets[i];
            this.createdOwners[i] = null;
            this.createdTargets[i] = null;
        }
        // Add null terminators to the end of the arrays if they're underfull
        if (createdIdx < crgcConfig.EntryFieldSize) {
            entry.createdOwners[createdIdx] = null;
            entry.createdTargets[createdIdx] = null;
        }
        createdIdx = 0;

        for (int i = 0; i < spawnedIdx; i++) {
            entry.spawnedActors[i] = this.spawnedActors[i];
            this.spawnedActors[i] = null;
        }
        if (spawnedIdx < crgcConfig.EntryFieldSize) {
            entry.spawnedActors[spawnedIdx] = null;
        }
        spawnedIdx = 0;

        entry.recvCount = recvCount;
        recvCount = (short) 0;

        for (int i = 0; i < updatedIdx; i++) {
            entry.updatedRefs[i] = this.updatedRefobs[i];
            entry.updatedInfos[i] = this.updatedRefobs[i].info();
            this.updatedRefobs[i].reset();
            this.updatedRefobs[i] = null;
        }
        if (updatedIdx < crgcConfig.EntryFieldSize) {
            entry.updatedRefs[updatedIdx] = null;
            entry.updatedInfos[updatedIdx] = 0;
        }
        updatedIdx = 0;

        metrics.commit();
    }

}
