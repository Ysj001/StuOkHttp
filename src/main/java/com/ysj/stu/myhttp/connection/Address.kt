package com.ysj.stu.myhttp.connection

import java.net.URL

/**
 * 连接请求目标服务器的地址。决定如何进行连接的信息的封装。
 *
 * @author Ysj
 * Create time: 2022/2/2
 */
class Address constructor(
    val host: String,
    val port: Int,
    val protocol: String
) {

    val url = URL(protocol, host, port, "")

    val isHttps: Boolean = protocol == "https"

    fun equalsNonHost(other: Address): Boolean {
        if (port != other.port) return false
        if (protocol != other.protocol) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Address

        if (host != other.host) return false
        if (port != other.port) return false
        if (protocol != other.protocol) return false

        return true
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + protocol.hashCode()
        return result
    }

    override fun toString(): String {
        return "Address(host='$host', port=$port, protocol='$protocol')"
    }
}