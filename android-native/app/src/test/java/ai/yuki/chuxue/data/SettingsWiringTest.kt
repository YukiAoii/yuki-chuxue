package ai.yuki.chuxue.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置字段接线」的守护测试。
 *
 * ## 为什么需要它
 *
 * `AppSettings` 的持久化**不是 JSON 一把梭**，而是 `Store.loadSettings` /
 * `saveSettings` 里**逐字段手写**的 `prefs.getXxx` / `putXxx`。这个设计本身没问题
 * （类型安全、可读），但它有一个**静默失效**：
 *
 * > 给 `AppSettings` 加一个新字段、忘了在 load/save 里接线 —— **编译照样通过**
 * > （Kotlin 默认参数兜住了构造），**已有测试照样全绿**（没人碰这个字段），
 * > 而用户看到的是「设置能改、重启就丢」。
 *
 * 本项目已经踩过一次同类坑（`sendMode` 归一化 / `enterToSend` 迁移那两条注释
 * 记着的教训）。v0.61.57 加全局美化四字段时又差点重演 —— 所以补上这道闸。
 *
 * ## 它怎么判
 *
 * 反射拿 `AppSettings` 的**全部属性名**，逐个到 `Store.kt` 的源码里找：
 * 1. `loadSettings` 段里必须出现 `字段名 =`（构造参数，证明读侧接了）；
 * 2. `saveSettings` 段里必须出现 `s.字段名`（证明写侧接了）。
 *
 * ## 诚实划界
 *
 * - 覆盖：**字段是否被读写**（这正是会静默失效的那一层）。
 * - 不覆盖：读写的 key 是否写错、默认值是否合理、类型是否匹配 ——
 *   那些在真机/集成层才能验，本项目没有 Robolectric（见 HANDOFF 未验证清单）。
 * - 判据是**源码文本匹配**，不是运行时行为。所以它只能证明"接线语句存在"，
 *   不能证明"接线是对的"。这已经是它承诺的全部。
 */
class SettingsWiringTest {

    /** 单元测试的 cwd 可能是模块目录或仓库根 —— 两边都试。 */
    private fun storeSource(): String {
        val candidates = listOf(
            File("src/main/java/ai/yuki/chuxue/data/Store.kt"),
            File("app/src/main/java/ai/yuki/chuxue/data/Store.kt"),
            File("../app/src/main/java/ai/yuki/chuxue/data/Store.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到 Store.kt（找过：${candidates.map { it.absolutePath }}）")
        return f.readText()
    }

    /**
     * `AppSettings` 的字段名。
     *
     * ⚠️ 用 **Java 反射**（`declaredFields`）而不是 `kotlin.reflect.memberProperties` ——
     *    后者要额外的 `kotlin-reflect` 依赖，本项目没引；Java 反射零依赖就够用
     *    （同 `SoftUiSpecTest` 的做法）。
     * ⚠️ 过滤 `\` / `Companion` 这类编译器合成字段 —— 它们不是构造参数。
     */
    private fun fieldNames(): List<String> =
        AppSettings::class.java.declaredFields
            .map { it.name }
            .filterNot { it.contains("\$") || it == "Companion" }

    /** 截出 `fun loadSettings` / `fun saveSettings` 各自的函数体。 */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        check(start >= 0) { "Store.kt 里找不到 $signature —— 函数被改名了？测试需要同步更新。" }
        // 从签名往后找第一个「行首的四个空格 + }」= 函数结束（本项目缩进是 4 空格）
        val end = src.indexOf("\n    }", start)
        check(end > start) { "$signature 的函数体没能截出来" }
        return src.substring(start, end)
    }

    @Test
    fun `AppSettings 的每个字段都必须在 loadSettings 里被读取`() {
        val src = storeSource()
        val load = body(src, "fun loadSettings")
        val missing = fieldNames()
            .filter { name -> !Regex("""\b${Regex.escape(name)}\s*=""").containsMatchIn(load) }

        assertTrue(
            "这些字段在 loadSettings 里没有读取 —— 表现是「设置能改、重启就丢」：$missing\n" +
                "（加字段时记得同时改 Store.kt 的 loadSettings / saveSettings / K_ 常量三处）",
            missing.isEmpty(),
        )
    }

    @Test
    fun `AppSettings 的每个字段都必须在 saveSettings 里被写入`() {
        val src = storeSource()
        val save = body(src, "fun saveSettings")
        val missing = fieldNames()
            .filter { name -> !save.contains("s.$name") }

        assertTrue(
            "这些字段在 saveSettings 里没有写入 —— 表现是「设置能改、重启就丢」：$missing\n" +
                "（加字段时记得同时改 Store.kt 的 loadSettings / saveSettings / K_ 常量三处）",
            missing.isEmpty(),
        )
    }

    /* ══════════ v0.61.57 全局美化的四字段（本测试诞生的直接原因）══════════
       上面两条是通用闸；下面这条把本次新增的字段**点名**钉一遍 ——
       万一将来有人把上面的通用闸误删/放宽，这条还能拦住最痛的那几个。 */

    @Test
    fun `全局美化字段确实接了线（背景 遮罩 顶栏透明度）`() {
        val src = storeSource()
        val load = body(src, "fun loadSettings")
        val save = body(src, "fun saveSettings")

        for (name in listOf("background", "scrimEnabled", "scrimAlpha", "scrimStyle", "topBarAlpha")) {
            assertTrue("loadSettings 漏了 $name", Regex("""\b${Regex.escape(name)}\s*=""").containsMatchIn(load))
            assertTrue("saveSettings 漏了 $name", save.contains("s.$name"))
        }
    }
}