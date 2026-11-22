package org.lumen.contrib;

/** What a unit tells the contribution base about. */
public enum Kind {
    /** The stock system info of this firmware was sent (ref = its report code). */
    REPORT("report"),
    /** A trial of Lumen OS started and came back (version = the trial build). */
    TRIAL("trial"),
    /** Lumen OS was installed for good (version = the installed build). */
    INSTALL("install");

    public final String wire;

    Kind(String wire) {
        this.wire = wire;
    }
}
