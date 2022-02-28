package com.ysj.stu.myhttp

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 将 hostname 解析为 ip 地址 [InetAddress]
 *
 * @author Ysj
 * Create time: 2022/2/2
 */
fun interface Dns {

    /**
     * 默认的解析。使用 [InetAddress.getAllByName] 请求操作系统底层查找 ip 地址
     */
    object DEFAULT : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            listOf(*InetAddress.getAllByName(hostname))
        } catch (e: NullPointerException) {
            val unknownHostException = UnknownHostException("Broken system behaviour for dns lookup of $hostname")
            unknownHostException.initCause(e)
            throw unknownHostException
        }
    }

    /**
     * 查找 ip 地址集。如果一个 [InetAddress] 连接失败就下一个继续尝试直到建立连接或用完集合的地址
     */
    fun lookup(hostname: String): List<InetAddress>
}