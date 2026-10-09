package ai.yuki.chuxue.data

/**
 * 搜索范围。三种口径对应界面上的三个过滤芯片。
 *
 * 用 [MINE] / [HERS] 而不是 `USER` / `ASSISTANT`：这是**用户体验层**的概念
 * （"我说的" / "她说的"），而 `user` / `assistant` 是协议层的角色名。
 * 两者恰好一一对应，但换个说法就不成立了 —— 分开命名，改协议不影响文案。
 */
enum class SearchScope {
    /** 全部消息 */
    ALL,

    /** 只看我说的 */
    MINE,

    /** 只看她说的 */
    HERS,
    ;

    fun accepts(role: String): Boolean = when (this) {
        ALL -> true
        MINE -> role == "user"
        HERS -> role == "assistant"
    }
}

/**
 * 一条命中。**下标与区间都是界面直接可用的最终形态**，UI 不必再换算。
 *
 * @param index   这条消息在 `Session.messages` 里的下标（跳转定位就靠它）
 * @param role    协议角色名（`user` / `assistant`），界面据此决定左右与头像
 * @param snippet 单行展示片段：**已剥掉后台附录、已把换行压成空格**，可能带首尾省略号
 * @param ranges  命中区间，坐标位于 [snippet] 之内 —— 直接拿它做高亮
 */
data class SearchHit(
    val index: Int,
    val role: String,
    val snippet: String,
    val ranges: List<IntRange>,
)

/**
 * 会话内搜索（对话设置页 →「查找聊天记录」）。
 *
 * ## 为什么搜索要在「显示文本」上做，而不是原始 content
 * 历史里的用户消息带着 `PromptEngine.plan()` 拼进去的
 * `<appendix><memories>…</memories></appendix>`（**给模型看的**记忆）。
 * 直接在原始 content 上搜会有两个后果：
 * 1. 用户会搜到一条自己从没说过的话 —— 那句话其实是她的记忆；
 * 2. 同一个词在正文里本只出现一次，却因为附录里也有，高亮出两处。
 *
 * 所以这里与气泡显示共用同一套剥离逻辑（[TranscriptText.stripAppendix]）：
 * **搜到的范围 === 用户眼睛能看到的范围**。数据一个字不动（附录仍留在历史里，
 * 那是缓存一致性的前提）。
 *
 * ## 为什么必须抽成纯函数
 * 本机没有模拟器、没有设备 —— 界面上的东西只能靠用户看真机。凡是能挪到
 * JVM 上验的（"搜得对不对""高亮位置歪不歪"）就都挪过来，别留给真机。
 */
object MessageSearch {

    /**
     * 命中点前后各留多少字。
     *
     * 18 是个观感数字，不是技术常数：够让用户看出上下文，又不至于让结果行换行。
     * 改它不影响任何断言 —— 测试用的是「区间能在片段里反查出原词」这个不变量，
     * 而不是某个具体偏移量。
     */
    private const val RADIUS = 18

    /**
     * 检索一段对话。
     *
     * 关键词 [keyword] **两端空白会被去掉**；去完为空则返回空表 —— 空关键词
     * 返回全部消息是"看起来合理但没人想要"的行为（一打开搜索页就刷出几百条）。
     */
    fun query(
        messages: List<ChatMessage>,
        keyword: String,
        scope: SearchScope = SearchScope.ALL,
    ): List<SearchHit> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()

        return messages.mapIndexedNotNull { index, msg ->
            if (!scope.accepts(msg.role)) return@mapIndexedNotNull null

            val text = flatten(msg.content)
            val ranges = matchRanges(text, kw)
            if (ranges.isEmpty()) return@mapIndexedNotNull null

            val (snippet, shifted) = snippetAround(text, ranges)
            SearchHit(index = index, role = msg.role, snippet = snippet, ranges = shifted)
        }
    }

    /**
     * 展示用文本：剥掉后台附录，并把换行压成空格。
     *
     * 换行替换是**等长替换**（`\n` → 空格），因此下标不漂移 ——
     * 这正是"先压平再匹配"能成立的原因。若哪天改成"折叠连续空白"（长度会变），
     * 下面所有区间都会错位，必须先匹配再压平。
     */
    private fun flatten(raw: String): String =
        TranscriptText.stripAppendix(raw).replace('\n', ' ')

    /**
     * 找出全部**不重叠**的出现位置。
     *
     * 不重叠是刻意的：搜「哈哈」时在「哈哈哈」里报出 `0..1` 和 `1..2` 两个重叠区间，
     * 高亮画出来会叠色、看着像渲染坏了。
     */
    private fun matchRanges(text: String, keyword: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - keyword.length) {
            val at = text.indexOf(keyword, startIndex = from, ignoreCase = true)
            if (at < 0) break
            out += at until (at + keyword.length)
            from = at + keyword.length
        }
        return out
    }

    /**
     * 以第一处命中为中心截一段用于展示，并把区间**平移**到片段自己的坐标系。
     *
     * 这是最容易写错的一步：片段带上省略号之后，所有下标都偏移了
     * （前缀省略号占 1 个字符）。偏移量必须与 `head` 的**实际长度**挂钩，
     * 而不是"有省略号就减 1" —— 一旦将来前缀换个符号，写法就得跟着改。
     */
    private fun snippetAround(
        text: String,
        ranges: List<IntRange>,
    ): Pair<String, List<IntRange>> {
        val first = ranges.first()
        val start = (first.first - RADIUS).coerceAtLeast(0)
        val end = (first.last + 1 + RADIUS).coerceAtMost(text.length)

        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        val body = text.substring(start, end)

        // 原文下标 + shift = 片段下标
        val shift = head.length - start
        val kept = ranges
            .filter { it.first >= start && it.last < end }
            .map { (it.first + shift)..(it.last + shift) }

        return (head + body + tail) to kept
    }
}
