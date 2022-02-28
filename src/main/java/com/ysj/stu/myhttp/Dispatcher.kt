package com.ysj.stu.myhttp

import com.ysj.stu.myhttp.call.RealCall
import com.ysj.stu.myhttp.utils.createThreadFactory
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 分发器，用于分发请求
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
class Dispatcher {

    /** 用于分发请求的线程池 */
    private val executor by lazy {
        ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            createThreadFactory("MyHttp Dispatcher", false)
        )
    }

    private val readyAsyncCall = ArrayDeque<RealCall.Async>()

    private val runningAsyncCall = ArrayDeque<RealCall.Async>()

    private val runningSyncCall = ArrayDeque<RealCall>()

    /** 当 [runningAsyncCall] 和 [runningSyncCall] 都空闲时执行的回调 */
    private var idleCallback: Runnable? = null

    /**
     * 最大并发请求数量
     */
    var maxRequests: Int = 64
        set(value) {
            require(value >= 1) { "max < 1: $value" }
            synchronized(this) { field = value }
            doAsync()
        }

    /**
     * 同一 host 下最大并发请求数量
     */
    var maxRequestsPerHost: Int = 5
        set(value) {
            require(value >= 1) { "max < 1: $value" }
            synchronized(this) { field = value }
            doAsync()
        }

    /**
     * 设置 [idleCallback]
     */
    @Synchronized
    fun setIdleCallback(callback: Runnable) {
        this.idleCallback = callback
    }

    /**
     * 将一个 [RealCall.Async] 放到 [readyAsyncCall]，并调用 [doAsync] 尝试执行。
     *
     * 注意：执行完成后必须调用 [finished]
     */
    internal

    fun async(call: RealCall.Async) {
        synchronized(this) {
            readyAsyncCall.add(call)
            findExistingCallWithHost(call.host)?.also {
                // 设置该 host 的并发数
                call.concurrentHostNum = it.concurrentHostNum
            }
        }
        doAsync()
    }

    /**
     * 同步调用
     *
     * 注意：执行完成后必须调用 [finished]
     */
    internal fun sync(call: RealCall) {
        runningSyncCall.add(call)
    }

    internal fun finished(call: RealCall.Async) {
        call.concurrentHostNum--
        finished(runningAsyncCall, call)
    }

    internal fun finished(call: RealCall) {
        finished(runningSyncCall, call)
    }

    private fun <T> finished(queue: MutableCollection<T>, call: T) {
        val idleCallback = synchronized(this) {
            if (!queue.remove(call)) throw AssertionError("Call wasn't in-flight!")
            this.idleCallback
        }
        doAsync()
        if (idleCallback != null && runningCallsCount() > 0) {
            idleCallback.run()
        }
    }

    /**
     * 尝试执行 [readyAsyncCall] 中的任务。
     * 如果满足 [maxRequests] 和 [maxRequestsPerHost] 则执行将任务放到 [runningAsyncCall] 中并执行它
     */
    private fun doAsync() {
        assert(!Thread.holdsLock(this))
        val needExecute = ArrayList<RealCall.Async>()
        synchronized(this) {
            val iterator = readyAsyncCall.iterator()
            for (call in iterator) {
                if (runningAsyncCall.size >= maxRequests) break
                if (call.concurrentHostNum >= maxRequestsPerHost) continue
                iterator.remove()
                call.concurrentHostNum++
                needExecute.add(call)
                runningAsyncCall.add(call)
            }
        }
        for (index in needExecute.indices) {
            needExecute[index].executeOn(executor)
        }
    }

    /**
     * 查找是否有相同 host 的 [RealCall.Async] 的对象
     */
    private fun findExistingCallWithHost(host: String): RealCall.Async? {
        return runningAsyncCall.find { it.host == host }
            ?: readyAsyncCall.find { it.host == host }
    }

    @Synchronized
    private fun runningCallsCount() = runningAsyncCall.size + runningSyncCall.size
}