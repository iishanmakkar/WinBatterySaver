package com.batterysaver.model;

public class PowerPlan {
    private String guid;
    private String name;
    private String status; // "active", "inactive"

    public String getGuid() { return guid; }
    public void setGuid(String guid) { this.guid = guid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}