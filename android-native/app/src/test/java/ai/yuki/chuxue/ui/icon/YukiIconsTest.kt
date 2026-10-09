package ai.yuki.chuxue.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「初雪」图标集的几何不变量。
 *
 * 这不是「测试图标好不好看」——那只能人眼看（无模拟器）。测的是**结构**：
 * 一旦某个图标越出 24 网格、混进填充色、或描边宽度不一致，这里会变红。
 * 含义是「新加的图标没遵守统一的几何语言」，而不是「测试该改」。
 */
class YukiIconsTest {

    /** 全量图标。新增图标必须同时加进来，否则 [图标集合完整_防止误删] 会提醒你。 */
    private val all: Map<String, ImageVector> by lazy {
        mapOf(
            "Conversations" to YukiIcons.Conversations,
            "Back" to YukiIcons.Back,
            "Add" to YukiIcons.Add,
            "Close" to YukiIcons.Close,
            "Delete" to YukiIcons.Delete,
            "Send" to YukiIcons.Send,
            "Image" to YukiIcons.Image,
            "Settings" to YukiIcons.Settings,
            "ChatBubble" to YukiIcons.ChatBubble,
            "Person" to YukiIcons.Person,
            "Storefront" to YukiIcons.Storefront,
            "AccountCircle" to YukiIcons.AccountCircle,
            "Info" to YukiIcons.Info,
            "ChevronRight" to YukiIcons.ChevronRight,
            "Warning" to YukiIcons.Warning,
            "Pencil" to YukiIcons.Pencil,
            "Search" to YukiIcons.Search,
            "Book" to YukiIcons.Book,
            "Snowflake" to YukiIcons.Snowflake,
        )
    }

    private fun pathsOf(icon: ImageVector): List<VectorPath> {
        // VectorGroup.children 是 private —— 通过它实现的 Iterable<VectorNode> 遍历
        val out = mutableListOf<VectorPath>()
        for (node in icon.root) if (node is VectorPath) out += node
        return out
    }

    /** 收集一条路径里所有**绝对**坐标点（相对命令与弧线端点不计，见下方断言注释）。 */
    private fun absolutePoints(icon: ImageVector): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        pathsOf(icon).forEach { p ->
            p.pathData.forEach { n ->
                when (n) {
                    is PathNode.MoveTo -> out += n.x to n.y
                    is PathNode.LineTo -> out += n.x to n.y
                    is PathNode.QuadTo -> { out += n.x1 to n.y1; out += n.x2 to n.y2 }
                    is PathNode.CurveTo -> {
                        out += n.x1 to n.y1; out += n.x2 to n.y2; out += n.x3 to n.y3
                    }
                    is PathNode.ArcTo -> out += n.arcStartX to n.arcStartY
                    else -> Unit
                }
            }
        }
        return out
    }

    @Test
    fun `图标集合完整_防止误删`() {
        assertEquals("图标数量变了：若是新增请同步更新本测试的 all 列表", 19, all.size)
    }

    @Test
    fun `每个图标都在 24dp 网格上`() {
        all.forEach { (name, icon) ->
            assertEquals("$name 的 viewportWidth", 24f, icon.viewportWidth, 0f)
            assertEquals("$name 的 viewportHeight", 24f, icon.viewportHeight, 0f)
            assertEquals("$name 的 defaultWidth", 24.dp, icon.defaultWidth)
            assertEquals("$name 的 defaultHeight", 24.dp, icon.defaultHeight)
        }
    }

    @Test
    fun `每个图标都带 Yuki 命名前缀`() {
        all.forEach { (name, icon) ->
            assertTrue("$name 的图标名 '${icon.name}' 缺少 Yuki. 前缀", icon.name.startsWith("Yuki."))
        }
    }

    @Test
    fun `每个图标都至少有一条子路径`() {
        all.forEach { (name, icon) ->
            assertTrue("$name 没有任何路径", pathsOf(icon).isNotEmpty())
        }
    }

    @Test
    fun `图标一律为描边风格_宽度 1_7_无填充`() {
        all.forEach { (name, icon) ->
            pathsOf(icon).forEach { p ->
                assertNull("$name 出现了填充色 —— 统一几何语言要求只描边", p.fill)
                assertNotNull("$name 缺少描边", p.stroke)
                assertEquals("$name 的描边宽度偏离 1.7", 1.7f, p.strokeLineWidth, 0.0001f)
            }
        }
    }

    /**
     * 绝对坐标必须落在 24×24 网格内 —— 越界意味着渲染时被裁掉。
     *
     * 只查绝对命令（Move/Line/Quad/Curve/Arc 终点）：本套图标中唯一的相对命令是
     * `circle()` 内部的 `arcToRelative`，其圆心与半径已由坐标常量约束，另行保证。
     */
    @Test
    fun `所有绝对坐标都落在 24 网格内`() {
        all.forEach { (name, icon) ->
            val pts = absolutePoints(icon)
            assertTrue("$name 没有可检查的坐标点", pts.isNotEmpty())
            pts.forEach { (x, y) ->
                assertTrue("$name 坐标越界：($x, $y)", x >= 0f && x <= 24f && y >= 0f && y <= 24f)
            }
        }
    }
}
