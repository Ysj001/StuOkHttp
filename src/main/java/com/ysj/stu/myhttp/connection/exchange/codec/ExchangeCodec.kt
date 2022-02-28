package com.ysj.stu.myhttp.connection.exchange.codec

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.connection.Connection
import com.ysj.stu.myhttp.connection.exchange.Exchange
import java.io.*

/**
 * 对 HTTP 请求编码，对 HTTP 响应解码。会被 [Exchange] 驱动
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
interface ExchangeCodec {

    /** 承载此编码器的连接 */
    val connection: Connection

    /**
     * 写请求头。这个操作会更新 sentRequestMillis。
     */
    @Throws(IOException::class)
    fun writeRequestHeaders(request: MyRequest)

    /**
     * 返回输出流，可以通过该流处理 request body。
     */
    @Throws(IOException::class)
    fun createRequestBody(request: MyRequest, contentLength: Long): BodyWriter

    /**
     * 将请求刷出到 socket。
     */
    @Throws(IOException::class)
    fun flushRequest()

    /**
     * 将请求刷出到 socket，并且不再传出内容。
     */
    @Throws(IOException::class)
    fun finishRequest()

    /**
     * 解析响应头
     *
     * @param expectContinue 如果响应码是 100，则为 true 表示中间响应，返回 null，其它都不为 null。
     */
    @Throws(IOException::class)
    fun readResponseHeaders(expectContinue: Boolean): MyResponse?

    /**
     * 解析响应体
     */
    @Throws(IOException::class)
    fun readResponseBody(response: MyResponse): BodyReader

    /**
     * 取消流。异步的，可能不会立即释放。
     */
    fun cancel()

    abstract class BodyWriter : Closeable {

        @Throws(IOException::class)
        abstract fun write(buff: ByteArray)
    }

    abstract class BodyReader : Closeable {

        abstract var inputStream: InputStream
            internal set

        @Throws(IOException::class)
        abstract fun read(buff: ByteArray, byteCount: Int): Int

    }
}