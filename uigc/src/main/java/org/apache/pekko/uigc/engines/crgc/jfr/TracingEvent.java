package org.apache.pekko.uigc.engines.crgc.jfr;

import jdk.jfr.*;

@Label("CRGC Tracing Event")
@Category("UIGC")
@Description("Tracing the shadow graph and asking garbage actors to stop.")
@StackTrace(false)
public class TracingEvent extends Event {

    @Label("Number of Garbage Actors Found")
    public int numGarbageActors;

    @Label("Number of Live Actors Remaining")
    public int numLiveActors;

    @Label("Time to Trace (ns)")
    public long nanosToTrace;

}
