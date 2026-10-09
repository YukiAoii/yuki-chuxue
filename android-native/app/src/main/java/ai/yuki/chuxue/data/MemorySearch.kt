package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity

/**
 * 记忆库搜索（记忆管理页的搜索框）。
 *
 * ## ⚠️ 空关键词的行为与 `MessageSearch` **刻意相反**
 * - `MessageSearch.query("")` → 返回**空表**（搜索页一打开就刷出几百条，没人想要）；
 * - `MemorySearch.query("")` → 返回**全部**（记忆页本来就默认列全部，搜索框只是**过滤**）。
 *
 * 两个场景的正确行为不同，所以不能"抽一个共用函数"把它们合并 ——
 * 合并的那天必然有一边变错。这里把差异写在类型旁边，而不是靠调用方记得。
 *
 * ## 为什么分类也算命中
 * 记忆卡片上分类标签（喜好 / 经历 / 约定…）是用户自己贴的。搜「约定」时
 * 想找的是**这一类**，而不是正文里恰好出现过"约定"两个字的那几条。
 * 只搜正文会让分类标签在搜索时形同虚设。
 *
 * ## 为什么抽成纯函数
 * 本机没有设备、没有模拟器 —— 界面上能验的东西只能靠用户看真机。
 * 凡是能挪到 JVM 上验的（"搜得对不对"）就都挪过来，别留给真机。
 */
object MemorySearch {

    /**
     * 过滤记忆。
     *
     * @param keyword 两端空白会被去掉。**去完为空则返回全部**（见类注释）。
     * @return 保持传入顺序的子集 —— 排序是列表自己的事（置顶 / 重要度 / 时间），
     *         搜索不该顺手改变它，否则用户一搜就觉得"顺序乱了"。
     */
    fun query(memories: List<MemoryEntity>, keyword: String): List<MemoryEntity> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return memories
        return memories.filter { matches(it, kw) }
    }

    /**
     * 一条记忆算不算命中。
     *
     * 大小写不敏感（`ignoreCase = true`）：用户可能搜英文词或拼音缩写，
     * 而"大小写不同就当没找到"是很让人恼火的一类失败。
     */
    fun matches(memory: MemoryEntity, keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return true
        return memory.content.contains(kw, ignoreCase = true) ||
            memory.category.contains(kw, ignoreCase = true)
    }
}
