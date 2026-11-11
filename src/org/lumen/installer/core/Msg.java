package org.lumen.installer.core;

/**
 * Every user-facing message of the engine, by code. The app shows res/values{,-ru}/strings.xml entry "msg_" + the
 * lower-case name with the args (%1$s ...); a host test checks that every code has an English and a Russian text with
 * the right number of arguments.
 */
public enum Msg {
    // detection (no root, no network): the projector's props against its entry of the signed list of projectors
    SUPPORTED(0), UNKNOWN_MODEL(1), NOT_YET(1), COMPUTER_ONLY(1), BOARD_MISMATCH(2), ALREADY_LUMEN(1), IN_TRIAL(0),
    NOT_ORANGE(1), STOCK_UNSUPPORTED(2), MODEL_STOCK_UNSUPPORTED(3),
    // the signed list of projectors (Index): not there yet, bad signature or content, older than one already seen;
    // INDEX_NOTE shows the list's own text for this model (English text, Russian text: each language shows its own)
    INDEX_MISSING(0), INDEX_BAD_SIG(0), INDEX_BAD(1), INDEX_OLD(2), INDEX_NOTE(2), INDEX_LOADING(0),
    // root side: the stock firmware is this model code's own (one firmware may serve several model codes)
    PRODUCT_MISMATCH(3), MODEL_CONFIG_MISSING(1),
    // root side
    ROOT_UNAVAILABLE(0), ROOT_TIMEOUT(0), ROOT_FAILED(1), NO_TRIAL_TOOL(0), OTA_PENDING(0), TRIAL_INSTALLED(1),
    TRIAL_INSTALLED_OTHER(0), CHECK_OK(1),
    // release (network)
    NET_FAILED(1), MANIFEST_BAD_SIG(0), MANIFEST_BAD(1), MANIFEST_WRONG_DEVICE(1), SUMS_BAD_SIG(0), SUMS_NO_IMAGE(1),
    IMAGE_SIZE_BAD(1), RELEASE_FOUND(2),
    // space: what the projector's memory must hold (need, free, trial system, trial data, reserve, download, margin)
    NO_SPACE(2), SPACE_PLAN(7),
    // USB stick (a folder with the release's image + SHA256SUMS + SHA256SUMS.sig, Engine.KIT_DIR)
    STICK_NOT_FOUND(0), STICK_NO_ACCESS(0), STICK_FOUND(3), STICK_NO_IMAGE(1), STICK_MISMATCH(2), STICK_ROOT_UNREADABLE(1),
    FROM_STICK(1), NO_STICK(0), STICK_NO_ROOM(2),
    // download / verify / prepare
    DOWNLOAD_INTERRUPTED(1), DOWNLOADED(1), SHA_MISMATCH(0), VERIFIED(0), HANDOVER_YES(0), HANDOVER_NO(1),
    HANDOVER_UNKNOWN(0), PREPARED(0), PREPARE_FAILED(1),
    // start / trial management
    START_PILOT(1), TRIAL_ONLY(1), NOT_READY(0), START_FAILED(1), STARTED(0), REMOVED(0), REMOVE_FAILED(1), REBOOTING(0),
    STOPPED(0), ERROR(1);

    public final int args;

    Msg(int args) { this.args = args; }

    public String key() { return "msg_" + name().toLowerCase(java.util.Locale.ROOT); }
}
