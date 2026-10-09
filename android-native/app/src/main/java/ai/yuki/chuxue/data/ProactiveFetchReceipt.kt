package ai.yuki.chuxue.data

/**
 * 取件后**该弹哪几条通知**（纯函数，可单测；v0.61.48）。
 *
 * ## 为什么需要它
 * 原实现是 `if (since.isNotBlank()) { 逐条弹 }` —— **首次取件一条都不弹**
 *（当时的理由：不拿用户开功能之前攒下的历史消息轰炸）。但那个设计有个致命副作用：
 * 开关打开后的**第一次取件把积压静默吃掉**，之后若心潮没产生**新**消息，
 * 用户端**永远没有任何回执** —— 看起来完全像功能坏了。
 *
 * 现在的语义：**首次也只弹最新一条**。既是"我去信箱看过了，这里有东西"的回执，
 * 又不会把十条历史一起炸出来。
 *
 * ## 与 [ProactiveFetchPolicy] 的分工
 * 那个管"**要不要**去取件"（30 分钟节流）；这个管"**取回来之后弹哪几条**"。
 * 两者都是纯函数，理由相同：这类判定最容易被"顺手改一下"改歪，钉在单测里最省事。
 */
object ProactiveFetchReceipt {

    /**
     * @param isFirstFetch 这个人设是不是**第一次**取件（已读水位为空）
     * @param messages 服务端返回的消息（按时间升序，最后一条最新）
     */
    fun pickForNotification(
        isFirstFetch: Boolean,
        messages: List<XinchaoMessage>,
    ): List<XinchaoMessage> = when {
        messages.isEmpty() -> emptyList()
        // 首次：只弹最新一条 —— 有回执、不轰炸
        isFirstFetch -> listOf(messages.last())
        // 非首次：新消息逐条弹（通常 1 条；同刻多条会自然堆叠成一组）
        else -> messages
    }
}
