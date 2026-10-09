package ai.yuki.chuxue.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「初雪」自绘图标集 —— 不依赖 Material Icons。
 *
 * ## 为什么自绘（用户明确要求）
 * Material 图标是通用视觉，与「初雪」的品牌意象无关。用户要求：不用 emoji，
 * 用与 App 同源的矢量图标。这一套图标即为此而生。
 *
 * ## 统一的几何语言（改图标时必须遵守）
 * - **网格**：24×24，安全边距 ≥2，所有图形在此网格内对齐；
 * - **描边**：1.7dp 圆角线（[StrokeCap.Round] / [StrokeJoin.Round]），不描边填充；
 * - **母题**：六芒冰晶 —— 出现在 [Settings]（雪晶齿轮）与后续的品牌符号上；
 * - **配色**：图标**本身不带颜色**（描边用占位黑），颜色一律由 `Icon(tint = …)`
 *   注入，因此天然跟随主题，不写死任何品牌色。
 *
 * ## 用法
 * ```
 * Icon(YukiIcons.Send, contentDescription = "发送", tint = SkyBlue)
 * ```
 *
 * ## 不变量（由 `YukiIconsTest` 钉住）
 * 每个图标 viewport 必为 24×24、内部至少有一条子路径。破坏其一会让测试变红 ——
 * 这是提醒你「新加的图标没遵守几何语言」，而不是测试本身需要改。
 */
object YukiIcons {

    /** 描边占位色。真正的颜色由 `Icon(tint = …)` 的 ColorFilter 覆盖。 */
    private val Pen = SolidColor(Color(0xFF1B2B45))

    /** 统一描边宽度（24dp 网格下的视觉重量） */
    private const val STROKE = 1.7f

    /* ═══════════════════════ 导航类 ═══════════════════════ */

    /** 对话列表（打开会话抽屉）。三线，中线略短，留出节奏。 */
    val Conversations: ImageVector by lazy {
        build("Yuki.Conversations") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 12f); lineTo(15.5f, 12f)
            moveTo(4f, 17f); lineTo(20f, 17f)
        }
    }

    /** 返回 / 取消（左向折线箭头）。 */
    val Back: ImageVector by lazy {
        build("Yuki.Back") {
            moveTo(19f, 12f); lineTo(5f, 12f)
            moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
        }
    }

    /* ═══════════════════════ 操作类 ═══════════════════════ */

    /**
     * 新建（对话 / 人设）。纯十字加号。
     *
     * 曾试过在中心嵌一枚小菱形，但 24dp 真实尺寸下它只有 ~3dp，会被 1.7dp 描边
     * 吞掉、在交叉点上糊成一个脏点 —— 无视觉收益，故移除（有渲染截图为证）。
     */
    val Add: ImageVector by lazy {
        build("Yuki.Add") {
            moveTo(12f, 4.5f); lineTo(12f, 19.5f)
            moveTo(4.5f, 12f); lineTo(19.5f, 12f)
        }
    }

    /** 关闭 / 移除（X）。 */
    val Close: ImageVector by lazy {
        build("Yuki.Close") {
            moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
            moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
        }
    }

    /** 删除（垃圾桶：盖 / 提手 / 桶身 / 双竖线）。 */
    val Delete: ImageVector by lazy {
        build("Yuki.Delete") {
            moveTo(4f, 6.5f); lineTo(20f, 6.5f)                                        // 盖
            moveTo(9.5f, 6.5f); lineTo(9.5f, 3.5f); lineTo(14.5f, 3.5f); lineTo(14.5f, 6.5f) // 提手
            moveTo(6f, 6.5f); lineTo(6.8f, 20.5f); lineTo(17.2f, 20.5f); lineTo(18f, 6.5f)    // 桶身
            moveTo(10f, 10f); lineTo(10f, 17f)                                         // 内线
            moveTo(14f, 10f); lineTo(14f, 17f)
        }
    }

    /** 发送（纸飞机：机身三角 + 内折线 + 尾翼）。 */
    val Send: ImageVector by lazy {
        build("Yuki.Send") {
            moveTo(21f, 3.5f); lineTo(3f, 10.5f); lineTo(10.5f, 13.5f); close()
            moveTo(10.5f, 13.5f); lineTo(21f, 3.5f)
            moveTo(10.5f, 13.5f); lineTo(13.5f, 20.5f); lineTo(21f, 3.5f)
        }
    }

    /* ═══════════════════════ 功能类 ═══════════════════════ */

    /**
     * 图片（圆角相框 + 远山 + 日轮）。
     * 用于「选图」按钮与「消息含图」标记。
     */
    val Image: ImageVector by lazy {
        build("Yuki.Image") {
            // 相框
            moveTo(5.5f, 5f); lineTo(18.5f, 5f)
            quadTo(21f, 5f, 21f, 7.5f); lineTo(21f, 16.5f)
            quadTo(21f, 19f, 18.5f, 19f); lineTo(5.5f, 19f)
            quadTo(3f, 19f, 3f, 16.5f); lineTo(3f, 7.5f)
            quadTo(3f, 5f, 5.5f, 5f); close()
            // 远山（两峰）
            moveTo(4.5f, 16.2f); lineTo(9.5f, 11f); lineTo(13f, 14.4f)
            moveTo(11.5f, 12.2f); lineTo(15.5f, 8.5f); lineTo(19.5f, 13f)
            // 日轮
            circle(16.3f, 8.4f, 1.3f)
        }
    }

    /**
     * 设置。**六芒冰晶齿轮** —— 中心孔 + 内环 + 六根齿。
     * 刻意不用通用齿轮：这个图形同时是「可调节」与「初雪」的双关。
     */
    val Settings: ImageVector by lazy {
        build("Yuki.Settings") {
            circle(12f, 12f, 2.8f)   // 中心孔
            circle(12f, 12f, 7.2f)   // 内环
            // 六根齿（每 60°，从 r=7.2 伸到 r=10）
            moveTo(19.2f, 12f);     lineTo(22f, 12f)
            moveTo(15.6f, 5.77f);   lineTo(17f, 3.34f)
            moveTo(8.4f, 5.77f);    lineTo(7f, 3.34f)
            moveTo(4.8f, 12f);      lineTo(2f, 12f)
            moveTo(8.4f, 18.23f);   lineTo(7f, 20.66f)
            moveTo(15.6f, 18.23f);  lineTo(17f, 20.66f)
        }
    }

    /* ═══════════════════════ 底部导航 Tab ═══════════════════════
       开发文档 §45.11 的五个 Tab。
       选中 / 未选中由 **tint 透明度 + 缩放** 区分（§45.3.3、§45.2），
       故不做填充版 —— 一套描边图标承担两态，视觉更统一。
       ═══════════════════════════════════════════════════════════ */

    /** 消息（会话列表）。圆角气泡 + 左下小尾。 */
    val ChatBubble: ImageVector by lazy {
        build("Yuki.ChatBubble") {
            moveTo(7f, 4f); lineTo(17f, 4f)
            quadTo(21f, 4f, 21f, 8f); lineTo(21f, 13f)
            quadTo(21f, 17f, 17f, 17f); lineTo(7f, 17f)
            quadTo(3f, 17f, 3f, 13f); lineTo(3f, 8f)
            quadTo(3f, 4f, 7f, 4f); close()
            // 尾巴
            moveTo(8.5f, 17f); lineTo(7.5f, 20.8f); lineTo(12.5f, 17f)
        }
    }

    /** 人设。圆头 + 肩。 */
    val Person: ImageVector by lazy {
        build("Yuki.Person") {
            circle(12f, 8.3f, 3.5f)
            moveTo(5.5f, 19.5f)
            quadTo(5.5f, 14.6f, 12f, 14.6f)
            quadTo(18.5f, 14.6f, 18.5f, 19.5f)
        }
    }

    /** 人设市场。店面：篷顶 + 房体 + 门。 */
    val Storefront: ImageVector by lazy {
        build("Yuki.Storefront") {
            moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(21f, 8.5f); lineTo(3f, 8.5f); close()
            moveTo(5f, 8.5f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 8.5f)
            moveTo(10f, 20f); lineTo(10f, 15.5f); lineTo(14f, 15.5f); lineTo(14f, 20f)
        }
    }

    /** 我的。外圈 + 人形。 */
    val AccountCircle: ImageVector by lazy {
        build("Yuki.AccountCircle") {
            circle(12f, 12f, 8.6f)
            circle(12f, 9.6f, 2.7f)
            moveTo(7.1f, 18f)
            quadTo(12f, 13.4f, 16.9f, 18f)
        }
    }

    /** 关于。圆 + i。 */
    val Info: ImageVector by lazy {
        build("Yuki.Info") {
            circle(12f, 12f, 8.6f)
            moveTo(12f, 7.6f); lineTo(12f, 8.6f)     // i 的点
            moveTo(12f, 11.6f); lineTo(12f, 16.6f)   // i 的竖
        }
    }

    /* ═══════════════════════ 通用 ═══════════════════════ */

    /** 雪花（默认头像 / 品牌符号）。三线交叉成六芒 + 上下分叉。 */
    val Snowflake: ImageVector by lazy {
        build("Yuki.Snowflake") {
            moveTo(12f, 3.5f); lineTo(12f, 20.5f)
            moveTo(4.6f, 7.7f); lineTo(19.4f, 16.3f)
            moveTo(19.4f, 7.7f); lineTo(4.6f, 16.3f)
            // 上端分叉
            moveTo(12f, 7.2f); lineTo(10.1f, 5.3f)
            moveTo(12f, 7.2f); lineTo(13.9f, 5.3f)
            // 下端分叉
            moveTo(12f, 16.8f); lineTo(10.1f, 18.7f)
            moveTo(12f, 16.8f); lineTo(13.9f, 18.7f)
        }
    }

    /** 列表项右侧的「>」。 */
    val ChevronRight: ImageVector by lazy {
        build("Yuki.ChevronRight") {
            moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
        }
    }

    /** 警告（缓存失效提示等）。 */
    val Warning: ImageVector by lazy {
        build("Yuki.Warning") {
            moveTo(12f, 3.5f); lineTo(21.6f, 20f); lineTo(2.4f, 20f); close()
            moveTo(12f, 9.2f); lineTo(12f, 14.2f)    // 感叹号竖
            moveTo(12f, 16.8f); lineTo(12f, 17.6f)   // 感叹号点
        }
    }

    /** 编辑（铅笔）。 */
    val Pencil: ImageVector by lazy {
        build("Yuki.Pencil") {
            moveTo(4f, 20f); lineTo(4f, 16.4f); lineTo(15.4f, 5f); lineTo(19f, 8.6f); lineTo(7.6f, 20f); close()
            moveTo(13.3f, 7.1f); lineTo(16.9f, 10.7f)
        }
    }

    /** 缓存诊断（放大镜）。 */
    val Search: ImageVector by lazy {
        build("Yuki.Search") {
            circle(10.5f, 10.5f, 6.2f)
            moveTo(15.1f, 15.1f); lineTo(20.4f, 20.4f)
        }
    }

    /** 记忆（摊开的书）。 */
    val Book: ImageVector by lazy {
        build("Yuki.Book") {
            moveTo(12f, 6.6f); lineTo(12f, 20f)
            moveTo(12f, 6.6f); quadTo(8f, 5.1f, 4f, 6.6f); lineTo(4f, 19f); quadTo(8f, 17.5f, 12f, 19f)
            moveTo(12f, 6.6f); quadTo(16f, 5.1f, 20f, 6.6f); lineTo(20f, 19f); quadTo(16f, 17.5f, 12f, 19f)
        }
    }

    /* ═══════════════════════ 构建辅助 ═══════════════════════ */

    /**
     * 用统一几何语言构建一个图标：24×24 网格 + 1.7dp 圆角描边。
     * 所有路径都塞进**同一条** path（多段 moveTo 天然形成独立子路径），
     * 这样 `Icon` 只需一次绘制调用。
     */
    private inline fun build(name: String, crossinline block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = Pen,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = { block() },
            )
        }.build()

    /* ═══════════════════════ 输入类 ═══════════════════════ */

    /**
     * 麦克风（语音输入）。
     *
     * 结构：话筒头（竖胶囊）+ 下凸的支架弧 + 立杆 + 底座 —— 全是描边，与全组语言一致。
     *
     * ⚠️ 支架弧的方向（`isPositiveArc = false`）是照 SVG 的弧语义推出来的：
     * y 轴向下时，从 (8.5,11) 到 (15.5,11) 取 `false` 才会**向下凸**（U 形）。
     * **本机没有 Compose 预览**，真机上若看到支架朝上翻，把那个 false 改成 true 即可 ——
     * 这是全图唯一一处无法离线确认的地方。
     */
    val Mic: ImageVector by lazy {
        build("Yuki.Mic") {
            // 话筒头：竖着的圆角矩形（1.7dp 圆角描边下，直角看起来已经是圆的）
            moveTo(10.5f, 5.5f); lineTo(13.5f, 5.5f)
            lineTo(13.5f, 11.5f); lineTo(10.5f, 11.5f); close()
            // 支架
            moveTo(8.5f, 11f)
            arcToRelative(
                3.5f, 3.5f, 0f,
                isMoreThanHalf = false,
                isPositiveArc = false,
                dx1 = 7f, dy1 = 0f,
            )
            // 立杆 + 底座
            moveTo(12f, 15f); lineTo(12f, 18f)
            moveTo(9.5f, 18f); lineTo(14.5f, 18f)
        }
    }

    /** 往当前路径追加一个正圆（两段半圆弧拼成）。 */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = r * 2, dy1 = 0f)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -r * 2, dy1 = 0f)
        close()
    }
}
