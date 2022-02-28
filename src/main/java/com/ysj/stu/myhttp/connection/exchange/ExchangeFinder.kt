package com.ysj.stu.myhttp.connection.exchange

import com.ysj.stu.myhttp.call.MyCall
import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.connection.Address
import com.ysj.stu.myhttp.connection.Connection
import com.ysj.stu.myhttp.connection.ConnectionPool
import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import com.ysj.stu.myhttp.connection.route.Route
import com.ysj.stu.myhttp.connection.route.RouteSelector
import com.ysj.stu.myhttp.exception.RouteException
import com.ysj.stu.myhttp.utils.closeQuietly
import com.ysj.stu.myhttp.utils.isSameConnection
import java.io.IOException
import java.net.Socket

/**
 * 查找用于连接 [Address] 的 [Connection] 并获取对应的 [ExchangeCodec]
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class ExchangeFinder(
    private val address: Address,
    private val call: MyCall,
    private val transmitter: Transmitter,
    private val connectionPool: ConnectionPool,
) {

    private val routeSelector = RouteSelector(transmitter.client, address, call, connectionPool.routeFailedCache)

    // 路由选择器当前所选的路由集合
    private var routeSelection: RouteSelector.Selection? = null

    /**
     * 正在连接的 [Connection]
     */
    var connectingConnection: Connection? = null
        private set(value) {
            assert(Thread.holdsLock(connectionPool))
            field = value
        }
        get() {
            assert(Thread.holdsLock(connectionPool))
            return field
        }

    /**
     * 如果重试可能修复失败，则该标记为 true
     */
    var hasStreamFailure = false
        get() = synchronized(connectionPool) { field }
        private set(value) {
            assert(Thread.holdsLock(connectionPool))
            field = value
        }

    /**
     * 下一个要去尝试连接的 [Route]
     */
    private var nextRouteToTry: Route? = null
        get() {
            assert(Thread.holdsLock(connectionPool))
            return field
        }
        set(value) {
            assert(Thread.holdsLock(connectionPool))
            field = value
        }

    /**
     * 查找一个 [Connection] 并调用 [Connection.newCodec] 创建 HTTP 编解码器并返回
     */
    @Throws(RouteException::class)
    fun find(doExtensiveHealthChecks: Boolean): ExchangeCodec {
        val client = transmitter.client
        try {
            while (true) {
                val connection = findConnection()
                synchronized(transmitter.client.connectionPool) {
                    if (connection.successCount == 0) {
                        // 新连接不 doExtensiveHealthChecks
                        return connection.newCodec(client)
                    }
                }

                if (!connection.isHealthy(doExtensiveHealthChecks)) {
                    // 如果已经不健康了就标记不可用，下次循环就会创建新的了
                    connection.noNewExchanges()
                    continue
                }

                return connection.newCodec(client)
            }
        } catch (e: RouteException) {
            trackFailure()
            throw e
        } catch (e: IOException) {
            trackFailure()
            throw RouteException(e)
        }
    }

    /**
     * 判断当前路由有效，或者还有可以尝试的路由则返回 true。
     */
    internal fun hasRouteToTry(): Boolean {
        synchronized(connectionPool) {
            if (nextRouteToTry != null) return true
            if (retryCurrentRoute()) {
                nextRouteToTry = transmitter.connection!!.route
                return true
            }
            return routeSelection?.hasNext == true || routeSelector.hasNext()
        }
    }

    internal fun trackFailure() {
        assert(!Thread.holdsLock(connectionPool))
        synchronized(connectionPool) {
            // 允许重试
            hasStreamFailure = true
        }
    }

    /**
     * 返回一个已经连接当前 [address] 的 [Connection]。
     * - 优先级：可用的 [Transmitter.connection] > [ConnectionPool] > 创建新的 [Connection] 并建立连接
     */
    @Throws(IOException::class)
    private fun findConnection(): Connection {
        var result: Connection? = null
        val pool = transmitter.client.connectionPool
        var toClose: Socket? = null
        // 是否从连接池中找到了连接
        var foundPooledConnection = false
        // 当前选中要去连接的路由
        var selectedRoute: Route? = null
        synchronized(pool) {
            if (transmitter.isCanceled()) throw IOException("Canceled")
            hasStreamFailure = false
            val connection = transmitter.connection
            if (connection != null) {
                if (connection.noNewExchanges) {
                    // 如果不能创建新的 Exchange 就释放并置空 transmitter 内的 connection
                    toClose = transmitter.releaseConnection()
                } else {
                    // 使用已有的 connection。
                    result = connection
                }
            } else {
                when {
                    connectionPool.transmitterSetPooledConnection(transmitter, address, null, false) -> {
                        foundPooledConnection = true
                        result = transmitter.connection
                    }
                    nextRouteToTry != null -> {
                        selectedRoute = nextRouteToTry
                        nextRouteToTry = null
                    }
                    retryCurrentRoute() -> {
                        selectedRoute = transmitter.connection!!.route
                    }
                }
            }
        }
        toClose.closeQuietly()

        // 找到了已经存在的，或者连接池中的连接
        if (result != null) return result!!

        // 是否找了一组新的路由
        var newRouteSelection = false
        if (selectedRoute == null && routeSelection?.hasNext != true) {
            // 如果当前所用的路由集合用完了就找新的
            newRouteSelection = true
            routeSelection = routeSelector.next()
        }

        var routes: List<Route>?
        synchronized(connectionPool) {
            if (transmitter.isCanceled()) throw IOException("Canceled")
            if (newRouteSelection) {
                routes = routeSelection?.getAll()
                // 拿到一组新的路由再次尝试从连接池中拿连接
                if (connectionPool.transmitterSetPooledConnection(transmitter, address, routes, false)) {
                    foundPooledConnection = true
                    result = transmitter.connection
                }
            }
            if (!foundPooledConnection) {
                if (selectedRoute == null) {
                    selectedRoute = routeSelection?.next()
                }
                result = Connection(connectionPool, selectedRoute!!)
                // 记录起来，这样后面握手过程中外部也可以取消
                connectingConnection = result
            }
        }

        // 找到了连接池中的连接
        if (foundPooledConnection) {
            return result!!
        }

        result!!.connect(transmitter.client)
        connectionPool.routeFailedCache.connected(result!!.route)

        synchronized(connectionPool) {
            // 已经连接完了，把这个置空
            connectingConnection = null
            // TODO 这里不考虑 HTTP2 多路复用

            // 将连接设置给 transmitter
            connectionPool.put(result!!)
            transmitter.connection = result!!
        }

        return result!!
    }

    /**
     * 判断当前路由是否可以重试，即使当前路由不健康。
     *
     * @return 如果可以重试则返回 ture
     */
    private fun retryCurrentRoute(): Boolean {
        val connection = transmitter.connection
        return connection != null
            && connection.routeFailureCount == 0
            && connection.route.address.url.isSameConnection(address.url)
    }

}