package ai.yuki.chuxue.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 「Yuki 初雪」动效系统（开发文档 §30）。
 *
 * 设计原则（§28.1）：**一致动效** —— 所有转场使用统一的缓动曲线与时长，
 * 不在各组件里另起一套数字。
 */

/* ═══════════════ 缓动曲线（§30.1）═══════════════ */

/** 标准缓动：绝大多数转场、微交互 */
val StandardEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/** 强调缓动：启动动画、登录过渡等需要"有分量"的场合 */
val EmphasizedEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/** 退场缓动：元素离场 */
val ExitEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

/* ═══════════════ 时长（毫秒，§30.2 / §47.2）═══════════════ */

object YukiDuration {
    /** 微交互（按钮反馈、点击态） */
    const val Micro = 150

    /** 消息气泡出现 */
    const val MessageBubble = 200

    /** 标签文字渐显（液态玻璃） */
    const val LabelFade = 200

    /** 列表项插入 / Tab 切换 */
    const val ListItem = 250
    const val TabSwitch = 250

    /**
     * Tab 内容**离场**的时长（v0.61.0）。
     *
     * ⚠️ 刻意比 [TabSwitch] **短得多**：离场那棵子树在动画期间与进场那棵**同时存在**，
     * 两个重页面叠在一起就会掉帧（用户 2026-10-02：「底部导航栏切换界面有时候会有一点卡顿」）。
     * 缩短它 = 缩短"双份布局 + 双份绘制"的窗口。
     */
    const val TabLeave = 120

    /** 页面转场（进入子页面 / 返回） */
    const val PageTransition = 350

    /** 登录过渡 */
    const val LoginTransition = 600

    /** 启动动画（Logo 淡入 → 缩放 → 文字淡入） */
    const val Splash = 1200

    /** 启动动画内部段落：Logo 淡入 / Logo 缩放 / 文字淡入各占一段 */
    const val SplashSegment = 400

    /** 液态玻璃：呼吸脉动一个来回 */
    const val GlassBreath = 2400

    /** 流式回复的光标闪烁半周期 */
    const val CursorBlink = 600
}

/* ═══════════════ 位移量（§30.3 转场位移）═══════════════ */

object YukiMotion {
    /** Tab 切换时的轻微位移 */
    const val TabShiftDp = 8

    /** 启动页文字淡入的起始位移 */
    const val SplashShiftDp = 16
}
