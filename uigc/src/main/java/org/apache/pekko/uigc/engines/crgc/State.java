package org.apache.pekko.uigc.engines.crgc;

import org.apache.pekko.uigc.engines.crgc.jfr.EntryFlushEvent;

import java.util.ArrayList;
import java.util.HashMap;

public class State implements org.apache.pekko.uigc.interfaces.State {

    RefInfo self;
    RefInfo creator;
    /** Tracks all the refobs that have been updated in this entry period */
    ArrayList<RefInfo> updatedRefobs;
    /** Tracks how many references to the target actor have been deactivated */
    HashMap<RefInfo, Integer> deactivatedRefs;
    /** Tracks how many messages this actor has received since the last entry */
    int recvCount;
    /** True iff the actor has anything to report in its next entry */
    boolean hasChanged;
    /** True iff the actor is a root (i.e. manually collected) */
    boolean isRoot;
    /** True if the GC has asked this actor to stop */
    boolean stopRequested;
    CrgcConfig crgcConfig;

    public static int SEND_COUNT_FULL = 1;
    public static int RECV_COUNT_FULL = 3;
    public static int BLOCKED = 4;
    public static int WAVE = 5;

    public State(RefInfo self, CrgcConfig crgcConfig) {
        this.self = self;
        this.crgcConfig = crgcConfig;
        this.updatedRefobs = null;
        this.deactivatedRefs = null;
        this.recvCount = 0;
        this.hasChanged = false;
        this.isRoot = false;
        this.stopRequested = false;
    }

    public void markAsRoot() {
        if (!this.isRoot) {
            this.hasChanged = true;
        }
        this.isRoot = true;
    }

    public boolean canRecordMessageReceived() {
        return recvCount < Integer.MAX_VALUE;
    }

    public void recordMessageReceived() {
        this.hasChanged = true;
        recvCount++;
    }

    public void recordUpdatedRefob(RefInfo refob) {
        this.hasChanged = true;
        if (updatedRefobs == null) {
            updatedRefobs = new ArrayList<>(4);
        }
        updatedRefobs.add(refob);
    }

    public void recordDeactivatedRefob(RefInfo refob) {
        this.hasChanged = true;
        if (deactivatedRefs == null) {
            deactivatedRefs = new HashMap<>();
        }
        int count = deactivatedRefs.getOrDefault(refob, 0);
        deactivatedRefs.put(refob, count + 1);
    }

    public void setCreator(RefInfo creator) {
        this.hasChanged = true;
        this.creator = creator;
    }

    public void flushToEntry(boolean isBusy, Entry entry, int reason) {
        EntryFlushEvent metrics = new EntryFlushEvent();
        metrics.recvCount = recvCount;
        //System.out.println(self.ref() + " flushing because " + reason);

        // Set basic fields
        entry.self = self;
        entry.creator = creator;
        entry.isBusy = isBusy;
        entry.isRoot = isRoot;
        entry.recvCount = recvCount;
        entry.updatedRefobs = updatedRefobs;
        entry.deactivatedRefs = deactivatedRefs;

        // Set the send counts
        if (updatedRefobs != null) {
            entry.sendCounts = new int[updatedRefobs.size()];
            entry.createdRefobs = new HashMap[entry.sendCounts.length];

            int i = 0;
            for (RefInfo refInfo : updatedRefobs) {
                entry.sendCounts[i] = refInfo.sendCount();
                entry.createdRefobs[i] = refInfo.createdRefs();
                refInfo.reset();
                i++;
            }
        }

        // Reset stuff
        this.creator = null;
        this.updatedRefobs = null;
        this.deactivatedRefs = null;
        this.recvCount = 0;
        this.hasChanged = false;

        metrics.commit();
    }

}
