package com.batterysaver.model;

public class Battery {
    private int percent = -1; // -1 = unknown (don't show a bogus "0%")
    private boolean onAC;
    private int remainingSeconds = -1;
    private boolean highFlag;
    private boolean lowFlag;
    private boolean criticalFlag;
    private boolean chargingFlag;
    private boolean noBatteryFlag;

    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public boolean isOnAC() { return onAC; }
    public void setOnAC(boolean onAC) { this.onAC = onAC; }
    public int getRemainingSeconds() { return remainingSeconds; }
    public void setRemainingSeconds(int remainingSeconds) { this.remainingSeconds = remainingSeconds; }
    public boolean isHighFlag() { return highFlag; }
    public void setHighFlag(boolean highFlag) { this.highFlag = highFlag; }
    public boolean isLowFlag() { return lowFlag; }
    public void setLowFlag(boolean lowFlag) { this.lowFlag = lowFlag; }
    public boolean isCriticalFlag() { return criticalFlag; }
    public void setCriticalFlag(boolean criticalFlag) { this.criticalFlag = criticalFlag; }
    public boolean isChargingFlag() { return chargingFlag; }
    public void setChargingFlag(boolean chargingFlag) { this.chargingFlag = chargingFlag; }
    public boolean isNoBatteryFlag() { return noBatteryFlag; }
    public void setNoBatteryFlag(boolean noBatteryFlag) { this.noBatteryFlag = noBatteryFlag; }
}