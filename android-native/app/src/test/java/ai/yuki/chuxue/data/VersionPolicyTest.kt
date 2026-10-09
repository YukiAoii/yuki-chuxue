package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新判定与下载地址拼装的契约（v0.50.0）。
 *
 * ## 为什么这批断言值得写
 * 这是本项目**唯一会弹窗打断用户**、且**可能让用户用不了 App** 的分叉：
 * 判成"强制"而下载又拿不到东西，用户就被锁在一个关不掉的弹窗里。
 * 这条判定原是 ViewModel 里的几行 if（要 Android 环境才跑得起来），
 * 抽成纯函数就是为了让下面每条边界都能在 JVM 上钉死。
 */
class VersionPolicyTest {

    private fun remote(
        code: Int = 71,
        name: String = "0.50.0",
        notes: String = "修了些问题",
        force: Boolean = false,
        minSupported: Int = 0,
        apkUrl: String = "/d/yuki.apk",
        apkSize: Long = 13_800_000L,
    ) = RemoteRelease(
        versionCode = code, versionName = name, notes = notes,
        force = force, minSupportedCode = minSupported,
        apkUrl = apkUrl, apkSize = apkSize,
    )

    /* ─────────── 基本判定 ─────────── */

    @Test
    fun `没有版本信息时不提示 —— 那是首次部署的正常状态`() {
        val d = VersionPolicy.decide(localCode = 70, latest = null)
        assertFalse("latest=null 不该提示更新（不是错误）", d.hasUpdate)
        assertFalse(d.force)
    }

    @Test
    fun `服务端版本更高时提示更新`() {
        val d = VersionPolicy.decide(70, remote(code = 71))
        assertTrue(d.hasUpdate)
        assertFalse("默认不强制", d.force)
    }

    @Test
    fun `版本相同时不提示`() {
        assertFalse(VersionPolicy.decide(71, remote(code = 71)).hasUpdate)
    }

    @Test
    fun `⚠️ 服务端版本比本地旧时不提示 —— 只认大于，不是不等于`() {
        // 回滚 / 测试环境的常见情形：服务端退回旧版本。
        // 若判据写成 !=，用户会看到一个"更新"到旧版本的弹窗。
        val d = VersionPolicy.decide(localCode = 80, remote(code = 71, name = "0.50.0"))
        assertFalse("服务端更旧不该提示", d.hasUpdate)
    }

    /* ─────────── 强制：两个来源 ─────────── */

    @Test
    fun `服务端标记 force 时强制`() {
        val d = VersionPolicy.decide(70, remote(code = 71, force = true))
        assertTrue(d.hasUpdate)
        assertTrue("force=true 应强制", d.force)
    }

    @Test
    fun `本地低于 minSupportedCode 时强制 —— 即使 force 是 false`() {
        val d = VersionPolicy.decide(
            localCode = 70,
            remote(code = 71, force = false, minSupported = 75),
        )
        assertTrue("70 < 75 应强制", d.force)
    }

    @Test
    fun `本地不低于 minSupportedCode 时不因它强制`() {
        val d = VersionPolicy.decide(
            localCode = 75,
            remote(code = 80, force = false, minSupported = 75),
        )
        assertTrue("有更新", d.hasUpdate)
        assertFalse("75 >= 75，不该因 min 强制", d.force)
    }

    @Test
    fun `⚠️ localCode 为 0（未知）时 minSupportedCode 不触发强制`() {
        // 0 是"读不到版本号"的哨兵值，不是"很旧的版本"。
        // 若直接比大小，0 < 任何 min 都会判强制 —— 那会把一个版本号读取失败
        // 变成"所有用户被强制更新"。
        val d = VersionPolicy.decide(
            localCode = 0,
            remote(code = 71, force = false, minSupported = 75),
        )
        assertFalse("版本号未知时不该因 min 强制", d.force)
    }

    @Test
    fun `minSupportedCode 为 0（未设置）时不触发强制`() {
        val d = VersionPolicy.decide(70, remote(code = 71, force = false, minSupported = 0))
        assertFalse(d.force)
    }

    /* ─────────── 护栏：拿不到包就不许锁死用户 ─────────── */

    @Test
    fun `⚠️ 下载地址为空时强制降级为非强制 —— 否则用户出不去`() {
        val d = VersionPolicy.decide(
            localCode = 70,
            remote(code = 71, force = true, apkUrl = ""),
        )
        assertTrue("仍要提示有更新", d.hasUpdate)
        assertFalse("⚠️ 没有下载地址时必须可取消，否则用户被锁死", d.force)
    }

    @Test
    fun `⚠️ apkSize 为 0 时同样降级为非强制`() {
        val d = VersionPolicy.decide(
            localCode = 70,
            remote(code = 71, force = true, apkSize = 0L),
        )
        assertTrue(d.hasUpdate)
        assertFalse("缺大小就无法校验完整性，不能强制", d.force)
    }

    @Test
    fun `minSupportedCode 触发的强制同样受空地址护栏约束`() {
        val d = VersionPolicy.decide(
            localCode = 70,
            remote(code = 71, minSupported = 75, apkUrl = ""),
        )
        assertTrue("仍应提示", d.hasUpdate)
        assertFalse("⚠️ 兜底：任何来源的强制都要先能拿到包", d.force)
    }

    /* ─────────── reason（排查用） ─────────── */

    @Test
    fun `reason 说得出结论的来源 —— 排查时不用猜`() {
        assertTrue(
            VersionPolicy.decide(70, remote(code = 71, force = true)).reason.contains("强制"),
        )
        assertTrue(
            VersionPolicy.decide(70, remote(code = 71, minSupported = 75)).reason.contains("最低支持"),
        )
        // ⚠️ 这条用例必须先让"本该强制"成立（否则没有可降级的东西）——
        //    `force=true` 且地址为空：reason 里要说明"本应强制但降级了"。
        assertTrue(
            VersionPolicy.decide(70, remote(code = 71, force = true, apkUrl = "")).reason.contains("降级"),
        )
        // 普通更新（不强制、地址齐全）不该出现"降级"字样
        assertFalse(
            VersionPolicy.decide(70, remote(code = 71)).reason.contains("降级"),
        )
    }
}
