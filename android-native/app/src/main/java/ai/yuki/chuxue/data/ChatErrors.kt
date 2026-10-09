package ai.yuki.chuxue.data

/**
 * 「请求失败」的人话翻译（H4：错误码对话化）。
 *
 * ## 为什么要它
 * 原先失败只有一条路：`_error` → 顶部灵动岛弹一句技术文案
 *（"认证失败（401）：API Key 无效。…"）。那是对**工程师**说的话。
 * 用户看到的是"她突然不理我了，屏幕上飘过一行像报错的字"—— 对话就此断掉，
 * 里面没有任何"她"的成分。
 *
 * 现在同一件事分两层表达：
 * - [Line.spoken] —— **她能说出口的一句话**，画成她的气泡，对话不断；
 * - [Line.detail] —— 原样的技术摘要，小字附在下面，用户要自查时有据可依。
 *
 * ## 它只是文字，不是历史
 * ⚠️ 这两句**都不写进 `messages`**（用户 2026-09-28 选定的方案 B）：
 * 错误不是她真的说过的话，而 `messages` 是下一轮请求的前缀 —— 一旦写进去，
 * 模型会看到"自己刚说过余额不足"（充值后重发时它可能还绕着这句话说），
 * 而且反复失败会在历史里堆上几条。所以它只活在界面层
 *（`ChatViewModel.errorLine`），退出会话即消失。
 *
 * ## 纯函数
 * 没有 IO、没有状态 —— 错误码进，文案出。因此可以逐条钉断言
 *（见 `ChatErrorsTest`）。
 */
object ChatErrors {

    /** 一次失败的两种说法。 */
    data class Line(
        /** 她说的话 —— 画成她的气泡 */
        val spoken: String,
        /** 技术摘要 —— 气泡里的小字，给用户自查用（可能为空） */
        val detail: String,
    )

    /**
     * 按 HTTP 状态码给说法。
     *
     * 覆盖用户点名的 400 / 401 / 402 / 422 / 429 / 500 / 503，
     * 并顺手带上 403（与 401 同类）与 404（地址/模型名写错，这个坑很常见）。
     * 其余 5xx 归为一档 —— 它们对用户的差别只是"等多久"，不是"做什么"。
     *
     * ## 2026-09-29 收紧措辞：短而精悍
     * 用户原话："有些提示不在于长在于短而精悍且易懂"。
     * 这一组原来平均 20 字上下，其实已经把"出什么事 + 你该做什么"说全了 ——
     * 问题在于**开头都有一截铺垫**（"你刚才那条…"、"你这条消息…"），
     * 而用户读第一眼要的是"哪里不对"。现在把铺垫砍掉，动作留在后半句。
     *
     * ⚠️ 改动时**必须同步看 `ChatErrorsTest`** —— 它对措辞有关键词断言
     *（402 要含"余额"、401/403 要含 "API Key"、429 要含"频繁"、
     * 500 与 599 必须同档、503 与 500 必须不同、兜底要带状态码）。
     * 这些不是"测试太脆"，而是"这几句话各自要对应用户的一种处置"。
     */
    fun forStatus(status: Int, detail: String): Line {
        val spoken = when (status) {
            // ⚠️ v0.51.0：400 在**第三方服务商**上最常见的原因是它不认 DeepSeek 的
            //    扩展参数（`thinking` / `reasoning_effort`），而不是"用户把话写错了"。
            //    只说"换个说法再发"会把用户引到一句话术问题上，反复重写消息 ——
            //    所以要同时点出那条真正能修好的路（见下面的 detail）。
            // ⚠️ v0.61.46：400 里有一类**不是内容问题、是长度问题**（上下文超窗）——
            //    「换个说法再发」对它纯属误导（换个说法长度不变，还是被拒）。
            //    认出来就直接说长度、给出路（先压缩再发）。
            400 -> if (ContextCompress.isContextOverflowError(detail)) {
                "内容太长，超出模型能装下的长度了 —— 去上下文里压缩一下再发？"
            } else {
                "这条被拒了，换个说法再发？要是刚换了第三方服务商，也看下下面那行。"
            }
            401 -> "我的钥匙好像不对 —— 去设置里看一眼 API Key 填完整了吗？"
            // ⚠️ v0.61.21：403 有**两种完全不同的成因**，不能都推给 API Key。
            //    上游对「这个密钥没权限用这个模型」也回 403，原话是
            //    `This token has no access to model <名字>` —— 那时**钥匙是好的**，
            //    让用户去翻 API Key 等于把他引到一条死路上（他反复检查也查不出问题）。
            //    2026-10-03 用户实测就卡在这里：新用户的模型名被后台存成了带装饰的
            //    显示名，于是每一条消息都 403，而提示一直在说"钥匙不对"。
            //    判据用上游原话里提没提 model；不提就还是按"钥匙不对"说（老行为不变）。
            403 -> if (detail.contains("model", ignoreCase = true)) {
                "这个模型我这边用不了 —— 名字可能不对，去「+」里换一个试试？"
            } else {
                "我的钥匙好像不对 —— 去设置里看一眼 API Key 填完整了吗？"
            }
            402 -> "我的余额不够了，充值之后我们接着聊好不好？"
            404 -> "地址没找到，看下设置里的 API 地址和模型名？"
            422 -> "这条我处理不了，换个说法试试？"
            429 -> "等一下下，我这边太频繁了，缓几秒？"
            503 -> "我有点忙，等会儿再叫我？"
            in 500..599 -> "我这边出了点状况，等会儿再试？"
            else -> "这次没接上（HTTP $status），再试一次？"
        }
        return Line(spoken, detailOf(status, detail))
    }

    /**
     * 给用户看的**原始回执**（可展开那一行）。
     *
     * 只为一件事：把 400 变成**可执行**的下一步。400 的常见成因里有两个是配置问题
     * （而不是内容问题），而配置问题不指出来用户永远想不到：
     * 第三方服务商不认识 `thinking` / `reasoning_effort`。
     */
    private fun detailOf(status: Int, detail: String): String = when (status) {
        400 -> "$detail\n（若在用第三方服务商：设置 →「连接设置」→ 那个分组 → 关掉「发送思考参数」）"
        401, 403 -> "$detail\n（检查「连接设置」里这个分组的密钥是否填完整）"
        else -> detail
    }

    /**
     * 连接层失败 —— 没有 HTTP 状态码可依据（DNS / 连接被拒 / 读写超时 / 响应不是 JSON）。
     *
     * ⚠️ 它与 [forStatus] 分开，是因为**用户要做的事不同**：
     * 状态码错误要么改设置、要么等；连接失败第一个要查的是网络与 API 地址。
     */
    fun forNetwork(detail: String): Line =
        Line("连不上你那边 —— 看下网络，或者设置里的 API 地址？", detail)

    /**
     * 首字看门狗超时 —— 请求发出去了，但 [seconds] 秒内一个事件都没收到。
     *
     * 这一档**没有** HTTP 状态码：服务端可能什么都没回、也可能一直在发
     * keep-alive 心跳（心跳不算内容，见 `ChatViewModel.send` 的注释）。
     * 所以文案里要把"可能是哪几件事"都点到 —— 否则用户只会以为是自己网差。
     */
    fun forFirstByteTimeout(seconds: Long): Line = Line(
        "等了 $seconds 秒都没等到回话。可能是网络，也可能是 API Key 或余额的问题。",
        "首字看门狗：$seconds 秒内没有收到任何内容事件",
    )

    /**
     * 模型把这一轮**收尾了，但正文一个字都没有**。
     *
     * ⚠️ 这是最容易被做成"静默失败"的一种 —— 原先 `commitAssistant` 里
     * 一句 `if (text.isBlank()) return` 就把它丢了：没有历史、没有气泡、
     * 没有报错，界面看上去像"什么都没发生"。用户报的**「发了消息、她完全不回复」**
     * 就是它。
     *
     * 两种情形的处置完全不同，所以这里分开说：
     *
     * - [sawReasoning] 为真（她想了一堆、正文空白）：几乎一定是**思考链把输出长度
     *   占满了**。官方在思考模式下把 `max_tokens` 的默认值从 8K 抬到 64K，
     *   正是因为思考要与正文**共用**这份预算（见 `memory/api-deepseek.md`）。
     *   给用户的可操作项是"把思考强度调低"或"把回复长度上限调大"。
     * - 连思考内容也没有：更像服务端这一次空手而归，让他重发即可。
     *
     * @param sawReasoning 这一轮是否收到过思考内容（`reasoning_content`）
     * @param detail 技术摘要（思考字数等），放进气泡小字供自查
     */
    /**
     * 服务端把输出推到了长度上限（`finish_reason = "length"` / Anthropic 的 `max_tokens`）。
     *
     * ⚠️ 照搬天枢 `src/agent/worker-repair-route.ts` 的 `isTruncationStopReason`。
     * 它的注释写着为什么必须把这个事实单独拎出来：
     * 「命中即文本必然未闭合、parse 必失败，**且同预算的修复轮只会再撞同一面墙** ——
     *   这个事实必须透传到失败结果里」（那边为此踩过两例事故：现场只剩笼统的报错，
     *   真正的截断原因被吞掉）。
     *
     * 在初雪这里，它的价值是**把两种不同的病分开**：
     * · `length` → 确定是"额度被占满"，用户改设置就能好；
     * · 其它 → 服务端说正常结束了、正文却是空的，那是另一回事，别让用户白改设置。
     */
    fun isTruncationStopReason(reason: String?): Boolean {
        val r = reason?.lowercase() ?: return false
        return r == "length" || r == "max_tokens"
    }

    fun forEmptyReply(sawReasoning: Boolean, detail: String, truncated: Boolean = false): Line =
        if (truncated) {
            // **确定**是截断：服务端明说 finish_reason=length
            Line(
                "Ta这次写满了输出上限就断了 —— 思考和正文共用一份额度，思考占得多，正文就没了。" +
                    "去设置里把思考强度调低一档，再试一次？",
                detail,
            )
        } else if (sawReasoning) {
            // 想过了但没正文，而服务端**没**说是截断：病根不一定是额度，别一口咬定
            Line(
                "Ta想了很多，却没写出正文（服务端没报截断，所以不一定是长度不够）。" +
                    "再发一次；若反复出现，把思考强度调低一档看看。",
                detail,
            )
        } else {
            Line("Ta这次什么都没说就结束了，再发一次试试？", detail)
        }

    /**
     * 从一个异常里取说法 —— 界面侧唯一的入口。
     *
     * 有状态码走 [forStatus]（[ApiHttpException]），没有就按连接失败处理。
     */
    fun forException(e: Throwable): Line {
        val detail = e.message?.takeIf { it.isNotBlank() }
            ?: e::class.simpleName.orEmpty()
        val status = (e as? ApiHttpException)?.status
        return if (status != null) forStatus(status, detail) else forNetwork(detail)
    }
}
