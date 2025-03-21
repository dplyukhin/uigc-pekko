package org.apache.pekko.uigc.engines.crgc;

import org.agrona.collections.Object2IntHashMap;
import org.apache.pekko.actor.Address;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.uigc.engines.crgc.jfr.TracingEvent;

import java.util.*;

public class ShadowGraph {
    /** The size of each array in an entry */
    boolean MARKED = true;
    int totalActorsSeen = 0;
    ArrayList<Shadow> from;
    HashMap<ActorRef, Shadow> shadowMap;
    CrgcConfig crgcConfig;

    public ShadowGraph(CrgcConfig crgcConfig) {
        this.from = new ArrayList<>();
        this.shadowMap = new HashMap<>();
        this.crgcConfig = crgcConfig;
    }

    public Shadow getShadow(RefInfo refob) {
        // Check if it's in the cache. This saves us an expensive hash table lookup.
        if (refob.targetShadow() != null)
            return refob.targetShadow();

        // Try to get it from the collection of all my shadows. Save it in the cache.
        Shadow shadow = getShadow(refob.target());
        refob.targetShadow_$eq(shadow);

        return shadow;
    }

    public Shadow getShadow(ActorRef ref) {
        // Try to get it from the collection of all my shadows.
        Shadow shadow = shadowMap.get(ref);
        if (shadow != null)
            return shadow;

        // Haven't heard of this actor yet. Create a shadow for it.
        return makeShadow(ref);
    }

    public Shadow makeShadow(ActorRef ref) {
        totalActorsSeen++;
        // Haven't heard of this actor yet. Create a shadow for it.
        Shadow shadow = new Shadow();
        shadow.self = ref;
        shadow.mark = !MARKED;
            // The value of MARKED flips on every GC scan. Make sure this shadow is unmarked.
        shadow.interned = false;
            // We haven't seen this shadow before, so we can't have received a snapshot from it.
        shadow.isLocal = false;
            // By default we assume that the shadow is from a different node. If the shadow
            // graph gets an entry from the actor, then it turns out the actor is local.

        shadowMap.put(ref, shadow);
        from.add(shadow);
        return shadow;
    }

    public void updateOutgoing(Object2IntHashMap<Shadow> outgoing, Shadow target, int delta) {
        int count = outgoing.getValue(target);
        if (count + delta == 0) {
            // Instead of writing zero, we delete the count.
            outgoing.remove(target);
        }
        else {
            outgoing.put(target, count + delta);
        }
    }

    public void mergeEntry(Entry entry) {
        // Local information.
        Shadow selfShadow = getShadow(entry.self);
        selfShadow.interned = true; // We now have a snapshot from the actor.
        selfShadow.isLocal = true;  // Entries only come from actors on this node.
        selfShadow.recvCount += entry.recvCount;
        selfShadow.isBusy = entry.isBusy;
        selfShadow.isRoot = entry.isRoot;
        if (entry.creator != null) {
            Shadow creatorShadow = getShadow(entry.creator);
            selfShadow.supervisor = creatorShadow;
            updateOutgoing(creatorShadow.outgoing, selfShadow, 1);
        }

        // Deactivated refs.
        if (entry.deactivatedRefs != null) {
            for (Map.Entry<RefInfo, Integer> deactivationEntry : entry.deactivatedRefs.entrySet()) {
                RefInfo target = deactivationEntry.getKey();
                int count = deactivationEntry.getValue(); // How many references to target have been deactivated
                Shadow targetShadow = getShadow(target);
                updateOutgoing(selfShadow.outgoing, targetShadow, -count);
            }
        }

        // Updated refs.
        if (entry.updatedRefobs != null) {
            for (int i = 0; i < entry.updatedRefobs.size(); i++) {
                RefInfo updatedRef = entry.updatedRefobs.get(i);
                int sendCount = entry.sendCounts[i]; // The number of messages that self has sent to updatedRef
                HashMap<RefInfo, Integer> createdRefs = entry.createdRefobs[i];
                Shadow updatedShadow = getShadow(updatedRef);

                updatedShadow.recvCount -= sendCount; // may become negative!

                if (createdRefs != null) {
                    for (Map.Entry<RefInfo, Integer> creationEntry : createdRefs.entrySet()) {
                        RefInfo targetRef = creationEntry.getKey();
                        int creationCount = creationEntry.getValue(); // The number of refs sent to updatedRef pointing to targetRef
                        Shadow targetShadow = getShadow(targetRef);
                        updateOutgoing(updatedShadow.outgoing, targetShadow, creationCount);
                    }
                }
            }
        }

    }

    public void mergeDelta(DeltaGraph delta) {
        // This array maps compressed IDs to ActorRefs.
        ActorRef[] decoder = delta.decoder();

        for (short i = 0; i < delta.shadows.size(); i++) {
            DeltaShadow deltaShadow = delta.shadows.get(i);
            Shadow shadow = getShadow(decoder[i]);

            shadow.interned = shadow.interned || deltaShadow.interned;
                // Set `interned` if we have already received a delta shadow in which
                // the actor was interned, or if the actor was interned in this delta.
            shadow.recvCount += deltaShadow.recvCount;
            if (deltaShadow.interned) {
                // Careful here! The isBusy and isRoot fields are only accurate if
                // the delta shadow is interned, i.e. we received an entry from this
                // actor in the given period. Otherwise, they are set at the default
                // value of `false`.
                shadow.isBusy = deltaShadow.isBusy;
                shadow.isRoot = deltaShadow.isRoot;
            }
            if (deltaShadow.supervisor >= 0) {
                shadow.supervisor = getShadow(decoder[deltaShadow.supervisor]);
            }
            for (Map.Entry<Short, Integer> entry : deltaShadow.outgoing.entrySet()) {
                short id = entry.getKey();
                int count = entry.getValue();
                updateOutgoing(shadow.outgoing, getShadow(decoder[id]), count);
            }
        }
    }

    public void mergeUndoLog(UndoLog log) {
        // 1. All actors on the node become `halted`.
        // 2. All actors have their undelivered message counts adjusted.
        // 3. All actors have their outgoing references adjusted.
        for (Shadow shadow : from) {
            if (shadow.self.path().address().equals(log.nodeAddress)) {
                shadow.isHalted = true;
            }
            UndoLog.Field field = log.admitted.get(shadow.self);
            if (field != null) {
                shadow.recvCount += field.messageCount;
                for (Map.Entry<org.apache.pekko.actor.ActorRef,Integer> pair : field.createdRefs.entrySet()) {
                    updateOutgoing(shadow.outgoing, getShadow(pair.getKey()), pair.getValue());
                }
            }
        }
    }

    public void assertEquals(ShadowGraph that) {
        HashSet<ActorRef> thisNotThat = new HashSet<>();
        for (ActorRef ref : this.shadowMap.keySet()) {
            if (!that.shadowMap.containsKey(ref)) {
                thisNotThat.add(ref);
            }
        }
        HashSet<ActorRef> thatNotThis = new HashSet<>();
        for (ActorRef ref : that.shadowMap.keySet()) {
            if (!this.shadowMap.containsKey(ref)) {
                thatNotThis.add(ref);
            }
        }
        assert (this.shadowMap.keySet().equals(that.shadowMap.keySet()))
                : "Shadow maps have different actors:\n"
                + "Actors in this, not that: " + thisNotThat + "\n"
                + "Actors in that, not this " + thatNotThis;

        for (Map.Entry<ActorRef, Shadow> entry : this.shadowMap.entrySet()) {
            Shadow thisShadow = entry.getValue();
            Shadow thatShadow = that.shadowMap.get(entry.getKey());
            thisShadow.assertEquals(thatShadow);
        }
    }

    private static boolean isPseudoRoot(Shadow shadow) {
        return (shadow.isRoot || shadow.isBusy || shadow.recvCount != 0 || !shadow.interned) && !shadow.isHalted;
    }

    public void trace(boolean shouldKill) {
        TracingEvent tracingEvent = new TracingEvent();
        tracingEvent.begin();
        long startTime = System.nanoTime();

        //System.out.println("Scanning " + from.size() + " actors...");
        ArrayList<Shadow> to = new ArrayList<>(from.size());
        // 0. Assume all shadows in `from` are in the UNMARKED state.
        //    Also assume that, if an actor has an incoming external actor, that external has a snapshot in `from`.
        // 1. Find all the shadows that are pseudoroots and mark them and move them to `to`.
        // 2. Trace a path from every marked shadow, moving marked shadows to `to`.
        // 3. Find all unmarked shadows in `from` and kill those actors.
        // 4. The `to` set becomes the new `from` set.
        for (Shadow shadow : from) {
            if (isPseudoRoot(shadow)) {
                to.add(shadow);
                shadow.mark = MARKED;
                //shadow.markDepth = 1;
            }
        }
        for (int scanptr = 0; scanptr < to.size(); scanptr++) {
            Shadow owner = to.get(scanptr);
            if (owner.isHalted) {
                // Don't mark actors reachable from halted actors.
                continue;
            }
            // Mark the outgoing references whose count is greater than zero
            for (Map.Entry<Shadow, Integer> entry : owner.outgoing.entrySet()) {
                Shadow target = entry.getKey();
                if (entry.getValue() > 0 && target.mark != MARKED) {
                    to.add(target);
                    target.mark = MARKED;
                    //target.markDepth = owner.markDepth + 1;
                }
                //if (entry.getValue() > 0 && target.markDepth > owner.markDepth + 1) {
                //    target.markDepth = owner.markDepth + 1;
                //}
            }
            // Next, we mark the actors that are monitoring or supervising this one.
            //
            // Since killing a supervisor causes all its descendants to be killed, a supervisor
            // should not be killed unless all of its descendants are garbage. We can prevent
            // supervisors from being killed by marking them, i.e. telling the GC that they're
            // not garbage.
            //
            // In theory, marking supervisors violates completeness. A supervisor S could become garbage and
            // one of its descendants D might never become garbage. If S has a
            // reference to a garbage actor G, then G should be collected. But since S
            // is marked as non-garbage, G will also be marked as non-garbage.
            //
            // I don't think these situations come up in practice, so our little shortcut
            // of marking supervisors is good enough. If we wanted theoretical completeness,
            // we'd need an extra bit of information that says "this actor is garbage but
            // should not be killed yet."
            Shadow supervisor = owner.supervisor;
            if (supervisor != null) {
                if (supervisor.mark != MARKED) {
                    to.add(supervisor);
                    supervisor.mark = MARKED;
                }
                //if (supervisor.markDepth > owner.markDepth + 1) {
                //    supervisor.markDepth = owner.markDepth + 1;
                //}
            }
        }

        // Unmarked actors are garbage. Killing an actor causes all its descendants to die too.
        // As remarked above, an actor will only be unmarked if all its descendants are unmarked.
        // So it suffices to send StopMsg to the oldest unmarked ancestors, not their descendants.
        for (Shadow shadow : from) {
            if (shadow.mark != MARKED) {
                tracingEvent.numGarbageActors++;
                shadowMap.remove(shadow.self);
                if (shadow.isLocal && shadow.supervisor.mark == MARKED && shouldKill && !shadow.isHalted) {
                    shadow.self.tell(StopMsg$.MODULE$, null);
                }
            }
            else {
                tracingEvent.numLiveActors++;
            }
        }
        from = to;
        MARKED = !MARKED;

        tracingEvent.nanosToTrace = System.nanoTime() - startTime;
        tracingEvent.commit();
    }

    public void startWave() {
        int count = 0;
        for (Shadow shadow : from) {
            if (shadow.isRoot && shadow.isLocal) {
                count++;
                shadow.self.tell(WaveMsg$.MODULE$, null);
            }
        }
    }

    /** Debugging method to look at how many actors are reachable by actors at `location`. */
    public int investigateRemotelyHeldActors(Address location) {
        // Mark everything reachable by `location`.
        ArrayList<Shadow> to = new ArrayList<>();
        for (Shadow shadow : from) {
            if (shadow.self.path().address().equals(location)) {
                to.add(shadow);
                shadow.mark = MARKED;
            }
        }

        for (int scanptr = 0; scanptr < to.size(); scanptr++) {
            Shadow owner = to.get(scanptr);
            if (owner.isHalted)
                continue;
            for (Map.Entry<Shadow, Integer> entry : owner.outgoing.entrySet()) {
                Shadow target = entry.getKey();
                if (entry.getValue() > 0 && target.mark != MARKED) {
                    to.add(target);
                    target.mark = MARKED;
                }
            }
        }

        // Now unmark all those actors, resetting the state so we can do GC again.
        for (Shadow shadow : to) {
            shadow.mark = !MARKED;
        }
        return to.size();
    }

    public void addressesInGraph() {
        HashMap<Address, Integer> addresses = new HashMap<>();
        for (Shadow shadow : from) {
            int count = addresses.getOrDefault(shadow.self.path().address(), 0);
            addresses.put(shadow.self.path().address(), count+1);
        }
        for (Map.Entry<Address, Integer> entry : addresses.entrySet()) {
            System.out.println(entry.getValue() + " uncollected at " + entry.getKey());
        }
    }

    /** Debugging method to dump information about the live set. */
    public void investigateLiveSet() {
        int nonInternedActors = 0;
        int rootActors = 0;
        int busyActors = 0;
        int unblockedActors = 0;
        int nonLocalActors = 0;
        HashMap<Integer, Integer> markDepths = new HashMap<>();
        for (Shadow shadow : from) {
            if (!shadow.interned) nonInternedActors++;
            if (shadow.isRoot) {
                rootActors++;
                System.out.println(shadow.outgoing.size() + " acquaintances of root actor " + shadow.self);
            }
            if (shadow.isBusy) busyActors++;
            if (shadow.recvCount != 0) unblockedActors++;
            if (!shadow.isLocal) nonLocalActors++;

            //int x = markDepths.getOrDefault(shadow.markDepth, 0);
            //markDepths.put(shadow.markDepth, x + 1);

            if (shadow.isLocal) {
                int c = 0;
                for (Shadow out : shadow.outgoing.keySet()) {
                    if (!out.isLocal) {
                        c++;
                        System.out.println("Local " + shadow.self + " appears acquainted with remote " + out.self + " (" + shadow.outgoing.get(out) + ")");
                    }
                }
                if (c > 0)
                    System.out.println("Local " + shadow.self + " has " + c + " nonlocal apparent acquaintances.");
            }
            else {
                int c = 0;
                for (Shadow out : shadow.outgoing.keySet()) {
                    if (out.isLocal) c++;
                }
                if (c > 0)
                    System.out.println("Remote " + shadow.self + " has " + c + " apparent acquaintances that are local to this node.");
            }
        }
        System.out.println(
                nonInternedActors + " actors not yet interned;\n" +
                rootActors + " root actors;\n" +
                busyActors + " busy actors;\n" +
                nonLocalActors + " nonlocal actors;\n" +
                unblockedActors + " actors have nonzero receive counts.\n"
        );
        for (int depth : markDepths.keySet()) {
            System.out.println(markDepths.get(depth) + " actors at mark depth " + depth + "\n");
        }
    }
}
