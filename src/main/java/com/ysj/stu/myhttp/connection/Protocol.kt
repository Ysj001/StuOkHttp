package com.ysj.stu.myhttp.connection

import java.io.IOException

/**
 * 网络请求协议
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
enum class Protocol(val protocol: String) {

    HTTP_1_0("http/1.0"),

    HTTP_1_1("http/1.1"),

    HTTP_2("h2"), ;

    companion object {
        fun get(protocol: String): Protocol = when (protocol) {
            HTTP_1_0.protocol -> HTTP_1_0
            HTTP_1_1.protocol -> HTTP_1_1
            HTTP_2.protocol -> HTTP_2
            else -> throw IOException("Unexpected protocol: $protocol")
        }
    }

}