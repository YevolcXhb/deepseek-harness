package com.dsh.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Application entry point.
 *
 * Initializes notification channels, directory structure, and releases
 * bundled proot binary and rootfs from assets on first launch.
 *
 * File system layout (/data/data/com.dsh.mobile/files/):
 *   proot           — proot binary
 *   rootfs/         — proot root filesystem (node + dsh + workspace)
 *   workspace/      — AI workspace
 *   keepalive.port  — TCP port file for keepalive service
 */
class DshApplication : Application() {

    companion object {
        private const val TAG = "DshApplication"
        const val CHANNEL_ID_KEEPALIVE = "dsh_keepalive"
        const val CHANNEL_ID_HEALTH = "dsh_health"
        const val FILES_DIR = "/data/data/com.dsh.mobile/files"
        const val PROOT_BIN = "$FILES_DIR/proot"
        const val ROOTFS_DIR = "$FILES_DIR/rootfs"
        const val WORKSPACE_DIR = "$FILES_DIR/workspace"
        const val LIB_DIR = "$FILES_DIR/lib"
        const val KEEPALIVE_PORT_FILE = "$FILES_DIR/keepalive.port"
        const val PREFS_NAME = "dsh"
        const val PREF_API_KEY = "api_key"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DshApplication onCreate")
        createNotificationChannels()
        ensureDirectories()
        releaseAssetsIfNeeded()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_KEEPALIVE,
                    getString(R.string.dsh_notification_channel_keepalive),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "DeepSeek Harness container running status"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_HEALTH,
                    getString(R.string.dsh_notification_channel_health),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority container health alerts"
                }
            )
        }
    }

    private fun ensureDirectories() {
        listOf(FILES_DIR, ROOTFS_DIR, WORKSPACE_DIR, LIB_DIR, "$ROOTFS_DIR/home/.dsh").forEach { dir ->
            java.io.File(dir).mkdirs()
        }
    }

    /**
     * Release bundled proot binary and rootfs from assets on first launch.
     * Subsequent launches skip if files already exist.
     */
    private fun releaseAssetsIfNeeded() {
        val prootFile = java.io.File(PROOT_BIN)
        if (!prootFile.exists()) {
            try {
                val abi = Build.SUPPORTED_ABIS[0]
                val assetName = when {
                    abi.contains("arm64") -> "proot-arm64"
                    abi.contains("x86_64") -> "proot-x86_64"
                    abi.contains("armeabi") -> "proot-arm"
                    else -> "proot-x86"
                }
                Log.i(TAG, "Releasing proot binary: $assetName (ABI: $abi)")
                assets.open(assetName).use { input ->
                    prootFile.outputStream().use { output -> input.copyTo(output) }
                }
                prootFile.setExecutable(true, false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release proot binary", e)
            }
        }

        // Release proot 依赖库 (libtalloc, libandroid-shmem)
        val libFiles = listOf("libtalloc.so.2.4.3", "libtalloc.so.2", "libandroid-shmem.so")
        for (libName in libFiles) {
            val libFile = java.io.File("$LIB_DIR/$libName")
            if (!libFile.exists()) {
                try {
                    assets.open("lib/$libName").use { input ->
                        libFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release lib: $libName", e)
                }
            }
        }

        val nodeFile = java.io.File("$ROOTFS_DIR/usr/bin/node")
        if (!nodeFile.exists()) {
            try {
                Log.i(TAG, "Extracting rootfs.tar.gz...")
                val tarball = java.io.File("$FILES_DIR/rootfs.tar.gz")
                assets.open("rootfs.tar.gz").use { input ->
                    tarball.outputStream().use { output -> input.copyTo(output) }
                }
                val process = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", ROOTFS_DIR)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                Log.i(TAG, "Rootfs extraction: exit=$exitCode")
                tarball.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract rootfs", e)
            }
        }
    }
}
