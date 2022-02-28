package com.ysj.stu.myhttp.connection

import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.connection.route.Route
import com.ysj.stu.myhttp.connection.route.RouteFailedCache
import com.ysj.stu.myhttp.utils.closeQuietly
import com.ysj.stu.myhttp.utils.createThreadFactory
import com.ysj.stu.myhttp.utils.notifyAll
import com.ysj.stu.myhttp.utils.wait
import java.util.concurrent.Exchanger
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 连接池
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class ConnectionPool(
    /** 最多可容纳的空闲连接数 */
    val maxIdleConnections: Int = 5,
    /** 空闲连接保持时间，超过该时间自动退出 */
    val keepAliveDurationNs: Long = TimeUnit.MINUTES.toNanos(5),
) {

    /** 用于清理过期的连接 */
    private val executor by lazy {
        ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            createThreadFactory("MyHttp ConnectionPool", true)
        )
    }

    private var cleanupRunning: Boolean = false
    private val cleanupRunnable = Runnable {
        while (true) {
            var waitNs = cleanup(System.nanoTime())
            if (waitNs == -1L) return@Runnable
            if (waitNs > 0) {
                val waitMs = waitNs / 1000_000L
                waitNs -= (waitMs * 1000_000L)
                val pool = this@ConnectionPool
                synchronized(pool) {
                    try {
                        // 用两个参数的 wait，更精确控制等待时间。可能可以精确多 1ms
                        pool.wait(waitMs, waitNs.toInt())
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        }
    }

    private val connections = ArrayDeque<Connection>()

    internal val routeFailedCache = RouteFailedCache()

    /**
     * 尝试给 [Transmitter] 设置连接池中的连接
     *
     * @return 如果在连接池中找到了符合要求的连接并设置到了 [Transmitter] 中则返回 true
     */
    internal fun transmitterSetPooledConnection(
        transmitter: Transmitter,
        address: Address,
        routes: List<Route>?,
        requireMultiplexed: Boolean,
    ): Boolean {
        connections.forEach { connection ->
            // TODO 不是 HTTP2 不用考虑 requireMultiplexed
            if (!connection.isEligible(address, routes)) return@forEach
            transmitter.connection = connection
            return true
        }
        return false
    }

    /**
     * 将连接放入连接池，并启动清理线程
     */
    internal fun put(connection: Connection) {
        assert(Thread.holdsLock(this))
        if (!cleanupRunning) {
            cleanupRunning = true
            executor.execute(cleanupRunnable)
        }
        connections.add(connection)
    }

    /**
     * 将连接闲置，并唤醒清理线程。
     * - 如果连接无法再继续创建 [Exchanger]，则从池中移除，此时应该将连接关闭
     *
     * @return 如果返回 true 则传入的连接需要关闭
     */
    internal fun connectionBecameIdle(connection: Connection): Boolean {
        assert(Thread.holdsLock(this))
        if (connection.noNewExchanges || maxIdleConnections == 0) {
            connections.remove(connection)
            return true
        }
        // 唤醒清理线程
        notifyAll()
        return false
    }

    /**
     * - 如果连接数量超过池限制了，或者等待事件超过限制了，就从把空闲时间最长的从池中移除并关闭。
     * - 如果有闲置连接，等到这个连接超时的那一刻 (keepAliveDurationNs - longestIdleDurationNs)。
     * - 如果池中的连接没有空闲的，都还在使用，继续等待 (keepAliveDurationNs)
     *
     * @return 下一次清理所需等待的时间
     */
    private fun cleanup(now: Long): Long {
        var inUseConnectionCount = 0
        var idleConnectionCount = 0

        var longestIdleConnection: Connection? = null
        var longestIdleDurationNs: Long = Long.MIN_VALUE

        synchronized(this) {
            // 找出闲置时间最长的 connection
            val iterator = connections.iterator()
            while (iterator.hasNext()) {
                val connection = iterator.next()

                if (checkTransmittersCount(connection, now) > 0) {
                    inUseConnectionCount++
                    continue
                }

                idleConnectionCount++

                val idleDurationNs = now - connection.idleAtNanos
                if (idleDurationNs > longestIdleDurationNs) {
                    longestIdleDurationNs = idleDurationNs
                    longestIdleConnection = connection
                }
            }

            when {
                longestIdleDurationNs >= this.keepAliveDurationNs || idleConnectionCount > this.maxIdleConnections -> {
                    // 如果连接数量超过池限制了，或者等待事件超过限制了，就从把空闲时间最长的从池中移除，并关闭
                    connections.remove(longestIdleConnection)
                }
                idleConnectionCount > 0 -> {
                    // 如果有闲置连接，等到这个连接超时的那一刻
                    return keepAliveDurationNs - longestIdleDurationNs
                }
                inUseConnectionCount > 0 -> {
                    // 如果池中的连接没有空闲的，都还在使用，继续等待
                    return keepAliveDurationNs
                }
                else -> {
                    // 没找到连接，关闭清理线程
                    cleanupRunning = false
                    return -1
                }
            }

        }

        longestIdleConnection?.socket.closeQuietly()

        return 0
    }

    /**
     * 检测该 [connection] 中活跃的 [Transmitter] 数量
     */
    private fun checkTransmittersCount(connection: Connection, now: Long): Int {
        val transmitters = connection.transmitters
        val iterator = transmitters.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            if (ref.get() != null) continue
            // 进了这里说明没有走 releaseConnection
            val url = connection.route.address.url
            println("A connection to $url was leaked. Did you forget to close a response body?")

            iterator.remove()
            connection.noNewExchanges = true
            // 该连接有泄漏的 transmitter，不能再使用了要立即释放
            if (!iterator.hasNext()) {
                connection.idleAtNanos = now - keepAliveDurationNs
                return 0
            }
        }
        return transmitters.size
    }
}