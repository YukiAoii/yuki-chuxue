package ai.yuki.chuxue.data

/**
 * 「用户人设」—— 用户在角色扮演里**自己的角色**（v0.61.41，用户拍板的新功能）。
 *
 * ## 它解决什么
 * 用户原话：「需要做个用户人设列表和用户人设编辑和新建界面…等同于就是角色扮演」。
 * 在这之前，"用户是谁"只有 [Persona.userNickname] / [Persona.userGender] 两个小字段；
 * 用户想演一个完整的身份（名字 / 身份 / 性格 / 说话方式）没有地方可写。
 *
 * ## ⚠️ 核心难题：别让 AI 把用户写的人设当成它自己
 * 三层防御（这是本功能的技术核心，改动时别拆掉任何一层）：
 * 1. **数据分离**（本文件）：用户人设是**独立实体**，不是 [Persona] 的字段 ——
 *    同一份用户人设可跨多个 AI 角色复用（"我在所有角色面前都是同一个我"），
 *    塞进 Persona 就得复制 N 份、改一处漏 N-1 处；
 * 2. **结构分离**（`PromptEngine.buildFrozenPrefix`）：正文永远进「# 关于用户」段、
 *    并附**第一人称指向条款**（"与你对话的人"/"不是你自己"），绝不与 AI 角色设定
 *    拼进同一段；
 * 3. **界面话术**（编辑页）：提示语明确区分"这里写**你**；Ta 是谁写在角色设定里"。
 *
 * ## 缓存代价（用户拍板的取舍）
 * [roleText] 进**冻结前缀**（与昵称/性别同段）—— 改它一次 = 该角色所有会话的
 * 缓存从改动处全碎。界面（编辑页）必须如实提示这个代价。
 * 绑定关系 [Persona.userPersonaId] 本身不进前缀，但它决定 [roleText] 是否被读到 ——
 * 换绑定同样会碎缓存。
 */
data class UserPersona(
    val id: String,
    /** 这个角色的名字（用户作为"这个身份"时叫什么）。列表与详情展示用。 */
    val name: String = "",
    /** 核心字段：「你在这个角色里是谁」—— 自由文本，**进冻结前缀**。 */
    val roleText: String = "",
    /** 纯给自己看的备注（比如"哪些角色在用"）。**不进任何提示词**、不影响缓存。 */
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 用户人设的集合操作（纯函数，可 JVM 测）。 */
object UserPersonas {

    /**
     * 解析「某个角色当前绑定的用户人设」—— **唯一口径**。
     *
     * 三个消费方（冻结前缀 / 上下文估算 / 记忆抽取）共用它 ——
     * 各写各的就会出现"前缀注入了、估算没算"那种不一致。
     *
     * 绑定为空 / id 找不到（人设被删留下的悬空引用）/ [UserPersona.roleText] 全空白
     * → `null`（= 不注入任何东西，行为与这个功能不存在时一致）。
     */
    fun resolve(persona: Persona, all: List<UserPersona>): UserPersona? {
        val id = persona.userPersonaId?.takeIf { it.isNotBlank() } ?: return null
        return all.firstOrNull { it.id == id }?.takeIf { it.roleText.isNotBlank() }
    }
}
