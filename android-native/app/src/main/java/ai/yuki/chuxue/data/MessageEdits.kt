package ai.yuki.chuxue.data

/**
 * 历史编辑操作（长按消息菜单 / 重新生成）。
 *
 * ## ⚠️ 这一组操作与项目的缓存铁律**直接冲突**，且是有意为之
 * `架构红线文档` 的三铁律之一是「**已写入的内容不再改写，新内容只追加**」——
 * 那是缓存能命中的前提。而「删除某条消息」与「重新生成」**本质上就是改写历史**：
 * 改动点之后的前缀全部失配，下一轮的输入 token 会按未命中的价格计费。
 *
 * 之所以还是做：它们是常规聊天功能（微信也有），用户明确要求。
 * 处理方式与文档 §3.5「改全局前缀必须警告用户」一致 —— **做，但让用户知道代价**。
 * 界面上的告知在 `ChatScreen`，这里只保证操作本身是纯函数、可测、不越界。
 *
 * ## 为什么单独抽成对象而不是写在 ViewModel 里
 * 这三条都是「列表 → 列表」的纯变换，边界（越界、空列表、连续助手消息）很多，
 * 放在 ViewModel 里就只能靠真机点出来。抽出来之后每条边界都能钉住。
 */
object MessageEdits {

    /**
     * 删掉第 [index] 条消息。
     *
     * 越界时**原样返回**而不是抛异常或删错一条 —— 长按菜单与真实下标之间
     * 可能因为列表刷新而错位，宁可什么都不做。
     *
     * ⚠️ **当前没有生产调用方**（v0.61.14 起删除入口是 [deleteExchangeAt] ——
     * 长按**任意**一枚她的气泡都能删那一轮）：
     * 只删最新一轮 —— 那样前缀不破、缓存不花代价）。留着它是因为它是一条**有测试的
     * 通用变换**，而且"删任意一条"将来若重新开放，边界（越界/空列表）已经钉好了。
     */
    fun deleteAt(messages: List<ChatMessage>, index: Int): List<ChatMessage> {
        if (index !in messages.indices) return messages
        return messages.filterIndexed { i, _ -> i != index }
    }

    /** 能不能重新生成：历史最后一条得是她的回复（否则没有"可换的那条"）。 */
    fun canRegenerate(messages: List<ChatMessage>): Boolean =
        messages.lastOrNull()?.role == "assistant"

    /**
     * 是不是「Ta 主动说的话」（v0.61.54）。
     *
     * 主动消息**没有配对的 user 消息**，不参与"最新一轮"的配对。
     * 删除 / 重新生成 / 压缩跳转都必须跳过它，否则会把主动消息
     * 误当成"最新一轮回复"删掉、或让它吃掉重新生成的配对位。
     */
    fun isProactive(messages: List<ChatMessage>, index: Int): Boolean =
        messages.getOrNull(index)?.let {
            it.role == "assistant" && it.sendMode == SEND_MODE_PROACTIVE
        } ?: false

    /**
     * 为重新生成做准备：去掉**尾部连续**的助手消息。
     *
     * 为什么是"连续的多条"而不是"最后一条"：如果只删一条，历史里可能留下
     * 更早的一条孤儿回复（崩溃恢复、或重试插入都可能造成），
     * 重生成后看起来就像"她说了两遍"。
     */
    fun dropLastAssistant(messages: List<ChatMessage>): List<ChatMessage> {
        var end = messages.size
        while (end > 0 && messages[end - 1].role == "assistant") end--
        return messages.subList(0, end).toList()
    }

    /**
     * 从尾部剥掉**连续主动消息**（v0.61.54），返回第一个非主动下标。
     *
     * 主动消息没有配对的 user 消息，删除/重新生成时**先跳过它们**，
     * 否则会把 Ta 主动说的话误当成\"最新一轮回复\"。
     */
    fun trimTrailingProactive(messages: List<ChatMessage>): Int {
        var end = messages.size
        while (end > 0 && messages[end - 1].sendMode == SEND_MODE_PROACTIVE) end--
        return end
    }

    /**
     * **删掉最后一轮问答**（v0.61.9，用户要求）。
     *
     * ⚠️ v0.61.14 起**生产入口是 [deleteExchangeAt]**（长按任意一枚她的气泡都能删那一轮）；
     * 本函数只被测试引用，保留是因为它是"只删尾部"这条语义的**规格**——
     * 「尾部删除不动缓存前缀」这个结论正是靠它成立的。
     *
     * ## 用户要的语义
     * 「不是删一个 data、也不是删气泡，而是删 AI 输出的完整消息、用户发送请求这条消息
     * 的内容和被自动保存的记忆内容也要一并删除」+「删除消息只支持最新的一条」。
     * （记忆那部分在 ViewModel 做 —— 它是副作用，不是纯变换。）
     *
     * ## 为什么"只支持最新一条"不是限制，而是**保护**
     * 尾部删除**不动前缀**：已写入的历史一个字节都没变，只是请求变短 ——
     * 下一次请求的前缀仍然逐字节匹配，**缓存命中量原样保留**。
     * 这是本项目唯一"不花缓存代价"的历史编辑；删中间某条则会让它之后的全部内容失配
     *（类注释里那条代价说明）。所以只支持最新一轮，是刻意为之。
     *
     * @return 去掉尾部"她的话 + 紧邻的那条用户输入"之后的列表；
     *         尾部不是她的回复（没有可删的问答对）时**原样返回**。
     */
    fun deleteLastExchange(messages: List<ChatMessage>): List<ChatMessage> {
        // ⚠️ v0.61.54：先剥掉尾部的**主动消息**（Ta 主动说的话没有配对的 user 消息，
        //    不应当作"最新一轮回复"被删）—— 它们**留在历史里**，删除只作用于真正的问答对。
        val end0 = trimTrailingProactive(messages)
        val tail = messages.drop(end0) // 尾部的主动消息，删完要接回去
        var end = end0
        // 剥掉尾部连续的她的话（崩溃恢复可能留下不止一条）
        while (end > 0 && messages[end - 1].role == "assistant") end--
        // ⚠️ 判据是「**有没有消费掉一条她的回复**」（end 相对 end0 前移了），
        //    而不是「end 是否等于 size」—— 后者在 `[user, pro]` 这种
        //    "用户说了话但她还没回"的历史上会误判成"有可删的问答对"，把 user 删掉。
        if (end == end0) return messages
        // 再删掉紧邻的那条用户请求 —— 问答对的另一半
        if (end > 0 && messages[end - 1].role == "user") end--
        // ⚠️ 必须把尾部主动消息**接回去**：`subList(0, end)` 会把它们一起丢掉，
        //    而它们是 Ta 说过的话，不属于被删的那一轮问答。
        return messages.subList(0, end).toList() + tail
    }

    /** 能不能删最后一轮：尾部得是**她的回复**（那才构成一轮问答）；主动消息不算。 */
    fun canDeleteLastExchange(messages: List<ChatMessage>): Boolean {
        val end = trimTrailingProactive(messages)
        return messages.getOrNull(end - 1)?.role == "assistant"
    }

    /**
     * **删掉某一轮问答**（v0.61.14，用户要求：长按**任意**一枚她的气泡都能删）。
     *
     * 用户原话：「我理想的删除是用户长按分段气泡的**不管哪个气泡**选择删除，二次确认，
     * 然后删除用户发送的消息和分段气泡输出的**全部消息**」——
     * 翻译成数据操作就是：「她这一条回复 + 紧邻它**前面**的那条用户请求」一起走。
     *（"分段气泡输出的全部消息"指的是**一条回复拆出的全部气泡** —— 它们在数据上
     *  本来就是**同一条** assistant 消息，所以删这一条即可。记忆那部分在 ViewModel。）
     *
     * ## ⚠️ 代价（界面必须告知，别学上次只写半句）
     * 删**中间**的某一轮会让它**之后**的全部内容与前缀失配 —— 下一次请求按未命中计费。
     * 「删最后一轮」不动前缀；这正是之前只开放最新一轮的原因，现在用户要求全开放，
     * 于是**告知必须写回来**。
     *
     * @param index 她的那条回复在**全量** messages 里的下标
     *              （调用方必须换算成绝对下标 —— 列表渲染的是窗口）
     */
    fun deleteExchangeAt(messages: List<ChatMessage>, index: Int): List<ChatMessage> {
        if (index !in messages.indices) return messages
        // ⚠️ v0.61.54：Ta 主动说的话没有配对的 user 消息 → 长按它**不删**（原样返回）。
        //    它不是\"某一轮问答\"，删它只会留下一个断裂的历史。
        if (isProactive(messages, index)) return messages
        // 紧邻在前的那条用户请求是这一轮的另一半；前面不是 user（或没有）就只删她自己
        val partner = if (index > 0 && messages[index - 1].role == "user") index - 1 else index

        // ⚠️ **连带清掉"同一轮的历史残留"**（v0.61.18，用户要求
        //    「我的理想状态是**连带前一次生成内容一起被删除**」）。
        //
        //    老版本（v0.61.16 之前）保存时只写不删，一次重新生成会在库里留下
        //    **重复的问答对**：`[你, 她(旧), 你(重发), 她(新)]` —— 两条 user **内容相同**。
        //    那是一条**独立残留消息**（没有 superseded、没有切换按钮），
        //    所以删一次只掉最新那对，用户得删好几次。
        //
        //    ⚠️ 判据只看"**user 内容相同**"。用户真的把同一句话连发两遍时，
        //       较早那轮会被一起删掉 —— 概率极低，且比"删不干净"轻
        //      （内容还能从 `superseded` 或重新生成里找回）。
        var start = partner
        val request = messages.getOrNull(partner)?.takeIf { it.role == "user" }
        if (request != null) {
            // ⚠️ 比的是**剥掉附录之后**的正文（v0.61.19）—— 这一条是"没删干净"的真正原因：
            //    历史里那条 user 的 content 是「附录 + 正文」（`PromptEngine` 把 `<appendix>`
            //    拼在前面），而重新生成重发的是 `stripAppendix(...)` 后的文本、
            //    **新一轮的附录会被重新注入**（记忆/表情提示都可能变）——
            //    两轮附录不同 → 直接比原文**永远不相等** → "往前清理"从来没触发过。
            val requestBody = TranscriptText.stripAppendix(request.content).trim()
            var i = partner - 1
            while (i >= 1 && messages[i].role == "assistant" &&
                messages[i - 1].role == "user" &&
                TranscriptText.stripAppendix(messages[i - 1].content).trim() == requestBody
            ) {
                start = i - 1
                i -= 2
            }
        }
        return messages.filterIndexed { i, _ -> i < start || i > index }
    }

    /**
     * **把"连续多条她的回复"收敛成一条**（v0.61.17，老用户兼容）。
     *
     * ## 它修的是什么历史
     * v0.61.16 之前 `saveSession` 只 upsert、**从不删除** —— 每次重新生成，
     * 旧回复在内存里没了、**库里却留着**。于是老会话长成
     * `[你, 她(旧), 她(旧), 她(旧), 她(新)]`（**连续多条 assistant**；干净的历史不会这样，
     * 因为每一轮都是 user/assistant 交替）。用户报的「重新生成 4 次就要删 4 次」就是它。
     *
     * ## 收敛规则
     * 每组连续的她的回复：**只留最后一条**（= 当前版本），前面的按**时间顺序**并入
     * 保留那条的 `superseded`（老的在前）—— 于是左右切换按钮照旧能翻到它们，内容不丢。
     *
     * ## 幂等
     * 再跑一次结果不变（每组只剩一条）—— 所以可以每次启动都跑，
     * 不必在库里记"迁移过没有"。
     */
    fun squashAssistantRuns(messages: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(messages.size)
        var i = 0
        while (i < messages.size) {
            if (messages[i].role != "assistant") {
                out += messages[i]
                i++
                continue
            }
            var j = i
            while (j < messages.size && messages[j].role == "assistant") j++
            val run = messages.subList(i, j)
            out += if (run.size == 1) {
                run[0]
            } else {
                val last = run.last()
                last.copy(
                    // 前面的每一条：它自己的旧版本 + 它自己的正文（时间顺序，老的在前）
                    superseded = run.dropLast(1).flatMap { it.superseded + it.content } +
                        last.superseded,
                )
            }
            i = j
        }
        return out
    }

    /**
     * **重新生成**要写回什么样的历史（v0.61.15）—— 把原来散在 ViewModel 里的这段
     * 逻辑抽成纯函数，让它**可测**。
     *
     * ## 用户要的语义（2026-10-03 原话）
     * 「用户重新生成，**保留了原消息**重新生成了一轮可切换查看历史的消息 ——
     * 这不是我想要的」。他要的是：**时间线上只留一条她的回复**，旧版通过
     * 气泡下方的左右按钮查看（`ChatMessage.superseded`）。
     *
     * 所以这条不变量必须钉死：
     * - [RegeneratePlan.trimmed] **不含**那条旧回复（时间线上不留痕）；
     * - 旧回复的内容进 [RegeneratePlan.superseded]，由新回复**继承**（供切换查看）；
     * - 那条用户请求也要从历史里去掉（[send] 会用 [RegeneratePlan.userText] 重新追加，
     *   否则历史里会出现两条一模一样的用户消息 —— v0.61.14 修过一次）。
     *
     * @return null = 历史里没有可重新生成的问答（尾部没有她的回复 / 没有用户请求）
     */
    fun regeneratePlan(messages: List<ChatMessage>): RegeneratePlan? {
        // ⚠️ v0.61.54：**先跳过尾部主动消息**——Ta 主动说的话没有配对的 user 消息，
        //    不能当"最新一轮回复"被重生成。真正可重生成的是其下最近一条她的话。
        val end = trimTrailingProactive(messages)
        val relevant = messages.subList(0, end)
        // 尾部的主动消息：重生成**不该动它们**，但要接回结果里（见下）
        val tail = messages.drop(end)
        // ⚠️ 必须是**尾部那条**（`lastOrNull()` 而不是 `lastOrNull { role == "assistant" }`）：
        //    后者会在"她还没回"的历史里**往中间找**一条旧回复去重生成 —— 那是删错东西。
        //    （这条边界正是被 `没有可重发的问答时返回 null` 那条测试逼出来的。）
        val previous = relevant.lastOrNull()?.takeIf { it.role == "assistant" } ?: return null
        val request = relevant.lastOrNull { it.role == "user" } ?: return null
        return RegeneratePlan(
            // ⚠️ 接回主动消息（v0.61.54）：`relevant` 已剥掉它们，若直接返回
            //    `deleteLastExchange(relevant)`，Ta 主动说过的话会在重生成时**被丢掉**。
            trimmed = deleteLastExchange(relevant) + tail,
            superseded = previous.superseded + listOf(previous.content),
            userText = request.content,
        )
    }
}

/** [MessageEdits.regeneratePlan] 的结果：要写回的历史 / 要继承的旧版本 / 重发用的输入。 */
data class RegeneratePlan(
    val trimmed: List<ChatMessage>,
    val superseded: List<String>,
    val userText: String,
)
