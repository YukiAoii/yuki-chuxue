package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端**功能开关**（v0.58.0）的解析契约。
 *
 * 当前只有一个：`persona_global_prefix` —— 关掉时编辑/创建人设界面不显示「通用设定」。
 *
 * ⚠️ 这一组最重要的断言是**兜底方向**：拉不到、认不出、字段缺，都必须按
 *    **"开"**处理。反过来的话，服务端一抖或返回一个空响应，所有人的通用设定
 *    开关就凭空消失 —— 那看起来像 App 坏了，用户会来报 bug，而真正的问题不在这。
 */
class AppFeaturesParseTest {

    @Test
    fun `服务端说关就关`() {
        assertFalse(AppFeaturesApi.parse("""{"persona_global_prefix":false}""").personaGlobalPrefix)
        assertFalse(AppFeaturesApi.parse("""{"persona_global_prefix":"0"}""").personaGlobalPrefix)
    }

    @Test
    fun `服务端说开就开`() {
        assertTrue(AppFeaturesApi.parse("""{"persona_global_prefix":true}""").personaGlobalPrefix)
        assertTrue(AppFeaturesApi.parse("""{"persona_global_prefix":"1"}""").personaGlobalPrefix)
    }

    @Test
    fun `拿不到时按开处理`() {
        // 这一条是刻意的：服务端不可用不该把功能"关掉"
        assertTrue(AppFeaturesApi.parse(null).personaGlobalPrefix)
        assertTrue(AppFeaturesApi.parse("").personaGlobalPrefix)
        assertTrue(AppFeaturesApi.parse("不是 JSON").personaGlobalPrefix)
        assertTrue(AppFeaturesApi.parse("[]").personaGlobalPrefix)
        assertTrue("缺字段也算开", AppFeaturesApi.parse("""{}""").personaGlobalPrefix)
    }

    @Test
    fun `默认值就是全开`() {
        assertEquals(true, AppFeatures.DEFAULT.personaGlobalPrefix)
    }
}
