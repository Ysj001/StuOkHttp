package com.ysj.stu.myhttp.connection.route

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.MyCall
import com.ysj.stu.myhttp.connection.Address
import java.io.IOException
import java.net.*

/**
 * 路由选择器，选择用于连接源服务器的路由。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class RouteSelector(
    private val client: MyHttpClient,
    private val address: Address,
    private val call: MyCall,
    private val failedCache: RouteFailedCache,
) {
    // 所有需要尝试连接的代理
    private var proxies: List<Proxy> = emptyList()

    // 下一个需要尝试的代理的索引
    private var nextProxyIndex: Int = 0

    private val hasNextProxy: Boolean get() = nextProxyIndex < proxies.size

    // 当前 proxy 所需的 InetSocketAddress 集合
    private val inetSocketAddresses = ArrayList<InetSocketAddress>(1)

    // 失败过的路由
    private val failedRoutes = ArrayList<Route>()

    init {
        resetNextProxy(address.url, client.proxy)
    }

    fun hasNext(): Boolean = hasNextProxy || failedRoutes.isNotEmpty()

    @Throws(IOException::class)
    fun next(): Selection {
        if (!hasNext()) throw NoSuchElementException()
        val routes = ArrayList<Route>()
        while (hasNextProxy && routes.isEmpty()) {
            // 将失败过的路由放在路由集合最后
            val proxy = nextProxy()
            for (i in inetSocketAddresses.indices) {
                val route = Route(address, proxy, inetSocketAddresses[i])
                if (failedCache.shouldPostpone(route)) {
                    failedRoutes.add(route)
                } else {
                    routes.add(route)
                }
            }
        }
        if (routes.isEmpty()) {
            // 保证最后一次 next 调用才尝试失败过的 route
            routes.addAll(failedRoutes)
            failedRoutes.clear()
        }
        return Selection(routes)
    }

    private fun resetNextProxy(url: URL, proxy: Proxy?) {
        if (proxy != null) {
            // 如果用户指定了代理，那就只用它
            this.proxies = listOf(proxy)
        } else {
            val proxies = client.proxySelector.select(url.toURI())
            this.proxies = if (!proxies.isNullOrEmpty()) proxies else listOf(Proxy.NO_PROXY)
        }
        nextProxyIndex = 0
    }

    /**
     * 获取下一个 [Proxy]。
     */
    @Throws(IOException::class)
    private fun nextProxy(): Proxy {
        if (!hasNextProxy) throw SocketException(
            "No route to ${address.host} exhausted proxy configurations: $proxies"
        )
        val proxy = proxies[nextProxyIndex++]
        resetNextInetSocketAddress(proxy)
        return proxy
    }

    /**
     * 重设该 [proxy] 需要访问的 [InetSocketAddress] 集合
     */
    @Throws(IOException::class)
    private fun resetNextInetSocketAddress(proxy: Proxy) {
        inetSocketAddresses.clear()
        val socketHost: String
        val socketPort: Int
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = address.host
            socketPort = address.port
        } else {
            val proxyAddress = proxy.address()
            require(proxyAddress is InetSocketAddress) { "Proxy.address() is not an InetSocketAddress: ${proxyAddress.javaClass}" }
            socketHost = proxyAddress.address?.hostAddress ?: proxyAddress.hostName
            socketPort = proxyAddress.port
        }

        if (socketPort < 1 || socketPort > 65535) throw SocketException(
            "No route to $socketHost:$socketPort; port is out of range"
        )

        if (proxy.type() == Proxy.Type.SOCKS) {
            inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort))
        } else {
            val dns = client.dns
            val addresses: List<InetAddress> = dns.lookup(socketHost)
            if (addresses.isEmpty()) {
                throw UnknownHostException("$dns returned no addresses for $socketHost")
            }
            for (i in addresses.indices) {
                val inetAddress = addresses[i]
                inetSocketAddresses.add(InetSocketAddress(inetAddress, socketPort))
            }
        }
    }

    /**
     * 所有可选 [Route]。
     */
    class Selection(private val routes: List<Route>) {
        private var nextRouteIndex = 0
        val hasNext: Boolean get() = nextRouteIndex < routes.size

        fun next(): Route {
            if (!hasNext) throw NoSuchElementException()
            return routes[nextRouteIndex++]
        }

        fun getAll() = ArrayList(routes)
    }
}