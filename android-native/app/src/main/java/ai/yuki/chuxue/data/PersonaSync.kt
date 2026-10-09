package ai.yuki.chuxue.data

/**
 * 人设同步的**判定**（纯函数，可单测）。
 *
 * ## 为什么是"整体快照"而不是逐条合并
 * 每次人设变动就把整个列表编码、加密、上传一次，用一个 rev（= 集合里最大的 `updatedAt`）
 * 做"谁更新"的判据。人设数量少（个位数），整体覆盖比逐条 diff 简单得多，
 * 也不会出现"两边各删一条、合并后又都回来了"这类没人能解释的状态。
 *
 * ## rev 就是 `updatedAt` 的最大值
 * 不另设一个"同步时间戳"：`Persona.updatedAt` 本来就是"这条被改过"的唯一事实，
 * 集合的最大值就代表"这份快照有多新"。它在 `upsertPersona` / `deletePersona` 里都会被刷新。
 */
object PersonaSync {

    /** 本次该往哪边同步。 */
    enum class Action { Upload, Download, None }

    /**
     * 决定动作。
     *
     * - 服务端没有（rev <= 0）：本地有就上传，本地也空就不动；
     * - 本地没有：服务端有就下载；
     * - 两边都有：谁 rev 大听谁的。
     */
    fun decide(localRev: Long, remoteRev: Long): Action = when {
        remoteRev <= 0L && localRev <= 0L -> Action.None
        remoteRev <= 0L -> Action.Upload
        localRev <= 0L -> Action.Download
        remoteRev > localRev -> Action.Download
        localRev > remoteRev -> Action.Upload
        else -> Action.None
    }

    /** 本地人设集合的 rev = 最大的 `updatedAt`（空集合为 0）。 */
    fun localRev(personas: List<Persona>): Long =
        personas.maxOfOrNull { it.updatedAt } ?: 0L

    /**
     * 服务端那份该不该应用。
     *
     * ⚠️ v0.61.21：只有 **`null`**（解密/解码失败）才跳过。
     * **空表是合法值** —— 它正是"对方把人设删光了"的同步方式。
     *
     * 原来调用点写的是 `if (!remote.isNullOrEmpty())`，把这两种"空"合并了：用户在一台
     * 设备上把人设删光，这个删除**永远同步不到另一台设备**；而且 `savePersonasRev`
     * 不推进，对面每次启动都重复同一判断，**永不收敛、也不报错**。
     */
    fun shouldApplyRemote(remote: List<Persona>?): Boolean = remote != null

    /**
     * 本地这份能不能**推上去覆盖云端**（v0.61.21）。
     *
     * ## 它防的是一次真实的数据丢失
     * 换设备 / 清数据之后刚登录时，本地人设是**空的**，而云端有旧快照。
     * 此时用户只要**新建一个人设**就会触发推送 —— 推上去的是"本地这份"，
     * 于是**云端的旧人设被一份近乎空的快照覆盖掉**：不是"新设备看不到"，
     * 是真的没了（旧设备下次拉也拉不回来）。用户报的「换设备登录后人设消失」就是它。
     *
     * ## 判据
     * · 本地**非空** → 可以推（正常路径）。
     * · 本地为空 + 本次安装**成功拉取过一次**（所以我们知道云端也是空的）→ 可以推
     *   —— 「我把人设删光了」这件事必须能同步出去（见 [shouldApplyRemote]）。
     * · 本地为空 + **还没拉过** → **不许推**：我们不知道云端有什么，这一推就是盲目覆盖。
     *
     * ⚠️ 失败方向是刻意的：宁可"这次没推上去、下次再说"，也不要"把用户的云端弄没了"。
     *
     * @param localCount 本地人设条数
     * @param pulledOnce 本次安装是否**成功**拉取过一次云端快照
     */
    fun canPush(localCount: Int, pulledOnce: Boolean): Boolean = localCount > 0 || pulledOnce

    /**
     * 该不该走「首次同步的合并」（v0.61.21 · fix2）。
     *
     * ## 它防的是一次真实的数据丢失（2026-10-04 真机日志确证）
     * 清数据/换设备重登后，拉取挂点曾经只在聊天页；用户还没拉过就新建人设时，
     * 推送会把云端旧快照**覆盖成"只有新建的这一个"**。
     * 合并 = 云端 ∪ 本地 —— 两边都保住。
     *
     * ## 为什么只有"从未同步过"才安全
     * 并集语义要求本地**没有删除语义**（本地缺的项 = "没拉到"，不是"用户删的"）。
     * 只有本设备从未见过云端数据时，这个前提才成立；一旦同步过，
     * 本地缺项就可能是删除 —— 合并会把删掉的人设**复活**。
     *
     * ## 为什么只认 `null`（而不是"和当前账号不同"）
     * `syncedUid` 非 null 且 ≠ 当前账号 = 换账号场景：本地的内容是**别的账号**的，
     * 更不能拿来并进这个账号的云端。那种场景回到普通 decide 逻辑（现状行为）。
     *
     * @param syncedUid 本设备最近一次"确知云端状态"的账号 uid；从未同步过为 null
     * @param remoteRev 云端快照的 rev（0 = 云端为空）
     * @param localCount 本地人设条数
     */
    fun shouldMergeFirstSync(syncedUid: String?, remoteRev: Long, localCount: Int): Boolean =
        syncedUid == null && remoteRev > 0L && localCount > 0

    /**
     * 首次同步的并集：**云端为主序，本地"云端没有的"追加保留**（v0.61.21 · fix2）。
     *
     * · 同 id 以**云端**为准 —— 本设备没见过云端，云端那份才是有基线的那份；
     * · 只并入本地独有的 —— 那些必然是本设备新建的（前提见 [shouldMergeFirstSync]）。
     *
     * 顺序刻意保持：云端顺序不动（列表观感稳定），本地新增按原顺序排在后面。
     */
    fun mergeFirstSync(cloud: List<Persona>, local: List<Persona>): List<Persona> {
        val cloudIds = cloud.map { it.id }.toHashSet()
        return cloud + local.filter { it.id !in cloudIds }
    }

    /**
     * 单次云同步里，**头像 base64 总量**的上限（v0.61.21）。
     *
     * 后端对人设密文的上限是 5MB，而密文是在明文基础上加密再 base64（约 ×1.33）。
     * 于是明文（人设文本 + 头像 base64）得留出余量：取 3MB 给头像，
     * 剩下的足够装下几万个字符的人设文本。
     */
    const val AVATAR_BUDGET_BYTES = 3 * 1024 * 1024

    /**
     * 按体积预算挑**能带上哪些头像**。
     *
     * 为什么要有预算：头像 base64 是整份快照里唯一会"几个就吃满配额"的东西。
     * 一旦超限，后端 400、**整份人设都传不上去** —— 那是拿"少数几个头像"换掉了
     * "全部人设能不能同步"，本末倒置。所以超预算时**少带几个**（退化成本次同步没有头像，
     * 也就是改动之前的行为），人设本体照常同步。
     *
     * ⚠️ 超预算的那条是 **skip 而不是 break**：一张特别大的图不该让它后面
     *    那些小图也一并被丢掉。
     *
     * @param ordered `(personaId, base64)`，调用方按自己的优先级排好（这里不改顺序）
     */
    fun fitAvatars(
        ordered: List<Pair<String, String>>,
        budgetBytes: Int = AVATAR_BUDGET_BYTES,
    ): Map<String, String> {
        var used = 0
        val out = LinkedHashMap<String, String>()
        for ((id, data) in ordered) {
            if (data.isEmpty()) continue
            if (used + data.length > budgetBytes) continue
            out[id] = data
            used += data.length
        }
        return out
    }
}

/* ═══════════════ 本地人设的账号归属（v0.61.24.3）═══════════════ */

/**
 * 本地人设属于谁 —— 换账号时用它决定"能不能当成自己的用"。
 *
 * ## 为什么需要它（用户 2026-10-05 报告的真 bug）
 * 本地人设库是**设备级**的（存储键不含 uid），登出也刻意不清人设。
 * 于是换账号后：① 上一账号的人设**直接显示**；② 同步判据把
 * "本地有内容 + 新账号云端为空" 判成**上传** → **用新账号的密钥把上一账号的人设
 * 推上云端**（一旦写入就真属新账号，**不可逆**）。
 */
enum class LocalPersonaOwner {
    /** 与当前账号一致 —— 正常使用 */
    SAME_ACCOUNT,

    /** 从未记录过归属（本机用户还没登录过）—— 首次登录时**绑定**给当前账号 */
    NO_OWNER,

    /** 属于**别的**账号 —— 必须隔离：不显示、不上传 */
    OTHER_ACCOUNT,
}

/** 归属值规范化（去空白）后比较。 */
fun classifyLocalOwner(ownerUid: String?, currentUid: String): LocalPersonaOwner {
    val owner = ownerUid?.trim().orEmpty()
    val current = currentUid.trim()
    return when {
        owner.isEmpty() -> LocalPersonaOwner.NO_OWNER
        owner == current -> LocalPersonaOwner.SAME_ACCOUNT
        else -> LocalPersonaOwner.OTHER_ACCOUNT
    }
}

/** 允许把本地人设当作"当前账号的"来用吗（显示 / 合并）。 */
fun canAdoptLocalPersonas(ownerUid: String?, currentUid: String): Boolean =
    classifyLocalOwner(ownerUid, currentUid) != LocalPersonaOwner.OTHER_ACCOUNT

/**
 * 允许把本地人设**上传**到当前账号云端吗。
 *
 * ⚠️ 与 [canAdoptLocalPersonas] 当前同判，但**语义不同、分开命名**：
 *    将来若"未登录时建的人设要不要自动上传"改口径，改这里即可，不影响显示。
 *    隔离态（别人的账号）**一律禁止上传** —— 这是防"污染他人云端"的最后一道闸。
 */
fun canPushLocalPersonas(ownerUid: String?, currentUid: String): Boolean =
    classifyLocalOwner(ownerUid, currentUid) != LocalPersonaOwner.OTHER_ACCOUNT
