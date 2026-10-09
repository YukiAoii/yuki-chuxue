package ai.yuki.chuxue.data.memory

import ai.yuki.chuxue.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台记忆提取的纯逻辑规格（开发文档 §8.3）。
 *
 * 这里钉住三件最容易出错、且**完全不依赖网络**的事：
 * 1. 送给模型的 Prompt 长什么样（隔离声明在不在、上下文有没有被限长）；
 * 2. 模型返回的东西能不能被容错地解析（它经常会裹 Markdown 围栏或加解释文字）；
 * 3. 模型给的词怎么映射到本项目的词（英文分类 → 界面中文；1–5 → 项目的 0–10）。
 *
 * 这三层都是纯函数，所以能在无设备、无 API Key 的环境里被完整钉死 ——
 * 而它们恰好也是"提取"这条链路里唯一能被钉死的部分。
 */
class MemoryExtractionTest {

    private fun history(n: Int): List<ChatMessage> =
        (0 until n).map { i ->
            if (i % 2 == 0) ChatMessage("user", "第 $i 句：我喜欢在雨天听钢琴")
            else ChatMessage("assistant", "第 $i 句：嗯，我记下了。")
        }

    /* ══════════════ Prompt 构造 ══════════════ */

    @Test
    fun `提取 Prompt 声明了它不是聊天角色`() {
        val p = MemoryExtraction.buildPrompt(history(4))
        assertTrue("必须戴上工作手套：明确它只是记录员", p.contains("记忆提取器"))
        assertTrue("要明确禁止感情色彩，否则会写成角色台词", p.contains("不要有任何感情色彩"))
    }

    @Test
    fun `提取 Prompt 含 JSON 格式要求与 scope 规则`() {
        val p = MemoryExtraction.buildPrompt(history(4))
        assertTrue("必须给出可解析的格式", p.contains("JSON"))
        assertTrue("要说明 persona / session 的区分依据", p.contains("persona"))
        assertTrue(p.contains("session"))
        assertTrue("必须允许「没什么可记的」这个答案", p.contains("[]"))
    }

    @Test
    fun `提取 Prompt 只带最近若干条消息`() {
        val p = MemoryExtraction.buildPrompt(history(60))
        assertTrue("最早的那句不该出现在提取上下文里", !p.contains("第 0 句"))
        assertTrue("最近的一句要在", p.contains("第 59 句"))
        // 断言"限了长"，而不是断言具体条数 —— 条数改动不该让测试变红
        val userLines = p.lines().count { it.contains("第 ") && it.contains("句：") }
        assertTrue("带进去的对话行数应当被限制，实际 $userLines", userLines <= MemoryExtraction.HISTORY_LIMIT)
    }

    @Test
    fun `超长单条消息被截断`() {
        val long = ChatMessage("user", "啊".repeat(5_000))
        val p = MemoryExtraction.buildPrompt(listOf(long))
        assertTrue(
            "单条 5000 字全塞进去，提取请求会白烧 token",
            !p.contains("啊".repeat(MemoryExtraction.MAX_MESSAGE_CHARS + 1)),
        )
    }

    @Test
    fun `空历史仍能构造出合法 Prompt`() {
        val p = MemoryExtraction.buildPrompt(emptyList())
        assertTrue(p.isNotBlank())
        assertTrue(p.contains("JSON"))
    }

    /* ══════════════ 响应解析（容错是这里的重点）══════════════ */

    @Test
    fun `解析标准 JSON 数组`() {
        val raw = """[{"scope":"persona","category":"preference","content":"用户喜欢猫","importance":4}]"""
        val out = MemoryExtraction.parse(raw)
        assertEquals(1, out.size)
        assertEquals("persona", out[0].scope)
        assertEquals("preference", out[0].category)
        assertEquals("用户喜欢猫", out[0].content)
        assertEquals(4, out[0].importance)
    }

    @Test
    fun `剥掉 Markdown 围栏`() {
        val raw = "```json\n[{\"scope\":\"persona\",\"category\":\"fact\",\"content\":\"用户是程序员\",\"importance\":3}]\n```"
        assertEquals(1, MemoryExtraction.parse(raw).size)
    }

    @Test
    fun `前后夹带解释文字也能解析`() {
        val raw = "好的，我提取到以下记忆：\n[{\"scope\":\"session\",\"category\":\"event\",\"content\":\"我们去了海边\",\"importance\":3}]\n希望有帮助。"
        val out = MemoryExtraction.parse(raw)
        assertEquals(1, out.size)
        assertEquals("我们去了海边", out[0].content)
    }

    @Test
    fun `非法 JSON 返回空表而不是抛异常`() {
        assertEquals(0, MemoryExtraction.parse("模型今天不想工作").size)
        assertEquals(0, MemoryExtraction.parse("").size)
        assertEquals(0, MemoryExtraction.parse("[{坏掉的").size)
    }

    @Test
    fun `空数组返回空表`() {
        assertEquals(0, MemoryExtraction.parse("[]").size)
        assertEquals(0, MemoryExtraction.parse("```json\n[]\n```").size)
    }

    @Test
    fun `缺少 content 的条目被跳过`() {
        val raw = """[{"scope":"persona","category":"fact","content":"  ","importance":3},
                      {"scope":"persona","category":"fact","content":"用户是程序员","importance":3}]"""
        val out = MemoryExtraction.parse(raw)
        assertEquals("空内容不该变成一条记忆", 1, out.size)
        assertEquals("用户是程序员", out[0].content)
    }

    @Test
    fun `importance 缺失时给默认值而不是崩掉`() {
        val raw = """[{"scope":"persona","category":"fact","content":"用户是程序员"}]"""
        val out = MemoryExtraction.parse(raw)
        assertEquals(1, out.size)
        assertTrue("默认值必须落在模型约定的 1-5 内", out[0].importance in 1..5)
    }

    /* ══════════════ 词表映射 ══════════════ */

    @Test
    fun `分类英文映射到界面中文`() {
        assertEquals("喜好", MemoryExtraction.categoryToDisplay("preference"))
        assertEquals("经历", MemoryExtraction.categoryToDisplay("event"))
        assertEquals("约定", MemoryExtraction.categoryToDisplay("relationship"))
        assertEquals("其他", MemoryExtraction.categoryToDisplay("fact"))
    }

    @Test
    fun `分类映射容忍大小写与空白`() {
        assertEquals("喜好", MemoryExtraction.categoryToDisplay(" Preference "))
    }

    @Test
    fun `未知分类兜底为其他`() {
        assertEquals("其他", MemoryExtraction.categoryToDisplay("something_else"))
        assertEquals("其他", MemoryExtraction.categoryToDisplay(""))
    }

    @Test
    fun `重要性 1-5 映射到项目的 0-10`() {
        assertEquals(2, MemoryExtraction.importanceToProject(1))
        assertEquals(10, MemoryExtraction.importanceToProject(5))
        // 不能把 5 映射成 5 —— 那会让"最重要"只到项目量程的一半
        assertNotEquals(5, MemoryExtraction.importanceToProject(5))
    }

    @Test
    fun `重要性越界被夹紧到量程内`() {
        assertEquals(2, MemoryExtraction.importanceToProject(0))
        assertEquals(2, MemoryExtraction.importanceToProject(-3))
        assertEquals(10, MemoryExtraction.importanceToProject(99))
    }

    @Test
    fun `scope 未知时兜底为 persona`() {
        assertEquals("persona", MemoryExtraction.normalizeScope("persona"))
        assertEquals("session", MemoryExtraction.normalizeScope("session"))
        assertEquals("persona", MemoryExtraction.normalizeScope("gibberish"))
        assertEquals("persona", MemoryExtraction.normalizeScope(null))
    }

    /* ══════════════ 提取守卫（成本护栏）══════════════ */

    /** 全部条件都满足的基准输入 —— 每条测试只改一个变量，差异才可归因。 */
    private fun shouldRun(
        enabled: Boolean = true,
        hasApiKey: Boolean = true,
        frontendStreaming: Boolean = false,
        now: Long = 1_000_000L,
        lastExtractAt: Long = 0L,
        latestSessionUpdatedAt: Long = 500L,
        lastExtractedSessionAt: Long = 0L,
        minIntervalMs: Long = MemoryExtractionThrottle.MIN_INTERVAL_MS,
    ): Boolean = MemoryExtractionThrottle.shouldRun(
        enabled = enabled,
        hasApiKey = hasApiKey,
        frontendStreaming = frontendStreaming,
        now = now,
        lastExtractAt = lastExtractAt,
        latestSessionUpdatedAt = latestSessionUpdatedAt,
        lastExtractedSessionAt = lastExtractedSessionAt,
        minIntervalMs = minIntervalMs,
    )

    @Test
    fun `条件都满足时应当提取`() {
        assertTrue("基准场景必须为真，否则其余断言无从对比", shouldRun())
    }

    @Test
    fun `开关关闭时不提取`() {
        assertFalse(shouldRun(enabled = false))
    }

    @Test
    fun `没填 API Key 时不提取`() {
        assertFalse(shouldRun(hasApiKey = false))
    }

    @Test
    fun `前台正在流式时不提取 —— 绝不与用户的对话抢配额`() {
        assertFalse(
            "前后台同 Key，同时发请求可能触发限流并让用户的对话报错",
            shouldRun(frontendStreaming = true),
        )
    }

    @Test
    fun `距上次提取不足间隔时不提取`() {
        assertFalse(shouldRun(now = 1_000_000L, lastExtractAt = 1_000_000L - 60_000L))
    }

    @Test
    fun `间隔正好达到时可以提取`() {
        val interval = MemoryExtractionThrottle.MIN_INTERVAL_MS
        assertTrue(shouldRun(now = 10_000_000L, lastExtractAt = 10_000_000L - interval))
    }

    @Test
    fun `没有新消息时不提取`() {
        assertFalse(
            "会话没更新过就再提取一次，纯属重复花钱",
            shouldRun(latestSessionUpdatedAt = 500L, lastExtractedSessionAt = 500L),
        )
    }

    @Test
    fun `有新消息时可以提取`() {
        assertTrue(shouldRun(latestSessionUpdatedAt = 900L, lastExtractedSessionAt = 500L))
    }
}
