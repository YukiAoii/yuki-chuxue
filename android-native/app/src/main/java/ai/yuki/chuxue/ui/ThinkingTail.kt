package ai.yuki.chuxue.ui

/**
 * 思考气泡「只留尾部」的判定（纯函数，可测）。
 *
 * ## 为什么需要它
 * 用户的原始抱怨是「思考动辄几百行，全量铺开会把聊天页撑爆」；但**只截不留痕**
 * 又会让人以为"上面那些被吞了"。两条要求合起来就一句话：
 * **取尾部 N 行，同时把"被省掉几行"数出来**，由界面在旁边说明。
 *
 * ## 它只管渲染
 * ⚠️ 结果只上屏。思考内容本身在 Yuki 的约定里是"只供回看、不进请求体"的
 *（见 `ChatMessage.reasoning` 的注释），这个函数同样不碰任何消息体 ——
 * 所以截断多少行都不会动冻结前缀，缓存不受影响。
 */
object ThinkingTail {

    /**
     * 流式中最多显示的逻辑行数（用户要求：只看最近几行）。
     *
     * 3 行是权衡：够看出"她此刻在想什么"，又不至于把聊天页撑开；
     * 想看全文等结束后点开（那时走非流式分支、显示全量）。
     */
    const val LIVE_LINES = 3

    /**
     * 截断结果。
     *
     * @param text 要显示的正文（流式中 = 尾部若干行；不足上限时 = 原文）
     * @param omitted 被省掉的逻辑行数；`> 0` 时界面应显示「… 上方省略 N 行」
     * @param totalLines 截断前的逻辑行数（尾部换行不算一行）
     */
    data class Tail(val text: String, val omitted: Int, val totalLines: Int)

    /**
     * 取 [reasoning] 的尾部 [liveLines] 行。
     *
     * ⚠️ 先 `trimEnd()` 再切行：流式增量常以换行收尾，不修掉会把尾部那个空串
     * 算成"多出来的一行"，`omitted` 就会凭空 +1。
     */
    fun of(reasoning: String, liveLines: Int = LIVE_LINES): Tail {
        val trimmed = reasoning.trimEnd()
        val all = trimmed.lines()
        if (all.size <= liveLines) return Tail(trimmed, 0, all.size)
        return Tail(
            text = all.takeLast(liveLines).joinToString("\n"),
            omitted = all.size - liveLines,
            totalLines = all.size,
        )
    }

    /**
     * 思考内容的**有效行数**（忽略空行）—— 给"过去式"标题行用的 `· N 行`。
     *
     * 忽略空行是因为模型常拿空行分段：照字面数会把"想了 20 行"抬成 40 行，
     * 那个数字反而失去参考价值。
     */
    fun lineCount(reasoning: String): Int = reasoning.lines().count { it.isNotBlank() }
}
