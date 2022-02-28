package com.ysj.stu.myhttp.connection.exchange

import com.ysj.stu.myhttp.call.Transmitter
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.call.response.RealResponseBody
import com.ysj.stu.myhttp.call.response.ResponseBody
import com.ysj.stu.myhttp.connection.Connection
import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException

/**
 * 用于驱动 [ExchangeCodec] 发送单个 HTTP 请求，并处理实际的 I/O。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class Exchange constructor(
    private val transmitter: Transmitter,
    private val codec: ExchangeCodec,
) {

    /**
     * 是否双工传输。通常用于 HTTP2。
     */
    var isDuplex: Boolean = false
        private set

    fun connection(): Connection {
        return codec.connection
    }

    fun cancel() {
        codec.cancel()
    }

    /**
     * [ExchangeCodec.writeRequestHeaders]
     */
    @Throws(IOException::class)
    fun writeRequestHeaders(request: MyRequest) {
        try {
            codec.writeRequestHeaders(request)
        } catch (e: IOException) {
            trackFailure(e)
            throw e
        }
    }

    /**
     * [ExchangeCodec.createRequestBody]
     */
    @Throws(IOException::class)
    fun createRequestBody(request: MyRequest, isDuplex: Boolean): ExchangeCodec.BodyWriter {
        this.isDuplex = isDuplex
        val contentLength = request.body?.contentLength() ?: -1L
        val rawWriter = codec.createRequestBody(request, contentLength)
        return RequestBodyWriter(rawWriter, contentLength)
    }

    @Throws(IOException::class)
    fun noRequestBody() {
        transmitter.exchangeMessageDone(this, null, responseDone = false, requestDone = true)
    }

    /**
     * [ExchangeCodec.flushRequest]
     */
    @Throws(IOException::class)
    fun flushRequest() {
        try {
            codec.flushRequest()
        } catch (e: IOException) {
            trackFailure(e)
            throw e
        }
    }

    /**
     * [ExchangeCodec.finishRequest]
     */
    @Throws(IOException::class)
    fun finishRequest() {
        try {
            codec.finishRequest()
        } catch (e: IOException) {
            trackFailure(e)
            throw e
        }
    }

    /**
     * [ExchangeCodec.readResponseHeaders]
     */
    @Throws(IOException::class)
    fun readResponseHeaders(expectContinue: Boolean): MyResponse? {
        try {
            return codec.readResponseHeaders(expectContinue)
        } catch (e: IOException) {
            trackFailure(e)
            throw e
        }
    }

    /**
     * [ExchangeCodec.readResponseBody]
     */
    @Throws(IOException::class)
    fun readResponseBody(response: MyResponse): ResponseBody {
        try {
            val contentType = response.getHeader("Content-Type")
            val contentLength = response.contentLength
            val rawReader = codec.readResponseBody(response)
            val source = ResponseBodyReader(rawReader, contentLength)
            return RealResponseBody(source, contentType, contentLength)
        } catch (e: IOException) {
            trackFailure(e)
            throw e
        }
    }

    internal fun bodyComplete(
        e: IOException?,
        bytesRead: Long,
        responseDone: Boolean,
        requestDone: Boolean,
    ): IOException? {
        if (e != null) trackFailure(e)
        return transmitter.exchangeMessageDone(this, e, responseDone, requestDone)
    }

    private fun trackFailure(e: IOException) {
        transmitter.exchangeFinder?.trackFailure()
        codec.connection.trackFailure(e)
    }

    /**
     * 对 [ExchangeCodec.BodyReader] 包装一层，便于内部处理一些逻辑。
     */
    private inner class ResponseBodyReader(
        private val reader: ExchangeCodec.BodyReader,
        private val contentLength: Long,
    ) : ExchangeCodec.BodyReader() {

        override var inputStream: InputStream = reader.inputStream

        private var completed: Boolean = false
        private var closed: Boolean = false

        private var bytesReceived: Long = 0L

        init {
            if (contentLength == 0L) {
                complete(null)
            }
        }

        override fun read(buff: ByteArray, byteCount: Int): Int {
            check(!closed) { "closed" }
            try {
                val read = reader.read(buff, byteCount)
                if (read == -1) {
                    complete(null)
                    return -1
                }
                val size = bytesReceived + read
                if (contentLength != -1L && size > contentLength) {
                    throw ProtocolException("expected $contentLength bytes but received $size")
                }
                bytesReceived = size
                if (size == contentLength) complete(null)
                return read
            } catch (e: IOException) {
                throw complete(e) ?: e
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                reader.close()
                complete(null)
            } catch (e: IOException) {
                throw complete(e) ?: e
            }
        }

        private fun complete(e: IOException?): IOException? {
            if (completed) return e
            completed = true
            return bodyComplete(e, bytesReceived, responseDone = true, requestDone = false)
        }
    }

    /**
     * 对 [ExchangeCodec.BodyWriter] 包装一层，便于内部处理一些逻辑。
     */
    private inner class RequestBodyWriter(
        private val writer: ExchangeCodec.BodyWriter,
        private val contentLength: Long,
    ) : ExchangeCodec.BodyWriter() {

        private var completed: Boolean = false
        private var closed: Boolean = false

        private var bytesReceived: Long = 0L

        override fun write(buff: ByteArray) {
            check(!closed) { "closed" }
            val size = bytesReceived + buff.size
            if (contentLength != -1L && size > contentLength) {
                throw ProtocolException("expected $contentLength bytes but received $size")
            }
            try {
                writer.write(buff)
                bytesReceived = size
            } catch (e: IOException) {
                throw complete(e) ?: e
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            if (contentLength != -1L && bytesReceived != contentLength) {
                throw ProtocolException("unexpected end of stream")
            }
            try {
                writer.close()
                complete(null)
            } catch (e: IOException) {
                throw complete(e) ?: e
            }
        }

        private fun complete(e: IOException?): IOException? {
            if (completed) return e
            completed = true
            return bodyComplete(e, bytesReceived, responseDone = false, requestDone = true)
        }
    }
}