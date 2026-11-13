package org.lumen.snapshot;

import java.nio.charset.StandardCharsets;

/**
 * Android's Virtual A/B update as libsnapshot keeps it in /metadata/ota (system/core/fs_mgr/libsnapshot snapshot.cpp
 * and snapshot.proto, Android 14 on the XGIMI stock): the state file, which states are an update still being
 * finished, the files RemoveAllUpdateState deletes, the device-mapper names of a partition under update and the
 * names of the COW partitions an update adds to super.
 */
public final class UpdateState {
    private UpdateState() {}

    /** UpdateState of snapshot.proto by number; the Android 10 state file held these words */
    public static final String[] NAMES = {"none", "initiated", "unverified", "merging", "merge-needs-reboot",
            "merge-completed", "merge-failed", "cancelled"};
    public static final int UNKNOWN = -1, NONE = 0, INITIATED = 1, UNVERIFIED = 2, MERGING = 3, MERGE_NEEDS_REBOOT = 4,
            MERGE_COMPLETED = 5, MERGE_FAILED = 6, CANCELLED = 7;

    /** the boot indicator: written when an update is applied, kept through the merge */
    public static final String BOOT_INDICATOR = "snapshot-boot";
    /** the files RemoveAllUpdateState deletes besides the boot indicator (AOSP 14 QPR1, the last one QPR2) */
    public static final String[] MARKERS = {"rollback-indicator", "allow-forward-merge", "old-partition-metadata",
            "snapshot-boot-without-slot-switch"};
    /** the dm names libsnapshot stacks for a partition under update (GetBaseDeviceName ... GetDmUserCowName) */
    public static final String[] SNAPSHOT_DM = {"-base", "-src", "-cow-img", "-user-cow", "-user-cow-init"};
    /** kCowGroupName and the suffix of the COW partitions (GetCowName) */
    public static final String COW_GROUP = "cow", COW = "-cow";

    public static String name(int state) { return state >= 0 && state < NAMES.length ? NAMES[state] : "unknown"; }

    /** an update applied and not finished: its reboot, its merge, or a merge to finish or retry */
    public static boolean pending(int state) {
        return state == UNVERIFIED || state == MERGING || state == MERGE_NEEDS_REBOOT || state == MERGE_FAILED;
    }

    /**
     * No update in use: none, or merge-completed. libsnapshot writes merge-completed only in recovery
     * (AcknowledgeMergeSuccess: the clean-up waits for the next boot), and the next update acknowledges it and starts
     * (BeginUpdate: ProcessUpdateState, AcknowledgeMergeSuccess, RemoveAllUpdateState). What that clean-up deletes
     * (the files, the snapshots) is judged on its own by the caller.
     */
    public static boolean finished(int state) { return state == NONE || state == MERGE_COMPLETED; }

    /**
     * UpdateState of a state file the way libsnapshot's ReadSnapshotUpdateStatus reads it: empty = none, then the
     * protobuf SnapshotUpdateStatus (field 1) whenever the bytes parse as protobuf, and the Android 10 word only when
     * they do not (exactly the word, one trailing newline allowed). UNKNOWN where libsnapshot would fall back to none
     * (an unknown word, a state number out of range, field 1 not a varint): stricter on purpose.
     */
    public static int decode(byte[] b) {
        if (b.length == 0) return NONE;
        int p = protobuf(b);
        if (p != NOT_PROTOBUF) return p;
        int n = b[b.length - 1] == '\n' ? b.length - 1 : b.length;
        String w = new String(b, 0, n, StandardCharsets.US_ASCII);
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(w)) return i;
        return UNKNOWN;
    }

    private static final int NOT_PROTOBUF = -2;

    /** field 1 of SnapshotUpdateStatus (NONE when absent), or NOT_PROTOBUF when the bytes are no protobuf message */
    private static int protobuf(byte[] b) {
        int state = NONE, i = 0;
        try {
            while (i < b.length) {
                long[] k = varint(b, i);
                i = (int) k[1];
                int field = (int) (k[0] >>> 3), type = (int) (k[0] & 7);
                if (field == 0) return NOT_PROTOBUF;
                if (type == 0) {
                    long[] v = varint(b, i);
                    i = (int) v[1];
                    if (field == 1) state = v[0] >= 0 && v[0] < NAMES.length ? (int) v[0] : UNKNOWN;
                } else if (type == 1) i += 8;
                else if (type == 5) i += 4;
                else if (type == 2) {
                    long[] n = varint(b, i);
                    if (n[0] < 0 || n[0] > b.length) return NOT_PROTOBUF;
                    i = (int) (n[1] + n[0]);
                } else return NOT_PROTOBUF;
                if (i > b.length) return NOT_PROTOBUF;
                if (field == 1 && type != 0) state = UNKNOWN;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return NOT_PROTOBUF;
        }
        return state;
    }

    /** {value, next index}; at most 10 bytes */
    private static long[] varint(byte[] b, int i) {
        long r = 0;
        for (int s = 0; s < 70; s += 7) {
            int c = b[i++] & 0xff;
            r |= (long) (c & 0x7f) << s;
            if ((c & 0x80) == 0) return new long[] {r, i};
        }
        throw new ArrayIndexOutOfBoundsException("varint too long");
    }
}
