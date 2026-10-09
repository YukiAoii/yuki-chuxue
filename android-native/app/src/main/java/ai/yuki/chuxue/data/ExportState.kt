package ai.yuki.chuxue.data

/**
 * **一次导出任务的状态**（v0.54.0）。
 *
 * 用户要求："导出之后给出一个固定在容器卡片里的临时卡片，导出状况包括成功与否、
 * 导出路径、点击跳转，会有导出的进度条"。
 *
 * ⚠️ 进度是**逐份**的（`done / total`），不是字节级 —— 一份聊天记录撑死几 MB、
 * 写到本地文件系统只是毫秒级的事；做成字节级进度只会带来两件事：
 * 更多的状态更新、以及一个永远在 99% 停一下的进度条。
 *
 * ⚠️ [Done.where] 是**给用户看的位置描述**（"你刚才选的那个文件夹"），
 * 不是 file:// 路径 —— Android 10+ 的 SAF 目录名对用户没有意义，
 * 而真正的可跳转能力在界面层（用拿到的 Uri 去打开）。
 */
sealed interface ExportState {

    /** 什么都没做（卡片不显示）。 */
    data object Idle : ExportState

    /** 正在导出第 [done] + 1 份，一共 [total] 份。 */
    data class Running(val done: Int, val total: Int) : ExportState

    /**
     * 成功。
     *
     * @param count 实际写成功的份数
     * @param where 位置描述（给用户看的那句话）
     */
    data class Done(val count: Int, val where: String) : ExportState

    /** 失败 —— [reason] 会直接显示给用户，所以要能看懂。 */
    data class Failed(val reason: String) : ExportState
}
