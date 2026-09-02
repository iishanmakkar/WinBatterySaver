package com.batterysaver.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ProcessTree.isInTree drives the throttling engines' foreground-tree exemption. */
@EnabledOnOs(OS.WINDOWS)
public class ProcessTreeTest {

    @Test
    void childIsInTreeOfItsAncestor() throws Exception {
        Process child = new ProcessBuilder("cmd.exe", "/c", "ping -n 30 127.0.0.1").start();
        try {
            Thread.sleep(400);
            long me = ProcessHandle.current().pid();
            assertTrue(ProcessTree.isInTree(child.pid(), me),
                    "spawned child must be recognized as our descendant");
            assertFalse(ProcessTree.isInTree(me, child.pid()),
                    "parent must NOT be a descendant of its own child");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void unrelatedPidNotInTree() {
        long me = ProcessHandle.current().pid();
        // PID 4 (System) is never our descendant
        assertFalse(ProcessTree.isInTree(4, me));
        // invalid ancestor
        assertFalse(ProcessTree.isInTree(me, -1));
        assertFalse(ProcessTree.isInTree(me, 0));
    }
}
