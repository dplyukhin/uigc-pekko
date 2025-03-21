package org.apache.pekko.uigc.engines.crgc;

import org.apache.pekko.actor.Address;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.uigc.engines.crgc.jfr.DeltaGraphSerialization;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A compact, serializable way to summarize a collection of {@link Entry}s produced by a particular actor system.
 * Initially, a delta graph is empty. Merging an entry into the graph may add {@link DeltaShadow}s to the graph;
 * each delta shadow represents an actor referenced by one of the entries.
 * <p>
 * To reduce bandwidth, the graph encodes ActorRefs with a compressed ID (a short integer).
 * The {@link DeltaGraph#decoder} method produces an array for mapping compressed IDs back to ActorRefs.
 * <p>
 * Delta shadows are stored consecutively in the {@link DeltaGraph#shadows} array. Their index in the array
 * is the same as their compressed ID.
 */
public class DeltaGraph implements Serializable {

    //@JsonDeserialize(using = AkkaSerializationDeserializer.class)
    //@JsonSerialize(using = AkkaSerializationSerializer.class)
    /**
     * The compression table that maps ActorRefs to compressed IDs.
     */
    HashMap<ActorRef, Short> compressionTable;
    /**
     * Delta shadows are stored in this array. An actor's compressed ID is its position in the array.
     */
    ArrayList<DeltaShadow> shadows;
    /**
     * The address of the node that produced this graph.
     */
    Address address;
    /**
     * CRGC Configuration options.
     */
    CrgcConfig crgcConfig;

    /**
     * FOR INTERNAL USE ONLY! The serializer wants a public empty constructor.
     * Use {@link DeltaGraph#initialize} instead.
     *
     * @deprecated
     */
    public DeltaGraph() {}

    /**
     * The main constructor for delta graphs.
     *
     * @param address the address of the ActorSystem that created this graph
     */
    public static DeltaGraph initialize(Address address, CrgcConfig crgcConfig) {
        DeltaGraph graph = new DeltaGraph();
        graph.compressionTable = new HashMap<>();
        graph.shadows = new ArrayList<>();
        graph.address = address;
        graph.crgcConfig = crgcConfig;
        return graph;
    }

    /**
     * Merges the given entry into the delta graph. Assumes the graph is not full.
     */
    public void mergeEntry(Entry entry) {
        // Local information.
        short selfID = encode(entry.self);
        DeltaShadow selfShadow = shadows.get(selfID);
        selfShadow.interned = true;
        selfShadow.recvCount += entry.recvCount;
        selfShadow.isBusy = entry.isBusy;
        selfShadow.isRoot = entry.isRoot;

        if (entry.creator != null) {
            short creatorID = encode(entry.creator);
            DeltaShadow creatorShadow = shadows.get(creatorID);
            selfShadow.supervisor = creatorID;
            updateOutgoing(creatorShadow.outgoing, selfID, 1);
        }

        // Deactivated refs.
        if (entry.deactivatedRefs != null) {
            for (Map.Entry<RefInfo, Integer> deactivationEntry : entry.deactivatedRefs.entrySet()) {
                RefInfo target = deactivationEntry.getKey();
                short targetID = encode(target);
                int count = deactivationEntry.getValue(); // How many references to target have been deactivated
                updateOutgoing(selfShadow.outgoing, targetID, -count);
            }
        }

        // Updated refs.
        if (entry.updatedRefobs != null) {
            for (int i = 0; i < entry.updatedRefobs.size(); i++) {
                RefInfo updatedRef = entry.updatedRefobs.get(i);
                short updatedID = encode(updatedRef);
                int sendCount = entry.sendCounts[i]; // The number of messages that self has sent to updatedRef
                HashMap<RefInfo, Integer> createdRefs = entry.createdRefobs[i];
                DeltaShadow updatedShadow = shadows.get(updatedID);

                updatedShadow.recvCount -= sendCount; // may become negative!

                if (createdRefs != null) {
                    for (Map.Entry<RefInfo, Integer> creationEntry : createdRefs.entrySet()) {
                        RefInfo targetRef = creationEntry.getKey();
                        short targetID = encode(targetRef);
                        int creationCount = creationEntry.getValue(); // The number of refs sent to updatedRef pointing to targetRef
                        updateOutgoing(updatedShadow.outgoing, targetID, creationCount);
                    }
                }
            }
        }
    }

    private void updateOutgoing(Map<Short, Integer> outgoing, Short target, int delta) {
        int count = outgoing.getOrDefault(target, 0);
        if (count + delta == 0) {
            // Instead of writing zero, we delete the count.
            outgoing.remove(target);
        }
        else {
            outgoing.put(target, count + delta);
        }
    }

    /**
     * Returns the compressed ID of a reference, possibly allocating a new {@link DeltaShadow} in the process.
     */
    private short encode(RefInfo refob) {
        return encode(refob.target());
    }

    /**
     * Returns the compressed ID of a reference, possibly allocating a new {@link DeltaShadow} in the process.
     */
    private short encode(ActorRef ref) {
        if (compressionTable.containsKey(ref))
            return compressionTable.get(ref);

        int id = shadows.size();
        compressionTable.put(ref, (short) id);
        shadows.add(new DeltaShadow());
        return (short) id;
    }

    /**
     * Returns an array that maps compressed IDs to ActorRefs. This is used to decode the compressed IDs
     * used in {@link DeltaShadow}.
     */
    public ActorRef[] decoder() {
        // This will act as a hashmap, mapping compressed IDs to actorRefs.
        ActorRef[] refs = new ActorRef[this.shadows.size()];
        for (Map.Entry<ActorRef, Short> entry : this.compressionTable.entrySet()) {
            refs[entry.getValue()] = entry.getKey();
        }
        return refs;
    }

    /**
     * Whether the graph is full, i.e. merging new entries can cause an error.
     */
    public boolean isFull() {
        /* Sleazy hack to avoid overflows: We know that merging an entry can only produce
         * so many new shadows. So we never fill the delta graph to actual capacity; we
         * tell the GC to finalize the delta graph if the next entry *could potentially*
         * cause an overflow. */
        return shadows.size() >= crgcConfig.MaxDeltaGraphSize;
    }

    /**
     * Whether the graph is nonempty, i.e. there is at least one {@link DeltaShadow} in the graph.
     */
    public boolean nonEmpty() {
        return !shadows.isEmpty();
    }

    public void serialize(ObjectOutputStream out) throws IOException {
        DeltaGraphSerialization metrics = new DeltaGraphSerialization();

        // Serialize the address
        out.writeObject(address);

        // Serialize the shadows
        out.writeShort(shadows.size());
        metrics.shadowSize += 2;
        for (DeltaShadow shadow : shadows) {
            metrics.shadowSize += shadow.serialize(out);
        }

        // Serialize the compression table
        assert(compressionTable.size() == shadows.size());
        for (Map.Entry<ActorRef, Short> entry : compressionTable.entrySet()) {
            out.writeShort(entry.getValue());
            out.writeObject(entry.getKey());
            metrics.compressionTableSize += 2 + entry.getKey().toString().length();
        }

        metrics.commit();
    }

    public void deserialize(ObjectInputStream in) throws IOException, ClassNotFoundException {
        // Deserialize the address
        address = (Address) in.readObject();

        // Deserialize the shadows
        int size = in.readShort();
        shadows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            shadows.add(new DeltaShadow());
            shadows.get(i).deserialize(in);
        }

        // Deserialize the compression table; it will have size `size`
        compressionTable = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            short id = in.readShort();
            ActorRef ref = (ActorRef) in.readObject();
            compressionTable.put(ref, id);
        }
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        serialize(out);
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        deserialize(in);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DeltaGraph that = (DeltaGraph) obj;
        return this.shadows.size() == that.shadows.size() && compressionTable.equals(that.compressionTable) && address.equals(that.address);
    }

}
