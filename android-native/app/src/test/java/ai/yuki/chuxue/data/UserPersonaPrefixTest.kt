package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户人设进冻结前缀」的结构契约（v0.61.42）。
 *
 * ## ⚠️ 本文件钉的是整个功能最危险的一条：误读
 * 用户会在自己的角色设定里写「你是……」（他是在对 AI 说"这个身份里你是谁"）——
 * 一旦这段文本被 AI 当成"我（AI）自己的人设"，角色扮演就人格错乱了。
 * 防线是**结构性的**：正文只许进「# 关于用户」段，绝不许碰「# 角色设定」段。
 */
class UserPersonaPrefixTest {

    private val settings = AppSettings()
    private val persona = Persona(
        id = "p1",
        userNickname = "阿澈",
        userGender = "男",
        customPrompt = "角色名称：初雪",
    )

    /** 刻意写成「你是…」—— 正是最容易被误读成 AI 人设的写法。 */
    private val blacksmith = UserPersona(
        id = "u1",
        name = "老铁",
        roleText = "你是村里手艺最好的铁匠，五十多岁，沉默寡言，左手有一道疤。",
    )

    @Test
    fun `关键 —— 用户人设里的"你是…"只进关于用户段，AI 自己的设定段逐字节不变`() {
        val withUp = PromptEngine.buildFrozenPrefix(settings, persona, blacksmith)
        val withoutUp = PromptEngine.buildFrozenPrefix(settings, persona)

        // ① 正文在
        assertTrue("用户人设正文没进前缀", withUp.contains(blacksmith.roleText))
        // ② 在「# 角色设定」**之前**（= 在关于用户段里）
        assertTrue(
            "用户人设正文跑到 AI 设定段后面去了",
            withUp.indexOf(blacksmith.roleText) < withUp.indexOf("# 角色设定"),
        )
        // ③ AI 自己的设定段逐字节不变（本功能的第一红线）
        assertEquals(
            "AI 自己的人设段被改写了 —— 人格错乱 bug",
            withoutUp.substringAfter("# 角色设定"),
            withUp.substringAfter("# 角色设定"),
        )
        // ④ 指向条款在场：明确说这段是"与你对话的人"、不是"你自己"
        assertTrue("缺第一人称指向条款", withUp.contains("不是你自己"))
    }

    @Test
    fun `没绑定时输出与旧调用逐字节一致 —— 老用户缓存不碎`() {
        assertEquals(
            PromptEngine.buildFrozenPrefix(settings, persona),
            PromptEngine.buildFrozenPrefix(settings, persona, null),
        )
    }

    @Test
    fun `roleText 全空白时不注入 —— 与没绑定完全一致`() {
        val blank = blacksmith.copy(roleText = "   \n ")
        assertEquals(
            PromptEngine.buildFrozenPrefix(settings, persona),
            PromptEngine.buildFrozenPrefix(settings, persona, blank),
        )
    }

    @Test
    fun `昵称与性别照旧在，用户人设追加在它们之后`() {
        val out = PromptEngine.buildFrozenPrefix(settings, persona, blacksmith)
        assertTrue(out.contains("- 用户昵称：阿澈"))
        assertTrue(out.contains("- 用户性别：男"))
        assertTrue(out.indexOf("- 用户性别") < out.indexOf(blacksmith.roleText))
    }

    @Test
    fun `昵称性别全空、只有用户人设时 —— 关于用户段仍要出现（有内容可放）`() {
        val bare = persona.copy(userNickname = "", userGender = "")
        val out = PromptEngine.buildFrozenPrefix(settings, bare, blacksmith)
        assertTrue("「# 关于用户」段丢了", out.contains("# 关于用户"))
        assertTrue(out.contains(blacksmith.roleText))
    }
}
