package com.batterysaver.interop;

import com.sun.jna.Structure;

@Structure.FieldOrder({"cbSize", "dwTime"})
public class LastInputInfo extends Structure {
    public int cbSize;
    public int dwTime; // milliseconds since last input
}