package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.call.MyCall
import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.connection.exchange.Exchange
import com.ysj.stu.myhttp.utils.supportPort

/**
 * 承载所有拦截器的链。该对象只是一个包装对象。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class RealInterceptorChain(
    override val request: MyRequest,
    override val call: MyCall,
    val transmitter: Transmitter,
    val exchange: Exchange?,
    private val interceptors: List<Interceptor>,
    private var index: Int,
) : Interceptor.Chain {

    // 用于确保 proceed 只调一次的标记
    private var proceedCount: Int = 0

    override fun proceed(request: MyRequest): MyResponse {
        return proceed(request, transmitter, exchange)
    }

    fun proceed(request: MyRequest, transmitter: Transmitter, exchange: Exchange?): MyResponse {
        if (index >= interceptors.size) throw AssertionError()
        proceedCount++

        // 如果已经有符合要求的 exchange，不能再处理了
        check(!(this.exchange != null && !this.exchange.connection().supports(request.url.host, request.url.supportPort))) {
            "network interceptor ${interceptors[index - 1]} must retain the same host and port"
        }

        // 如果已经有 exchange，且处理过了，不能再处理了
        check(!(this.exchange != null && proceedCount > 1)) {
            ("network interceptor ${interceptors[index - 1]} must call proceed() exactly once")
        }

        val nextIndex = index + 1
        val nextChain = RealInterceptorChain(
            request, call, transmitter, exchange, interceptors, nextIndex
        )
        val interceptor = interceptors[index]
        val response = interceptor.intercept(nextChain)

        // 对 nextChain 的 proceed 校验
        check(!(exchange != null && index + 1 < interceptors.size && nextChain.proceedCount != 1)) {
            ("network interceptor $interceptor must call proceed() exactly once")
        }

        checkNotNull(response.body) {
            "interceptor $interceptor returned a response with no body"
        }

        return response
    }
}