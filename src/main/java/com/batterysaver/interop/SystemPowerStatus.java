package com.batterysaver.interop;

import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;

@FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag",
        "BatteryLifeTime", "BatteryFullLifeTime"})
public class SystemPowerStatus extends Structure {
    public byte ACLineStatus;        // 0=offline,1=online,255=unknown
    public byte BatteryFlag;         // bitmask: 1=high,2=low,4=critical,8=charging,128=no battery
    public byte BatteryLifePercent;  // 0-100, 255=unknown
    public byte SystemStatusFlag;
    public int BatteryLifeTime;      // seconds remaining, -1 if unknown
    public int BatteryFullLifeTime;  // seconds of full charge, -1 if unknown
}