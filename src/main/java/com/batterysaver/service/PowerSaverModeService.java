package com.batterysaver.service;

public class PowerSaverModeService {
    private final PowerPlanService powerPlanService = new PowerPlanService();
    private final BrightnessService brightnessService = new BrightnessService();
    private String previousPlanGuid;
    private int previousBrightness = -1;
    private boolean active = false;

    public void enable(int dimToPercent) {
        try {
            previousPlanGuid = powerPlanService.getActivePlanGuid();
            if (previousPlanGuid != null && powerPlanService.isPowerSaverGuid(previousPlanGuid)) {
                previousPlanGuid = PowerPlanService.BALANCED;
            }
            try {
                previousBrightness = brightnessService.getBrightness();
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("OS Power Plan switch skipped: " + e.getMessage());
        }
        try {
            powerPlanService.ensureAndActivatePowerSaver();
        } catch (Exception e) {
            System.err.println("OS Power Plan enable skipped: " + e.getMessage());
        }
        try {
            brightnessService.setBrightness(dimToPercent);
        } catch (Exception ignored) {}
        active = true;
    }

    public void disable() {
        if (!active && previousPlanGuid == null) return;
        try {
            if (previousPlanGuid != null) powerPlanService.setActivePlan(previousPlanGuid);
            else powerPlanService.setActivePlan(PowerPlanService.BALANCED);
        } catch (Exception e) {
            System.err.println("OS Power Plan restore skipped: " + e.getMessage());
        }
        try {
            if (previousBrightness >= 0) brightnessService.setBrightness(previousBrightness);
        } catch (Exception ignored) {}
        active = false;
        previousPlanGuid = null;
        previousBrightness = -1;
    }

    public boolean isActive() { return active || isActiveReal(); }

    public boolean isActiveReal() {
        try {
            String cur = powerPlanService.getActivePlanGuid();
            if (cur != null) return powerPlanService.isPowerSaverGuid(cur);
        } catch (Exception ignored) {}
        return active;
    }

    public PowerPlanService getPowerPlanService() { return powerPlanService; }
}