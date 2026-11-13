package org.lumen.installer.core;

import java.util.Locale;

/** The work a button of the screen hands to the app's service, one at a time behind {@link ActionGate}. */
public enum Task {
    /** download the signed catalog of builds again (at the app's start and on Try again) */
    CATALOG(false, true),
    /** check, download, verify, prepare */
    PREPARE(true, true),
    /** the trial goes into the projector's memory and one boot is armed */
    START(false, false),
    /** remove the old trial, prepare the new image, start it */
    REPLACE(true, true),
    /** arm the installed trial for one more boot */
    AGAIN(false, false),
    /** remove the trial */
    REMOVE(false, false),
    /** restart the projector */
    RESTART(false, false);

    /** Pause is offered while it runs (it downloads) */
    public final boolean pausable;
    /** BACK leaves the screen while it runs (a download goes on in the background); during root work and the
     *  restart request BACK does nothing */
    public final boolean backLeaves;

    Task(boolean pausable, boolean backLeaves) {
        this.pausable = pausable;
        this.backLeaves = backLeaves;
    }

    /** the name in the service's intent */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** the task of an intent's name, null for anything else */
    public static Task of(String id) {
        for (Task t : values()) if (t.id().equals(id)) return t;
        return null;
    }
}
