package org.lumen.installer.core;

/** what a stock XGIMI update left, as StockUpdate.judge reads the root probe */
public enum StockVerdict {
    /** no update, no leftovers */
    NONE,
    /** an update applied and not finished: restart, leave XGIMI on about 20 minutes */
    PENDING,
    /** COW partitions of a finished update, nothing else left: the install clears them */
    LEFTOVERS,
    /** COW partitions and something the install will not clear (an unfinished clean-up, odd names) */
    LEFTOVERS_UNSURE
}
