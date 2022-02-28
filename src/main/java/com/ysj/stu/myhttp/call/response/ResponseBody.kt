package com.ysj.stu.myhttp.call.response

import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import com.ysj.stu.myhttp.utils.closeQuietly
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * 响应体
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
abstract class ResponseBody : Closeable {

    abstract val source: ExchangeCodec.BodyReader

    abstract val contentType: String?

    /**
     * 返回内容的字节数，如果字节数未知则返回 -1
     */
    abstract val contentLength: Long

    @Throws(IOException::class)
    fun string(): String {
        val sb = StringBuilder()
        val buffer = ByteArray(1024)
        var read = source.read(buffer, buffer.size)
        while (read != -1) {
            sb.append(String(buffer, 0, read))
            read = source.read(buffer, buffer.size)
        }
        return sb.toString()
    }

    override fun close() {
        source.closeQuietly()
    }

}