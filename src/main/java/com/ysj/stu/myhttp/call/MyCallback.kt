package com.ysj.stu.myhttp.call

import com.ysj.stu.myhttp.call.response.MyResponse
import java.lang.Exception

/**
 * 结果的回调
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
interface MyCallback {

    /**
     * 失败时回调
     */
    fun onFailure(call: MyCall, e: Exception)

    /**
     * 成功时回调
     */
    fun onSuccess(call: MyCall, response: MyResponse)
}