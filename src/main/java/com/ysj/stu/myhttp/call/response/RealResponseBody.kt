package com.ysj.stu.myhttp.call.response

import com.ysj.stu.myhttp.connection.exchange.codec.ExchangeCodec

/**
 * [ResponseBody] 的实现
 *
 * @author Ysj
 * Create time: 2022/1/31
 */
class RealResponseBody(
    override val source: ExchangeCodec.BodyReader,
    override val contentType: String?,
    override val contentLength: Long,
) : ResponseBody()