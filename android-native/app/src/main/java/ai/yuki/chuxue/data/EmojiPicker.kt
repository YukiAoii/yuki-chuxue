package ai.yuki.chuxue.data

/**
 * 「这次发不发图、发哪张」的**全部决策逻辑**（纯函数，可单测）。
 *
 * ## 为什么把它单独拎出来
 * 这段逻辑有三个分支（有没有标签 / 有没有对应图 / 概率过没过），
 * 而它们**全都无法在真机上快速验证**（要等模型真的输出标签、要反复试概率）。
 * 抽成纯函数之后，"概率 0.3 时 roll=0.9 不该发"这种事在 JVM 上就能钉住。
 *
 * ## ⚠️ 随机源全部由参数注入
 * [roll]（这次掷出的骰子）与 [index]（抽第几张）都从外面传，
 * 所以同一个输入永远得到同一个输出 —— 这是它能被测试的前提。
 * 调用方用 `Random.nextFloat()` / `Random.nextInt()` 填这两个参数。
 */
object EmojiPicker {

    /** 挑中的结果：命中哪个情绪标签、发哪张图。 */
    data class Pick(val tag: String, val path: String)

    /**
     * @param reply   模型这一次的完整回复（标签就藏在正文里）
     * @param owned   **该人设专属**的：分类 → 该类下的图片路径。空分类不必传（传了也会被跳过）
     * @param global  **全局**的，形状同 [owned]；某一类在专属里没有可用图时回退到它
     * @param chance  这次发图的概率（已由调用方解析过"人设覆盖 vs 全局默认"）
     * @param roll    `[0, 1)` 的随机数 —— 用来跟 [chance] 比
     * @param index   抽第几张（会在该分类的候选里取模回绕）
     */
    fun pick(
        reply: String,
        owned: Map<String, List<String>>,
        global: Map<String, List<String>>,
        chance: Float,
        roll: Float,
        index: Int,
    ): Pick? {
        // 两层都空 = 库里一张可用的图都没有
        if (owned.isEmpty() && global.isEmpty()) return null

        val tags = EmojiCategories.extractTags(reply)
        if (tags.isEmpty()) return null

        // ⚠️ 只认**确实有图**的分类（两层任一层有就算）：模型说了 `[开心]` 但这一类
        // 一张图都没有时，不该把这次概率白白消耗掉 —— 让位给同一句里的下一个标签。
        val hit = tags.firstOrNull { !owned[it].isNullOrEmpty() || !global[it].isNullOrEmpty() }
            ?: return null

        if (roll >= chance.coerceIn(0f, 1f)) return null

        // 专属优先；这一类在专属里没有可用的图（没有这类，或这类全坏）才回退到全局。
        //
        // ⚠️ 回退必须做在**分类这一层**，不是"专属库整个空了才用全局"：
        // 后者会让专属库有「开心」没有「难过」时，用户说难过她**一张也发不出** ——
        // 而全局库里明明有难过的图。方案 C 的价值全在这条边界上。
        val candidates = owned[hit]?.takeIf { it.isNotEmpty() } ?: global.getValue(hit)
        return Pick(hit, candidates[Math.floorMod(index, candidates.size)])
    }

    /**
     * 解析**这次实际生效**的概率：人设覆盖优先，否则用全局默认。
     *
     * 这是用户 2026-09-28 定的混合模式：「全局设默认，人设可覆盖，
     * 用户不用管每个人设，想精调时也能单独改」。
     *
     * ⚠️ 夹到 `0..1`：这个值一路可能来自滑块、DataStore、甚至手改过的偏好文件，
     * 一个越界值会让 `roll < chance` 恒真或恒假（表现为"永远发"或"永远不发"且不报错）。
     */
    fun effectiveChance(global: Float, personaOverride: Float?): Float =
        (personaOverride ?: global).coerceIn(0f, 1f)
}
