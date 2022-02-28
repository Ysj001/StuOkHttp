package com.ysj.stu.myhttp.connection.exchange.codec

import com.ysj.stu.myhttp.MyHttpClient
import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.call.response.StatusLine
import com.ysj.stu.myhttp.connection.Connection
import java.io.*
import java.lang.NumberFormatException
import java.net.ProtocolException
import java.net.Proxy
import java.net.URL
import kotlin.math.min

/**
 * 用于处理 HTTP/1.1 的编解码器。
 *
 * @author Ysj
 * Create time: 2022/2/3
 */
class Http1ExchangeCodec(
    private val client: MyHttpClient,
    override val connection: Connection,
) : ExchangeCodec {

    companion object {
        /** 空闲，用于写请求头 */
        private const val STATE_IDLE = 0
        private const val STATE_OPEN_REQUEST_BODY = 1
        private const val STATE_WRITING_REQUEST_BODY = 2
        private const val STATE_READ_RESPONSE_HEADERS = 3
        private const val STATE_OPEN_RESPONSE_BODY = 4
        private const val STATE_READING_RESPONSE_BODY = 5
        private const val STATE_CLOSED = 6
    }

    // 当前状态
    private var state = STATE_IDLE

    override fun writeRequestHeaders(request: MyRequest) {
        check(state == STATE_IDLE) { "state: $state" }
        val writer: BufferedWriter = connection.outputStream!!.bufferedWriter()
        // 准备 HTTP 报头并将其发送到服务器。
        // 对于带有 body 的流请求，必须在输出流被写入之前准备好报头。否则 body 将需要缓冲!
        // 对于带有 body 的非流请求，必须在输出流写入并关闭后准备报头。这确保 Content-Length 报头字段接收到正确的值。
        val proxyType = connection.route.proxy.type()
        // 1. 请求状态行。如："GET / HTTP/1.1"
        writer.append(request.method).append(' ').append('/')
        if (!request.isHttps && proxyType == Proxy.Type.HTTP) {
            writer.append(request.url.toString())
        } else {
            writer.append(request.url.file)
        }
        writer.append(" HTTP/1.1").append("\r\n")
        // 2. 发送整个请求头，并切换状态
        request.headers.forEach {
            writer.append(it.key).append(": ").append(it.value).append("\r\n")
        }
        writer.append("\r\n").flush()
        state = STATE_OPEN_REQUEST_BODY
    }

    override fun createRequestBody(request: MyRequest, contentLength: Long): ExchangeCodec.BodyWriter {
        check(state == STATE_OPEN_REQUEST_BODY) { "state: $state" }
        if (request.equalsHeaderValue("Transfer-Encoding", "chunked")) {
            state = STATE_WRITING_REQUEST_BODY
            return ChunkedBodyWriter()
        }
        if (contentLength != -1L) {
            state = STATE_WRITING_REQUEST_BODY
            return KnownLengthBodyWriter()
        }
        throw IllegalStateException(
            "Cannot stream a request body without chunked encoding or a known content length!"
        )
    }

    override fun flushRequest() {
        connection.outputStream!!.flush()
    }

    override fun finishRequest() {
        connection.outputStream!!.flush()
    }

    override fun readResponseHeaders(expectContinue: Boolean): MyResponse? {
        check(state == STATE_OPEN_REQUEST_BODY || state == STATE_READ_RESPONSE_HEADERS) { "state: $state" }
        val ips = connection.inputStream!!
        val reader = LineReader(ips)
        try {
            val statusLine = StatusLine.parse(reader.readLine())
            val response = MyResponse(statusLine)
            if (statusLine.code == StatusLine.HTTP_CONTINUE) {
                return if (expectContinue) null else response.apply { readHeaders(reader) }
            }
            response.readHeaders(reader)
            state = STATE_OPEN_RESPONSE_BODY
            return response
        } catch (e: EOFException) {
            // 服务器在给出 response 前就关闭流了
            throw IOException("unexpected end of stream on ${connection.route.address}", e)
        }
    }

    override fun readResponseBody(response: MyResponse): ExchangeCodec.BodyReader {
        check(state == STATE_OPEN_RESPONSE_BODY) { "state: $state" }
        state = STATE_READING_RESPONSE_BODY
        if (!response.hasBody) {
            return KnownLengthBodyReader(0)
        }
        if (response.equalsHeaderValue("Transfer-Encoding", "chunked")) {
            return ChunkedBodyReader(response.request.url)
        }
        val contentLength = response.contentLength
        if (contentLength != -1L) {
            return KnownLengthBodyReader(contentLength)
        }
        return UnknownLengthBodyReader()
    }

    override fun cancel() {
        connection.cancel()
    }

    /**
     * 将 HTTP 响应头读到 [MyResponse] 中
     */
    @Throws(IOException::class)
    private fun MyResponse.readHeaders(reader: LineReader) {
        var line: String = reader.readLine()
        while (line != "") {
            val split = line.split(": ")
            addHeader(split[0], split[1])
            line = reader.readLine()
        }
    }

    private fun responseBodyComplete() {
        if (state == STATE_CLOSED) return
        check(state == STATE_READING_RESPONSE_BODY) { "state: $state" }
        state = STATE_CLOSED
    }

    /**
     * 处理 Transfer-Encoding:chunked 。需要分块读取的 response body
     *
     * 协议如：(chunk-size)(\r\n)(chunk-data)(\r\n)(chunk-size:0\r\n)(\r\n)
     */
    private inner class ChunkedBodyReader(private val url: URL) : AbsBodyReader() {

        private val NO_CHUNK_YET = -1L

        // chunk 中剩余的的字节数，-1 表示没有
        private var bytesRemainingInChunk: Long = NO_CHUNK_YET

        // 标记是否还有 chunk
        private var hasMoreChunks = true

        override fun read(buff: ByteArray, byteCount: Int): Int {
            check(!closed) { "closed" }
            if (!hasMoreChunks) return -1
            if (bytesRemainingInChunk == 0L || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize()
                if (!hasMoreChunks) return -1
            }
            val read = super.read(buff, min(byteCount, bytesRemainingInChunk.toInt()))
            if (read == -1) {
                // 还有内容要读，但是没读出来
                connection.noNewExchanges()
                responseBodyComplete()
                throw ProtocolException("unexpected end of stream")
            }
            bytesRemainingInChunk -= read
            return read
        }

        override fun close() {
            if (closed) return
            if (hasMoreChunks /* && TODO 这里要尝试耗尽这个流，以便这个连接可以重用，如果尝试失败返回 false */) {
                // 有内容没有读取。此时该连接不能再复用了，否则下个请求会读到这次剩下的内容
                connection.noNewExchanges()
                responseBodyComplete()
            }
            closed = true
        }

        /**
         * 读取 Chunk 的大小
         */
        @Throws(IOException::class)
        private fun readChunkSize() {
            val lineReader = LineReader(inputStream)
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                lineReader.readLine() // 前一个 \r\n 先读出来
            }
            try {
                bytesRemainingInChunk = lineReader.readLine().toLong(16) // chunk-size
            } catch (e: NumberFormatException) {
                throw ProtocolException(e.message)
            }
            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false
                // TODO trailers header
                lineReader.readLine() // 结束的 \r\n 读出来
                responseBodyComplete()
            }
        }
    }

    /**
     * 用于读取未知长度的 response body
     */
    private inner class UnknownLengthBodyReader : AbsBodyReader() {

        private var inputExhausted = false

        override fun read(buff: ByteArray, byteCount: Int): Int {
            check(!closed) { "closed" }
            val read = super.read(buff, byteCount)
            if (inputExhausted) return -1
            if (read == -1) {
                inputExhausted = true
                responseBodyComplete()
            }
            return read
        }

        override fun close() {
            if (closed) return
            if (!inputExhausted) {
                responseBodyComplete()
            }
            closed = true
        }
    }

    /**
     * 用于读取固定长度的 response body
     */
    private inner class KnownLengthBodyReader(private var bytesRemaining: Long) : AbsBodyReader() {

        init {
            if (bytesRemaining == 0L) {
                responseBodyComplete()
            }
        }

        override fun read(buff: ByteArray, byteCount: Int): Int {
            check(!closed) { "closed" }
            if (bytesRemaining == 0L) return -1
            val read = super.read(buff, byteCount)
            if (read == -1) {
                // 还有内容要读，但是没读出来
                connection.noNewExchanges()
                responseBodyComplete()
                throw ProtocolException("unexpected end of stream")
            }
            bytesRemaining -= read
            if (bytesRemaining == 0L) responseBodyComplete()
            return read
        }

        override fun close() {
            if (closed) return
            try {
                if (bytesRemaining != 0L) inputStream.skip(bytesRemaining)
            } catch (e: IOException) {
                // 还有内容要读，但是读失败了
                connection.noNewExchanges()
                responseBodyComplete()
            }
            closed = true
        }
    }

    /**
     * 发送知道长度的 request body
     */
    private inner class KnownLengthBodyWriter : AbsBodyWriter() {

        override fun write(buff: ByteArray) {
            check(!closed) { "closed" }
            ops.write(buff)
        }

        override fun close() {
            if (closed) return
            closed = true
            state = STATE_READ_RESPONSE_HEADERS
        }
    }

    /**
     * 发送分块 request body
     */
    private inner class ChunkedBodyWriter : AbsBodyWriter() {

        override fun write(buff: ByteArray) {
            check(!closed) { "closed" }
            ops.write(buff.size.toULong().toString(16).toByteArray()) // chunked size
            ops.write("\r\n".toByteArray())
            ops.write(buff) // chunked content
            ops.write("\r\n".toByteArray())
        }

        override fun close() {
            if (closed) return
            closed = true
            ops.write("0\r\n\r\n".toByteArray())
            state = STATE_READ_RESPONSE_HEADERS
        }
    }

    private abstract inner class AbsBodyWriter : ExchangeCodec.BodyWriter() {
        protected val ops: OutputStream = connection.outputStream!!
        protected var closed: Boolean = false
    }

    private abstract inner class AbsBodyReader : ExchangeCodec.BodyReader() {
        override var inputStream: InputStream = connection.inputStream!!
        protected var closed: Boolean = false
        override fun read(buff: ByteArray, byteCount: Int): Int {
            try {
                return inputStream.read(buff, 0, byteCount)
            } catch (e: IOException) {
                connection.noNewExchanges()
                responseBodyComplete()
                throw e
            }
        }
    }

    private class LineReader(private val ips: InputStream) {
        private val buff = ByteArray(Char.SIZE_BYTES)
        private val sb = StringBuilder()

        @Throws(IOException::class)
        fun readLine(): String {
            sb.clear()
            var hasCR = false // \r
            var hasLF = false // \n
            var read = ips.read()
            if (read == -1) throw EOFException()
            var buffIndex = 0
            while (read != -1) {
                var c = Char(read)
                if (c == '\r') hasCR = !hasCR
                if (c == '\n') {
                    if (hasCR) hasLF = true
                    else hasCR = false
                }
                buff[buffIndex] = read.toByte()
                if (buffIndex < buff.lastIndex && (!hasCR || !hasLF)) {
                    read = ips.read()
                    if (read != -1) {
                        buffIndex++
                        continue
                    }
                }
                val str = String(buff, 0, buffIndex + 1)
                for (strIndex in str.indices) {
                    c = str[strIndex]
                    sb.append(c)
                }
                if (hasCR && hasLF) break
                buffIndex = 0
                read = ips.read()
            }
            return sb.toString().replace("\r\n", "")
        }

    }
}
