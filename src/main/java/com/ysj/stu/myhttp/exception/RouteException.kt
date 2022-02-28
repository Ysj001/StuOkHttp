package com.ysj.stu.myhttp.exception

import com.ysj.stu.myhttp.connection.Connection
import com.ysj.stu.myhttp.connection.route.Route
import java.io.IOException

/**
 * 路由异常，通常是在 [Connection.connect] 过程中，和 [Route] 连接不成功时抛出。
 */
class RouteException constructor(e: IOException) : RuntimeException(e) {

    var firstException: IOException = e
        private set
    var lastException: IOException = e
        private set

    fun addConnectException(e: IOException) {
        firstException.addSuppressed(e)
        lastException = e
    }
}