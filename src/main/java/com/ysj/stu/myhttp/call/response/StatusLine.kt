package com.ysj.stu.myhttp.call.response

import com.ysj.stu.myhttp.connection.Protocol
import java.io.IOException
import java.net.ProtocolException

/**
 * HTTP 响应行。如："HTTP/1.1 200 OK"
 */
class StatusLine(
    val protocol: Protocol,
    val code: Int,
    val message: String
) {

    companion object {
        /** Numeric status code, 307: Temporary Redirect.  */
        const val HTTP_TEMP_REDIRECT = 307
        const val HTTP_PERM_REDIRECT = 308
        const val HTTP_CONTINUE = 100

        @Throws(IOException::class)
        fun parse(statusLine: String): StatusLine {
            if (statusLine.length < 12 || !statusLine.startsWith("HTTP/1.") || statusLine[8] != ' ') {
                throw ProtocolException("Unexpected status line: $statusLine")
            }
            val protocol = when (statusLine[7]) {
                '0' -> Protocol.HTTP_1_0
                '1' -> Protocol.HTTP_1_1
                else -> throw ProtocolException("Unexpected status line: $statusLine")
            }
            val code = statusLine.substring(9, 12).toInt()
            val message = if (statusLine.length == 12) "" else {
                if (statusLine[12] != ' ') throw ProtocolException("Unexpected status line: $statusLine")
                else statusLine.substring(13)
            }
            return StatusLine(protocol, code, message)
        }
    }

    override fun toString(): String {
        return "StatusLine(protocol='$protocol', code=$code, message='$message')"
    }

}