package com.qwr.streamviewer;

import java.util.Objects;

public class DeviceItem {

    private final String name;
    private final String addressIp;
    private final String serialNumber;
    private DeviceStatus status;

    public DeviceItem(String name, String addressIp, String serialNumber, DeviceStatus status) {
        this.name = name;
        this.addressIp = addressIp;
        this.serialNumber = serialNumber;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public String getAddressIp() {
        return addressIp;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceItem that = (DeviceItem) o;
        return Objects.equals(addressIp, that.addressIp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(addressIp);
    }
}

// ---------------------------------------------------------------------------
// Reachability status shown on each device card
// ---------------------------------------------------------------------------
enum DeviceStatus {
    CONNECTED,
    DISCONNECTED,
    IDLE
}
