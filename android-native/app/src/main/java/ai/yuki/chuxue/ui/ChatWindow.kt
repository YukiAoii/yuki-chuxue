package ai.yuki.chuxue.ui

import ai.yuki.chuxue.data.ChatMessage

/**
 * 聊天列表的**可见窗口**与**滚动跟随**（纯函数，可单测 —— 见 `ChatWindowTest`）。
 *
 * ## 为什么要"窗口"这一层
 * 消息一多，`LazyColumn` 每次要处理全部条目 —— 卡的是**渲染**，不是数据存取。
 * 所以界面只渲染**最近 N 条**，往上翻再往前放一批。
 *
 * ## ⚠️ 它**绝不改变**发给模型的内容（这是本项目最容易踩穿的一条线）
 * `Session.messages` 仍然是**全量**，`PromptEngine.plan(history = session.messages)`
 * 一个字都没变 —— 窗口只是**渲染这一侧**的事。
 * 哪天要是有人图省事，把"窗口里的消息"当成"历史"传进请求，那就是**上下文断裂**：
 * 模型只会看到最近 50 条，前面聊过的一概不认，而且**不报错**。
 *
 * ## 为什么不用 androidx.paging
 * 项目一贯不引文档示例里的重依赖（HANDOFF 里记着：文档假设了 Hilt / WorkManager / Coil
 * 这些本项目没有的东西）。而这里的诉求只是"滑到顶部再放一批" ——
 * 一个窗口计数 + 一次切片就够了，用不上 `Pager` / `RemoteMediator` 那一整套。
 */
object ChatWindow {

    /** 一段对话初次进入时渲染多少条；每次"加载更早"也放这么多。 */
    const val PAGE = 50

    /** 按"已放多少条"算出实际该渲染多少条 —— 不能超过总数，也不能是负数。 */
    fun visibleCount(total: Int, loaded: Int): Int =
        loaded.coerceAtMost(total).coerceAtLeast(0)

    /**
     * 列表长度变化时，要不要**跟随滚到底**。
     *
     * ⚠️ 这是分页带来的必答题：原来靠"`messages.size` 变了"就滚到底，
     * 而**往上加载更早的记录同样会让 size 变大** —— 不区分的话，
     * 用户每翻一次旧记录就被拽回底部一次（计划里点名要先拆的就是这个坑）。
     *
     * 所以判据从"长度变了"收紧成"**尾部追加了**才跟随"：
     * [appendedAtEnd] 由调用方给出（新消息/流式增量 = true；加载更早 = false）。
     */
    fun shouldFollowToBottom(prevTotal: Int, newTotal: Int, appendedAtEnd: Boolean): Boolean =
        appendedAtEnd && newTotal > prevTotal

    /**
     * 加载更早的记录之后，顶部那条**应该落在哪个索引** —— 用来保持画面不动。
     *
     * 例：加载前窗口是"最近 50 条"，其中第 3 条在屏幕顶部；又往前放了 50 条，
     * 那第 3 条在新列表里的下标就是 53 —— 把滚动放到 53，画面与加载前**逐像素相同**。
     *
     * ⚠️ 少了这一步，列表会在每次"加载更早"之后**跳一段**（最直观的症状是：
     * 用户明明没动，内容却突然往下窜了一屏）。
     *
     * @param firstVisibleBefore 加载前顶部那条**在旧窗口里的下标**
     * @param addedBefore 这次往前面放了多少条
     */
    fun anchorAfterPrepend(firstVisibleBefore: Int, addedBefore: Int): Int =
        (firstVisibleBefore + addedBefore).coerceAtLeast(0)

    /**
     * 加载更早的记录之后，顶部那个 item 的新位置 —— **精确版**。
     *
     * ## ⚠️ [anchorAfterPrepend] 漏了什么
     * 它算的是 `原位置 + 新增条数`，这**默认了列表头部 item 数不变**。
     * 可顶部那个 `loading-earlier` 指示器是条件存在的：把全部记录都放出来之后
     * `hasEarlier` 翻成 false → 指示器消失 → 它后面所有 item 位置整体 -1。
     * 于是按旧公式滚过去会**偏一条** —— 表现正是用户说的"往回弹的迹象"。
     *
     * ## 为什么这么算
     * 先把"位置"翻译成"窗口内偏移"（减掉头部 item 数），加上新塞进来的条数，
     * 再加回**加载之后**的头部 item 数。
     *
     * @param firstVisiblePositionBefore 加载前顶部那条在**整个列表**里的位置
     *                                   （就是 `listState.firstVisibleItemIndex`，含头部 item）
     * @param headerBefore 加载前列表头部有几个非消息 item（加载指示器算 1，否则 0）
     * @param addedBefore  这次往前面放了多少条消息
     * @param headerAfter  加载后头部有几个非消息 item
     */
    fun positionAfterPrepend(
        firstVisiblePositionBefore: Int,
        headerBefore: Int,
        addedBefore: Int,
        headerAfter: Int,
    ): Int =
        (firstVisiblePositionBefore - headerBefore + addedBefore + headerAfter).coerceAtLeast(0)

    /**
     * **最终判据**：这一刻到底要不要把列表滚到底。
     *
     * ⚠️ 为什么在 [shouldFollowToBottom] 之上还要一条：
     * 那条只回答"这次变长是不是尾部追加"，它**不知道用户此刻在看哪里**。
     * v0.45.x 把它直接接到 `LaunchedEffect` 上，再叠一句"键盘弹着就补滚一次"，
     * 结果那道"不跟随"的闸门写成了 `imeHeight == 0` 的合取项 ——
     * **只要键盘弹着，闸门就失效**，于是往上翻历史、加载更早、甚至键盘动画
     * 让 `imeHeight` 抖一下，都会被无条件拽回底部。
     * 用户报的"无法翻阅历史、被强制弹回最底部"就是这条。
     *
     * 所以把"要不要跟随"收进纯函数，让**用户意图**成为一等公民：
     *
     * - 首帧（还没定位过）→ 跟随：进入会话就该停在最新一条；
     * - [forced]（刚发出自己的消息、或主动跳转）→ 跟随：
     *   哪怕他此刻正翻在上面，自己发的消息也必须让他看见；
     * - 否则**只要用户已经翻离底部，一律不跟随** —— 这是本条的核心。
     *   在读历史时被拽走，比"少跟随一次"糟得多；
     * - 其余情况（尾部追加 / 正在流式）且人在底部 → 跟随。
     *
     * ⚠️ 键盘（IME）**不是**这条判据的输入：键盘弹起只改变可用高度，
     * 它从不意味着"用户想看最新一条"。键盘那点收尾交给单独的 effect，
     * 且同样要先过 `nearBottom`。
     *
     * @param firstScrollDone 这一次会话是否已经做过首帧定位
     * @param appendedAtEnd   这次变长是不是"尾部追加了新消息"（见 [shouldFollowToBottom]）
     * @param streaming       此刻是否有流式气泡挂在列表末尾
     * @param nearBottom      用户当前位置是否已经贴着底部
     * @param forced          是否是一次"必须让他看见"的动作（自己发消息 / 主动跳转）
     */
    fun decideFollowToBottom(
        firstScrollDone: Boolean,
        appendedAtEnd: Boolean,
        streaming: Boolean,
        nearBottom: Boolean,
        forced: Boolean = false,
    ): Boolean = when {
        !firstScrollDone -> true
        forced -> true
        !nearBottom -> false
        appendedAtEnd || streaming -> true
        else -> false
    }

    /**
     * 列表**内容自己变高**了（表情包图片解码完成）之后，要不要把画面贴回底部。
     *
     * ## ⚠️ 这条判据是后补的，它修的是"翻历史还是被拽回底部"
     * `EmojiBubble` 的 `onLoaded` 原本无条件滚到底（只挡了键盘一维）：
     *
     * ```kotlin
     * onLoaded = { if (imeHeight == 0) listState.animateScrollToItem(msgs.lastIndex) }
     * ```
     *
     * 于是**每张表情包解码完成都会拽一次**。用户往上翻历史时，
     * 新的表情包进入视野 → 解码 → 回调触发 → 被拉回最新一条。
     * 这与 [decideFollowToBottom] 修的是同一个毛病，只是发生在另一条路径上 ——
     * **"内容变高了"从来不等于"用户想看最新一条"**。
     *
     * @param nearBottom 用户当前位置是否已经贴着底部
     * @param imeOpen    键盘是否弹起。弹着时这一下**不做** ——
     *                   键盘那条 effect 会在收尾时负责吸附，两边一起动会打架。
     */
    fun shouldStickAfterGrow(nearBottom: Boolean, imeOpen: Boolean): Boolean =
        nearBottom && !imeOpen

    /**
     * **流式输出中**每来一块增量，要不要把画面带到最新内容（v0.61.7）。
     *
     * ## 它修的是什么
     * 「AI 输出时自动跟随底部」此前**没有真正生效**：那条跟随 effect 的 key 是
     * 消息数与"有没有流式气泡" —— 而流式**过程中**这两者都不变
     *（增量不改变 `messages.size`，流式气泡从头到尾就挂在列表末尾）。
     * 于是判据再对也没用：**它根本不会被唤起**。
     * 所以流式跟随必须挂在"内容本身变长"上（调用方用增量长度当 key）。
     *
     * ## 为什么与 [decideFollowToBottom] 分开
     * 那条管"尾部追加 / 进会话"这类**离散**事件；这一条管**每一块增量**
     *（频率高得多，一块几十毫秒），所以判据要更保守：
     *
     * - [nearBottom]：人翻在上面读历史 → 绝不打扰（既有纪律，别退回去）；
     * - [scrollInProgress]：**用户自己的滚动还没停**（含惯性滑动）→ 让位 ——
     *   这条正是用户说的「不和用户做竞争」；
     * - [userTouching]：手指正按着列表（滑动起点、或只是点一下）→ 同样让位。
     *
     * ⚠️ 键盘**不是**这一条的输入：流式期间键盘开着是常态（刚发完消息），
     * 拿它当闸门会让跟随整个失效（历史上正是这么坏过一次，见
     * [decideFollowToBottom] 的注释）。
     */
    fun shouldFollowStreaming(
        nearBottom: Boolean,
        userTouching: Boolean,
        scrollInProgress: Boolean,
    ): Boolean = nearBottom && !userTouching && !scrollInProgress

    /**
     * 一条消息在**整段对话里的绝对下标** —— 拿它当 LazyColumn 的 `key`。
     *
     * ## ⚠️ 为什么消息 item 必须有一个稳定 key（2026-09-29）
     * 列表原来写的是 `itemsIndexed(msgs) { index, msg -> ... }`，**没有 key** ——
     * 于是 LazyColumn 只能用**位置**来认 item。
     *
     * 而列表顶部那个 `loading-earlier` 指示器是**条件存在**的（由 `hasEarlier` 决定）。
     * 加载更早的记录之后 `hasEarlier` 可能翻成 false → 指示器消失 →
     * 它后面**每一条消息的位置都 -1**。对"按位置认人"的列表来说，这**不是**
     * "整体上移一格"，而是"**每一条都换成了另一条**"→
     * 全部 item 重新组合（卡顿）+ 滚动位置错乱（回弹）。
     *
     * 用户报的「历史消息**概率性**弹回、滑动还卡顿」正是这里 ——
     * "概率性"来自 `hasEarlier` 会不会翻转（取决于 `loaded` 与总条数）。
     *
     * ## 为什么绝对下标是稳定的
     * 本项目有一条红线：**`messages` 写入后不改写、只追加**（改历史会让缓存前缀崩）。
     * 于是任一条消息在 `messages` 里的下标**永远不会变**。
     * 而渲染窗口是 `messages.takeLast(n)` —— 尾部切片，切点变化不影响元素下标。
     *
     * @param total         整段对话的条数（`allMessages.size`）
     * @param windowSize    当前窗口条数（`msgs.size`）
     * @param indexInWindow 它在窗口里的下标（`itemsIndexed` 给的那个）
     */
    fun absoluteMessageIndex(total: Int, windowSize: Int, indexInWindow: Int): Int =
        (total - windowSize) + indexInWindow

    /**
     * 顶部那条**「加载更早」指示器该不该出现**（v0.50.5）。
     *
     * ## 它修的是什么
     * 用户说"聊天记录分页加载没实现"、"从来没看到过任何加载提示" ——
     * 而分页**是实现了的**（见本对象顶部注释；v0.45.0 起就在）。
     * 他看不到，是因为指示器只在 `总数 > 已加载` 时为真，
     * 而一页就是 [PAGE] 条 —— **消息不到 51 条的会话永远不会出现它**。
     * 这不是"没做"，是"条件没满足"，外加指示器文案写着「正在读取记忆…」认不出来。
     *
     * 提成纯函数的好处：判据可单测，且界面与测试引用**同一个**定义，
     * 不会各写一份而慢慢漂移。
     */
    fun showEarlierIndicator(total: Int, loaded: Int): Boolean =
        total > visibleCount(total, loaded)
}

/**
 * 聊天列表里的一行（v0.61.23 · 表情包重写）。
 *
 * ## 为什么要"行"这一层
 * 表情包原先贴在消息 item 内部 —— 图异步解码后整条 item 突然变高，
 * 把列表往下推（历史上为它写了一串"滚到底"补丁）。改成**独立一行**之后，
 * 每行的测量互不影响：表情包行自己长高，消息行的布局纹丝不动。
 *
 * ⚠️ 它是**渲染侧的行**，不是消息：一行要么是某条消息（[isEmoji] = false），
 *    要么是"挂在某条消息后面的表情包"（[isEmoji] = true，[msgIndex] 指回它的主人）。
 *    数据层一个字都没改（`emojiPath` 仍挂在消息行上，不进请求体）。
 *
 * ⚠️ 它使**行数 ≠ 消息数** —— 一切"按 item 位置"算的地方（滚动锚点、key）
 *    必须经过这个映射，别再直接拿消息下标当 item 下标（`ChatRowsTest` 钉着这条）。
 */
data class ChatRow(
    val msgIndex: Int,
    val isEmoji: Boolean,
)

/**
 * 消息列表 → 聊天列表行的映射（纯函数）。
 *
 * 规则：每条消息一行；它若带了表情包（[ChatMessage.emojiPath] 非空白），
 * **紧跟其后**再多一行。空串按"没有"处理 —— 老 / 边界数据里出现过空串，
 * 空串不该产出一个空图行。
 */
fun buildChatRows(messages: List<ChatMessage>): List<ChatRow> =
    messages.flatMapIndexed { i, m ->
        buildList {
            add(ChatRow(i, isEmoji = false))
            if (!m.emojiPath.isNullOrBlank()) add(ChatRow(i, isEmoji = true))
        }
    }
