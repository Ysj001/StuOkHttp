package com.ysj.stu.myhttp

import com.ysj.stu.myhttp.call.request.MyRequest
import com.ysj.stu.myhttp.call.response.MyResponse
import com.ysj.stu.myhttp.connection.route.Route
import java.io.IOException
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * 授权器，用于在 [HTTP_UNAUTHORIZED] 时自动授权。
 */
fun interface Authenticator {

    object NONE : Authenticator {
        override fun authenticate(route: Route?, response: MyResponse): MyRequest? = null
    }

    @Throws(IOException::class)
    fun authenticate(route: Route?, response: MyResponse): MyRequest?
}