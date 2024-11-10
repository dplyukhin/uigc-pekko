package org.apache.pekko.uigc.engines.crgc;

import java.util.ArrayList;
import java.util.HashMap;

public class Entry {
    public RefInfo self;
    public RefInfo creator;
    HashMap<RefInfo, Integer> deactivatedRefs;
    ArrayList<RefInfo> updatedRefobs;
    public int[] sendCounts;
    public HashMap[] createdRefobs;
    public int recvCount;
    public boolean isBusy;
    public boolean isRoot;

    public Entry() {
        this.self            = null;
        this.deactivatedRefs = null;
        this.updatedRefobs   = null;
        this.sendCounts      = null;
        this.createdRefobs   = null;
        this.recvCount       = 0;
        this.isBusy          = false;
        this.isRoot          = false;
    }
}
