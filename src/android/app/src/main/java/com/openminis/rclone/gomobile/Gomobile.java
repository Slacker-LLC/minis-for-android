package com.openminis.rclone.gomobile;

/**
 * Gomobile binding bridge for rclone Android integration.
 * When compiled with deps/build_rclone_android.sh, librclone provides the full native binding.
 */
public final class Gomobile {
    private Gomobile() {}

    public static void rcloneInitialize() {}
    public static void rcloneFinalize() {}

    public static RcloneRPCResult rcloneRPC(String method, String input) {
        return new RcloneRPCResult("{}", 200L);
    }
}

class RcloneRPCResultHelper {}
