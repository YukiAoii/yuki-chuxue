package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * APK 相对地址 → 绝对地址（v0.50.0）。
 *
 * ## 为什么这个三行的函数值得测
 * 后端存的是**相对路径**（`/d/yuki.apk`）—— 它刻意不硬编码自己的域名
 * （换端口/反代/域名都不用改库里的数据，见后端注释）。
 * 于是"拼绝对地址"这一步落在客户端，而它**拼错的形式很隐蔽**：
 * 少一个斜杠会变成 `https://hostd/yuki.apk`，下载 404，用户只看到"下载失败"。
 *
 * 与头像、收款码那些相对路径同一口径 —— 两处不一致会让"头像能显示、
 * APK 下不下来"这种奇怪组合出现。
 */
class ApkUrlTest {

    @Test
    fun `相对路径拼上服务端地址`() {
        val out = ApkDownloader.absoluteUrl("/d/yuki.apk")
        assertEquals(ServerConfig.BASE_URL + "/d/yuki.apk", out)
        // 关键：域名与路径之间**只应有一个斜杠**
        assertEquals(1, out.removePrefix(ServerConfig.BASE_URL).take(1).count { it == '/' })
    }

    @Test
    fun `已经是绝对地址时原样返回 —— 便于将来改用图床或 CDN`() {
        val abs = "https://cdn.example.com/yuki.apk"
        assertEquals(abs, ApkDownloader.absoluteUrl(abs))
    }

    @Test
    fun `空路径返回空串 —— 判定层据此降级为非强制`() {
        assertEquals("", ApkDownloader.absoluteUrl(""))
    }
}
