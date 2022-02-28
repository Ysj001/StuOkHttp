package com.ysj.stu.myhttp.connection.route

import com.ysj.stu.myhttp.connection.Address
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 路由，包含用于连接到服务器的信息。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class Route(
    val address: Address,
    val proxy: Proxy,
    val inetSocketAddress: InetSocketAddress,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Route) return false

        if (address != other.address) return false
        if (proxy != other.proxy) return false
        if (inetSocketAddress != other.inetSocketAddress) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + proxy.hashCode()
        result = 31 * result + inetSocketAddress.hashCode()
        return result
    }

    override fun toString(): String {
        return "Route{$inetSocketAddress}"
    }
}