package ai.yuki.chuxue.data

import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference

/**
 * 最近一次在飞的 HTTP 调用（进程内唯一）。
 *
 * ## 它修的是什么
 * `interrupt()` 原来只做 `streamJob.cancel()`，而 OkHttp 的 `execute()` 是**阻塞**的、
 * 也不响应协程取消 —— 用户按了「停下」，界面得等底层 socket 自己返回
 *（非流式最长 120 秒）才复位。把 Call 攥在手里就能当场 `cancel()` 它，
 * 让那次读**立刻**抛 IOException 收场，`finally` 随之跑完、界面复位。
 *
 * ## ⚠️ 两个刻意的设计取舍
 * 1. **只 track、不 clear**。清掉它反而制造一个新麻烦：流式的 response body 是在
 *    `execute()` 返回**之后**才一段段读的，而"读完"这个时刻散落在很长的函数体里 ——
 *    为了清一个引用去把那些函数重构成一个大 try/finally，得不偿失。
 *    留着旧引用的代价是**零**：`cancel()` 对已经结束的调用是空操作。
 * 2. **进程内单例**（与 `MemoryExtractionScheduler.setFrontendStreaming` 同一族做法）：
 *    这个 App 同一时刻只有一路前台请求，所以"最近那一个"够用。
 *    后台记忆提取有自己的节流护栏，不会和前台的"停下"抢。
 */
object ActiveCall {

    private val current = AtomicReference<Call?>(null)

    /** 记下这一路调用。后一次会覆盖前一次 —— 我们只关心"最近那一次"。 */
    fun track(call: Call) {
        current.set(call)
    }

    /** 掐掉最近那一路调用。没有任何调用在飞时什么都不做（**不抛**）。 */
    fun cancel() {
        current.get()?.cancel()
    }
}
