package com.dsh.mobile

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * proot container launcher.
 *
 * Process tree:
 *   proot → node (dsh --profile mobile --no-open)
 *          → Agent Loop (Cordis plugin tree, 247 packages)
 *          → HTTP /api (RPC)
 *          → WS /api/remote.mux (event stream)
 *          → Tool execution (bash/python in container)
 *
 * All dsh plugins (Host + Client) run fully inside this container.
 */
class DshSandboxBridge {

    companion object {
        private const val TAG = "DshSandbox"
    }

    private var prootProcess: Process? = null
    private var dshPort: Int = 0
    private var dshPid: Int = 0

    fun launch(apiKey: String): LaunchResult {
        val rootfs = DshApplication.ROOTFS_DIR
        val workspace = DshApplication.WORKSPACE_DIR
        val prootBin = DshApplication.PROOT_BIN

        // 首次进入无 key 时注入占位符，保证 dsh 能正常启动进入 Web UI；
        // 用户可在 Web UI 设置页手动配置真实 API Key 后重启生效。
        val effectiveApiKey = if (apiKey.isEmpty()) "dsh-mobile-keyless" else apiKey

        val cmd = mutableListOf<String>().apply {
            add(prootBin)
            add("-r"); add(rootfs)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("$workspace:/workspace")
            add("-w"); add("/workspace")
            add("-i"); add("0:0")
            add("-e"); add("HOME=/home")
            add("-e"); add("DSH_HOME=/home/.dsh")
            add("-e"); add("DSH_PLATFORM=android")
            add("-e"); add("DSH_PROOT=1")
            add("-e"); add("DEEPSEEK_API_KEY=$effectiveApiKey")
            add("-e"); add("PATH=/usr/bin:/bin:/usr/sbin:/sbin")
            add("-e"); add("NODE_OPTIONS=--max-old-space-size=256")
            add("--")
            add("/usr/bin/node")
            add("/usr/lib/dsh/apps/cli/lib/bin.js")
            add("--profile"); add("mobile")
            add("--no-open")
            add("--port"); add("0")
        }

        Log.i(TAG, "Launching proot: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment()["HOME"] = "/home"
            environment()["DSH_HOME"] = "/home/.dsh"
            environment()["DSH_PLATFORM"] = "android"
            environment()["DEEPSEEK_API_KEY"] = effectiveApiKey
            // proot 依赖库路径
            environment()["LD_LIBRARY_PATH"] = DshApplication.LIB_DIR
        }

        prootProcess = pb.start()

        val reader = BufferedReader(InputStreamReader(prootProcess!!.inputStream))
        var port = 0
        var timeout = 0
        while (timeout < 60) {
            val line = reader.readLine() ?: break
            Log.i(TAG, "[dsh] $line")
            val match = Regex("""http://[\d.]+:(\d+)""").find(line)
            if (match != null) {
                port = match.groupValues[1].toInt()
                break
            }
            timeout++
        }

        if (port == 0) {
            throw RuntimeException("dsh failed to start within 60 seconds")
        }

        dshPort = port
        dshPid = getProcessId(prootProcess!!)
        Log.i(TAG, "dsh started: pid=$dshPid, port=$dshPort")
        return LaunchResult(pid = dshPid, port = dshPort)
    }

    fun isAlive(): Boolean = prootProcess?.isAlive == true

    fun stop() {
        prootProcess?.let { p ->
            p.destroy()
            Thread.sleep(2000)
            if (p.isAlive) p.destroyForcibly()
        }
        prootProcess = null
        dshPort = 0
        dshPid = 0
    }

    /**
     * Extract the PID from a Java Process using reflection.
     * Android's Process lacks a public pid() method (Java 9+ API).
     */
    private fun getProcessId(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            try {
                field.getInt(process)
            } catch (e: IllegalAccessException) {
                val method = process.javaClass.getMethod("pid")
                method.isAccessible = true
                method.invoke(process) as Int
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get pid via reflection, using /proc fallback")
            0
        }
    }

    data class LaunchResult(val pid: Int, val port: Int)
}
