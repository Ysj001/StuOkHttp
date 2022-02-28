package com.ysj.stu.myhttp.call.request

import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec
import java.io.IOException

/**
 * 请求体
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
abstract class RequestBody {

    /**
     * 请求头的 Content-Type
     */
    abstract val contentType: String?

    /**
     * 返回内容的字节数，如果字节数未知则返回 -1
     */
    @Throws(IOException::class)
    open fun contentLength(): Long = -1

    @Throws(IOException::class)
    abstract fun writeTo(writer: ExchangeCodec.BodyWriter)

    companion object {

        fun create(contentType: String, content: ByteArray) = object : RequestBody() {
            override val contentType: String = contentType

            override fun contentLength(): Long = content.size.toLong()

            override fun writeTo(writer: ExchangeCodec.BodyWriter) {
                writer.write(content)
            }
        }
    }
}