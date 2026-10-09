package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.EmojiPackEntity

/**
 * 表情包的**可用性检测**（用户 2026-09-28 补充的第四条）。
 *
 * ## 它防的是什么
 * 库里存的是**图片路径**，而文件可能已经不在了：用户清过应用数据、手动删过图、
 * 或那一次保存其实没成功（磁盘满、权限被拒）。不筛的话会抽到一张画不出来的图 ——
 * 用户看到的是**一个空的图片气泡**，而且不知道为什么会这样。
 *
 * ## 为什么写成纯函数、把 `exists` 从外面传进来
 * 真实的判断要碰文件系统（Android 运行时），**那在 JVM 单测里没有**。
 * 把"文件在不在"抽成参数之后，筛选规则本身就能被钉住
 *（`EmojiAvailabilityTest`），而 IO 留给调用方（ViewModel，它有 IO 上下文）。
 *
 * ## ⚠️ 它与"该分类有没有图"是两道不同的关
 * `EmojiPicker` 已经会跳过"没有候选的分类"；这里管的是**候选中混着坏文件**的情况 ——
 * 两种症状不同：前者是"发不出来"，后者是"发出来是个空气泡"。
 */
object EmojiAvailability {

    /**
     * 只留下**文件真的还在**的那些。
     *
     * @param exists 判"这个路径的文件还在不在"。调用方传 `File(path).exists()`；
     *               单测里传一个假的集合查询。
     */
    fun filterAvailable(
        packs: List<EmojiPackEntity>,
        exists: (String) -> Boolean,
    ): List<EmojiPackEntity> = packs.filter { runCatching { exists(it.id) }.getOrDefault(false) }

    /**
     * 按分类分组成 `EmojiPicker` 要的形状。
     *
     * 与 [filterAvailable] 分开是有意的：**先筛再分组**，顺序反了会把空分类也建出来
     *（那些分类进到 map 里，`EmojiPicker` 还得再判一次"候选是不是空的"）。
     */
    fun availableByCategory(
        packs: List<EmojiPackEntity>,
        exists: (String) -> Boolean,
    ): Map<String, List<String>> = byCategory(filterAvailable(packs, exists))

    /**
     * 按**归属**分成两层，再各自按分类分组 —— 这就是方案 C 要的数据形状。
     *
     * 返回值与 [EmojiPicker.pick] 的两个入参一一对应：`first` = 专属，`second` = 全局。
     *
     * ⚠️ 分组**分两次做**（而不是先合起来再标来源）：合起来之后
     * "同一分类下哪些图属于专属"这个信息就没了，而回退判定全靠它。
     *
     * @param personaId 当前说话的人设；`null` 表示没有上下文（那就只剩全局一层）
     */
    fun availableByOwner(
        packs: List<EmojiPackEntity>,
        personaId: String?,
        exists: (String) -> Boolean,
    ): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val alive = filterAvailable(packs, exists)
        return byCategory(alive.filter { it.personaId != null && it.personaId == personaId }) to
            byCategory(alive.filter { it.personaId == null })
    }

    /**
     * 分组这一步单独抽出来，让两条入口（单层 / 两层）共用同一套规则。
     *
     * 空组要去掉：同类全坏时 `groupBy` 会留下一个空列表 —— 让它留在 map 里，
     * 下游还得再判一次"候选是不是空的"。在数据形状上就表达出"没有可用图"，更省事。
     */
    private fun byCategory(packs: List<EmojiPackEntity>): Map<String, List<String>> =
        packs.groupBy({ it.category }, { it.id }).filterValues { it.isNotEmpty() }
}
