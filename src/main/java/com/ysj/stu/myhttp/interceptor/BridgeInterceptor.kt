package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.call.response.RealResponseBody
import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 桥接网络层。主要用于处理请求头。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class BridgeInterceptor : Interceptor {

    // todo cookie
    // CookieHandle

    override fun intercept(chain: Interceptor.Chain): MyResponse {
        val request = chain.request

        request.body?.also { body ->
            body.contentType?.also {
                request.addHeader("Content-Type", it)
            }
            val contentLength = body.contentLength()
            if (contentLength != -1L) {
                request.addHeader("Content-Length", contentLength.toString())
                request.removeHeader("Transfer-Encoding")
            } else {
                request.addHeader("Transfer-Encoding", "chunked")
                request.removeHeader("Content-Length")
            }
        }

        request.addHeader("Host", request.url.host)
        request.addHeader("Connection", "Keep-Alive")

        // 判断是否要设置 gzip
        var useGzip = false
        if (!request.containHeader("Accept-Encoding") && !request.containHeader("Range")) {
            request.addHeader("Accept-Encoding", "gzip")
            useGzip = true
        }

        // todo cookie

        request.addHeader("User-Agent", MyHttpClient.USER_AGENT)

        val response = chain.proceed(request)
        response.request = request

        // todo cookie

        // 处理 gzip
        if (useGzip && response.equalsHeaderValue("Content-Encoding", "gzip") && response.hasBody) {
            val source = response.body!!.source
            // 替换成 gzip 包装的流，外部使用就不用处理
            source.inputStream = GZIPInputStream(source.inputStream)
            response.removeHeader("Content-Encoding")
            response.removeHeader("Content-Length")
            response.body = RealResponseBody(source, response.getHeader("Content-Type"), -1L)
        }

        return response
    }

}