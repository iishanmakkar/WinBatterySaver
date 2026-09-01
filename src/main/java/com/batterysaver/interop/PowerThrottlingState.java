/* PowerThrottlingState.java - JNA Structure for Windows EcoQoS (Efficiency Mode)
 * PROCESS_POWER_THROTTLING via SetProcessInformation
 * Same mechanism Task Manager uses for the green leaf icon
 */
package com.batterysaver.interop;

import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;

@FieldOrder({"Version", "ControlMask", "StateMask"})
public class PowerThrottlingState extends Structure {
    public static final int PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1;
    public static final int PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1;

    public int Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    public int ControlMask;
    public int StateMask;

    public static PowerThrottlingState throttleOn() {
        PowerThrottlingState s = new PowerThrottlingState();
        s.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        s.StateMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED; // enable EcoQoS
        return s;
    }

    public static PowerThrottlingState throttleOff() {
        PowerThrottlingState s = new PowerThrottlingState();
        s.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        s.StateMask = 0; // explicitly restore normal scheduling
        return s;
    }
}