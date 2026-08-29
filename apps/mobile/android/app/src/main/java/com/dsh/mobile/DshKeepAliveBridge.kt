package com.dsh.mobile

/**
 * Keep-alive state sharing: cross-component state transfer.
 */
object DshKeepAliveBridge {
    @Volatile private var monitoredPid: Int = 0
    @Volatile private var port: Int = 0
    @Volatile private var active: Boolean = false
    @Volatile private var level: String = "medium"
    @Volatile private var remainingSeconds: Long = 0

    fun setMonitoredPid(pid: Int) { monitoredPid = pid }
    fun getMonitoredPid(): Int = monitoredPid
    fun setPort(p: Int) { port = p }
    fun getPort(): Int = port
    fun isActive(): Boolean = active
    fun getLevel(): String = level
    fun getRemainingSeconds(): Long = remainingSeconds
}
