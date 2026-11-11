package org.lumen.installer;

import org.lumen.installer.core.Engine;
import org.lumen.installer.core.Msg;

import java.util.ArrayList;
import java.util.List;

/** What the screen shows, written by the worker thread, read by the activity every few hundred ms. */
final class Snap {
    static final Snap I = new Snap();

    static final class Line {
        final Msg msg;
        final String[] args;
        Line(Msg m, String[] a) { msg = m; args = a; }
    }

    volatile int seq;
    volatile boolean busy;
    volatile String task = "";
    final Engine.Status[] steps = new Engine.Status[Engine.Step.values().length];
    volatile int curStep = -1;
    volatile long done, total;
    volatile Line last;
    final List<Line> lines = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    /** set by the worker after a task: what the buttons offer next */
    volatile boolean prepared, armed, canStart, trialOnly;
    volatile Engine.Trial trial = Engine.Trial.NONE;
    volatile Engine.Detection det;
    volatile String need = "", free = "";
    /** the image source (download or USB stick folder), the keep-on-stick choice, and whether a stick is in */
    volatile Engine.Source source = Engine.Source.NET;
    volatile boolean keepOnStick, stickPresent;
    /** Version: Stable or Beta (download source, a model with a beta channel) */
    volatile Engine.Channel channel = Engine.Channel.STABLE;
    /** the list of projectors was asked for once since the app started */
    volatile boolean indexTried;
    /** can the downloaded release install itself from the trial: 1 yes, 0 no, -1 unknown */
    volatile int handover = -1;

    Snap() { resetSteps(); }

    synchronized void resetSteps() {
        for (int i = 0; i < steps.length; i++) steps[i] = Engine.Status.WAITING;
        curStep = -1;
        done = total = 0;
        lines.clear();
        last = null;
        seq++;
    }

    synchronized void step(Engine.Step s, Engine.Status st) {
        steps[s.ordinal()] = st;
        curStep = s.ordinal();
        if (st != Engine.Status.RUNNING) { done = 0; total = 0; }
        seq++;
    }

    synchronized void progress(Engine.Step s, long d, long t) {
        curStep = s.ordinal();
        done = d;
        total = t;
        seq++;
    }

    synchronized void message(Msg m, String[] a) {
        last = new Line(m, a);
        lines.add(last);
        if (lines.size() > 40) lines.remove(0);
        seq++;
    }

    synchronized void log(String s) {
        log.add(s);
        if (log.size() > 300) log.remove(0);
        seq++;
    }

    synchronized List<String> tail(int n) {
        return new ArrayList<>(log.subList(Math.max(0, log.size() - n), log.size()));
    }

    synchronized List<Line> messages() { return new ArrayList<>(lines); }
}
