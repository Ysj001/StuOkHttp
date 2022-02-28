package com.ysj.stu.myhttp.call

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import java.io.IOException

/**
 * 封装对请求调用的方式。同一个 [MyCall] 对象不能调用两次。
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
interface MyCall : Cloneable {

    /** 初始化该对象时用的原始请求 */
    val originalRequest: MyRequest

    /** 是否已经执行过 */
    val isExecuted: Boolean

    /**
     * 同步执行
     */
    @Throws(IOException::class)
    fun syncExec(): MyResponse

    /**
     * 异步执行
     */
    fun asyncExec(callback: MyCallback)

    /**
     * 取消调用
     */
    fun cancel()

    override fun clone(): MyCall
}