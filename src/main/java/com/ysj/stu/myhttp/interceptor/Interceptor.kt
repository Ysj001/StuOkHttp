package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.call.MyCall
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import java.io.IOException

/**
 * 拦截器，用于处理请求过程
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
interface Interceptor {

    @Throws(IOException::class)
    fun intercept(chain: Chain): MyResponse

    interface Chain {

        val request: MyRequest

        val call: MyCall

        fun proceed(request: MyRequest): MyResponse


    }
}