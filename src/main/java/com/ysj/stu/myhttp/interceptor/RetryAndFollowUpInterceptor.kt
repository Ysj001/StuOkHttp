package com.ysj.stu.myhttp.interceptor

import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.request.RequestBody
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.connection.route.Route
import com.ysj.stu.myhttp.exception.RouteException
import com.ysj.stu.myhttp.utils.closeQuietly
import com.ysj.stu.myhttp.utils.isSameConnection
import java.io.IOException
import java.net.HttpURLConnection.*
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 用于处理重试和持续跟进的拦截器。
 * 包括处理代理身份验证，授权，重定向，和各种异常重试。
 *
 * @author Ysj
 * Create time: 2022/2/2
 */
class RetryAndFollowUpInterceptor(
    private val transmitter: Transmitter,
) : Interceptor {

    companion object {
        /**
         * 最大重定向次数
         *
         * How many redirects and auth challenges should we attempt?
         * - Chrome follows 21 redirects;
         * - Firefox, curl, and wget follow 20;
         * - Safari follows 16;
         * - HTTP/1.0 recommends 5.
         */
        private const val MAX_FOLLOW_UPS = 20

    }

    private val client get() = transmitter.client

    override fun intercept(chain: Interceptor.Chain): MyResponse {
        // 当前请求，会在重定向过程中不断改变
        var request = chain.request

        var followUpCount = 0
        var priorResponse: MyResponse? = null
        while (true) {
            // 1. 准备连接
            transmitter.prepareToConnect(request)

            if (transmitter.isCanceled()) throw IOException("Canceled!")

            // 2. 尝试建立连接
            var response: MyResponse
            var connectSuccess = false
            try {
                response = chain.proceed(request)
                connectSuccess = true
            } catch (e: RouteException) {
                if (!recover(e.lastException, false, transmitter)) throw e.firstException
                continue
            } catch (e: IOException) {
                val requestSendStarted = true // TODO 判断 http2
                if (!recover(e, requestSendStarted, transmitter)) throw e
                continue
            } finally {
                if (!connectSuccess) {
                    transmitter.exchangeDoneDueToException()
                }
            }

            // 3. 持续跟进，处理代理身份验证，授权，重定向，和各种重试
            if (priorResponse != null) {
                priorResponse.body = null
                response.priorResponse = priorResponse
            }

            val exchange = response.exchange
            val route = exchange?.connection()?.route
            val followUp: MyRequest? = followUpRequest(response, route)
            if (followUp == null) {
                if (exchange != null && exchange.isDuplex) {
                    transmitter.timeoutEarlyExit()
                }
                return response
            }
            val followUpBody: RequestBody? = followUp.body
            if (followUpBody != null) {
                return response
            }

            response.body.closeQuietly()
//            if (transmitter.ha)

            if (++followUpCount > MAX_FOLLOW_UPS) {
                throw ProtocolException("Too many follow-up requests: $followUpCount")
            }
            request = followUp
            priorResponse = response
        }
    }

    /**
     * 持续跟踪请求。请求可能由于重定向等原因发送变更，通过该方法拿到最新的请求。
     */
    @Throws(IOException::class)
    private fun followUpRequest(response: MyResponse, route: Route?): MyRequest? {
        val method = response.request.method
        return when (val code = response.code) {
            HTTP_PROXY_AUTH -> {
                val selectedProxy = route?.proxy ?: client.proxy
                if (selectedProxy == null || selectedProxy.type() != Proxy.Type.HTTP) {
                    throw ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy")
                }
                // todo 代理身份验证
                null
            }
            HTTP_UNAUTHORIZED -> {
                // 处理未授权
                client.authenticator.authenticate(route, response)
            }
            307, 308,
            HTTP_MULT_CHOICE,
            HTTP_MOVED_PERM,
            HTTP_MOVED_TEMP,
            HTTP_SEE_OTHER -> {
                // 处理重定向
                if ((code == 307 || code == 308) && method != MyRequest.METHOD_GET && method != MyRequest.METHOD_HEAD) return null
                if (!client.followRedirects) return null
                val location = response.getHeader("Location") ?: return null
                val url = URL(location)
                val sameProtocol = url.protocol.equals(response.request.url.protocol)
                if (!sameProtocol && !client.followSSLRedirects) return null
                val request = response.request.newRequest()
                if (method != MyRequest.METHOD_GET && method != MyRequest.METHOD_HEAD) {
                    val redirectsWithBody = method == "PROPFIND"
                    if (redirectsWithBody) {
                        request.propfind(response.request.body)
                    } else {
                        request.get()
                        request.removeHeader("Transfer-Encoding")
                        request.removeHeader("Content-Length")
                        request.removeHeader("Content-Type")
                    }
                }
                if (url.isSameConnection(response.request.url)) {
                    request.removeHeader("Authorization")
                }
                request.url(url)
            }
            HTTP_CLIENT_TIMEOUT -> {
                // 请求超时，重试
                if (!client.retryOnConnectionFailure) return null
                val priorResponse = response.priorResponse
                if (priorResponse != null && priorResponse.code == HTTP_CLIENT_TIMEOUT) {
                    // 重试过一次还是超时。放弃
                    return null
                }
                if (retryAfter(response, 0) > 0) {
                    // 稍候重试的话，本次请求放弃
                    return null
                }
                response.request
            }
            HTTP_UNAVAILABLE -> {
                // 服务不可用
                val priorResponse = response.priorResponse
                if (priorResponse != null && priorResponse.code == HTTP_UNAVAILABLE) {
                    // 之前尝试过还是失败，放弃。
                    return null
                }
                if (retryAfter(response, Int.MAX_VALUE) == 0) {
                    // 响应说明需要立即重试，则尝试一次
                    return response.request
                }
                null
            }
            else -> null
        }
    }

    // Retry-After 详见：https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.3
    private fun retryAfter(response: MyResponse, defaultDelay: Int): Int {
        val retryAfter = response.getHeader("Retry-After") ?: return defaultDelay
        return retryAfter.toInt()
    }

    /**
     * 判断连接是否可以恢复，如果是可恢复的返回 true，否则返回 false。
     *
     * @param requestSendStarted 请求是否已经发送
     */
    private fun recover(e: Exception, requestSendStarted: Boolean, transmitter: Transmitter): Boolean {
        if (!client.retryOnConnectionFailure) return false
        when (e) {
            // 协议异常，不可恢复
            is ProtocolException -> return false
            // 超时
            is SocketTimeoutException -> return !requestSendStarted
            // SSL 握手异常且是 CertificateException 导致的不重试
            is SSLHandshakeException -> return e.cause !is CertificateException
            is SSLPeerUnverifiedException -> return false
        }
        return transmitter.canRetry()
    }
}