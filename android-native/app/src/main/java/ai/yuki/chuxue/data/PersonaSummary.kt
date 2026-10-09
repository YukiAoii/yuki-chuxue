package ai.yuki.chuxue.data

/**
 * 「AI 为这个人设写一小段简介」—— 提示词与产出清洗（v0.61.21）。
 *
 * ## 起因
 * 用户原话：「人设详情界面不一定非得硬编码和计算东西，你可以让 AI 填人设详情界面的内容，
 * 更加富有创新性」。原来是把设定拆成字段罗列（性别 / 昵称 / 备注…），读起来像参数表；
 * 换成一两句话，详情页才开始像"关于 Ta 的一页"。
 *
 * ## 花多少额度（用户明确要求过）
 * 「用户启动软件的时候让 AI 生成一次就可以了，具体花多少额度你随便写，别写太多消耗」。
 * 所以：**低 max_tokens + 关思考** —— 思考链对"写一两句话"这种活纯属多花钱多等
 *（同 [DeepSeekClient.extractMemories] 的取舍）。
 *
 * ## 纯函数，因此可测
 * 提示词怎么拼、模型吐出来的脏东西怎么清 —— 这些是**行为**，不该埋在网络层里。
 */
object PersonaSummary {

    /**
     * 简介的额度上限。
     *
     * 中文一句话约 20–40 token，两句 100 出头 —— 220 足够写满且**不会跑远**。
     * 定这么小也是刻意的：这是个"启动时顺手做"的活，不该比一次正常聊天还贵。
     */
    const val MAX_TOKENS = 220

    /**
     * 拼生成简介的提示词。
     *
     * 三条约束都对着**已知的翻车方式**写的：
     * 1. 只输出正文 —— 不然模型爱加"好的，这是为您写的简介："这种开场白；
     * 2. 不要复述字段名 —— 否则等于把参数表换成散文，白做；
     * 3. 用「Ta」不用他/她 —— 人设千奇百怪，写死性别是替用户认定。
     */
    fun buildPrompt(persona: Persona): String = buildString {
        appendLine("请根据下面这份角色设定，用**中文**写一两句话介绍这个角色。")
        appendLine()
        appendLine("要求：")
        appendLine("1. 只输出介绍本身，不要任何开场白、标题、引号或 markdown 标记；")
        appendLine("2. 不要复述设定里的字段名（不要出现「性格」「设定」这类词），直接描述；")
        appendLine("3. 指代这个角色时用「Ta」，不要用「他/她」；")
        appendLine("4. 字数控制在 80 字以内。")
        appendLine()
        appendLine("—— 角色设定如下 ——")
        persona.roleName.takeIf { it.isNotBlank() }?.let { appendLine("名字：$it") }
        persona.userGender.takeIf { it.isNotBlank() }?.let { appendLine("用户性别：$it") }
        persona.personality?.takeIf { it.isNotBlank() }?.let { appendLine("性格：${it.trim()}") }
        appendLine("设定：${persona.customPrompt.trim()}")
    }

    /**
     * 把模型吐出来的东西清成**能直接贴在界面上的一行**。
     *
     * 网络回来的是自由文本，常见脏东西：首尾引号、`**加粗**`、markdown 标题、
     * "好的，这是…："这种开场白、以及换行。这里只做**机械清洗**，
     * 不做语义判断 —— 清完为空就返回 null，让调用方保持"待生成"状态。
     */
    fun clean(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val cleaned = t
            // markdown 标记：加粗/斜体/标题/引用
            .replace(Regex("""^[#>\-\*\s]+""", RegexOption.MULTILINE), "")
            .replace("**", "")
            .replace("__", "")
            // 首尾成对的引号（中英文都算）
            .trim('"', '\'', '“', '”', '‘', '’', '「', '」', ' ')
            // 换行压成空格：详情页那是一行文字，不是一篇文章
            .replace(Regex("""\s*\n+\s*"""), " ")
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }
}
