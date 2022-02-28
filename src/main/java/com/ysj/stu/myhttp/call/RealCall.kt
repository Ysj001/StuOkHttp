package com.ysj.stu.myhttp.call

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.interceptor.*
import com.ysj.stu.myhttp.utils.closeQuietly
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * [MyCall] 实现类
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class RealCall constructor(
    private val client: MyHttpClient,
    override val originalRequest: MyRequest,
) : MyCall {

    override var isExecuted: Boolean = false
        private set

    private lateinit var transmitter: Transmitter

    companion object {
        fun newRealCall(client: MyHttpClient, request: MyRequest): RealCall {
            val realCall = RealCall(client, request)
            realCall.transmitter = Transmitter(client, realCall)
            return realCall
        }
    }

    override fun syncExec(): MyResponse {
        checkExecuted()
        transmitter.timeoutEnter()
        transmitter.callStart()
        try {
            client.dispatcher.sync(this)
            return getResponseWithInterceptorChain()
        } finally {
            client.dispatcher.finished(this)
        }
    }

    override fun asyncExec(callback: MyCallback) {
        checkExecuted()
        transmitter.callStart()
        client.dispatcher.async(Async(callback))
    }

    override fun cancel() {
        transmitter.cancel()
    }

    override fun clone(): MyCall {
        return newRealCall(client, originalRequest)
    }

    @Synchronized
    private fun checkExecuted() {
        if (isExecuted) throw IllegalStateException("Already Executed")
        isExecuted = true
    }

    @Throws(IOException::class)
    private fun getResponseWithInterceptorChain(): MyResponse {
        val interceptors = ArrayList<Interceptor>()
        interceptors.addAll(client.interceptors)
        interceptors.add(RetryAndFollowUpInterceptor(transmitter))
        interceptors.add(BridgeInterceptor())
        interceptors.add(ConnectInterceptor())
        interceptors.add(CallServerInterceptor())

        val chain = RealInterceptorChain(
            originalRequest, this, transmitter, null, interceptors, 0,
        )
        var calledNoMoreExchanges = false
        try {
            val response = chain.proceed(originalRequest)
            if (transmitter.isCanceled()) {
                response.closeQuietly()
                throw IOException("Canceled")
            }
            return response
        } catch (e: IOException) {
            calledNoMoreExchanges = true
            throw transmitter.noMoreExchanges(e)
        } finally {
            if (!calledNoMoreExchanges) {
                transmitter.noMoreExchanges(null)
            }
        }
    }

    internal inner class Async(private val callback: MyCallback) : Executable() {

        override val name: String = originalRequest.url.toString()

        /** 当前请求的 host */
        val host: String = originalRequest.url.host

        /** 当前 host 正在并发的数量 */
        @Volatile
        var concurrentHostNum: Int = 0

        override fun execute() {
            var response: MyResponse? = null
            transmitter.timeoutEnter()
            try {
                response = getResponseWithInterceptorChain()
                callback.onSuccess(this@RealCall, response)
            } catch (e: IOException) {
                if (response == null) callback.onFailure(this@RealCall, e)
                else println("用户使用时的异常：$e")
            } catch (t: Throwable) {
                cancel()
                if (response == null) {
                    val canceledException = IOException("canceled due to $t")
                    canceledException.addSuppressed(t)
                    callback.onFailure(this@RealCall, canceledException)
                }
                throw t
            } finally {
                client.dispatcher.finished(this)
            }
        }

        fun executeOn(executor: ExecutorService) {
            assert(!Thread.holdsLock(client.dispatcher))
            var success = false
            try {
                executor.execute(this)
                success = true
            } catch (e: Exception) {
                callback.onFailure(this@RealCall, e)
            } finally {
                if (!success) client.dispatcher.finished(this)
            }
        }
    }
}