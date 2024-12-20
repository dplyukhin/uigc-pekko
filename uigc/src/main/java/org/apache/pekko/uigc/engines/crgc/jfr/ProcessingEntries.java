package org.apache.pekko.uigc.engines.crgc.jfr;

import jdk.jfr.*;

@Label("CRGC Processing Entries")
@Category("UIGC")
@Description("Local GC reading entries and updating the shadow graph.")
@StackTrace(false)
public class ProcessingEntries extends Event {

    @Label("Number of Entries Processed")
    public int numEntries;

    @Label("Time to Process Entries (ns)")
    public long nanosToProcess;

}
