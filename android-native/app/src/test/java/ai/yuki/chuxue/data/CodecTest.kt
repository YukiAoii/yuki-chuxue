package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化往返测试。
 *
 * ══ 为什么这组测试是第一优先 ══
 * 用户报告：「应用关闭之后对话自动全部丢失」。
 * 最可能的原因是解码失败被静默吞掉（旧代码 `runCatching{...}.getOrElse { emptyList() }`），
 * 于是应用看到「没有会话」而不是「解析出错」。
 *
 * 这组测试专门打这个假设：
 *   · 正常内容能往返 → 排除编解码本身的问题
 *   · **含换行/引号/emoji 的内容能往返** → 这类字符最容易让解析出错
 *   · 结构损坏时**抛异常**而非返回空 → 钉死「不许静默吞掉」
 */
class CodecTest {

    /* ══════════════ 会话往返 ══════════════ */

    @Test
    fun `会话往返：基本内容不变`() {
        val sessions = listOf(
            Session(
                id = "s1",
                personaId = "p1",
                title = "和初雪的对话",
                messages = listOf(
                    ChatMessage("user", "你好"),
                    ChatMessage("assistant", "你好呀"),
                ),
                createdAt = 1000L,
                updatedAt = 2000L,
                totalHit = 800,
                totalMiss = 200,
            ),
        )
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(sessions, back)
    }

    @Test
    fun `会话往返：含换行、引号、反斜杠的消息不变`() {
        val tricky = "第一行\n第二行\t制表\r\n\"引号\"和\\反斜杠"
        val sessions = listOf(
            Session(
                id = "s1",
                messages = listOf(ChatMessage("user", tricky)),
            ),
        )
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(
            "含特殊字符的消息往返后必须逐字节一致 —— 否则下一轮前缀会错位",
            tricky,
            back[0].messages[0].content,
        )
    }

    @Test
    fun `会话往返：emoji 与中文不变`() {
        val text = "今天好开心🎉 我们一起看雪❄️ 好吗"
        val sessions = listOf(Session(id = "s1", messages = listOf(ChatMessage("assistant", text))))
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(text, back[0].messages[0].content)
    }

    @Test
    fun `会话往返：多会话顺序不变`() {
        val sessions = (1..5).map {
            Session(id = "s$it", title = "会话$it", messages = listOf(ChatMessage("user", "msg$it")))
        }
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(5, back.size)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), back.map { it.id })
    }

    @Test
    fun `会话往返：长对话（100 条）不丢消息`() {
        val msgs = (1..100).map {
            ChatMessage(if (it % 2 == 0) "assistant" else "user", "第 $it 条消息")
        }
        val sessions = listOf(Session(id = "s1", messages = msgs))
        val back = SessionCodec.decode(SessionCodec.encode(sessions))
        assertEquals(100, back[0].messages.size)
        assertEquals(msgs, back[0].messages)
    }

    @Test
    fun `空列表往返为空列表`() {
        assertEquals(emptyList<Session>(), SessionCodec.decode(SessionCodec.encode(emptyList())))
    }

    @Test
    fun `空白输入解码为空列表（不是异常）`() {
        assertEquals(emptyList<Session>(), SessionCodec.decode(""))
        assertEquals(emptyList<Session>(), SessionCodec.decode("   "))
    }

    /* ══════════════ 出错必须暴露，不能静默 ══════════════ */

    @Test
    fun `损坏的 JSON 抛异常，而不是静默返回空列表`() {
        val e = runCatching { SessionCodec.decode("{这不是合法 JSON") }.exceptionOrNull()
        assertTrue("损坏数据必须抛异常 —— 静默返回空正是「对话全丢」被掩盖的原因", e != null)
    }

    @Test
    fun `会话缺少 id 时抛异常，而不是给个默认值往下漂`() {
        val bad = """[{"title":"没有id的会话"}]"""
        val e = runCatching { SessionCodec.decode(bad) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("id"))
    }

    @Test
    fun `消息缺少 content 时抛异常`() {
        val bad = """[{"id":"s1","messages":[{"role":"user"}]}]"""
        val e = runCatching { SessionCodec.decode(bad) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    /* ══════════════ 人设往返 ══════════════ */

    @Test
    fun `人设往返：全部字段不变`() {
        val personas = listOf(
            Persona(
                id = "p1",
                userNickname = "小明",
                userGender = "男",
                personality = "温柔、体贴",
                customPrompt = "角色名称：小夏\n年龄：23\n职业：咖啡师",
                avatarPath = "/data/avatars/p1.jpg",
                greeting = "（抬头看见你）{user_nickname}，你来了。",
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
        assertEquals(personas, PersonaCodec.decode(PersonaCodec.encode(personas)))
    }

    @Test
    fun `人设往返：可空字段为 null 时保持 null`() {
        val p = Persona(id = "p1", userNickname = "A", userGender = "女")
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(null, back[0].personality)
        assertEquals(null, back[0].avatarPath)
        assertEquals(null, back[0].greeting)
    }

    @Test
    fun `人设往返：含换行的自由编辑框不变`() {
        val prompt = "角色名称：初雪\n\n性格：安静\n爱好：看雪\n\n世界观：冬天的城市"
        val p = Persona(id = "p1", customPrompt = prompt)
        val back = PersonaCodec.decode(PersonaCodec.encode(listOf(p)))
        assertEquals(prompt, back[0].customPrompt)
    }

    /* ══════════════ 模型辅助属性 ══════════════ */

    @Test
    fun `人设显示名从自由框里提取角色名称`() {
        val p = Persona(customPrompt = "角色名称：初雪\n年龄：20")
        assertEquals("初雪", p.displayName)
    }

    @Test
    fun `人设显示名在提取不到时退回首行 —— 新版行为`() {
        // ⚠️ 行为在 v0.61.10 **有意**变更（用户要求「角色名称独立成一项」「考虑老用户适配」）：
        //    抓不到「角色名称：X」时不再直接落到"未命名角色"，而是退到**设定首行** ——
        //    这与 `ChatViewModel.displayNameOf` 的老口径一致（同一个人设不该在两处显示两个名字）。
        //    完整回退链（roleName → 「角色名称：X」→ 首行 → Ta）的规格见 `PersonaDisplayNameTest`。
        assertEquals("随便写点什么", Persona(customPrompt = "随便写点什么").displayName)
        // 真正什么都没有时才给「Ta」
        assertEquals("Ta", Persona().displayName)
    }

    @Test
    fun `人设可用性判定：必填三项齐全才可用`() {
        assertTrue(
            Persona(userNickname = "A", userGender = "男", customPrompt = "角色名称：B").isUsable,
        )
        // ⚠️ v0.61.21 翻转：原来这里断言「缺昵称不可用」。用户已把那
        //    「Ta 怎么称呼你」一栏从编辑页移除（新建人设常年为空），
        //    再要求它等于让**所有人设**都显示"没填完"。契约跟着字段的移除一起改，
        //    而不是提前抢跑（见 Models.isUsable 的注释）。
        assertTrue("缺昵称仍然可用（那一栏已经不让人填了）", Persona(userGender = "男", customPrompt = "x").isUsable)
        // ⚠️ 2026-10-06 再翻转：「你的性别」也移除了（用户要求写进用户人设正文），
        //    理由与昵称完全相同 —— 新建人设的 userGender 会常年为空。
        assertTrue("缺性别仍可用（那一栏也已经不让填了）", Persona(userNickname = "A", customPrompt = "x").isUsable)
        assertTrue("缺人设内容不可用", !Persona(userNickname = "A", userGender = "男").isUsable)
    }

    /* ══════════════ 会话缓存摘要 ══════════════ */

    @Test
    fun `会话缓存摘要：无请求时显示占位`() {
        assertEquals("尚无请求", Session().cacheSummary)
    }

    @Test
    fun `会话缓存摘要：有数据时显示百分比`() {
        assertEquals("命中 75%", Session(totalHit = 750, totalMiss = 250).cacheSummary)
    }

    @Test
    fun `会话预览取最后一条消息并压掉换行`() {
        val s = Session(messages = listOf(ChatMessage("user", "第一行\n第二行")))
        assertNotEquals("", s.preview)
        assertTrue("预览不应含换行", !s.preview.contains('\n'))
    }

    /* ─── 列表预览的「形态优先」（v0.61.14，用户要求）───
     *
     * 用户原话：「AI 输出的表情包应该在退出去的消息列表显示［动画表情］，
     * 用户发送的图片应该显示［图片］」。
     * ⚠️ 关键是**先看最后一条的形态**：它是图/表情包时 content 可能是空的，
     *    旧逻辑 `lastOrNull { content.isNotBlank() }` 会跳过它去显示**更早**的文字 ——
     *    列表于是显示"上一条我说的话"，而不是"刚刚发生了什么"。
     */

    @Test
    fun `她发了表情包 → 预览显示［动画表情］`() {
        val s = Session(
            messages = listOf(
                ChatMessage("user", "在吗"),
                ChatMessage("assistant", "在的", emojiPath = "/emoji/x.gif"),
            ),
        )
        assertEquals("[动画表情]", s.preview)
    }

    @Test
    fun `用户只发了图 → 预览显示［图片］`() {
        val s = Session(
            messages = listOf(
                ChatMessage("assistant", "在的"),
                ChatMessage("user", "", images = listOf("data:image/jpeg;base64,AAA")),
            ),
        )
        assertEquals("[图片]", s.preview)
    }

    @Test
    fun `带图又带字 → 仍显示文字（字比"图片"两个字更有信息量）`() {
        val s = Session(
            messages = listOf(
                ChatMessage("user", "看这张 很好笑", images = listOf("data:image/jpeg;base64,AAA")),
            ),
        )
        assertEquals("看这张 很好笑", s.preview)
    }
}
