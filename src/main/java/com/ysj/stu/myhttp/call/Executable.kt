package com.ysj.stu.myhttp.call

/**
 * 用于执行任务
 *
 * @author Ysj
 * Create time: 2022/1/28
 */
abstract class Executable : Runnable {

    abstract val name: String

    final override fun run() {
        val currentThread = Thread.currentThread()
        val oldName = currentThread.name
        try {
            currentThread.name = name
            execute()
        } finally {
            currentThread.name = oldName
        }
    }

    protected abstract fun execute()
}