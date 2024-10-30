package org.apache.pekko.uigc.engines.crgc;

/**
 * A set of phantom actor references. The set is designed to use very little memory with
 * fast insertion.
 */
public class PhantomBuffer {

    // NOTE: Indices above idx may contain garbage deactivated refs
    PhantomActorRef[] buffer;
    /** The index of the next free slot in the buffer. */
    int idx;

    public PhantomBuffer() {
        buffer = new PhantomActorRef[8];
    }

    public void cull() {
        int j = 0;
        // Invariant: buffer[0..j) contains only active refs
        // Invariant: buffer[j..idx) contains only deactivated refs
        for (int i = 0; i < idx; i++) {
            if (!buffer[i].isDeactivated()) {
                // Overwrite the first deactivated ref with this active one
                buffer[j++] = buffer[i];
            }
        }
        idx = j;
    }

    public void add(PhantomActorRef ref) {
        if (idx == buffer.length) {
            PhantomActorRef[] newBuffer = new PhantomActorRef[buffer.length * 2];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            buffer = newBuffer;
        }
        buffer[idx++] = ref;
    }

}
