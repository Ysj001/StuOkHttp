package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.connection.exchange.Exchange

/**
 * 连接目标服务器的拦截器，会在这创建 [Exchange]。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class ConnectInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): MyResponse {
        val realChain = chain as RealInterceptorChain
        val request = chain.request
        val transmitter = realChain.transmitter

        // We need the network to satisfy this request. Possibly for validating a conditional GET.
        val doExtensiveHealthChecks = request.method != MyRequest.METHOD_GET
        val exchange = transmitter.newExchange(doExtensiveHealthChecks)

        return realChain.proceed(request, transmitter, exchange)
    }

}