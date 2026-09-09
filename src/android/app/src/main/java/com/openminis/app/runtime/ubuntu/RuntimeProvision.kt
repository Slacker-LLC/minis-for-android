package com.openminis.app.runtime.ubuntu

/** Packaged Ubuntu rootfs paths retained for repair/migration code. */
object RuntimeProvision {
    const val ROOTFS_ASSET = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
    const val STAGED_ROOTFS_ARCHIVE = "/data/adb/minis/runtime/staging/ubuntu-arm64-rootfs.tar.gz"
}
