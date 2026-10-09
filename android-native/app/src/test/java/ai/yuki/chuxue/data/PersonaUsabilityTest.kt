package ai.yuki.chuxue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设「能不能存」与「填得全不全」是**两件事**（v0.61.21 拆开）。
 *
 * ## 起因
 * 用户报「弹窗里点人设没反应」，查下去是 `isUsable` 在拦 —— 而 `isUsable`
 * 要求 `userNickname` / `userGender` / `customPrompt` **三项全非空**。
 * 更要命的是「创建 / 保存」按钮也用它当准入判据，于是**三项缺一就存不下来**，
 * 而按钮只是灰着、不解释为什么。
 *
 * 再往后一步是死局：用户要求去掉「Ta 怎么称呼你」（= `userNickname`）——
 * 去掉之后 `isUsable` 对**新建的人设**恒为 false，那颗按钮会**永久禁用**，
 * 谁都建不出人设。所以这一组测试钉的是：**准入门槛与完整度提示必须分开**。
 */
class PersonaUsabilityTest {

    private fun p(name: String = "", gender: String = "", prompt: String = "") =
        Persona(id = "p1", userNickname = name, userGender = gender, customPrompt = prompt)

    @Test
    fun `只填了角色设定就能保存 —— 不该被"怎么称呼你"卡住`() {
        assertTrue(p(prompt = "她是咖啡师，话少。").canSave)
    }

    @Test
    fun `什么都没有时不能保存 —— 别造出空人设`() {
        assertFalse(p().canSave)
    }

    @Test
    fun `「怎么称呼你」为空**不再**影响"填得全不全" —— Wave 4 到，契约跟着改`() {
        // 我早前在这里写过「这条契约先不动，等 Wave 4 真去掉了那一栏再改」。
        // 现在那一栏已经从编辑页移除了（用户要求）：新建人设的这个值常年为空 ——
        // 再要求它等于让**所有人设**都显示"没填完"，那条判据就废了。
        // 同一时刻一起改的还有 CodecTest 的「缺昵称不可用」（已翻转）。
        assertTrue(p(gender = "女", prompt = "她是咖啡师。").isUsable)
    }

    @Test
    fun `但它**不该再拦保存** —— 这一条才是本次要修的`() {
        assertTrue(p(gender = "女", prompt = "她是咖啡师。").canSave)
    }

    @Test
    fun `缺角色设定仍然算"没填完" —— 完整度提示还留着`() {
        assertFalse(p(gender = "女").isUsable)
    }

    @Test
    fun `能保存的，一定是填了角色设定的`() {
        // 两条判据的关系：canSave 是 isUsable 的必要条件（不是等价）
        assertTrue(p(gender = "女", prompt = "设定").canSave)
        assertFalse(p(gender = "女").canSave)
    }
}
