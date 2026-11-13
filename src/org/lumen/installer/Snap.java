package org.lumen.installer;

import org.lumen.installer.core.Engine;
import org.lumen.installer.core.ImageSource;
import org.lumen.installer.core.InstallStep;
import org.lumen.installer.core.Msg;
import org.lumen.installer.core.ReleaseChannel;
import org.lumen.installer.core.StepStatus;
import org.lumen.installer.core.TrialState;

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
    final StepStatus[] steps = new StepStatus[InstallStep.values().length];
    volatile int curStep = -1;
    volatile long done, total;
    volatile Line last;
    final List<Line> lines = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    /** set by the worker after a task: what the buttons offer next */
    volatile boolean prepared, armed, canStart, trialOnly;
    volatile TrialState trial = TrialState.NONE;
    volatile Engine.Detection det;
    volatile String need = "", free = "";
    /** the image source (download or a LumenOS folder on a USB stick) */
    volatile ImageSource source = ImageSource.NET;
    /** Version: Stable or Beta (download source, a model with a stable and a beta build) */
    volatile ReleaseChannel channel = ReleaseChannel.STABLE;
    /** the catalog was asked for once since the app started */
    volatile boolean catalogTried;
    /** does the build install itself from the trial on this stock version: 1 yes, 0 no, -1 not checked yet */
    volatile int handover = -1;

    Snap() { resetSteps(); }

    synchronized void resetSteps() {
        for (int i = 0; i < steps.length; i++) steps[i] = StepStatus.WAITING;
        curStep = -1;
        done = total = 0;
        lines.clear();
        last = null;
        seq++;
    }

    synchronized void step(InstallStep s, StepStatus st) {
        steps[s.ordinal()] = st;
        curStep = s.ordinal();
        if (st != StepStatus.RUNNING) { done = 0; total = 0; }
        seq++;
    }

    synchronized void progress(InstallStep s, long d, long t) {
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
