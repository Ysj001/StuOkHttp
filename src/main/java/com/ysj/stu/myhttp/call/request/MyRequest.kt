package com.ysj.stu.myhttp.call.request

import java.net.URL

/**
 * 请求
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class MyRequest {

    companion object {
        const val METHOD_GET = "GET"
        const val METHOD_POST = "POST"
        const val METHOD_HEAD = "HEAD"
        const val METHOD_OPTIONS = "OPTIONS"
        const val METHOD_PUT = "PUT"
        const val METHOD_DELETE = "DELETE"
        const val METHOD_TRAC = "TRAC"
    }

    lateinit var url: URL
        private set

    var method: String = METHOD_GET
        private set

    val headers: Map<String, String> = HashMap()

    var body: RequestBody? = null
        private set

    val isHttps: Boolean get() = url.protocol == "https"

    fun url(url: String) = apply {
        this.url = URL(url)
    }

    fun url(url: URL) = apply {
        this.url = url
    }

    fun get() = apply {
        this.method = METHOD_GET
        this.body = null
    }

    fun post(body: RequestBody) = apply {
        this.method = METHOD_POST
        this.body = body
    }

    // 用于重定向
    internal fun propfind(body: RequestBody?) = apply {
        this.method = "PROPFIND"
        this.body = body
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

    fun newRequest() = MyRequest().also {
        it.url = url
        it.method = method
        (it.headers as MutableMap).putAll(headers)
        it.body = body
    }
}