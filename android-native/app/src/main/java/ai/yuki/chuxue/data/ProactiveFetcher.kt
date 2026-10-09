package ai.yuki.chuxue.data

import android.content.Context
import ai.yuki.chuxue.data.room.AppDatabase
import ai.yuki.chuxue.data.room.SessionRepository
import ai.yuki.chuxue.service.ProactiveNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「去信箱看看 Ta 有没有想说的话」—— **不依赖 ViewModel** 的取件门面（v0.61.49）。
 *
 * ## 为什么要把这段从 ChatViewModel 抽出来
 * 原来取件逻辑长在 `ChatViewModel.runProactiveFetchIfDue` 里，只能由界面触发
 * （App 启动 / 回前台）—— **App 在后台时永远不取件**，也就永远收不到 Ta 的消息。
 * 现在抽成这个门面：界面触发它、常驻前台服务（`ProactiveWatchService`）也触发它，
 * 两条路径共用同一份守卫与水位，不会出现"两条链各记各的"。
 *
 * ## 设计红线（照旧，别走歪）
 * 它只做"去信箱看看"：**何时说、说什么由心潮的情绪状态机决定**。
 * 这里**没有**、也不许有"到点提醒 Ta 说话"的逻辑 —— [ProactiveFetchPolicy] 的节流
 * 只防"反复打点"，不是唤醒源。
 *
 * ## 失败静默
 * 未登录 / 没网 / 服务端 5xx：一律安静返回 0（与 telemetry / xinchao 上报同一纪律）。
 */
object ProactiveFetcher {

    /**
     * 取件一次。返回**实际弹出的通知条数**（供调用方打点/测试；0 表示没有新消息或静默失败）。
     *
     * ⚠️ **时间戳先落盘再发网络**：界面与常驻服务两个触发点可能几乎同时命中
     *（都读到旧时间戳），不先落盘就会双发请求、用户收到两条一样的通知。
     */
    suspend fun fetchOnce(context: Context, now: Long = System.currentTimeMillis()): Int {
        val store = Store(context)
        if (!ProactiveFetchPolicy.shouldFetch(now, store.lastProactiveFetchAt())) return 0

        val base = ServerConfig.BASE_URL
        val token = store.loadAuth().token
        if (base.isBlank() || token.isBlank()) return 0

        val personas = when (val loaded = store.loadPersonas()) {
            is Store.Loaded.Ok -> loaded.value
            else -> return 0
        }
        // ⚠️ 2026-10-06：判据改为**「Ta 主动来找我」这个开关本身**（用户要求单独做开关）。
        //    沿革：v0.61.48 曾用「记忆方式是不是云端」当判据（三项绑死）；
        //    现在云端模式下该项可单独关，所以必须看它自己的字段。
        //    ⚠️ 仍要求云端模式 —— 本地模式没有云端桶、也没有消息可取（取件接口就不通）。
        val candidates = personas.filter { it.isCloudMemory && it.proactiveEnabled }
        // ⚠️ v0.61.50 修（审查指出）：`markProactiveFetchAt` 原来在这三关校验**之前**，
        //    于是"未登录 / 没有云端人设"时也会吃掉一个 30 分钟窗口 —— 等用户真登录了反而取不到。
        //    现在只有"确实要去取件"时才落盘。
        //    ⚠️ 但**落盘仍要先于网络**（防两个触发点同时命中造成双发）。
        store.markProactiveFetchAt(now)
        if (candidates.isEmpty()) return 0

        var shown = 0
        // ⚠️ v0.61.54：Ta 主动说的话要落进聊天记录 —— 否则只有通知、
        //    不在记录里、也不算进上下文，问她"你刚才说了什么"她对不上。
        //    落库是"追加"：找到该人设最近一个会话，把消息 append 成 assistant，
        //    再整体 saveSession（幂等，靠消息 id 确定性不会重插）。
        val db = AppDatabase.get(context)
        val repo = SessionRepository(db)
        // ⚠️ 不在这里读 allSessions —— 见下面循环内的注释：快照会被整段覆写回库。

        for (persona in candidates) {
            val since = store.lastProactiveSeenAt(persona.id)
            val msgs = runCatching {
                XinchaoPendingApi.fetch(base, token, persona.id, since)
            }.getOrNull() ?: continue // 失败静默，下个机会再试
            if (msgs.isEmpty()) continue

            // 落库目标 = 该人设「最近一个会话」。
            // ⚠️ **每次循环内重新读**（v0.61.54 自审修正）：`loadSessions()` 是快照，
            //    而 `saveSession` 是**整段覆写**（read-modify-write）。若在循环外读一次，
            //    用户在取件期间发的新消息会被这份旧快照**整段覆盖掉** ——
            //    正是 SessionRepository 注释里警告过的"吞对话"。
            val targetSession = runCatching { repo.loadSessions() }
                .getOrDefault(emptyList())
                .filter { it.personaId == persona.id }
                .maxByOrNull { it.updatedAt }

            if (targetSession != null) {
                // ⚠️ 幂等（v0.61.54 自审修正）：消息主键是**按位置**生成的
                //    （`SessionMapper.messageId` = "$sessionId#$seq"），append 后位置会漂移，
                //    所以"重复取件不会写两遍"**不能**靠主键保证。这里显式按正文判重：
                //    已经在历史里的主动消息不再追加（水位未推进 / 双触发点都会走到这）。
                val already = targetSession.messages
                    .filter { it.sendMode == SEND_MODE_PROACTIVE }
                    .map { it.content }
                    .toSet()
                val fresh = msgs.filter { it.message !in already }
                if (fresh.isNotEmpty()) {
                    val newMessages = fresh.map { m ->
                        ChatMessage(
                            role = "assistant",
                            content = m.message,
                            // v0.61.54：落库的主动消息带 sendMode 标记，
                            // 渲染层可识别（Ta 主动说的话），删除/重新生成逻辑跳过它。
                            sendMode = SEND_MODE_PROACTIVE,
                        )
                    }
                    runCatching {
                        repo.saveSession(
                            targetSession.copy(messages = targetSession.messages + newMessages),
                        )
                    }.onFailure {
                        // 落库失败不阻塞通知（通知是用户主视角；落库失败只剩通知，下轮重试）
                    }
                }
            }
            // 无论落库与否，通知照弹（用户可见的收件提示）
            ProactiveFetchReceipt.pickForNotification(
                isFirstFetch = since.isBlank(),
                messages = msgs,
            ).forEach { m ->
                // ⚠️ v0.61.50：头像解码 + 圆形裁剪是 CPU/IO 活，**别在主线程做**
                //（fetchOnce 由 viewModelScope(Main) 或 Service 调用，返回后线程不定）——
                // 审查指出这里原来会把 decodeFile/createBitmap 落在主线程上。
                withContext(Dispatchers.IO) {
                    ProactiveNotifier.show(
                        context,
                        persona.id,
                        persona.displayName,
                        m.message,
                        m.at,
                        avatarPath = persona.avatarPath,
                    )
                }
                shown++
            }
            // 已读水位推到最新（首次也推）
            msgs.map { it.at }.filter { it.isNotBlank() }.maxOrNull()
                ?.let { store.markProactiveSeenAt(persona.id, it) }
        }
        return shown
    }

}
