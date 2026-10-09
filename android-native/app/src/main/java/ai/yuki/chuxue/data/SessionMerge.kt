package ai.yuki.chuxue.data

/**
 * 数据库快照 与 本地（乐观）状态 的合并规则。
 *
 * ## 它防的是什么
 * 聊天是**乐观更新**的：消息先上屏、再异步落盘。于是存在一个窗口 ——
 * 数据库里还没有这条消息，而一条**稍旧的**快照经 `Flow` 到达。
 * 若那时无条件 `_sessions.value = fromDb`，刚上屏的消息就被抹掉了。
 *
 * 这就是用户报的「发出去的消息消失（但 AI 回复了）」的另一半机制
 * （另一半在 `SessionRepository.saveSession`：两次写入之间被观察到）。
 *
 * ## 判据为什么用 `updatedAt`
 * 它在乐观更新时就被写成"此刻"，而数据库里那个值来自**上一次成功保存**。
 * 所以「本地 updatedAt 更大」恰好等价于「还有未落盘的改动」——
 * 不需要新增任何状态字段（版本号、脏标记都不必）。
 *
 * 相等时**采用数据库**：宁可让数据库赢，也不要让本地旧值赖着不走
 * （保存完成后两边本就该一致，真正的分歧只发生在"本地更新"的情形）。
 *
 * ## 为什么做成纯函数
 * 它是"消息会不会凭空消失"这条不变量的守卫，值得被单测钉住 ——
 * 藏在 ViewModel 的 `collect` 里就只能靠真机观察了。
 * 纯函数意味着**顺序、时间戳、缺项**这些边界都能被精确断言。
 */
object SessionMerge {

    /**
     * 用数据库快照为准，但**保留本地更新的会话**。
     *
     * @param fromDb 数据库当前状态（Flow 发来的，可能稍旧）
     * @param local 当前内存里的状态（可能含尚未落盘的乐观更新）
     */
    fun merge(fromDb: List<Session>, local: List<Session>): List<Session> {
        if (fromDb.isEmpty()) return emptyList()
        val localById = local.associateBy { it.id }
        return fromDb.map { dbSession ->
            val mine = localById[dbSession.id]
            if (mine != null && mine.updatedAt > dbSession.updatedAt) mine else dbSession
        }
    }
}
