package com.ysj.stu.myhttp.connection.route

/**
 * 路由失败的缓存。如果某个 [Route] 连接失败了会被记住，从而首选其它的 [Route]
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class RouteFailedCache {

    private val failedRoutes = LinkedHashSet<Route>()

    /**
     * 记录该 [failed] 连接失败了
     */
    @Synchronized
    fun failed(failed: Route) {
        failedRoutes.add(failed)
    }

    /**
     * 从失败记录中移除 [route]
     */
    @Synchronized
    fun connected(route: Route) {
        failedRoutes.remove(route)
    }

    /**
     * 如果最近该 [route] 失败了，则应该避开它
     */
    @Synchronized
    fun shouldPostpone(route: Route): Boolean {
        return route in failedRoutes
    }
}