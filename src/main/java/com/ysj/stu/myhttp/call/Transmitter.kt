package com.ysj.stu.myhttp.call

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.connection.Address
import com.ysj.stu.myhttp.connection.Connection
import com.ysj.stu.myhttp.connection.exchange.Exchange
import com.ysj.stu.myhttp.connection.exchange.ExchangeFinder
import com.ysj.stu.myhttp.utils.closeQuietly
import com.ysj.stu.myhttp.utils.isSameConnection
import com.ysj.stu.myhttp.utils.supportPort
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.Socket

/**
 * 用于建立连接并发送请求
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class Transmitter(
    val client: MyHttpClient,
    val call: MyCall,
) {

    private lateinit var request: MyRequest
    var exchangeFinder: ExchangeFinder? = null
        private set

    // Guarded by connectionPool.
    private var exchange: Exchange? = null
    private var canceled = false
    private var noMoreExchanges = false
    private var exchangeRequestDone = false
    private var exchangeResponseDone = false

    internal var connection: Connection? = null
        set(value) {
            assert(Thread.holdsLock(client.connectionPool))
            field = value
            (value ?: return).transmitters.add(TransmitterRef(this))
        }

    fun callStart() {
        // TODO
    }

    fun timeoutEnter() {
        // TODO
    }

    /**
     * 准备进行连接。会初始化当前请求的 [ExchangeFinder]。
     */
    fun prepareToConnect(request: MyRequest) {
        if (this::request.isInitialized) {
            if (request.url.isSameConnection(this.request.url) && exchangeFinder?.hasRouteToTry() == true) {
                // 说明当前 transmitter 中有可用的连接
                return
            }
            if (exchangeFinder != null) {
                // 请求变更了，将当前连接释放掉
                maybeReleaseConnection(null, true)
                exchangeFinder = null
            }
        }
        val address = Address(
            request.url.host,
            request.url.supportPort,
            request.url.protocol,
        )
        this.request = request
        this.exchangeFinder = ExchangeFinder(
            address,
            call,
            this,
            client.connectionPool
        )
    }

    fun canRetry(): Boolean {
        val exchangeFinder = this.exchangeFinder ?: return false
        return exchangeFinder.hasStreamFailure && exchangeFinder.hasRouteToTry()
    }

    /**
     * 取消请求，会立即中断请求过程。如果请求已经建立会立即关闭 socket。
     */
    fun cancel() {
        val exchangeToCancel: Exchange?
        val connectionToCancel: Connection?
        synchronized(client.connectionPool) {
            canceled = true
            exchangeToCancel = exchange
            connectionToCancel = exchangeFinder?.connectingConnection ?: connection
        }
        exchangeToCancel?.cancel()
        connectionToCancel?.cancel()
    }

    fun isCanceled(): Boolean = synchronized(client.connectionPool) { canceled }

    fun <T : IOException?> noMoreExchanges(e: T): T {
        synchronized(client.connectionPool) {
            noMoreExchanges = true
        }
        return maybeReleaseConnection(e, false)
    }

    /**
     * 由于异常导致的 exchange 结束
     */
    fun exchangeDoneDueToException() {
        synchronized(client.connectionPool) {
            check(!noMoreExchanges)
            exchange = null
        }
    }

    fun timeoutEarlyExit() {
        TODO("Not yet implemented")
    }

    internal fun newExchange(doExtensiveHealthChecks: Boolean): Exchange {
        synchronized(client.connectionPool) {
            check(!noMoreExchanges) { "released" }
            check(exchange == null) {
                "cannot make a new request because the previous response is still open: please call response.close()"
            }
        }

        val exchangeFinder = this.exchangeFinder
            ?: throw IllegalStateException("not prepareConnect()")
        val codec = exchangeFinder.find(doExtensiveHealthChecks)
        val result = Exchange(this, codec)
        synchronized(client.connectionPool) {
            this.exchange = result
            exchangeRequestDone = false
            exchangeResponseDone = false
        }
        return result
    }

    internal fun exchangeMessageDone(
        exchange: Exchange,
        e: IOException?,
        responseDone: Boolean,
        requestDone: Boolean,
    ): IOException? {
        var exchangeDone = false
        synchronized(client.connectionPool) {
            if (exchange != this.exchange) return e
            // 记录请求和响应结束的标记是否有改变
            var changed = false
            if (requestDone) {
                if (!exchangeRequestDone) changed = true
                exchangeRequestDone = true
            }
            if (responseDone) {
                if (!exchangeResponseDone) changed = true
                exchangeResponseDone = true
            }
            if (exchangeRequestDone && exchangeResponseDone && changed) {
                exchangeDone = true
                exchange.connection().successCount++
                this.exchange = null
            }
        }
        return if (exchangeDone) maybeReleaseConnection(e, false) else e
    }

    /**
     * 释放内部的 [connection]。
     * - 如果返回不为 null，说明内部的连接已经空闲且不在连接池内了，此时返回的 socket 应该将其关闭
     */
    internal fun releaseConnection(): Socket? {
        assert(Thread.holdsLock(client.connectionPool))
        val released = this.connection ?: return null
        var index = -1
        for (i in released.transmitters.indices) {
            val ref = released.transmitters[i].get()
            // 从 connection 中找出当前 transmitter
            if (ref == this) {
                index = i
                break
            }
        }
        // 如果还没有连接就释放说明状态不对
        check(index != -1)
        // 将自己从连接中移除
        released.transmitters.removeAt(index)
        this.connection = null
        // 如果连接中的 transmitters 已经空了，并且连接已经空闲且不在连接池内了，则返回其 socket
        if (released.transmitters.isEmpty()) {
            released.idleAtNanos = System.nanoTime()
            if (client.connectionPool.connectionBecameIdle(released)) {
                return released.socket
            }
        }
        return null
    }

    /**
     * 尝试释放 [connection]。如果超时会退传入的 [e] 做包装。
     *
     * @param force 如果为 true，则 [noMoreExchanges] 为 false 也进行释放
     */
    private fun <T : IOException?> maybeReleaseConnection(e: T, force: Boolean): T {
        val end: Boolean
        val needClose: Socket?
        synchronized(client.connectionPool) {
            check(!force || exchange == null) { "cannot release connection while it is in use" }
            val canRelease = this.connection != null && this.exchange == null && (force || noMoreExchanges)
            needClose = if (canRelease) releaseConnection() else null
            end = this.noMoreExchanges && this.exchange == null
        }
        needClose.closeQuietly()
        var result = e
        if (end) {
            // TODO 退出 timeout
        }
        return result
    }

    internal class TransmitterRef(
        ref: Transmitter,
    ) : WeakReference<Transmitter>(ref)
}