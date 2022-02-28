package com.ysj.stu.myhttp

import com.ysj.stu.myhttp.call.MyCall
import com.ysj.stu.myhttp.call.RealCall
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.connection.ConnectionPool
import com.ysj.stu.myhttp.interceptor.Interceptor
import java.io.IOException
import java.net.*
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.util.*
import javax.net.SocketFactory
import javax.net.ssl.*

/**
 * Http 请求客户端
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class MyHttpClient {

    companion object {
        const val USER_AGENT = "MyHttp/1.0.0"
    }

    object NullProxySelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    val dispatcher = Dispatcher()

    val connectionPool = ConnectionPool()

    val interceptors = ArrayList<Interceptor>()

    var dns: Dns = Dns.DEFAULT
        private set

    /**
     * 设置用于创建 [Socket] 的工厂。默认使用系统 [SocketFactory.getDefault]
     */
    var socketFactory: SocketFactory = SocketFactory.getDefault()
        private set

    /**
     * 设置用于创建 [Socket] 的工厂。默认使用系统 [SSLContext.getSocketFactory]
     */
    var sslSocketFactory: SSLSocketFactory
        private set

    /**
     * 用于 HTTPS 连接请求的 host name 校验器。默认使用系统的 [HttpsURLConnection.getDefaultHostnameVerifier]
     */
    var hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        private set

    /**
     * 处理 cookie
     */
    var cookieHandler: CookieHandler = CookieManager()
        private set

    var authenticator: Authenticator = Authenticator.NONE

    /**
     * HTTP 代理，优先级高于 [proxySelector]。
     * 为 null 则使用 [proxySelector]，若想真正禁用代理需要设置 [Proxy.NO_PROXY]
     */
    var proxy: Proxy? = null
        private set

    var proxySelector: ProxySelector = ProxySelector.getDefault() ?: NullProxySelector
        private set

    /**
     * 是否允许重定向
     */
    var followRedirects: Boolean = true
        private set

    /**
     * 是否允许 ssl 重定向
     */
    var followSSLRedirects: Boolean = true
        private set

    /**
     * 在连接失败时是否重试。默认重试。
     * - 如果 URL 的 host 有多个 ip 地址，某一个不可用不代表所有的不可用，会尝试其它的。
     * - 在使用 [ConnectionPool] 重用 sockets 时可能超时，此时可以重试。
     * - 如果有多个代理服务器，某一个不可以不代表所有不可用，会尝试其它的。
     */
    var retryOnConnectionFailure: Boolean = true
        private set

    var callTimeoutMs: Int = 0
    var connectTimeoutMs: Int = 10_000
    var readTimeoutMs: Int = 30_000
    var writeTimeoutMs: Int = 10_000
    var pingIntervalMs: Int = 0

    init {
        val trustManager: X509TrustManager = try {
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                throw IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers))
            }
            trustManagers[0] as X509TrustManager
        } catch (e: GeneralSecurityException) {
            throw AssertionError("No System TLS", e) // The system has no TLS. Just give up.
        }
        val sslContext = try {
            SSLContext.getInstance("TLSv1.2")
        } catch (e: NoSuchAlgorithmException) {
            SSLContext.getInstance("TLS")
        }
        sslContext.init(null, arrayOf(trustManager), null)
        sslSocketFactory = sslContext.socketFactory
    }

    /**
     * 生成一个对 [MyRequest] 的调用，你可以决定对其调用的方式
     */
    fun newCall(request: MyRequest): MyCall = RealCall.newRealCall(this, request)

    /**
     * 添加用户定义的拦截器
     */
    fun addInterceptor(interceptor: Interceptor) = apply {
        this.interceptors.add(interceptor)
    }

    /**
     * 设置 [Dns]
     */
    fun setDns(dns: Dns) = apply {
        this.dns = dns
    }

    fun setSocketFactory(socketFactory: SocketFactory) = apply {
        require(socketFactory !is SSLSocketFactory) { "socketFactory is SSLSocketFactory" }
        this.socketFactory = socketFactory
    }

    fun setSSLSocketFactory(sslSocketFactory: SSLSocketFactory, trustManager: X509TrustManager) = apply {
        this.socketFactory = sslSocketFactory
        // todo trustManager
    }

    fun setRetryOnConnectionFailure(retryOnConnectionFailure: Boolean) = apply {
        this.retryOnConnectionFailure = retryOnConnectionFailure
    }

}