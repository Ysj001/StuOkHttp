package com.ysj.stu.myhttp.utils

import java.io.Closeable
import java.net.URL
import java.util.concurrent.ThreadFactory

/*
 * 通用的一些工具方法
 *
 * @author Ysj
 * Create time: 2022/2/2
 */

/**
 * 判断是否是安卓平台
 */
val isAndroid: Boolean = "Dalvik" == System.getProperty("java.vm.name")

val URL.supportPort: Int get() = if (port == -1) defaultPort else port

/**
 * 判断是否是相同的连接，相同的连接可以重用
 */
fun URL.isSameConnection(other: URL): Boolean =
    host == other.host
        && supportPort == other.supportPort
        && protocol == other.protocol

fun createThreadFactory(name: String, daemon: Boolean = false) = ThreadFactory {
    Thread(it, name).apply { isDaemon = daemon }
}

/** Closes this, ignoring any checked exceptions. */
fun Closeable?.closeQuietly() {
    if (this == null) return
    try {
        close()
    } catch (rethrown: RuntimeException) {
        throw rethrown
    } catch (_: Exception) {
    }
}

inline fun Any.wait(waitMs: Long, waitNs: Int) = (this as Object).wait(waitMs, waitNs)
inline fun Any.notifyAll() = (this as Object).notifyAll()