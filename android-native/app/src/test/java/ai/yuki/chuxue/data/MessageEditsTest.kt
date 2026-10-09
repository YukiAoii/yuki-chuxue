package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史编辑操作的规格（长按消息菜单 / 重新生成）。
 *
 * ## ⚠️ 这里有一条与项目铁律冲突的功能，必须显式记录
 * 本项目的缓存纪律是「**已写入的内容不再改写，新内容只追加**」（§11 三铁律）。
 * 而「删除某条消息」和「重新生成」**本质上就是改写历史** ——
 * 它们会让该会话的缓存前缀从改动处断开，下一轮输入 token 费用上升。
 *
 * 之所以还是做，是因为这是用户明确要求的常规聊天功能（微信也有）。
 * 处理方式与文档 §3.5「改全局前缀要警告用户」一致：**做，但让用户知道代价**。
 * 界面上的提示由 `ChatScreen` 负责，这里只保证编辑操作本身是**纯函数、可测**的。
 */
class MessageEditsTest {

    private fun msg(role: String, text: String) = ChatMessage(role, text)

    private val sample = listOf(
        msg("assistant", "开场白"),
        msg("user", "在吗"),
        msg("assistant", "在的"),
        msg("user", "今天好累"),
        msg("assistant", "那早点休息"),
    )

    private fun texts(list: List<ChatMessage>) = list.map { it.content }

    /* ══════════════ 删除单条 ══════════════ */

    @Test
    fun `删除指定下标的消息`() {
        assertEquals(
            listOf("开场白", "在的", "今天好累", "那早点休息"),
            texts(MessageEdits.deleteAt(sample, 1)),
        )
    }

    @Test
    fun `下标越界时原样返回 —— 不崩，也不误删`() {
        assertEquals(sample, MessageEdits.deleteAt(sample, 99))
        assertEquals(sample, MessageEdits.deleteAt(sample, -1))
    }

    @Test
    fun `删除只影响一条`() {
        assertEquals(sample.size - 1, MessageEdits.deleteAt(sample, 0).size)
    }

    /* ══════════════ 重新生成 ══════════════ */

    @Test
    fun `最后一条是助手回复时可以重新生成`() {
        assertTrue(MessageEdits.canRegenerate(sample))
    }

    @Test
    fun `最后一条是用户消息时不能重新生成 —— 没有可换的回复`() {
        assertFalse(MessageEdits.canRegenerate(sample.dropLast(1)))
    }

    @Test
    fun `空会话不能重新生成`() {
        assertFalse(MessageEdits.canRegenerate(emptyList()))
    }

    @Test
    fun `重新生成去掉尾部那条助手回复`() {
        assertEquals(
            listOf("开场白", "在吗", "在的", "今天好累"),
            texts(MessageEdits.dropLastAssistant(sample)),
        )
    }

    @Test
    fun `重新生成要连带删掉尾部连续的多条助手消息`() {
        val two = sample + msg("assistant", "补一句")
        assertEquals(
            "只删一条会在历史里留下孤儿回复（重生成后又接一条，看起来像她说了两遍）",
            listOf("开场白", "在吗", "在的", "今天好累"),
            texts(MessageEdits.dropLastAssistant(two)),
        )
    }

    @Test
    fun `最后一条是用户消息时重新生成不改动历史`() {
        val trimmed = sample.dropLast(1)
        assertEquals(trimmed, MessageEdits.dropLastAssistant(trimmed))
    }

    @Test
    fun `全是助手消息时会被清空 —— 那是"她没有可以依据的话"的退化情形`() {
        val only = listOf(msg("assistant", "开场白"), msg("assistant", "又一句"))
        assertTrue(MessageEdits.dropLastAssistant(only).isEmpty())
    }

    /* ─── deleteLastExchange：删最新一轮（v0.61.9，用户要求）───
     *
     * 用户要的语义：「删 AI 输出的完整消息、用户发送请求这条消息的内容和
     * 被自动保存的记忆内容也要一并删除」+「只支持最新的一条」。
     * （记忆那部分在 ViewModel —— 它是副作用，这里只钉"消息怎么变"。）
     */

    @Test
    fun `删最后一轮 —— 她的回复与那条用户请求一起走`() {
        val full = sample + msg("assistant", "她答一句")
        assertEquals(
            listOf("开场白", "在吗", "在的"),
            texts(MessageEdits.deleteLastExchange(full)),
        )
    }

    @Test
    fun `删最后一轮只动最新那一对 —— 上一轮原样留着`() {
        // sample 尾部本来还有一条「那早点休息」，再接两轮 = 共三轮
        val full = sample + msg("assistant", "答一") + msg("user", "再问") + msg("assistant", "答二")
        val once = MessageEdits.deleteLastExchange(full)
        assertEquals(
            listOf("开场白", "在吗", "在的", "今天好累", "那早点休息", "答一"),
            texts(once),
        )
        // 再删一次：把「答一」连同它前面那条用户请求一起去掉 —— 不是一次删光
        assertEquals(
            listOf("开场白", "在吗", "在的"),
            texts(MessageEdits.deleteLastExchange(once)),
        )
    }

    @Test
    fun `尾部是用户消息时删最后一轮什么都不做「她还没回」`() {
        // 去掉尾部那条她的回复 → 得到"她还没回"的状态
        val pending = sample.dropLast(1)
        assertEquals(pending, MessageEdits.deleteLastExchange(pending))
    }

    @Test
    fun `删最后一轮的入口判据跟着尾部角色走`() {
        assertTrue(MessageEdits.canDeleteLastExchange(sample))
        assertFalse(MessageEdits.canDeleteLastExchange(sample.dropLast(1)))
        assertFalse(MessageEdits.canDeleteLastExchange(emptyList()))
    }

    /* ─── deleteExchangeAt：长按**任意**一枚她的气泡都能删那一轮（v0.61.14）───
     *
     * 用户原话：「我理想的删除是用户长按分段气泡的**不管哪个气泡**选择删除，
     * 二次确认然后删除用户发送的消息和分段气泡输出的**全部消息**」。
     * （记忆那部分在 ViewModel —— 它是副作用，这里只钉"消息怎么变"。）
     */

    @Test
    fun `删中间那一轮 —— 她的回复与紧邻它前面的请求一起走`() {
        // sample = [A开场白, U在吗, A在的, U今天好累, A那早点休息]
        // 删 index=2（A「在的」）→ 它前面紧邻的 U「在吗」也该走
        assertEquals(
            listOf("开场白", "今天好累", "那早点休息"),
            texts(MessageEdits.deleteExchangeAt(sample, 2)),
        )
    }

    @Test
    fun `删最后一轮 —— 同样带上它前面的请求`() {
        assertEquals(
            listOf("开场白", "在吗", "在的"),
            texts(MessageEdits.deleteExchangeAt(sample, 4)),
        )
    }

    @Test
    fun `前面不是用户消息时只删她自己 —— 不误伤更早的话`() {
        // 崩溃恢复可能留下连续的她的消息，那时不该再往上吞
        val two = listOf(msg("assistant", "开场白"), msg("assistant", "补一句"))
        assertEquals(listOf("开场白"), texts(MessageEdits.deleteExchangeAt(two, 1)))
    }

    @Test
    fun `下标越界时原样返回`() {
        assertEquals(sample, MessageEdits.deleteExchangeAt(sample, 99))
        assertEquals(sample, MessageEdits.deleteExchangeAt(sample, -1))
    }

    /* ─── regeneratePlan：重新生成后时间线上只留**一条**她的回复（v0.61.15）───
     *
     * 用户 2026-10-03 报：「重新生成保留了原消息，重新生成了一轮可切换查看历史的消息 ——
     * 这不是我想要的」。他要的是：**一条**回复 + 气泡下方的左右按钮切历史版本。
     * 下面这几条就是不变量本身。
     */

    @Test
    fun `重新生成后旧回复不留痕 —— 历史里只剩之前的内容`() {
        val plan = MessageEdits.regeneratePlan(sample)!!
        // sample 尾部 = A「那早点休息」，它前面是 U「今天好累」
        assertEquals(listOf("开场白", "在吗", "在的"), texts(plan.trimmed))
        assertTrue(
            "旧回复不能留在历史里（否则界面会多出一条）",
            plan.trimmed.none { it.content == "那早点休息" },
        )
        // 旧内容进 superseded，供气泡下方的左右按钮查看
        assertEquals(listOf("那早点休息"), plan.superseded)
        // 那条用户请求也一并去掉（send 会用 userText 重新追加，避免出现两条一模一样的）
        assertEquals("今天好累", plan.userText)
    }

    @Test
    fun `反复重新生成会累积历史版本 —— 最早的排在最前`() {
        val first = MessageEdits.regeneratePlan(sample)!!
        // 模拟 send 追加之后的形态：trimmed + 新 user + 新 assistant（继承 superseded）
        val after = first.trimmed + msg("user", first.userText) +
            ChatMessage("assistant", "新的一版", superseded = first.superseded)
        val second = MessageEdits.regeneratePlan(after)!!
        assertEquals(listOf("那早点休息", "新的一版"), second.superseded)
        assertEquals("今天好累", second.userText)
        assertTrue(
            "上一版也要从历史里去掉",
            second.trimmed.none { it.content == "新的一版" },
        )
    }

    @Test
    fun `没有可重发的问答时返回 null`() {
        assertNull(MessageEdits.regeneratePlan(emptyList()))
        // 尾部是用户消息（她还没回）→ 没有"可换的那条"
        assertNull(MessageEdits.regeneratePlan(sample.dropLast(1)))
    }

    /* ─── squashAssistantRuns：清理老用户历史里遗留的多条连续回复 ───
     *
     * ## 为什么要它（v0.61.17，用户报「重新生成 4 次就要删 4 次」）
     * v0.61.16 之前 `saveSession` 只 upsert、**从不删除** —— 每次重新生成，
     * 旧回复在内存里没了、**库里却留着**。于是老会话的历史长成
     * `[你, 她(旧), 她(旧), 她(旧), 她(新)]`（**连续多条 assistant**，正常历史不会这样），
     * 删一次只掉最新那对，得删 N 次。这个函数把这种历史**收敛成一条**：
     * 每组连续的她的回复只留**最后一条**（当前版本），前面的按时间顺序并入 `superseded`
     * （版本切换按钮照旧能翻到它们）。
     */

    @Test
    fun `连续多条回复合并成一条 —— 前面的按顺序并入 superseded`() {
        val dirty = listOf(
            msg("user", "在吗"),
            msg("assistant", "第1版"),
            msg("assistant", "第2版"),
            msg("assistant", "第3版"),
        )
        val fixed = MessageEdits.squashAssistantRuns(dirty)
        assertEquals(listOf("在吗", "第3版"), texts(fixed))
        assertEquals(listOf("第1版", "第2版"), fixed[1].superseded)
    }

    @Test
    fun `本来就干净的历史一个字不动`() {
        assertEquals(sample, MessageEdits.squashAssistantRuns(sample))
    }

    @Test
    fun `分段之间各自合并 —— 不跨过用户消息`() {
        val dirty = listOf(
            msg("assistant", "A1"), msg("assistant", "A2"),
            msg("user", "问"),
            msg("assistant", "B1"), msg("assistant", "B2"),
        )
        val fixed = MessageEdits.squashAssistantRuns(dirty)
        assertEquals(listOf("A2", "问", "B2"), texts(fixed))
    }

    @Test
    fun `幂等 —— 清理过的历史再清一次不变（启动时每次都跑）`() {
        val dirty = listOf(msg("assistant", "1"), msg("assistant", "2"))
        val once = MessageEdits.squashAssistantRuns(dirty)
        assertEquals(once, MessageEdits.squashAssistantRuns(once))
    }

    @Test
    fun `原本带着历史版本的那条被合并时，自己的旧版本不丢`() {
        val dirty = listOf(
            msg("assistant", "很早的"),
            ChatMessage("assistant", "当前", superseded = listOf("更早的一版")),
        )
        val fixed = MessageEdits.squashAssistantRuns(dirty)
        assertEquals(listOf("很早的", "更早的一版"), fixed[0].superseded)
    }

    /* ─── 删除时连带清掉"同一轮的历史残留"（v0.61.18，用户要求）───
     *
     * ## 用户报的
     * 「重新生成的消息不是可以切换查看吗，当我删除的时候，它只会删除重新生成的内容，
     * 然后前一次生成的内容还在（下面的切换消失了），需要再删一次。
     * **我的理想状态是连带前一次生成内容一起被删除**」。
     *
     * ## 现场是什么
     * v0.61.16 之前 `saveSession` 不删，一次重新生成在库里留下的是**重复的问答对**：
     * `[你(旧), 她(旧), 你(重发), 她(新)]` —— 两条 user **内容相同**。
     * 它**不是**"同一条消息的版本"（那种才带 superseded / 切换按钮），
     * 而是一条独立残留消息，所以删一次只掉最新那对。
     */

    @Test
    fun `删一轮时连带清掉前面同一轮的历史残留`() {
        val dirty = listOf(
            msg("assistant", "开场白"),
            msg("user", "在吗"),
            msg("assistant", "在的"),
            msg("user", "今天好累"), // ← 旧的一轮（残留）
            msg("assistant", "那早点休息"), // ← 旧回复（残留）
            msg("user", "今天好累"), // ← 重发，内容与上面那条相同
            msg("assistant", "嗯，去睡吧"), // ← 当前
        )
        val fixed = MessageEdits.deleteExchangeAt(dirty, 6)
        assertEquals(listOf("开场白", "在吗", "在的"), texts(fixed))
    }

    @Test
    fun `连删多轮时一并清干净 —— 重新生成过 N 次也只需删一次`() {
        val dirty = listOf(
            msg("user", "问"), // ← 残留 1
            msg("assistant", "答1"), // ← 残留 1
            msg("user", "问"), // ← 残留 2
            msg("assistant", "答2"), // ← 残留 2
            msg("user", "问"), // ← 当前
            msg("assistant", "答3"), // ← 当前
        )
        assertEquals(emptyList<ChatMessage>(), MessageEdits.deleteExchangeAt(dirty, 5))
    }

    @Test
    fun `内容不同的相邻轮次不会被误删 —— 那是用户真的聊了两轮`() {
        val clean = listOf(
            msg("user", "在吗"),
            msg("assistant", "在的"),
            msg("user", "今天好累"),
            msg("assistant", "那早点休息"),
        )
        assertEquals(
            listOf("在吗", "在的"),
            texts(MessageEdits.deleteExchangeAt(clean, 3)),
        )
    }

    @Test
    fun `残留对的 user 带着不同附录时也要认出来 —— 真实数据的形态`() {
        // ⚠️ 这条是"没删干净"的真正原因（用户 2026-10-03 报）：
        //    历史里那条 user 的 content 是「**附录 + 正文**」（PromptEngine 把 <appendix> 拼前面），
        //    而重新生成重发的是 `stripAppendix(...)` 后的文本、**新一轮的附录会被重新注入** ——
        //    两轮的附录（记忆/表情提示）不一样，直接比原文**永远不相等**，
        //    于是"往前清理"从来没触发过，用户还得删好几次。
        //    ⇒ 判据必须**剥掉附录后再比**。
        val dirty = listOf(
            ChatMessage("user", "<appendix>旧记忆</appendix>\n今天好累"),
            ChatMessage("assistant", "那早点休息"),
            ChatMessage("user", "<appendix>新记忆，比上一轮多了一条</appendix>\n今天好累"),
            ChatMessage("assistant", "嗯，去睡吧"),
        )
        assertEquals(
            emptyList<ChatMessage>(),
            MessageEdits.deleteExchangeAt(dirty, 3),
        )
    }

    @Test
    fun `剥掉附录后内容不同就不算同一轮 —— 不能因为都带附录就误判`() {
        val clean = listOf(
            ChatMessage("user", "<appendix>附录</appendix>\n在吗"),
            ChatMessage("assistant", "在的"),
            ChatMessage("user", "<appendix>附录</appendix>\n今天好累"),
            ChatMessage("assistant", "那早点休息"),
        )
        assertEquals(
            listOf("<appendix>附录</appendix>\n在吗", "在的"),
            texts(MessageEdits.deleteExchangeAt(clean, 3)),
        )
    }

    /* ══════════════ 主动消息（v0.61.54）══════════════ */

    private fun proMsg(text: String) = ChatMessage(
        role = "assistant",
        content = text,
        sendMode = SEND_MODE_PROACTIVE,
    )

    @Test
    fun `删最后一轮时跳过尾部的主动消息 —— 主动消息不该被删、也不该当配对对象`() {
        val history = listOf(
            msg("user", "在吗"),
            msg("assistant", "在的"),
            proMsg("我想你了"),
        )
        // 删的是「在吗 + 在的」这一轮问答；**主动消息留在历史里**（它是 Ta 说过的话）
        assertEquals(
            listOf("我想你了"),
            texts(MessageEdits.deleteLastExchange(history)),
        )
    }

    @Test
    fun `全是主动消息时删最后一轮什么都不做 —— 不能清空历史`() {
        val history = listOf(proMsg("我想你了"), proMsg("你在干嘛"))
        assertEquals(history, MessageEdits.deleteLastExchange(history))
    }

    @Test
    fun `尾部是主动消息、其下是用户消息时不动 user —— 她还没回，没有可删的问答对`() {
        val history = listOf(
            msg("assistant", "在的"),
            msg("user", "今天好累"),
            proMsg("我想你了"),
        )
        // `[assistant, user, pro]`：尾部剥掉 pro 后是 user → 没有"她的回复"可删
        assertEquals(history, MessageEdits.deleteLastExchange(history))
    }

    @Test
    fun `主动消息在尾部时 canDeleteLastExchange 为 false —— 没有可删的问答对`() {
        // ⚠️ 尾部是主动消息 → 不算"她的回复"，不可删最后一轮
        assertFalse(MessageEdits.canDeleteLastExchange(listOf(proMsg("我想你了"))))
    }

    @Test
    fun `重新生成跳过尾部的主动消息 —— 目标是最新一条真回复`() {
        val history = listOf(
            msg("user", "在吗"),
            msg("assistant", "在的"),
            proMsg("我想你了"),
        )
        val plan = MessageEdits.regeneratePlan(history)
        // 重生成的是「在吗 + 在的」这一轮：两者都被摘掉（重发用 request="在吗"），
        // **主动消息留在历史里**（它不是那一轮的一部分）。
        assertTrue(plan != null)
        assertEquals("在吗", plan!!.userText)
        assertEquals(listOf("我想你了"), texts(plan.trimmed))
    }

    @Test
    fun `长按主动消息不删 —— 它不是某一轮问答`() {
        val history = listOf(
            msg("user", "在吗"),
            msg("assistant", "在的"),
            proMsg("我想你了"),
        )
        assertEquals(history, MessageEdits.deleteExchangeAt(history, 2))
    }

    /* ══════════ isProactive 的直接契约（v0.61.57）══════════
       ⚠️ 渲染层（`ChatScreen` 的 canRegenerate / canDelete）现在**直接调它** ——
       用户报的「AI 主动发的消息会被计为最近一条消息的重新生成的内容」就是
       那两个判据漏了它。这里把它的语义单独钉住，别让渲染层依赖一个没测试的函数。 */

    @Test
    fun `isProactive：主动消息为真`() {
        val history = listOf(msg("user", "在吗"), msg("assistant", "在的"), proMsg("我想你了"))
        assertTrue(MessageEdits.isProactive(history, 2))
    }

    @Test
    fun `isProactive：普通回复为假 —— 不能把"她回我的话"也当成主动消息`() {
        val history = listOf(msg("user", "在吗"), msg("assistant", "在的"))
        assertFalse(MessageEdits.isProactive(history, 1))
    }

    @Test
    fun `isProactive：用户消息恒为假 —— 主动消息只可能是 assistant`() {
        val history = listOf(msg("user", "在吗"))
        assertFalse(MessageEdits.isProactive(history, 0))
    }

    @Test
    fun `isProactive：下标越界为假 —— 不崩`() {
        val history = listOf(msg("user", "在吗"))
        assertFalse(MessageEdits.isProactive(history, 5))
        assertFalse(MessageEdits.isProactive(emptyList(), 0))
    }

    @Test
    fun `渲染层判据：尾部是主动消息时，它不该被当成"最新一条回复"`() {
        // 这条复现用户报的场景：Ta 主动发消息 → 它在尾部 →
        // 渲染层若只看 `index == lastIndex`，长按它就会给出「重新生成」，
        // 而点下去重生成的是**上一条用户消息的回复**（用户看到的正是
        // "主动消息变成了重新生成的内容"）。
        val history = listOf(msg("user", "在吗"), msg("assistant", "在的"), proMsg("我想你了"))
        val lastIndex = history.lastIndex
        // 渲染层的判据（ChatScreen 里那两行）现在长这样：
        val canRegenerate = history[lastIndex].role == "assistant" &&
            !MessageEdits.isProactive(history, lastIndex) &&
            lastIndex == history.lastIndex
        assertFalse("尾部主动消息不该可重新生成", canRegenerate)
    }
}
