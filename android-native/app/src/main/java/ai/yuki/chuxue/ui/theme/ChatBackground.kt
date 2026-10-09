package ai.yuki.chuxue.ui.theme

import ai.yuki.chuxue.data.SCRIM_FROST
import ai.yuki.chuxue.data.SCRIM_GLASS
import ai.yuki.chuxue.data.SCRIM_LIQUID
import ai.yuki.chuxue.data.SCRIM_PLAIN
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 聊天背景预设（对话设置页 →「聊天背景」）。
 *
 * [id] 就是 `Session.background` 存的那个标识 —— 只有 [DEFAULT] 用 `null`，
 * 其余都必须有可持久化的非空串。将来删掉某个预设时，老会话里那个 id 会被
 * [ChatBackgrounds.of] 回落到默认，**不会打不开聊天页**。
 *
 * ## 为什么全是浅色渐变，不做深色/图片背景
 * 三件事一起决定了这个取舍：
 *
 * 1. **气泡与顶栏是白的**。深底会让白气泡浮起来（对比更强），但顶栏
 *    `SnowSurface`、输入框 `FieldFill`、时间分割条都是按浅底标定的 ——
 *    换深底就得**逐件重标定**，而那正是本项目"浅底上白元素隐形"栽过两次的地方。
 * 2. **没有视觉回归手段**。本机无设备，改完只能等用户看真机；
 *    一次动一堆参数的代价太高。
 * 3. **不用图片资源**：一张背景图起步几十 KB，且不同屏幕比例裁切不可控。
 *    两端渐变能做出"纸的层次"，成本是零。
 *
 * 所以这套预设的共同点写在测试里了（[ChatBackgroundTest]）：**够浅**（白卡片立得住）、
 * **又确实与纯白有差别**（不然等于没换）。
 *
 * ⚠️ 渲染未经真机验证（本机无 adb / emulator）。
 */
enum class ChatBackground(
    val id: String?,
    val label: String,
    /** 从上到下的渐变色标；单元素即纯色。 */
    val gradient: List<Color>,
) {
    /** 默认：与全局背景同色（`#F5F7FA` 纯色，不加渐变）。 */
    DEFAULT(null, "默认", listOf(FlatBackground)),

    /** 冷蓝白，像清晨的窗。 */
    MIST("mist", "晨雾", listOf(Color(0xFFEDF2F8), Color(0xFFF6F9FC))),

    /** 暖调象牙白，像台灯下的纸。 */
    GLOW("glow", "微光", listOf(Color(0xFFFDF6EE), Color(0xFFF7F9FC))),

    /**
     * 中性灰蓝。
     *
     * 这里原本是「薄紫」(`#F2EFFB`)。用户 2026-09-27 反馈「整体 ui 别用紫色」
     * 之后一并换掉 —— 一个叫"薄紫"的选项留在列表里，等于把已经否掉的配色
     * 又摆回用户眼前。`ChatBackgroundTest` 只钉亮度与唯一性，不钉色相，
     * 所以这条替换是安全的。
     */
    FOG("fog", "雾灰", listOf(Color(0xFFEEF1F6), Color(0xFFF7F8FB))),

    /** 极浅青绿。 */
    MINT("mint", "薄荷", listOf(Color(0xFFEFF8F4), Color(0xFFF6FAFB))),
}

object ChatBackgrounds {

    /**
     * 自定义图片背景的标识前缀。
     *
     * `Session.background` 是**一个字符串字段**，它要同时能表达"内置预设"与
     * "用户自己选的图"。用前缀区分是这里唯一合理的做法 —— 加一个类型列
     * 意味着一次 Room 迁移，而这个字段的语义没有复杂到需要那种代价。
     *
     * 形如 `file:/data/user/0/ai.yuki.chuxue/files/backgrounds/bg_xxx.jpg`。
     */
    const val CUSTOM_PREFIX = "file:"

    /** 供界面按顺序铺出全部选项。 */
    val presets: List<ChatBackground> = ChatBackground.entries.toList()

    /**
     * 标识 → 预设。
     *
     * **未知一律回落默认**，不抛异常：这个值来自数据库，可能比当前版本的
     * 预设列表更旧（用户装回老版本又升级、或某个预设被删掉）。
     * 背景解析失败不该让整页打不开 —— 与项目在持久化场景的一贯取舍一致
     * （读取失败要显式暴露，但**展示层**的回落不该阻断功能）。
     *
     * ⚠️ 自定义图片（[CUSTOM_PREFIX] 开头）**不在这里解析**：它没有对应的预设，
     * 会落到 [ChatBackground.DEFAULT]。要判断图片请用 [isCustomImage]。
     */
    fun of(id: String?): ChatBackground =
        presets.firstOrNull { it.id != null && it.id == id } ?: ChatBackground.DEFAULT

    /** 这个标识是不是一张自定义图片。 */
    fun isCustomImage(id: String?): Boolean = id != null && id.startsWith(CUSTOM_PREFIX)

    /** 自定义图片的本地路径；不是图片则 `null`（包括 `null` 标识与内置预设）。 */
    fun customPathOf(id: String?): String? =
        if (isCustomImage(id)) id!!.removePrefix(CUSTOM_PREFIX) else null

    /** 本地路径 → 存进 `Session.background` 的标识。与 [customPathOf] 互逆。 */
    fun customId(path: String): String = CUSTOM_PREFIX + path

    /**
     * 给界面看的一句话描述。
     *
     * 刻意收在这里而不是各页各写一段 `when`：对话设置页的入口行、
     * 背景选择页的当前项，说的是同一件事 —— 两处口径分家就会出现
     * "设置页说晨雾、选择页说默认"这种自相矛盾。
     */
    fun summaryOf(id: String?): String =
        if (isCustomImage(id)) "自定义图片" else of(id).label
}

/**
 * 遮罩不透明度的**默认值**（新会话与老数据的初值）。
 *
 * ⚠️ 真正生效的是**会话设置** `Session.scrimAlpha`（「对话设置 → 聊天背景」里能拉）。
 * 0.55 是"图还看得出、字一定念得清"的折中：更低一点，深色照片上的气泡开始糊；
 * 更高一点，图就等于没设。**它是按道理算的，不是看图定的**（本机无设备）——
 * 所以它必须是用户能调的，那个滑块就是为这件事存在的。
 */
const val DEFAULT_SCRIM_ALPHA = 0.55f

/**
 * 模糊半径的上限（dp）—— v0.61.57。
 *
 * 顶栏毛玻璃用 **10dp** 就已经"看不清文字轮廓"（见 `ChatScreen` 的注释），
 * 24dp 足以把任何背景图糊成色块。再高只是白烧 GPU —— `Modifier.blur` 是离屏渲染，
 * 每帧都要重做（滚动时尤其贵）。
 */
const val SCRIM_BLUR_MAX_DP = 24f

/**
 * 那层淡色蒙版的**上限**（v0.61.57）。
 *
 * 模糊接管了保读性的主力后，这层只做最后一道保险（高对比图纯模糊仍会干扰阅读）。
 * 0.35 是"够提亮、又不把图洗白"的量 —— 再高用户会觉得"图白选了"。
 */
const val SCRIM_TINT_MAX = 0.35f

/**
 * 「遮罩风格」选择器上那块小预览的**底**：中灰。
 *
 * ⚠️ 为什么必须用中灰而不是浅色：四种风格铺在浅底上**看起来是一样的**
 *（那正是本项目在"浅底上的半透明元素"上栽过的坑）。中灰底才分得出
 * "薄 / 厚 / 上下渐变 / 斜向高光"。
 *
 * 它只服务于这个预览，不属于产品色板 —— 所以单独命名，不要当主题色用。
 */
val ScrimPreviewBackdrop: Color = Color(0xFF6B7280)

/**
 * 背景遮罩的**风格**（对应 `Session.scrimStyle`）。
 *
 * ## ⚠️ 这里是"观感"，不是物理：**没有一层是真模糊**
 * 真正的高斯模糊（backdrop blur）要把下层内容抓成位图再模糊，Compose 里代价不小，
 * 本项目曾经那套实现（`LiquidGlassBottomBar` / `GlassDrawing`）**已经删了**。
 * 所以这四种风格的差别是**色与渐变的差别**：
 *
 * | 风格 | 做法 |
 * |---|---|
 * | 浅色 | 一层纯白（此前的既有行为） |
 * | 磨砂 | 白 + 略实（雾面比通透的玻璃更"闷"） |
 * | 玻璃拟态 | 白 + 自上而下的微弱渐变（有一点"厚度"） |
 * | 液态玻璃 | 白 + 斜向高光带 |
 *
 * 要做成**真模糊**得另开一轮（拿 `layer.toImageBitmap()` 截背景再
 * `RenderEffect.createBlurEffect`），而且只能在真机上判断值不值。
 *
 * ⚠️ 本机无设备，**四种风格的实际观感全部未经真机验证**。
 */
enum class ScrimStyle(val id: String, val label: String) {
    PLAIN(SCRIM_PLAIN, "浅色"),
    FROST(SCRIM_FROST, "磨砂"),
    GLASS(SCRIM_GLASS, "玻璃拟态"),
    LIQUID(SCRIM_LIQUID, "液态玻璃"),
    ;

    /**
     * 遮罩的色标（自上而下；单元素 = 纯色）。
     *
     * ⚠️ 每个色标的 alpha 都 `coerceIn(0f, 1f)` 夹住：风格里对用户给的 alpha 做了
     * 乘除（磨砂 ×1.10），滑块拉到 1.0 时不能溢出成非法 alpha（`Color.copy` 会抛）。
     *
     * ## ⚠️ v0.61.57：它现在只是**很淡的一层提亮**，不再是"遮罩浓度"本身
     * 用户要求「遮罩浓度改为高斯模糊程度」—— 保读性的主力已交给 [blurRadius]，
     * 这里只留最后一道保险（高对比图纯模糊仍会干扰阅读）。
     * 所以系数整体**大幅调低**（原来最高 ×1.15，现在最高 ×[SCRIM_TINT_MAX]）。
     *
     * @param alpha 用户设定的强度（0..1）—— 现在它同时驱动模糊与这层淡色
     */
    fun stops(alpha: Float): List<Color> {
        val a = alpha.coerceIn(0f, 1f)
        // 淡色上限：够提亮、又不至于把图"洗白"（那会让用户觉得图白选了）
        fun w(mul: Float) = Color.White.copy(alpha = (a * mul * SCRIM_TINT_MAX).coerceIn(0f, 1f))
        return when (this) {
            PLAIN -> listOf(w(1f))
            // 雾面：比通透的玻璃更"闷"一点
            FROST -> listOf(w(1.10f))
            // 上实下虚 —— 一点点"厚度"
            GLASS -> listOf(w(1.08f), w(0.92f))
            // 上下各留一道虚、中间实，斜着铺出来就是"高光带"
            LIQUID -> listOf(w(0.90f), w(1.15f), w(0.90f))
        }
    }

    /**
     * **模糊半径**（dp）—— v0.61.57 新增，遮罩强度的主要载体。
     *
     * ⚠️ 与 [stops] 共用同一个 `alpha`：用户拉一个滑块，模糊与淡色**一起**变 ——
     *    界面上只有一个"强度"，不该拆成两个旋钮让他自己配（那必然配出难看的组合）。
     *
     * ⚠️ 上限 [SCRIM_BLUR_MAX_DP] 取 **24dp**：顶栏毛玻璃用 10dp 就已经"看不清文字轮廓"，
     *    24dp 足以把任何背景图糊成色块 —— 再高只是白烧 GPU（`Modifier.blur` 是离屏渲染）。
     *
     * ⚠️ 各风格给不同的**基数**：这是它们之间唯一还剩的视觉差异
     *   （原来靠色的铺法区分，现在模糊是主力）。
     *   · PLAIN  → 0：**浅色风格不模糊**（用户选它就是想要图清楚）
     *   · FROST  → 1.0：磨砂 —— 标准模糊
     *   · GLASS  → 0.8：玻璃拟态 —— 留一点图的可辨识度
     *   · LIQUID → 1.2：液态玻璃 —— 最糊（它本来就是"隔着水看"）
     */
    fun blurRadius(alpha: Float): Float {
        val a = alpha.coerceIn(0f, 1f)
        val base = when (this) {
            PLAIN -> 0f
            FROST -> 1.0f
            GLASS -> 0.8f
            LIQUID -> 1.2f
        }
        return a * base * SCRIM_BLUR_MAX_DP
    }

    companion object {
        /** 供界面按顺序铺出全部选项。 */
        val presets: List<ScrimStyle> = entries.toList()

        /**
         * 标识 → 风格。**未知一律回落 [PLAIN]** —— 与背景预设同一条纪律：
         * 这个值来自数据库，可能比当前版本的列表更旧，解析失败不该让整页打不开。
         */
        fun of(id: String?): ScrimStyle = entries.firstOrNull { it.id == id } ?: PLAIN
    }

    /**
     * 这个风格**怎么画**（给定不透明度）。
     *
     * ⚠️ 它是渲染与"风格选择器上的小预览"**共用**的唯一来源 ——
     * 两处各写一份画笔必然会漂移，然后用户就会看到"选的时候是磨砂、进去是浅色"。
     * 这正是本项目反复吃亏的那类问题（设置页说晨雾、选择页说默认）。
     */
    fun brush(alpha: Float): Brush {
        val stops = stops(alpha)
        return when {
            stops.size == 1 -> SolidColor(stops.first())
            // 液态玻璃的"高光带"是斜的 —— 默认 linearGradient 走对角线
            this == LIQUID -> Brush.linearGradient(stops)
            else -> Brush.verticalGradient(stops)
        }
    }
}
