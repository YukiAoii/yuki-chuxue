package ai.yuki.chuxue.data

/**
 * **数据概览**（v0.53.0，数据备份页顶部那块）：本机现在有多少数据。
 *
 * ⚠️ 它回答的是"我要备份的东西有多大"，**不是**"我花了多少钱" ——
 * 后者本地算不出来（同缓存诊断页的纪律：不显示金额）。
 *
 * ⚠️ 记忆条数只能查库（`memoryDao().count()`），所以取它的那个函数是 suspend；
 * 会话与人设有常驻状态，直接数即可。
 */
data class DataOverview(
    val sessions: Int,
    val messages: Int,
    val memories: Int,
    val personas: Int,
)
