package ai.yuki.chuxue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天列表的可见窗口与滚动跟随契约。
 *
 * ⚠️ 包名是 `ai.yuki.chuxue.ui`（不是 `ui.chat` 之类）—— `ChatScreen.kt` 自己的包声明
 * 就是它；新建同包测试前先看主源码的 `package` 行，这一步我踩过一次。
 *
 * 这三条里最要紧的是 [ChatWindow.shouldFollowToBottom]：
 * 分页上线后，"往上加载更早"与"新消息到达"都会让列表变长 ——
 * 判错方向的话，用户每翻一次历史就被拽回底部一次。
 */
class ChatWindowTest {

    /* ─────────── 渲染窗口 ─────────── */

    @Test
    fun `总数少于窗口时就渲染全部`() {
        assertEquals(30, ChatWindow.visibleCount(total = 30, loaded = 50))
    }

    @Test
    fun `总数多于窗口时只渲染窗口那么多`() {
        assertEquals(50, ChatWindow.visibleCount(total = 500, loaded = 50))
    }

    @Test
    fun `加载更多之后窗口变大，但仍不超过总数`() {
        assertEquals(100, ChatWindow.visibleCount(total = 500, loaded = 100))
        assertEquals(120, ChatWindow.visibleCount(total = 120, loaded = 200))
    }

    @Test
    fun `空会话与异常入参都不会算出负数`() {
        assertEquals(0, ChatWindow.visibleCount(total = 0, loaded = 50))
        assertEquals(0, ChatWindow.visibleCount(total = 10, loaded = -5))
    }

    /* ─────────── 要不要跟随滚到底（本波的核心） ─────────── */

    @Test
    fun `尾部追加新消息时跟随到底`() {
        assertTrue(ChatWindow.shouldFollowToBottom(prevTotal = 10, newTotal = 11, appendedAtEnd = true))
    }

    @Test
    fun `往上加载更早的记录时**不**跟随 —— 这是分页最核心的一条`() {
        // 列表同样变长了，但那是"前面放了一批"，不是"她说了新话"。
        // 不区分的话，用户每翻一次旧记录就被拽回底部。
        assertFalse(ChatWindow.shouldFollowToBottom(prevTotal = 10, newTotal = 60, appendedAtEnd = false))
    }

    @Test
    fun `长度没变就不跟随（例如只是重算了一遍列表）`() {
        assertFalse(ChatWindow.shouldFollowToBottom(prevTotal = 10, newTotal = 10, appendedAtEnd = true))
    }

    @Test
    fun `切换会话导致的长度变化不由这条判定负责`() {
        // 那是"首帧瞬时定位"那条 effect 的事（见 ChatScreen 里那段注释）。
        // 这里只保证：长度变小（比如切到更短的会话）不会被当成"跟随到底"。
        assertFalse(ChatWindow.shouldFollowToBottom(prevTotal = 60, newTotal = 10, appendedAtEnd = true))
    }

    /* ─────────── 加载后保持画面不动 ─────────── */

    @Test
    fun `往前放了多少条，顶部索引就往后挪多少`() {
        // 加载前第 3 条在顶部；往前放了 50 条之后，它变成了第 53 条
        assertEquals(53, ChatWindow.anchorAfterPrepend(firstVisibleBefore = 3, addedBefore = 50))
    }

    @Test
    fun `没往前放东西时索引不变`() {
        assertEquals(7, ChatWindow.anchorAfterPrepend(firstVisibleBefore = 7, addedBefore = 0))
    }

    @Test
    fun `异常入参不会算出负索引`() {
        assertEquals(0, ChatWindow.anchorAfterPrepend(firstVisibleBefore = -3, addedBefore = 0))
    }

    /* ─────────── 最终判据：用户意图优先（v0.50.x 修「被强制弹回底部」） ───────────
     *
     * 上面那组 `shouldFollowToBottom` 全绿，用户却仍在报"翻历史被拽回底部"——
     * 因为它只回答"这次变长是不是尾部追加"，不知道**用户此刻在看哪里**。
     * 下面这组盯的就是那条：**人在上面时，任何自动跟随都必须让路。**
     */

    @Test
    fun `首帧必须定位到底部 —— 进入会话就该停在最新一条`() {
        assertTrue(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = false, appendedAtEnd = false,
                streaming = false, nearBottom = false,
            ),
        )
    }

    @Test
    fun `用户翻离底部时，尾部追加新消息也不跟随 —— 本条是修复的核心`() {
        // 之前 `imeHeight != 0` 会绕开闸门，于是这一格实际返回了 true。
        assertFalse(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = true,
                streaming = false, nearBottom = false,
            ),
        )
    }

    @Test
    fun `用户翻离底部时，正在流式也不跟随 —— 否则她会把你的旧记录顶走`() {
        assertFalse(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = false,
                streaming = true, nearBottom = false,
            ),
        )
    }

    @Test
    fun `自己发消息时，哪怕人翻在上面也必须跟随`() {
        assertTrue(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = true,
                streaming = false, nearBottom = false, forced = true,
            ),
        )
    }

    @Test
    fun `人在底部且有新消息 → 跟随`() {
        assertTrue(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = true,
                streaming = false, nearBottom = true,
            ),
        )
    }

    @Test
    fun `人在底部且正在流式 → 跟随`() {
        assertTrue(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = false,
                streaming = true, nearBottom = true,
            ),
        )
    }

    @Test
    fun `人在底部但没有新东西 → 不跟随（别打断正在进行的滚动手势）`() {
        assertFalse(
            ChatWindow.decideFollowToBottom(
                firstScrollDone = true, appendedAtEnd = false,
                streaming = false, nearBottom = true,
            ),
        )
    }

    @Test
    fun `键盘这一维不进判据 —— 同一个输入换键盘状态不该改变结论`() {
        // decideFollowToBottom 根本没有键盘参数，这条断言把"不许再加回去"钉死：
        // 键盘弹起只改变可用高度，它从不意味着"用户想看最新一条"。
        val a = ChatWindow.decideFollowToBottom(
            firstScrollDone = true, appendedAtEnd = false,
            streaming = true, nearBottom = false,
        )
        val b = ChatWindow.decideFollowToBottom(
            firstScrollDone = true, appendedAtEnd = false,
            streaming = true, nearBottom = false,
        )
        assertEquals(a, b)
        assertFalse(a)
    }
    /* ─────────── 内容自己变高之后要不要贴回底部（v0.50.x 第二次修"翻历史被弹回"） ───────────
     *
     * 上一轮修的是「列表变长」那条路径。用户回来说**还是**会被拽回去 ——
     * 因为还有另一条：表情包图片解码完成时的 `onLoaded` 在无条件滚到底。
     * 它连"这次变长是不是尾部追加"都没问，只看键盘。
     *
     * 这组用例盯住那条路径：**"内容变高了"从来不等于"用户想看最新一条"。**
     */

    @Test
    fun `人在底部、图解码完了 → 贴回底部（这是 onLoaded 的原本意图）`() {
        assertTrue(ChatWindow.shouldStickAfterGrow(nearBottom = true, imeOpen = false))
    }

    @Test
    fun `人翻在上面时，图解码完也不许贴底 —— 本条是第二次修复的核心`() {
        // 这条以前是 true（只挡了键盘），于是用户每翻到一张表情包就被拽回最新一条
        assertFalse(ChatWindow.shouldStickAfterGrow(nearBottom = false, imeOpen = false))
    }

    @Test
    fun `键盘弹着时不贴底 —— 交给键盘那条 effect，两边一起动会打架`() {
        assertFalse(ChatWindow.shouldStickAfterGrow(nearBottom = true, imeOpen = true))
    }

    @Test
    fun `两个条件都不满足时当然不贴`() {
        assertFalse(ChatWindow.shouldStickAfterGrow(nearBottom = false, imeOpen = true))
    }
    /* ─────────── 消息 item 的稳定 key（v0.50.x 第三次修"概率性回弹 + 卡顿"） ───────────
     *
     * 前两轮修的都是"主动滚到底"的调用点。这一轮找到的是**结构性问题**：
     * 消息 item 没有 key，而列表顶部的「加载更早」指示器条件存在 ——
     * 它一消失，后面每条消息位置 -1，无 key 的列表就认为"每条都换了"，
     * 于是全量重组（卡顿）+ 滚动错乱（回弹）。
     */

    @Test
    fun `窗口等于全量时，绝对下标就是窗口内下标`() {
        // msgs == allMessages 的常见情形（条数还没超过一页）
        assertEquals(0, ChatWindow.absoluteMessageIndex(total = 12, windowSize = 12, indexInWindow = 0))
        assertEquals(11, ChatWindow.absoluteMessageIndex(total = 12, windowSize = 12, indexInWindow = 11))
    }

    @Test
    fun `窗口是全量尾部切片时，绝对下标要补上被切掉的那一段`() {
        // 总共 200 条，只渲染最后 50 条 → 窗口第 0 条其实是全量的第 150 条
        assertEquals(150, ChatWindow.absoluteMessageIndex(total = 200, windowSize = 50, indexInWindow = 0))
        assertEquals(199, ChatWindow.absoluteMessageIndex(total = 200, windowSize = 50, indexInWindow = 49))
    }

    @Test
    fun `加载更早之后，同一条消息的绝对下标不变 —— 这正是它适合当 key 的原因`() {
        // 200 条里，第 180 条这条消息。
        // 窗口 50 条时它在窗口内下标 30；加载更早、窗口变 100 条后它在下标 80。
        assertEquals(180, ChatWindow.absoluteMessageIndex(total = 200, windowSize = 50, indexInWindow = 30))
        assertEquals(180, ChatWindow.absoluteMessageIndex(total = 200, windowSize = 100, indexInWindow = 80))
    }

    @Test
    fun `追加新消息后，仍留在窗口里的那条消息绝对下标不变`() {
        // ⚠️ 这里要盯的是"**同一条消息**的身份"，所以必须选一条两次都还在窗口里的。
        //    尾部切片在追加新消息时会**右移**，被切掉的那些已不在窗口内 ——
        //    拿它们做断言会算出一个不相干的数（我第一次就写错了）。
        //
        // 全量 156 这条消息：
        //   · 全量 200、窗口 50 时，它在窗口下标 6（150 + 6）
        //   · 新增 3 条后（全量 203、窗口 50），它在窗口下标 3（153 + 3）
        assertEquals(156, ChatWindow.absoluteMessageIndex(total = 200, windowSize = 50, indexInWindow = 6))
        assertEquals(156, ChatWindow.absoluteMessageIndex(total = 203, windowSize = 50, indexInWindow = 3))
    }

    /* ─────────── 锚点补偿要算上指示器的增减 ─────────── */

    @Test
    fun `头部 item 数不变时，与旧公式结果一致`() {
        // 旧公式：位置 + 新增条数
        assertEquals(
            ChatWindow.anchorAfterPrepend(firstVisibleBefore = 3, addedBefore = 50),
            ChatWindow.positionAfterPrepend(
                firstVisiblePositionBefore = 3, headerBefore = 1, addedBefore = 50, headerAfter = 1,
            ),
        )
    }

    @Test
    fun `指示器消失时，新位置要比旧公式少一条 —— 这正是"往回弹"的来源`() {
        // 加载前头部有指示器(1)，加载后全部放出来、指示器消失(0)
        val precise = ChatWindow.positionAfterPrepend(
            firstVisiblePositionBefore = 3, headerBefore = 1, addedBefore = 50, headerAfter = 0,
        )
        assertEquals(52, precise)
        // 旧公式会给 53 —— 多一条，画面因此往下偏一格
        assertEquals(53, ChatWindow.anchorAfterPrepend(firstVisibleBefore = 3, addedBefore = 50))
    }

    @Test
    fun `头部本来就没有指示器时也不出错`() {
        assertEquals(
            7,
            ChatWindow.positionAfterPrepend(
                firstVisiblePositionBefore = 7, headerBefore = 0, addedBefore = 0, headerAfter = 0,
            ),
        )
    }

    @Test
    fun `异常入参不会算出负位置`() {
        assertEquals(
            0,
            ChatWindow.positionAfterPrepend(
                firstVisiblePositionBefore = 0, headerBefore = 5, addedBefore = 0, headerAfter = 0,
            ),
        )
    }

    /* ─────────── 顶部「加载更早」指示器何时出现（v0.50.5） ───────────
     *
     * 用户说"聊天记录分页加载没实现""从来没看到过任何加载提示"。
     * 分页其实早就实现了 —— 他看不到是因为**消息不到一页**时指示器根本不存在。
     * 这组用例把那条边界钉死，免得下次又被读成"没做"。
     */

    @Test
    fun `消息多于一页 → 指示器出现`() {
        assertTrue(ChatWindow.showEarlierIndicator(total = 120, loaded = 50))
    }

    @Test
    fun `消息不到一页 → 指示器不出现 —— 用户"看不到加载提示"的直接原因`() {
        // 一页 50 条。30 条的会话根本没有"更早"可加载，所以看不到任何提示。
        assertFalse(ChatWindow.showEarlierIndicator(total = 30, loaded = 50))
    }

    @Test
    fun `刚好一页 → 也没有更早的`() {
        assertFalse(ChatWindow.showEarlierIndicator(total = 50, loaded = 50))
    }

    @Test
    fun `全部放出来之后 → 指示器消失`() {
        assertFalse(ChatWindow.showEarlierIndicator(total = 120, loaded = 120))
        // 加载量超过总数也算"全出来了"
        assertFalse(ChatWindow.showEarlierIndicator(total = 120, loaded = 999))
    }

    /* ─────────── 流式输出中的跟随（v0.61.7）───────────
     *
     * 用户要求：「AI 输出时自动滚动到底部……用户在输出时滑动屏幕不和用户做竞争」。
     * 这组把"每一块增量到达时要不要贴底"的判据钉死 —— 它是**高频**路径
     *（一块几十毫秒），所以比 [decideFollowToBottom] 更保守。
     */

    @Test
    fun `流式增量：人在底部、手没碰屏幕 → 跟随`() {
        assertTrue(
            ChatWindow.shouldFollowStreaming(
                nearBottom = true,
                userTouching = false,
                scrollInProgress = false,
            ),
        )
    }

    @Test
    fun `流式增量：人翻离底部 → 不跟随（别把他正在读的记录顶走）`() {
        assertFalse(
            ChatWindow.shouldFollowStreaming(
                nearBottom = false,
                userTouching = false,
                scrollInProgress = false,
            ),
        )
    }

    @Test
    fun `流式增量：手指正按着列表 → 让位 —— 「不和用户做竞争」`() {
        assertFalse(
            ChatWindow.shouldFollowStreaming(
                nearBottom = true,
                userTouching = true,
                scrollInProgress = false,
            ),
        )
    }

    @Test
    fun `流式增量：用户自己的滚动还没停（含惯性）→ 让位`() {
        assertFalse(
            ChatWindow.shouldFollowStreaming(
                nearBottom = true,
                userTouching = false,
                scrollInProgress = true,
            ),
        )
    }
}