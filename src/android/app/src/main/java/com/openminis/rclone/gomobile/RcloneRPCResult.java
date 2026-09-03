package com.openminis.rclone.gomobile;

public final class RcloneRPCResult {
    public String output;
    public long status;

    public RcloneRPCResult() {
        this.output = "{}";
        this.status = 200L;
    }

    public RcloneRPCResult(String output, long status) {
        this.output = output;
        this.status = status;
    }
}
