package com.ysj.stu.myhttp.call.response

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.connection.exchange.Exchange
import java.io.Closeable
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT

/**
 * 响应
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class MyResponse(val statusLine: StatusLine) : Closeable {

    lateinit var request: MyRequest
        internal set

    /** HTTP 响应状态码 */
    val code: Int get() = statusLine.code

    /** HTTP 响应头 */
    val headers: Map<String, String> = HashMap()

    /** 响应体 */
    var body: ResponseBody? = null
        internal set

    /** 重定向或授权产生的，没有 [body] */
    internal var priorResponse: MyResponse? = null
        internal set(value) {
            require(value != null && value.body == null) { "priorResponse.body != null" }
            field = value
        }

    internal var exchange: Exchange? = null

    /**
     * 发送请求时的时间戳
     */
    var sentRequestAtMillis: Long = 0L
        internal set

    /**
     * 接收到响应的时间戳
     */
    var receivedResponseAtMillisL: Long = 0L

    /** 如果 [code] 在 [200..300) 则请求成功 */
    val isSuccessful: Boolean get() = code in 200 until 300

    /** 根据 [headers] 获取 Content-Length */
    val contentLength: Long get() = getHeader("Content-Length")?.toLong() ?: -1

    /** 判断 body 是否有内容 */
    val hasBody: Boolean
        get() {
            if (request.method == MyRequest.METHOD_HEAD) return false
            val code: Int = this.code
            if ((code < StatusLine.HTTP_CONTINUE || code >= 200) && code != HTTP_NO_CONTENT && code != HTTP_NOT_MODIFIED) return true
            if (contentLength != -1L) return true
            if (equalsHeaderValue("Transfer-Encoding", "chunked")) return true
            return false
        }

    override fun close() {
        val body = this.body
        checkNotNull(body) { "response is not eligible for a body and must not be closed" }
        body.close()
    }

    fun addHeader(name: String, value: String, overwrite: Boolean = false) = apply {
        if (!overwrite && containHeader(name)) return@apply
        (headers as MutableMap)[name.lowercase()] = value.lowercase()
    }

    fun getHeader(name: String) = headers[name.lowercase()]

    fun containHeader(name: String) = name.lowercase() in headers

    fun equalsHeaderValue(name: String, value: String, ignoreCase: Boolean = true) =
        getHeader(name).equals(value, ignoreCase)

    fun removeHeader(name: String) = (headers as MutableMap).remove(name.lowercase())
}