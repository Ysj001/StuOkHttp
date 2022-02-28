package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.HttpURLConnection.HTTP_RESET
import java.net.ProtocolException

/**
 * 最后一个拦截器，用于进行网络调用。
 */
class CallServerInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): MyResponse {
        val realChain = chain as RealInterceptorChain
        val exchange = realChain.exchange
        check(exchange != null)
        val request = realChain.request
        val requestBody = request.body
        var response: MyResponse? = null
        val sendRequestMs = System.currentTimeMillis()
        // 发请求头
        exchange.writeRequestHeaders(request)
        // 发请求体
        if (request.method != MyRequest.METHOD_GET &&
            request.method != MyRequest.METHOD_HEAD &&
            requestBody != null) {
            // 如果在发请头中有 "Expect: 100-continue"，则在发请求体前要等待 "HTTP/1.1 100 Continue"
            // 如果没有响应 "HTTP/1.1 100 Continue" 就不发送，并返回 Response
            if (request.equalsHeaderValue("Expect", "100-continue")) {
                exchange.flushRequest()
                response = exchange.readResponseHeaders(true)
            }
            if (response == null) {
                // TODO 不考虑双工
                val writer = exchange.createRequestBody(request, false)
                requestBody.writeTo(writer)
                writer.close()
            } else {
                // 如果 "Expect: 100-continue" 期望没有满足则阻止连接重用
                exchange.noRequestBody()
                // TODO 不处理 HTTP2
                exchange.connection().noNewExchanges()
            }
        } else {
            exchange.noRequestBody()
        }

        if (requestBody == null /* TODO 不考虑双工 */) {
            // 请求结束
            exchange.finishRequest()
        }

        // 处理响应头
        if (response == null) {
            response = exchange.readResponseHeaders(false)!!
        }

        var code = response.code
        if (code == 100) {
            // 如果响应码是 100，再次尝试一次
            response = exchange.readResponseHeaders(false)!!
            code = response.code
        }

        response.request = request
        // TSL handshake
        response.sentRequestAtMillis = sendRequestMs
        response.receivedResponseAtMillisL = System.currentTimeMillis()

        // TODO 不处理 WebSocket
        // 读响应体
        val responseBody = exchange.readResponseBody(response)
        response.body = responseBody

        if (request.equalsHeaderValue("Connection", "close") ||
            response.equalsHeaderValue("Connection", "close")) {
            // 如果请求头 "Connection: close" 则不能复用连接
            exchange.connection().noNewExchanges()
        }

        if ((code == HTTP_NO_CONTENT || code == HTTP_RESET) &&
            responseBody.contentLength > 0) {
            throw ProtocolException("HTTP $code had non-zero Content-Length: ${responseBody.contentLength}")
        }

        return response
    }
}