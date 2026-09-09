package game.wreckriff.diagnostics;

/** Coordinates diagnostic checks and renderer shutdown; any failure remains latched. */
public final class DiagnosticCompletion {
    private boolean stopRequested,checksSucceeded,shutdownComplete,failed;

    /** Returns true only to the first stop requester. A repeated failed request still invalidates PASS. */
    public synchronized boolean requestStop(boolean checksPassed) {
        if(!checksPassed) failed=true;
        if(stopRequested) return false;
        stopRequested=true;checksSucceeded=checksPassed;
        return true;
    }

    public synchronized void shutdownFailed() { failed=true; }

    public synchronized void shutdownCompleted() { shutdownComplete=true; }

    public synchronized boolean passed() {
        return stopRequested&&checksSucceeded&&shutdownComplete&&!failed;
    }
}
