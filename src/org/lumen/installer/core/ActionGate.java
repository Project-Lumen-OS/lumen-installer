package org.lumen.installer.core;

/**
 * One task at a time from the screen. The first press of a button that starts a {@link Task} takes the gate and
 * starts it. Every later press, of the same button or another one, does nothing until that task ends. The end, a
 * failure too, opens the gate again, and the screen shows its buttons with the result. A restart that went through
 * never ends: the projector goes down with the gate closed.
 */
public final class ActionGate {
    /** the remote's keys the screen asks about */
    public enum Key { SELECT, BACK, MOVE, OTHER }

    private Task held;

    /** Starts {@code start} when no task holds the gate and returns true. Otherwise does nothing and returns false. */
    public boolean press(Task t, Runnable start) {
        synchronized (this) {
            if (held != null) return false;
            held = t;
        }
        try {
            start.run();
        } catch (RuntimeException e) {
            end(t);
            throw e;
        }
        return true;
    }

    /** The task ended, done or failed: the gate opens. The end of a task that does not hold it changes nothing. */
    public synchronized void end(Task t) {
        if (held == t) held = null;
    }

    /** the task that holds the gate, null when it is open */
    public synchronized Task held() { return held; }

    /**
     * Does the screen drop this key while a task holds the gate? OK does nothing on the busy button and works only on
     * a control the running task offers ({@code onOffered}: Pause of a download, About). The arrows stay where they
     * are unless the task offers Pause. BACK does nothing during root work and the restart request.
     */
    public synchronized boolean swallows(Key k, boolean onOffered) {
        if (held == null) return false;
        switch (k) {
            case SELECT: return !onOffered;
            case MOVE: return !held.pausable;
            case BACK: return !held.backLeaves;
            default: return false;
        }
    }
}
