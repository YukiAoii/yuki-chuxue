package ai.yuki.chuxue.data

/**
 * 对话里那条**可关闭的提醒**该显示什么。
 *
 * ## 为什么会有它（一次反转）
 * 这三条通道（`_error` / `_loadWarning` / `_notice`）原来走**顶部一闪而过的胶囊**——
 * 那是上一轮专门改过去的（`ChatScreen.kt` 里当时留了注释：「临时提示统一走灵动岛」）。
 * 用户 2026-10-05 又提出来：**重要的提醒不该一闪就没**，要画进对话里、能关掉、能回头再看。
 *
 * ## 为什么同一时刻只显示一条
 * 画进对话里就意味着它会**占列表的位置、还会把消息往下挤**。三条都摆出来是叠三层，
 * 所以定死优先级：**出错 > 数据警告 > 一般提示**。
 * 出错排最前，因为它多半要用户**做点什么**（去填密钥、去设置里看地址）；
 * 另外两条更多是"知道一下"。
 *
 * ⚠️ 它只管**界面**。这三条本来就不写 `messages`、不进请求体（缓存红线），
 *    这个函数也不碰它们 —— 从"临时提示"换成"对话里的卡片"，请求的字节一个都没变。
 */
object AppNotice {

    enum class Kind {
        /** 要用户做点什么的那种（先填密钥 / 图片读不出来 / 导出失败）。 */
        ERROR,

        /** 数据层面的警告（人设、旧记录读取失败）。 */
        WARN,

        /** 一般提示（已导出、已恢复等）。 */
        INFO,
    }

    data class Item(val text: String, val kind: Kind)

    /** 三选一；全空返回 `null`（= 不显示）。空白串一律当空。 */
    fun of(error: String?, loadWarning: String?, notice: String?): Item? = when {
        !error.isNullOrBlank() -> Item(error, Kind.ERROR)
        !loadWarning.isNullOrBlank() -> Item(loadWarning, Kind.WARN)
        !notice.isNullOrBlank() -> Item(notice, Kind.INFO)
        else -> null
    }
}
