package com.dsh.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** rootfs 打包版本号：内容变更时递增，强制旧安装重新解压 */
private const val ROOTFS_VERSION = "2"

/**
 * Application entry point.
 *
 * Initializes notification channels, directory structure, and extracts
 * bundled rootfs from assets on first launch.
 *
 * W^X note (Android 10+): app-provided binaries cannot be execve'd from
 * /data/data/.../files/ (SELinux app_data_file denies execute, error=13).
 * proot and its native deps are therefore shipped as jniLibs
 * (libproot.so / libtalloc.so / libandroid-shmem.so) so the system extracts
 * them into nativeLibraryDir — the only exec-allowed location.
 */
class DshApplication : Application() {
    companion object {
        private const val TAG = "DshApplication"
        const val CHANNEL_ID_KEEPALIVE = "dsh_keepalive"
        const val CHANNEL_ID_HEALTH = "dsh_health"
        const val FILES_DIR = "/data/data/com.dsh.mobile/files"
        const val ROOTFS_DIR = "$FILES_DIR/rootfs"
        const val WORKSPACE_DIR = "$FILES_DIR/workspace"
        /** files 下的库目录：仅放符号链接（真实库在 nativeLibraryDir，exec 检查作用于最终 inode） */
        const val LIB_DIR = "$FILES_DIR/lib"
        const val KEEPALIVE_PORT_FILE = "$FILES_DIR/keepalive.port"
        const val PREFS_NAME = "dsh"
        const val PREF_API_KEY = "api_key"

        lateinit var instance: DshApplication
            private set

        /** 系统从 jniLibs 提取原生库的目录，Android 10+ 唯一允许 execve 的位置 */
        val nativeLibDir: String
            get() = instance.applicationInfo.nativeLibraryDir

        /** proot 可执行文件（APK 安装时由系统从 jniLibs/libproot.so 提取） */
        val prootBin: String
            get() = "$nativeLibDir/libproot.so"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DshApplication onCreate")
        createNotificationChannels()
        ensureDirectories()
        releaseAssetsIfNeeded()
        ensureLibAliases()
        Log.i(
            TAG,
            "nativeLibDir=$nativeLibDir proot=${File(prootBin).exists()} " +
                "talloc2=${File("$nativeLibDir/libtalloc.so.2").exists()} " +
                "shmem=${File("$nativeLibDir/libandroid-shmem.so").exists()}"
        )
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
            File(dir).mkdirs()
        }
    }

    /**
     * proot 的 DT_NEEDED 是 "libtalloc.so.2"。jniLibs 里同时打包了 libtalloc.so 与
     * libtalloc.so.2 两个名字；若系统仅按 .so 名提取，则在 files/lib 建符号链接兜底
     * （链接指向 nativeLibraryDir 内的真实库，exec/dlopen 检查作用于最终 inode，允许）。
     */
    private fun ensureLibAliases() {
        if (File("$nativeLibDir/libtalloc.so.2").exists()) return
        try {
            val link = File(LIB_DIR, "libtalloc.so.2")
            link.delete()
            Files.createSymbolicLink(link.toPath(), Paths.get("$nativeLibDir/libtalloc.so"))
            Log.i(TAG, "Created libtalloc.so.2 alias -> $nativeLibDir/libtalloc.so")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create libtalloc.so.2 alias", e)
        }
    }

    /**
     * 首次启动（或 rootfs 版本变更时）从 assets 解压 rootfs。
     * 版本 marker 不匹配时自动清空重建，保证升级后内容一致。
     */
    private fun releaseAssetsIfNeeded() {
        val nodeFile = File("$ROOTFS_DIR/usr/bin/node")
        val verFile = File("$FILES_DIR/rootfs.version")
        val current = if (verFile.exists()) verFile.readText().trim() else ""
        if (nodeFile.exists() && current == ROOTFS_VERSION) return
        try {
            Log.i(TAG, "Extracting rootfs.tar.gz (version $ROOTFS_VERSION)...")
            File(ROOTFS_DIR).deleteRecursively()
            File(ROOTFS_DIR).mkdirs()
            val tarball = File("$FILES_DIR/rootfs.tar.gz")
            assets.open("rootfs.tar.gz").use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
            val process = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", ROOTFS_DIR)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            Log.i(TAG, "Rootfs extraction: exit=$exitCode")
            tarball.delete()
            if (exitCode == 0) verFile.writeText(ROOTFS_VERSION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract rootfs", e)
        }
    }
}
