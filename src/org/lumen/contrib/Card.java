package org.lumen.contrib;

/** The card the app shows after a call, one per state (the texts belong to the app). */
public enum Card {
    /** Nothing to offer (offline, known firmware, nothing left to earn). */
    NONE,
    /** "This firmware is not in the base yet: send the system info". */
    SEND_INFO,
    /** A claim code to take with the phone (QR + text); {@link Claim#waiting} adds "counts after a check". */
    CODE,
    /** "Lumen OS was installed on this projector before": no code this time. */
    INSTALLED_BEFORE
}
