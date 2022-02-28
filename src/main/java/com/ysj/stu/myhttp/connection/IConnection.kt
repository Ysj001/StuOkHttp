package com.ysj.stu.myhttp.connection

import com.ysj.stu.myhttp.connection.route.Route
import java.net.Socket

/**
 * 定义连接
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
interface IConnection {

    /**
     * 该连接的路由信息
     */
    val route: Route

    /**
     * 该连接所建立的 [Socket]，成功连接后才有
     */
    val socket: Socket?

    /**
     * 该连接的协议，成功连接后才有
     */
    val protocol: Protocol?
}