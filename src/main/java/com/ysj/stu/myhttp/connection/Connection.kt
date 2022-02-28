package com.ysj.stu.myhttp.connection

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.connection.exchange.Exchange
import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import com.ysj.stu.myhttp.connection.exchange.codec.Http1ExchangeCodec
import com.ysj.stu.myhttp.connection.route.Route
import com.ysj.stu.myhttp.exception.RouteException
import com.ysj.stu.myhttp.utils.closeQuietly
import com.ysj.stu.myhttp.utils.isAndroid
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.Reference
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * 连接。用于真正进行连接的对象。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class Connection internal constructor(
    private val connectionPool: ConnectionPool,
    override val route: Route,
) : IConnection {

    override var socket: Socket? = null
        private set

    override var protocol: Protocol? = null
        private set

    /**
     * 每次 exchange 请求和响应都结束了则会 +1
     */
    internal var successCount: Int = 0

    /**
     * [connect] 成功后赋值
     */
    internal var inputStream: InputStream? = null
        private set

    /**
     * [connect] 成功后赋值
     */
    internal var outputStream: OutputStream? = null
        private set

    /**
     * [connectTls] 成功后赋值
     */
    private var handshake: Handshake? = null

    /**
     * 如果为 true，则不能在创建新的 [Exchange]
     *
     * 注意：修改时记得在 [connectionPool] 锁内
     */
    internal var noNewExchanges: Boolean = false

    /**
     * 在指定时间戳前闲置，到了该时间就会被释放。
     */
    internal var idleAtNanos: Long = Long.MAX_VALUE

    /**
     * 路由失败的次数
     */
    internal var routeFailureCount: Int = 0

    /**
     * 由于连接可以被复用，因此一个连接可以有多个 [Transmitter]
     */
    internal val transmitters = ArrayList<Reference<Transmitter>>()

    /**
     * 该连接所能分配的 [Transmitter] 数。通常 HTTP2 才会 >1
     */
    private var allocationLimit: Int = 1

    // 原始 socket，内部使用
    private var rawSocket: Socket? = null

    /**
     * 检测该连接是否支持 [host] ，[port]
     */
    fun supports(host: String, port: Int): Boolean {
        if (route.address.port != port) return false
        if (host != route.address.host) {
            // todo host name 校验
            return handshake != null
        }
        return true
    }

    /**
     * 标记此链接不能再创建 [Exchange]，同时也说明该连接不可用了。
     */
    fun noNewExchanges() {
        assert(!Thread.holdsLock(connectionPool))
        synchronized(connectionPool) {
            noNewExchanges = true
        }
    }

    /**
     * 创建 HTTP 编解码器，用于对请求编码和对响应解码
     */
    @Throws(SocketException::class)
    fun newCodec(client: MyHttpClient): ExchangeCodec {
        socket!!.soTimeout = client.readTimeoutMs
        return Http1ExchangeCodec(client, this)
    }

    /**
     * 检查该 [Connection] 是否健康，并检查其中的 [socket] 是否可用。
     *
     * @param doExtensiveChecks true 进行额外的检查。GET请求不需要额外检查。
     */
    fun isHealthy(doExtensiveChecks: Boolean): Boolean {
        val socket = this.socket ?: return false
        if (socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown) {
            return false
        }
        // TODO http2 检查
        if (!doExtensiveChecks) return true
        try {
            val readTimeout = socket.soTimeout
            try {
                socket.soTimeout = 1
                return TODO("检测是否可以读")
            } finally {
                socket.soTimeout = readTimeout
            }
        } catch (ignored: SocketTimeoutException) {
            // Read timed out; socket is good.
        } catch (e: IOException) {
            // Couldn't read; socket is closed.
            return false
        }
        return true
    }

    fun cancel() {
        rawSocket.closeQuietly()
    }

    /**
     * 建立连接。进行 TCP + TLS 握手，该操作是阻塞的。
     * 如果连接失败会抛异常，如果正常调用完成说明连接成功。
     */
    @Throws(RouteException::class)
    fun connect(client: MyHttpClient) {
        check(protocol == null) { "already connected" }
        val pingIntervalMs = client.pingIntervalMs
        val retryOnConnectionFailure = client.retryOnConnectionFailure

        // todo ssl socket

        var routeException: RouteException? = null
        while (true) {
            try {
                // todo connect tunnel

                connectSocket(client)
                establishProtocol(client)
                break
            } catch (e: IOException) {
                socket.closeQuietly()
                socket = null
                rawSocket.closeQuietly()
                rawSocket = null
                protocol = null

                if (routeException == null) {
                    routeException = RouteException(e)
                } else {
                    routeException.addConnectException(e)
                }

                if (!retryOnConnectionFailure) {
                    throw routeException
                }
            }
        }

        // todo
    }

    /**
     * 判断该连接是否符合连接池的复用要求
     */
    internal fun isEligible(address: Address, routes: List<Route>?): Boolean {

        if (transmitters.size >= allocationLimit || noNewExchanges) return false

        if (!address.equalsNonHost(this.route.address)) return false
        if (address.host == this.route.address.host) return true

        if (routes == null) return false
        var hasMatchRoute = false
        for (i in routes.indices) {
            if (this.route.proxy.type() != Proxy.Type.DIRECT) break
            val candidate = routes[i]
            if (candidate.proxy.type() != Proxy.Type.DIRECT) continue
            if (this.route.inetSocketAddress != candidate.inetSocketAddress) continue
            hasMatchRoute = true
            break
        }
        if (!hasMatchRoute) return false

        // TODO hostnameVerifier

        if (!supports(address.host, address.port)) return false

        // TODO 证书校验

        return true
    }

    /**
     * 连接失败
     */
    internal fun trackFailure(e: IOException) {
        assert(!Thread.holdsLock(connectionPool))
        synchronized(connectionPool) {
            // TODO 支持 HTTP2

        }
    }

    /**
     * SSL 握手，并建立 [SSLSocket] 连接，并初始化 [protocol]，[socket]，[handshake]
     */
    @Throws(IOException::class)
    private fun connectTls(client: MyHttpClient) {
        val address = route.address
        var sslSocket: SSLSocket? = null
        var success = false
        try {
            sslSocket = client.sslSocketFactory.createSocket(
                rawSocket,
                address.host,
                address.port,
                true
            ) as SSLSocket
            // todo 多平台判断，配置 socket 密钥，TLS 版本和扩展

            sslSocket.startHandshake()
            val session = sslSocket.session
            val unverifiedHandshake = Handshake.get(session)
            // 校验证书
            if (!client.hostnameVerifier.verify(address.host, session)) {
                val peerCertificates = unverifiedHandshake.peerCertificates
                if (peerCertificates.isEmpty()) {
                    val cert = peerCertificates[0] as X509Certificate
                    // 证书公钥的 SHA-256
                    val pin = "sha256/${String(MessageDigest.getInstance("SHA-512").digest(cert.publicKey.encoded))}"
                    throw SSLPeerUnverifiedException(
                        """
                            Hostname ${address.host} not verified:
                            certificate: $pin
                            DN: ${cert.subjectDN.name}
                        """.trimIndent()
                    )
                } else {
                    throw SSLPeerUnverifiedException(
                        "Hostname ${address.host} not verified (no certificates)"
                    )
                }
            }
            socket = sslSocket
            inputStream = sslSocket.inputStream
            outputStream = sslSocket.outputStream
            handshake = unverifiedHandshake
            protocol = Protocol.HTTP_1_1 // todo 分 jdk 平台的
            success = true
        } finally {
            if (sslSocket != null) {
                // todo jdk 版本兼容
            }
            if (!success) {
                socket.closeQuietly()
                socket = null
            }
        }
    }

    /**
     * 选择合适的协议，并初始化 [protocol]。
     */
    @Throws(IOException::class)
    private fun establishProtocol(client: MyHttpClient) {
        if (!route.address.isHttps) {
            // TODO http2
            socket = rawSocket
            protocol = Protocol.HTTP_1_1
            return
        }
        connectTls(client)

        // TODO http2
    }

    /**
     * 连接 socket，如果连接失败记得关闭 [rawSocket]。
     */
    @Throws(IOException::class)
    private fun connectSocket(client: MyHttpClient) {
        val socket = when (route.proxy.type()) {
            Proxy.Type.DIRECT,
            Proxy.Type.HTTP -> client.socketFactory.createSocket()
            else -> Socket(route.proxy)
        }
        rawSocket = socket
        try {
            socket.soTimeout = client.readTimeoutMs
            socket.connect(route.inetSocketAddress, client.connectTimeoutMs)
        } catch (e: ConnectException) {
            val ce = ConnectException("Failed to connect to " + route.inetSocketAddress)
            ce.initCause(e)
            throw ce
        }

        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }


}