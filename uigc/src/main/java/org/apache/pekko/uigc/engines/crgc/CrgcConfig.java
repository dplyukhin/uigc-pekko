package org.apache.pekko.uigc.engines.crgc;

import com.typesafe.config.Config;

/**
 * A data structure for quickly fetching CRGC-specific configuration options.
 */
public class CrgcConfig {
    final public short DeltaGraphSize;
    final public int EntryFieldSize;
    final public CRGC.CollectionStyle CollectionStyle;
    final public int numNodes;
    final public int waveFrequency;
    final public int entryProcessingFrequency;
    final public int tracingFrequency;

    public CrgcConfig(Config config) {
        DeltaGraphSize = (short) config.getInt("uigc.crgc.delta-graph-size");
        EntryFieldSize = config.getInt("uigc.crgc.entry-field-size");
        String _collectionStyle = config.getString("uigc.crgc.collection-style");
        switch (_collectionStyle) {
            case "wave":
                CollectionStyle = CRGC.Wave$.MODULE$;
                break;
            case "on-block":
                CollectionStyle = CRGC.OnBlock$.MODULE$;
                break;
            default:
                throw new IllegalArgumentException("Unknown collection style: " + _collectionStyle);
        }
        numNodes = config.getInt("uigc.crgc.num-nodes");
        waveFrequency = config.getInt("uigc.crgc.wave-frequency");
        entryProcessingFrequency = config.getInt("uigc.crgc.entry-processing-frequency");
        tracingFrequency = config.getInt("uigc.crgc.tracing-frequency");
    }
}
