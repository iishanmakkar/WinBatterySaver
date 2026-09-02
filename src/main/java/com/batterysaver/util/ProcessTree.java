package com.batterysaver.util;

import java.util.Optional;

/** Process-tree helpers shared by the throttling engines. */
public final class ProcessTree {

    /** Max parent hops when walking up a process tree (guards against cycles from PID reuse). */
    private static final int MAX_DEPTH = 8;

    /**
     * True if {@code pid} is {@code ancestorPid} itself or a descendant of it.
     * Used by the throttling engines to exempt the whole foreground process tree:
     * focusing a terminal (WindowsTerminal.exe) must also exempt its child shells
     * (powershell.exe, cmd.exe) or the user's console visibly lags.
     */
    public static boolean isInTree(long pid, long ancestorPid) {
        if (ancestorPid <= 0) return false;
        if (pid == ancestorPid) return true;
        long cur = pid;
        for (int i = 0; i < MAX_DEPTH; i++) {
            Optional<ProcessHandle> ph = ProcessHandle.of(cur);
            if (ph.isEmpty()) return false;
            long parent = ph.get().parent().map(ProcessHandle::pid).orElse(-1L);
            if (parent <= 0) return false;
            if (parent == ancestorPid) return true;
            cur = parent;
        }
        return false;
    }

    private ProcessTree() {}
}
