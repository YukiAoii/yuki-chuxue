package ai.yuki.chuxue.data

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/* ═══════════════════════════════════════════════════════════════════════════
   领域模型
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * 一条对话消息。
 *
 * ⚠️ 写进历史后**永不修改** —— 这是缓存命中的前提：它一旦成为历史，
 * 就是后续所有请求前缀的一部分，改一个字节前缀就断在那里。
 *
 * ## 为什么标 `@Immutable`（v0.50.5）
 * `images` 是 `List<String>`，而 Compose 编译器**无法证明** `List` 不可变
 *（接口，实现可能是 `MutableList`）→ 整个 `ChatMessage` 被判为 unstable →
 * `MessageBubble(msg = …)` **永远不会被跳过重组**。
 * 后果是列表里任何一处状态变化（滚动、键盘、流式增量）都会让窗口内
 * **每一条消息**重跑"剥附录 + 剥情绪标签 + 切句 + 格式化时间" ——
 * 这正是用户报的"滑动历史有点间歇性卡顿"的**放大器**。
 *
 * 标上它等于**向编译器承诺**：实例构造后不再改变。
 * 本项目对这条承诺有硬保障 —— "messages 写入后不改写"是红线。
 */
@Immutable
data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    /**
     * 图片，以 base64 data URL 形式内联（`data:image/jpeg;base64,...`）。
     *
     * ⚠️ 官方限制（见 memory/api-deepseek.md）：
     * - **图片只能出现在 `user` 消息里** —— system/assistant 带图会返回 400；
     * - 单图 base64 内联最大 32 MiB，整个请求体最大 48 MiB；
     * - 格式必须是 JPEG / PNG / GIF / WebP（按文件实际内容判断）。
     *
     * ⚠️ 缓存影响：图片属于「本轮新内容」，天然不在缓存范围内，
     * 但**它会让该条消息之后的全部内容无法命中** —— 所以带图消息应尽量
     * 放在对话末尾发送，而不是隔几轮插一条。
     */
    val images: List<String> = emptyList(),
    /**
     * 这条消息的时间戳（毫秒）。
     *
     * ⚠️ **只用于界面显示**（聊天窗口的时间分割条），**绝不进请求体** ——
     * 它一旦进了稳定前缀，缓存就从那里碎掉（§11 三铁律，本项目最贵的教训）。
     * `PromptEngine` 只读 [role] / [content] / [images]，不认识这个字段。
     *
     * 默认 `0L` = 时间未知（加这个字段之前存下的老消息）。界面遇到 0 就**不画**分割条，
     * 而不是画一个 1970 年的时间。
     */
    val createdAt: Long = 0L,
    /**
     * 这条回复的**思考过程**（`reasoning_content`）。`null` / 空 = 模型没输出思考。
     *
     * ⚠️ 与 [createdAt] 同类：**只上屏，绝不进请求体**。
     * 思考内容是模型的输出、不是输入；把它拼进历史会让下一轮前缀凭空长出几 KB
     * 且与模型自己的记录不符 —— 除了缓存失效，还白花钱。
     */
    val reasoning: String? = null,
    /**
     * 这条回复的**思考用时**（毫秒）。`null` = 没开思考、或这次没量到。
     *
     * ⚠️ 与 [createdAt] / [reasoning] 同一类：**只上屏、绝不进请求体**。
     * 它是「她想了多久」这个观感的来源；加进历史只会让前缀凭空变化。
     */
    val thinkingMs: Long? = null,

    /**
     * 这条回复**生成时**用的呈现方式（v0.45.1）。
     *
     * ## ⚠️ 为什么必须冻结在消息上
     * 用户报的现象：把设置从「连发」切到「一次性」之后，**之前那些连发的气泡全并成一枚了**；
     * 根因是渲染时**实时读**全局设置 —— 于是一改设置，**历史消息的呈现也跟着变**。
     * 那不是"设置生效"，那是**把已经发生过的事改写了**：
     * 她当时分四条说，这件事本身就是那段对话的样子。
     *
     * 现在渲染只看这个字段（`null` = 加它之前的老消息，按"不拆"处理 —— 最保守，
     * 不会让老记录突然长出几枚本来没有的气泡）。
     */
    val sendMode: String? = null,

    /**
     * 这条回复**生成时**的「分段气泡」开关（v0.61.6）。
     *
     * ## ⚠️ 为什么必须冻结在消息上（与 [sendMode] 同一条纪律）
     * 用户要求：「老消息保持原样」。分段气泡是**新功能**（v0.61.4）——
     * 若不冻结，老消息会随着这个开关"突然长出几枚本来没有的气泡"，
     * 那正是 [sendMode] 那段注释里说的「把已经发生过的事改写了」。
     *
     * `null` = 加这个字段之前的消息（v0.61.6 以前）—— 渲染时**完全保持原样**：
     * 整段一枚、思考画在气泡内部（那两样都是它们当时的样子）。
     */
    val splitBubbles: Boolean? = null,

    /**
     * 这条回复**被重新生成替掉的历史版本**（v0.61.11，用户要求）。
     *
     * ## 用户要的
     * 「长按气泡可以重新生成 AI 消息 …… 然后历史输出的消息可以点气泡下方的
     * **左右切换按钮**切换到生成之前的消息 —— 但这个消息只是本地留了、**不进缓存**，
     * 仅供用户复制查看」。
     *
     * 顺序：**最早的在前**，[content] 是当前版本（`versions = superseded + content`）。
     *
     * ## ⚠️ 三条边界
     * - **绝不进请求体**：`PromptEngine` 只读 role / content / images ——
     *   老版本永远不回传给模型（否则她会被自己写过的话再喂回去，既乱又费钱）；
     * - **不进会话预览**：预览取的是 [content]（当前版本）；
     * - 它随消息一起落库（Room），所以**重启之后仍能回看** —— 用户要的"本地留了"。
     */
    val superseded: List<String> = emptyList(),

    /**
     * 这条回复配的**表情包路径**（v0.45.1）。
     *
     * ⚠️ 它以前只活在内存里（`ChatViewModel._emojiByMessage`），于是**退出再进来图就没了**；
     * 切换呈现方式时也会丢（用户报的"切换之后发送的表情包也会丢失"）。
     * 落到消息上之后，"哪张图属于哪条回复"变成历史的一部分，不再随进程或设置蒸发。
     *
     * ⚠️ 它**仍然不进请求体**：`PromptEngine` 只读 role / content / images，
     * 不认识这个字段 —— 图只在渲染时画出来（理由见 `EmojiPackEntity` 的类注释：
     * assistant 带图会 400，且历史里一旦有图，它之后的内容全部无法命中缓存）。
     */
    val emojiPath: String? = null,
) {
    val hasImages: Boolean get() = images.isNotEmpty()
}

/**
 * 人设（开发文档 2.2）。
 *
 * 设计理念：**最小必填 + 最大自由**。
 * 只强制用户昵称与性别（每轮对话都要用来称呼用户），其余全部写在 [customPrompt] 自由框里。
 *
 * ⚠️ 缓存纪律（2026-10-06 修正）：**不是**"除头像外全进前缀"。以
 * `PromptEngine.buildFrozenPrefix` 的**读取集**为准 —— 当前只有
 * [useGlobalPrefix] / [userNickname] / [userGender] / [personality] / [customPrompt]
 * 进 Frozen Prefix；其余字段（头像 / 备注 / 置顶 / 简介 / 表情概率…）改了**不碎缓存**。
 * 改上面那五个字段会让该人设的**所有会话**前缀从改动处全碎 → 界面要提示代价。
 */
data class Persona(
    val id: String = "",
    /** 必填：用户希望角色怎么称呼自己 */
    val userNickname: String = "",
    /** 必填：用户性别 */
    val userGender: String = "",
    /** 可选：角色性格，留空即不设固定性格 */
    val personality: String? = null,
    /** 必填：自由编辑框全文（年龄/爱好/世界观都写这里） */
    val customPrompt: String = "",
    /**
     * **角色名称**（v0.61.10，用户要求）—— 单独一个填写项。
     *
     * ## 它解决的问题
     * 以前"她叫什么"**必须写在角色设定的第一行**（编辑页的提示语教用户这么写），
     * 列表再从 `customPrompt` 里正则抓「角色名称：X」。用户原话：
     * 「解决角色设定第一行必须是角色名称的问题 …… 这个名称不代表角色人设的名称，
     * 而是类似备注的东西」。
     *
     * ## ⚠️ 老用户适配：空串 = 走老路径
     * 老数据没有这一栏（[PersonaCodec] 读成空串）→ [displayName] 依次回退到
     * 设定里的「角色名称：X」→ 设定首行 → 「Ta」。**老数据在界面上一个字都不会变。**
     *
     * ⚠️ 它**不进 Frozen Prefix**（`PromptEngine` 的人设段落不读它）—— 改名字
     * **不会**让已有会话的缓存碎掉。
     *
     * ⚠️ **但有一条间接路径**（审查核出来的，所以这段注释不能写得太满）：
     * 开场白里的 `{persona_name}` 占位符取的是 [displayName]
     *（`PromptEngine.buildGreeting`），而那句话会作为**新建会话**的第一条消息进历史 ——
     * 所以改 roleName 会改变**之后新建**会话的开场白文本。
     * 已存在的会话不受影响（它们的话早就冻结在历史里了）。
     */
    val roleName: String = "",
    /** 头像本地路径。**不进 Frozen Prefix**，随便改不影响缓存 */
    val avatarPath: String? = null,
    /**
     * AI 为这个人设写的一小段简介（v0.61.21）—— **详情页展示用**。
     *
     * ## 为什么要有它
     * 用户原话：「人设详情界面不一定非得硬编码和计算东西，你可以让 AI 填人设详情界面的内容」。
     * 原来是几张"资料"卡把设定拆成字段罗列，读起来像表单；
     * 换成一小段话之后，详情页才像"关于 Ta 的一页"。
     *
     * ⚠️ **不进 Frozen Prefix**：它由 AI 生成、只给界面看。若进了提示词，
     *    "改一次展示文案"就会碎掉该人设所有会话的缓存 —— 那是本项目最贵的一条红线。
     *
     * ⚠️ `null` = 还没生成过（新老数据都一样）。**界面必须"没有就不显示"**，
     *    不能因为没生成成功就留一块空的。
     */
    val detailSummary: String? = null,
    /**
     * 生成 [detailSummary] 时用的**输入指纹**（见 [detailSummaryFingerprint]）。
     *
     * 它回答"什么时候该重新生成"：人设的设定改了，那句简介就过期了。
     * 用指纹而不是时间戳 —— 时间戳会被"打开一次编辑页又原样保存"刷掉，
     * 于是白白再花一次额度。
     */
    val detailSummaryKey: String? = null,
    /** 开场白。会话创建时作为首条 assistant 消息写入历史，之后不再变 */
    val greeting: String? = null,
    /**
     * 这个人设的表情包发送概率**覆盖值**（用户 2026-09-28 定的混合模式：
     * 「全局设默认，人设可覆盖 —— 用户不用管每个人设，想精调时也能单独改」）。
     *
     * `null` = **用全局默认**（`AppSettings.emojiChance`）—— 这也是"没配过"的自然表示，
     * 所以默认值就是 null，不需要额外的开关字段。
     *
     * ⚠️ 它**不进冻结前缀**（`PromptEngine` 只读昵称/性别/性格/自由设定），
     * 所以改它**不会让任何会话的缓存失效** —— 与"改人设文字会让该人设所有会话全碎"正相反。
     */
    val emojiChanceOverride: Float? = null,
    /**
     * 这个人设**是否使用「通用设定」**（全局前缀）。**默认关闭**（v0.53.0）。
     *
     * 三态语义（**这是兼容老用户的关键**）：
     * - `null` = **从未设置过**（老数据里没有这一栏）→ 沿用 `AppSettings.globalPrefixEnabled`
     *   —— 也就是**升级前的行为原样保留**，前缀一个字节都不变，**老用户缓存不碎**；
     * - `true` / `false` = 用户在这个人设上**显式**选过。
     *
     * 新建设的人设一律写显式 `false`（默认关）。于是：
     * **老用户升级 = 行为不变；新用户 = 默认不使用通用设定。**
     *
     * ⚠️ 它**进入 Frozen Prefix** —— 显式打开 / 关闭它会让这个人设的**所有会话**
     * 前缀从改动处断开，缓存碎一次（下一次请求按未命中计费）。界面必须把这句话说清。
     */
    val useGlobalPrefix: Boolean? = null,

    /**
     * **给人设写的一句备注**（v0.56.0，用户要求）。
     *
     * ## ⚠️ 它只是备注 —— 不参与任何计算
     * 用户原话："人设不读备注做缓存，字面意思就只是个备注"。
     * 所以它：
     * - **不进 Frozen Prefix**（改它**不会**让任何会话的缓存碎掉 —— 它压根不进请求）；
     * - **不进记忆提取 / 不进检索 / 不进任何提示词**；
     * - 只在**人设详情页和人设列表**上给人看。
     *
     * ⚠️ 这条边界必须守住：一旦有人"顺手"把它拼进 system prompt，就会变成一个
     *    **改了会静默打碎全部缓存的输入框** —— 而界面上看不出这个代价。
     *    [Persona.useGlobalPrefix] 的注释说明了同类风险（那个确实进前缀，所以要说清）。
     */
    val note: String = "",
    /**
     * 在**人设列表**里是否置顶（用户 2026-09-28 要求）。
     *
     * ⚠️ 它与 `Session.pinned`（会话列表的置顶）是**两件事**，用户明确要求
     * 「这个置顶跟消息列表的置顶完全独立，互不影响」：那个管"哪段对话排前面"，
     * 这个管"哪个人设排前面"。
     *
     * ⚠️ 人设整体住在 DataStore（JSON），所以加这个字段**不需要 Room 迁移** ——
     * 老数据缺这一栏时 [Codec] 读成 false，行为与加它之前完全一致。
     */
    val isPinned: Boolean = false,
    /**
     * **是否把这个人设接入「Ta 的状态」**（服务器端状态同步，用户 2026-10-06 拍板的功能）。
     *
     * 开启后：这个人设的每轮对话会上报给服务器，用于生成 Ta 此刻的心情状态
     *（网页端「Ta 此刻」可见）；保存人设时还会把名字登记过去（网页上显示用）。
     * 关闭（默认）= **零上报**，对话一个字都不出设备。
     *
     * ⚠️ 它**不进 Frozen Prefix**（纯功能开关，不进任何提示词）——改它
     * **不会**让任何会话的缓存碎掉（与 useGlobalPrefix 正好相反）。
     * ⚠️ 人设整体住在 DataStore（JSON），加它**不需要 Room 迁移**；老数据缺这一栏
     * 时 [Codec] 读成 false（= 升级前行为，零上报）。
     */
    val xinchaoEnabled: Boolean = false,
    /**
     * **是否把这个角色的记忆同步到「云端记忆」（OB 记忆大脑）**（用户 2026-10-06 拍板）。
     *
     * 开启后：新记下的记忆会**自动**送到云端；关着（默认）= 一条都不上云。
     * 与 [xinchaoEnabled] **独立**（一个是"看 Ta 此刻"、一个是"记忆上云"），
     * 但云端记忆页要能读到 OB，所以**依附**于 [xinchaoEnabled]（没接入就没有桶）。
     *
     * ⚠️ **隐私边界**：上云的记忆在服务器上是**明文**的（OB 要用它做语义检索/做梦，
     *    做不到端到端加密）——这与"人设快照是 E2E 密文"是两种级别，界面文案别含糊。
     * ⚠️ 不进 Frozen Prefix；人设住 DataStore，加它不需要 Room 迁移；老数据缺栏读 false。
     */
    val cloudMemoryEnabled: Boolean = false,
    /**
     * **「Ta 主动来找我」**（v0.61.40，用户 2026-10-06 拍板）——
     * 这个人设是否允许 App 去云端**取 Ta 主动想说的话**、弹本地通知。
     *
     * ## 语义（设计红线，别走歪）
     * App 只做"去信箱看看有没有信"：触发点 = 用户打开 App / 回到前台（机会式 +
     * 30 分钟节流，见 `ProactiveFetchPolicy`）。**何时说、说什么由心潮的情绪状态机决定**
     *（情绪变化 / 梦境 / 念头涌现 / 驱力变化）——接入侧**不许**出现任何
     * "到点就提醒 Ta 说话"的逻辑（心潮作者原话：不需要闹钟唤醒）。
     *
     * ## 关系
     * **依附** [xinchaoEnabled]：没接入就没有桶、也没有消息可取（两个都开才取件）。
     * 默认关；老数据缺栏读 false（= 升级前行为：一个请求都不发）。
     *
     * ⚠️ **不进 Frozen Prefix**（纯功能开关）——改它不会让任何会话的缓存碎掉。
     * ⚠️ 人设住 DataStore（JSON），加它不需要 Room 迁移。
     */
    val proactiveEnabled: Boolean = false,
    /**
     * **这个角色面前，"我"在扮演谁**（v0.61.41）—— 指向某个 [UserPersona] 的 id。
     *
     * `null` / 空串 = 没绑定（不注入任何用户人设，行为与这个功能不存在时一致）。
     * 绑定放 Persona 这一侧：用户可以给不同角色绑不同身份，也可以都绑同一个
     *（跨角色复用是 [UserPersona] 独立成实体的理由，见其类注释）。
     *
     * ⚠️ **它本身不进 Frozen Prefix**，但它指向的 [UserPersona.roleText] **会进** ——
     *    换绑定 / 解绑会让该角色的前缀变化、缓存从改动处断开，界面要提示代价。
     *    （判据以 `PromptEngine.buildFrozenPrefix` 的读取集为准。）
     */
    val userPersonaId: String? = null,
    /**
     * **记忆方式：本地 / 云端**（v0.61.48，用户 2026-10-06 拍板）。
     *
     * - `null` = **未选过**（老数据缺栏）→ 按 [MEMORY_MODE_LOCAL] 处理，**三项云端功能
     *   （接入 Ta 的状态 / 记忆上云 / Ta 主动来找我）一律关闭** —— 用户 2026-10-06 明确
     *   决定"老数据一律归本地、三项关闭"（代价已知：老数据的「Ta 此刻」会停更）。
     *   **这是一次有意的行为变更，不是"老用户零变化"** —— 原先读 `xinchaoEnabled` 的
     *   地方一律改读 [isCloudMemory]；
     * - [MEMORY_MODE_LOCAL]：记忆只在本机，不上云（「记忆上云」开关不适用）；
     * - [MEMORY_MODE_CLOUD]：**云端为母本**，本机只留一份不显示的加速快照
     *   （断网/服务器没开时降级用它；界面只显示一份，消除"串记忆"观感）。
     *
     * ⚠️ **选后不可改**（用户 2026-10-06 要求）：创建人设时定，编辑页只读。
     * ⚠️ **2026-10-06 二次调整**（用户原话）：「记忆上云在用户选择云端记忆的时候不就等于
     *    默认开启吗，不用单独做个按钮吧？然后主动发消息和 Ta 的状态单独做开关」——
     *    所以：
     *      · 「记忆上云」**不再有开关**（选云端 = 记忆就上云，这是一件事的两面）；
     *      · [xinchaoEnabled]（接入 Ta 的状态）与 [proactiveEnabled]（Ta 主动来找我）
     *        改为**各自独立的开关**，云端模式下可单独关。
     * ⚠️ 不进 Frozen Prefix（纯功能开关）——改它**不会**让任何会话的缓存碎掉；
     *    人设住 DataStore（JSON），加它**不需要 Room 迁移**。
     */
    val memoryMode: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        /** 记忆只在本机（老数据 [memoryMode] 为 null 时也按它处理）。 */
        const val MEMORY_MODE_LOCAL = "local"
        /** 记忆在云端（记忆上云随之默认开启，无独立开关）。 */
        const val MEMORY_MODE_CLOUD = "cloud"
    }

    /**
     * **是否云端记忆模式** —— 记忆走哪条轨道的**唯一判据**（v0.61.48）。
     *
     * ⚠️ 它现在只回答「记忆写哪儿 / 读哪儿」这一个问题：
     *    · true  → 记忆写 OB、读 OB（见 `MemoryExtractionScheduler` / `ChatViewModel`）；
     *    · false → 记忆只在本机 Room。
     *
     * ⚠️ **不再**决定「接入 Ta 的状态」与「Ta 主动来找我」——那两项自 2026-10-06 起
     *    是**各自独立的开关**（[xinchaoEnabled] / [proactiveEnabled]），用户可单独关。
     *    但两者仍**依附**云端：本地模式没有云端桶，开了也没用（界面在本地模式下不显示它们）。
     */
    val isCloudMemory: Boolean get() = memoryMode == MEMORY_MODE_CLOUD

    /**
     * 用于列表与标题展示的名字（**唯一口径**，v0.61.10 统一）。
     *
     * ⚠️ 在此之前有**两套口径**：这里用正则抓「角色名称：X」（抓不到就说"未命名角色"），
     * 而 `ChatViewModel.displayNameOf` 取的是**首行**（没有就说"Ta"）——
     * 同一个人设在列表与标题上显示不同名字。现在两处都走这里。
     *
     * 回退链（**顺序本身就是老用户兼容**）：
     * 1. [roleName] —— 单独填的那个（新）；
     * 2. 设定里的「角色名称：X」—— 老数据的既有写法；
     * 3. 设定**首行** —— 老 `displayNameOf` 的口径（宁可显示一行设定，也不要"未命名角色"）；
     * 4. 都没有 → 「Ta」。
     */
    val displayName: String
        get() {
            roleName.trim().takeIf { it.isNotBlank() }?.let { return it }
            Regex("""角色名称[：:]\s*(\S+)""").find(customPrompt)
                ?.groupValues?.get(1)?.let { return it }
            customPrompt.substringBefore('\n').trim()
                .takeIf { it.isNotBlank() }?.let { return it }
            return "Ta"
        }

    /** 是否已填够必填项，可以用于建会话 */
    /**
     * 「信息填得全不全」—— **只用来提示，不拦任何操作**。
     *
     * ⚠️ v0.61.21：准入判据在 [canSave]；这一条只管"提示"。
     *
     * ⚠️ 同一次改动里**去掉了 `userNickname`**：那一栏已从编辑页移除
     *（用户要求），新建的人设会常年为空 —— 再拿它当判据，
     * 所有人设都会显示"没填完"，那就等于这条判据失效。
     *
     * ⚠️ **2026-10-06 再去掉 `userGender`**（用户原话：「你的性别这个改到用户的人设卡吧，
     *    再改成不需要单独有个输入框的，让用户直接在他的设定里写就行」）——
     *    理由与 `userNickname` 完全相同：那一栏已从编辑页移除，新建人设会常年为空，
     *    再拿它当判据就等于判据失效。
     *    判据只剩「人设本身写活了没有」：**角色设定**。
     *    （`userGender` 字段**保留**：老用户填过的值照旧进提示词，只是不再让人填。）
     */
    val isUsable: Boolean
        get() = customPrompt.isNotBlank()

    /**
     * 能不能保存 / 创建（v0.61.21）。
     *
     * ⚠️ 与 [isUsable] **刻意分开**：那个是"全不全"，这个是"有没有实质内容"。
     *    判据只留 **角色设定** —— 那才是这个人设的实质。
     *    `userNickname` / `userGender` 描述的是**你**，不是 Ta，不该成为 Ta 能否诞生的门槛。
     *
     * 这一条也**顺手**把 Wave 4 那颗雷拆了：去掉「怎么称呼你」之后
     * `userNickname` 会常年为空，若按钮继续用 [isUsable]，它会**永久禁用**、谁都建不出人设。
     */
    val canSave: Boolean
        get() = customPrompt.isNotBlank()

    /**
     * 生成 [detailSummary] 的**输入指纹**：只取真正会改变那句话的字段。
     *
     * 刻意**不含** [avatarPath] / [note] / [isPinned] / 时间戳 —— 那些改了不影响简介内容，
     * 把它们算进去只会让"换张头像"白花一次额度。
     * 最后取 `hashCode` 的十六进制：只是为了短（要跟着快照上云），
     * 碰撞的后果仅仅是"简介没重生成"，可接受。
     */
    val detailSummaryFingerprint: String
        get() = listOf(roleName, customPrompt, personality.orEmpty(), userGender)
            .joinToString("\u0001")
            .hashCode()
            .toString(16)

    /**
     * 该不该（重新）生成简介。
     *
     * 两种情况要生成：**从没生成过**（null/空白），或**人设内容改过了**（指纹对不上）。
     * 生成失败时调用方**不要写 `detailSummaryKey`** —— 保持"待生成"状态，
     * 下次启动再试；否则一次网络抖动就把它永久钉成"生成过了"。
     */
    val needsDetailSummary: Boolean
        get() = detailSummary.isNullOrBlank() || detailSummaryKey != detailSummaryFingerprint
}

/**
 * 「我」是谁 —— 本地用户资料（头像 + 昵称）。
 *
 * ## 为什么现在就有了它（而账号系统还没做）
 * 搜索聊天记录要区分"我说的"和"她说的"。**文案不如头像**：一边是她的头像与名字、
 * 一边是我的头像与名字，用户一眼就分得清，而且顺带认得出这是哪段对话、跟谁在聊。
 *
 * ## 它不等同于账号
 * 这是一个**纯本地**的资料，没有 uid、没有服务端、换手机就没了。
 * 开发文档 §39 的「我的」页是围绕 `UserEntity`（账号）设计的 ——
 * 那套要等后端（§32 认证流程）落地。届时这个类会变成
 * "账号资料的本地缓存"，字段加 `uid` 即可，**不需要推翻重来**
 * （设计文档见 `docs/后端与我的页设计_v1.0.md`）。
 *
 * ⚠️ 与缓存无关：它**不进请求体**，也不进冻结前缀。改头像/昵称不会让任何会话的
 * 缓存失效 —— 这一点与 [Persona] 正好相反（改人设文字会让该人设全部会话缓存全碎）。
 */
data class UserProfile(
    /** 展示名。空 = 用「我」 */
    val nickname: String = "",
    /** 本地头像文件路径；null = 用默认（浅色圆 + 「我」字） */
    val avatarPath: String? = null,
    /**
     * 本地这张头像**是从哪个服务器 URL 拉下来的**（v0.59.0）。
     *
     * ## 它修的是什么
     * 拉取判据原先是"本地没图才拉" —— 于是**在别的设备换了头像，这台永远看不到**
     * （本地有图就不再拉）。记下来源 URL 之后，判据变成
     * "本地的来源 ≠ 服务器当前的" → 改了才会重拉。
     *
     * - `null` = 这张是**本地自己选的**（没从服务器拉过），或老数据没有这一栏
     * - 非 null = 上次从服务器拉到它时，服务器给的就是这个 URL
     */
    val avatarSourceUrl: String? = null,
) {
    val displayName: String get() = nickname.ifBlank { "我" }
}

/**
 * 本地保存的**登录态**（开发文档 §32）。
 *
 * ## 它和 [UserProfile] 的分工（别把它们混成一件事）
 *
 * | | 权威来源 | 存什么 | 换设备后 |
 * |---|---|---|---|
 * | [AuthSnapshot] | **服务端** | uid / 令牌 / 账号昵称 / 账号头像 URL | 登录后自动回来 |
 * | [UserProfile] | **本机** | 头像的**本地文件路径** | 消失（要重新选） |
 *
 * 简单说：uid 与账号昵称**属于账号**，所以从服务器来；头像文件是本机的，
 * 所以只存路径。两者的昵称若不一致，**以 [AuthSnapshot] 为准**
 * （它是服务端权威值）。
 *
 * ## 为什么令牌明文存在 SharedPreferences 里
 * 这是本项目**唯一**的取舍点：`EncryptedSharedPreferences` 要多引一个
 * androidx.security 依赖。当前判断是**不引** —— 因为这些令牌的权限只到
 * "读自己的昵称、改自己的昵称"，读不到对话、人设、记忆（那些全在本机且不上传）。
 * ⚠️ 一旦将来把「云备份」做起来（备份里含聊天记录），**必须改成加密存储** ——
 * 那时令牌的权限等级完全不同了。
 */
data class AuthSnapshot(
    val uid: String = "",
    val token: String = "",
    val refresh: String = "",
    val nickname: String = "",
    val avatarUrl: String? = null,
    val email: String? = null,
) {
    /** 有 uid 且有 access 令牌才算登录。 */
    val isLoggedIn: Boolean get() = uid.isNotBlank() && token.isNotBlank()

    val displayName: String get() = nickname.ifBlank { "用户 $uid" }
}

/**
 * 一段对话。
 *
 * ⚠️ [messages] **只追加、永不修改**。任何「编辑历史 / 重排 / 删中间某条」
 * 都会让下一轮前缀与上一轮错位，缓存从此不再命中。
 */
data class Session(
    val id: String = "",
    /** 绑定的人设 id —— 会话与人设一一对应，缓存前缀因此隔离 */
    val personaId: String = "",
    val title: String = "新的对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val totalHit: Int = 0,
    val totalMiss: Int = 0,

    /* ── 会话级设置（对话设置页）── */

    /** 消息免打扰：不影响功能，只表示「这个会话别吵我」 */
    val muted: Boolean = false,

    /** 置顶：在会话列表里排在最前 */
    val pinned: Boolean = false,

    /** 聊天背景标识（如 `"sakura"`）；`null` = 用默认水彩底 */
    val background: String? = null,

    /**
     * 这个会话是否开思考模式。
     *
     * 新建会话时**从全局设置复制一份**，之后各会话独立 —— 用户可以
     * 「跟初雪深聊时开思考，随口闲聊时关掉」，而全局设置只是新会话的初值。
     */
    val thinkingEnabled: Boolean = true,

    /** 这个会话的思考强度（`low` / `high` / `max`） */
    val reasoningEffort: String = "high",

    /* ── 背景遮罩（外观；只对**自定义图片**背景有意义）── */

    /**
     * 背景遮罩是否开启。
     *
     * ⚠️ 内置预设背景本身就是浅色渐变（对比度下限由 `ChatBackgroundTest` 的亮度断言
     * 兜住），本来就不需要遮罩；**遮罩是给"用户自己选的图"用的** ——
     * 用户选的图什么亮度都有，白气泡 + 深色文字直接铺上去会有读不了的时候。
     */
    val scrimEnabled: Boolean = true,

    /** 遮罩的不透明度（0..1）。图越深，越要往上加。 */
    val scrimAlpha: Float = 0.55f,

    /** 遮罩风格（`SCRIM_*` 常量之一）。 */
    val scrimStyle: String = SCRIM_PLAIN,

    /**
     * **上下文压缩摘要**（v0.45.0）。
     *
     * 当历史长到快撞上模型上下文上限时，较早的那一整段会被交给模型总结成这段文字；
     * 之后**发给模型的** history 就是「这段摘要 + 最近若干条原文」（见 [ContextCompress]）。
     *
     * ## ⚠️ 它不影响库里与界面上的聊天记录
     * `messages` 一条都没动、界面照旧显示全部 —— 用户原话「聊天记录不能丢失」。
     * 摘要只出现在**请求组装**这一步。
     *
     * `null` = 还没压缩过（这也是"没配过"的自然表示，不需要额外的开关字段）。
     */
    val summary: String? = null,

    /**
     * 摘要**覆盖到**哪一条（那条消息的 `createdAt`；0 = 没压缩过）。
     *
     * ## ⚠️ 它是为了缓存命中才存在的（v0.45.6）
     * 压缩后发给模型的 history 是「摘要 + 之后的消息」。如果"之后的消息"用
     * `takeLast(30)` 这种**滑动窗口**取，每加一条新消息窗口整体右移 ——
     * **窗口里第一条就变了**，而 DeepSeek 的缓存按**前缀**匹配：
     * 前缀一开头就不同，后面整段全部 miss（用户看到的正是"压缩后命中率卡在 80%"）。
     *
     * 记下切点之后，历史变成「摘要 + 切点之后的消息」——**每轮只追加**，
     * 前面那一大段永远逐字节相同，缓存才真正吃得满。
     */
    val summaryUpTo: Long = 0L,

    /**
     * 压缩**那一刻**的累计命中/未命中快照（v0.46.1）。
     *
     * ## 为什么需要它
     * `totalHit/totalMiss` 是**累计**的。压缩那一次因为前缀全变，会把当轮**全额计入未命中** ——
     * 长对话里那可能是几万 token。之后每轮虽然正常命中，**累计命中率却被那一坨分母拖住**，
     * 要很多轮才爬得回来。用户看到的是"压完之后命中率不增反降"，其实每轮都在命中。
     *
     * 记下快照后，看板就能算**"压缩之后"的命中率**：拿现在的累计值减去快照，
     * 得到的才是"压缩以来真实的表现"。这比只看累计值有用得多 ——
     * 否则用户永远不知道是自己有问题、还是那个统计口径的问题。
     */
    /**
     * 摘要**覆盖了多少条消息**（v0.46.5）。
     *
     * ## ⚠️ 为什么不用时间戳（`summaryUpTo`）
     * 原来用 `filter { it.createdAt > upTo }` 取"切点之后的消息" ——
     * 而 `createdAt` **不可靠**：迁移前的老消息是 0，压缩时 `segment.lastOrNull()?.createdAt`
     * 取到 0，`upTo` 就成了 0，于是退回 `takeLast(30)` 滑动窗口 ——
     * **前缀每轮都变**，命中率自然上不去（实测：输入只发 5510 token，命中仅 512）。
     *
     * 改用**条数**：压缩时记下"压掉了前 N 条"，之后 `drop(N)` —— 不依赖任何时间戳，
     * 而且**只追加、不滑动**，前缀逐字节稳定。
     */
    val summaryCount: Int = 0,
    val hitAtCompress: Int = 0,
    val missAtCompress: Int = 0,

    /* ── 会话级 provider（v0.51.0，「分组」功能）── */

    /**
     * 这段对话用哪个**连接分组**（`ProviderGroup.id`）。
     *
     * `null` = 用全局当前分组。与 [thinkingEnabled] 同一条路：
     * **新建会话时留空**（跟着全局走），用户显式选过之后才钉在这一段会话上。
     *
     * ⚠️ 存的是 **id 不是配置本身**：分组可以被改名、改密钥，
     * 而这段对话应该跟着**那个分组**走 —— 存快照会让"改了密钥的会话还用旧密钥"。
     * 分组被删掉时由 `ProviderGroups.resolveActive` 退回第一个（不报错）。
     */
    val providerGroupId: String? = null,

    /**
     * 这段对话用哪个**模型**。
     *
     * `null` = 用分组里勾选的第一个。用户从聊天输入框的「+」里选过之后才写这里。
     *
     * ⚠️ 它**不参与缓存前缀**（选择本身不进请求体），但它决定了请求里
     * `model` 字段的值 —— 而**换模型等于换一份服务端缓存**：
     * 同一个前缀在 A 模型下命中、在 B 模型下未必。所以切换只在用户显式操作时发生。
     */
    val model: String? = null,
) {
    val preview: String
        get() {
            // ⚠️ **先看最后一条消息的形态**（v0.61.14，用户要求）：
            //    她发的是表情包 → 「[动画表情]」；你只发了图（没写字）→ 「[图片]」。
            //    不能因为它 content 为空就跳过、去显示**更早**那条的文字 ——
            //    列表回答的是"刚刚发生了什么"，不是"最近一句文字是什么"。
            val lastMsg = messages.lastOrNull() ?: return "还没有开始说话"
            if (!lastMsg.emojiPath.isNullOrBlank()) return "[动画表情]"
            if (lastMsg.hasImages && lastMsg.content.isBlank()) return "[图片]"
            val last = messages.lastOrNull { it.content.isNotBlank() }?.content
                ?: return "还没有开始说话"
            val cleaned = EmojiCategories.stripTags(
                // 剥掉后台注入的附录 —— 它是给模型看的，不该出现在列表预览里
                TranscriptText.stripAppendix(last),
                // 情绪标签也要摘：模型会在正文里写 `[开心]`，聊天页显示时会摘，
                // 列表预览原来没摘 —— 于是列表里能看见记号
                EmojiCategories.DEFAULTS,
            )
            // ⚠️ 取**最后一句**，而不是整段的开头（v0.44.2 起、v0.45.5 修正口径）。
            // 连发模式下她的一整条回复会被拆成好几枚气泡，聊天页看到的是**最后那枚**；
            // 而预览取整段开头 —— 于是"列表显示的"与"聊天页末尾的"对不上。
            //
            // ⚠️ 口径**必须**与 `WaifuBubbles` 拆句完全一致（v0.45.5 修）。
            // 上一版这里按**换行**粗切，而连发是按**句末标点**（。！？…；）拆的 ——
            // 两边根本不是一回事，所以用户报"预览不是最新消息"报了两次都没根治。
            // 现在两边共用 `TextSegments.SENTENCE_END`，一处定义、两处消费。
            val tail = TextSegments.lastSentence(cleaned)
            return tail.replace('\n', ' ').trim().take(28)
                .takeIf { it.isNotBlank() } ?: "还没有开始说话"
        }

    /**
     * 会话列表的预览，**优先显示还没发出去的草稿**。
     *
     * 用户原话：「输入框留有没发送的草稿，退出之后列表界面会显示那个会话外部消息的
     * 预览内容是［草稿］未发送的内容」。
     *
     * ## 为什么是"盖过"而不是"追加"
     * 列表那一行回答的问题是**"这段对话现在什么状态"**。手里还攥着一句没发出去的话，
     * 比上一句已经说过的话更该被看见 —— 而且草稿只活在内存里（`ChatViewModel.drafts`），
     * 退出聊天页不会丢，列表却是用户唯一能再看到它的地方。
     *
     * ⚠️ 草稿为空/全空白时**原样退回** [preview] —— "还没开始说话"、「［动画表情］」、
     * 「［图片］」这些形态判定一条都不能少。所以调用方永远传 `drafts[id]` 即可。
     *
     * ⚠️ 纯渲染：草稿不进 `messages`、不进请求体（缓存红线）。
     */
    fun previewWith(draft: String?): String {
        val text = draft?.trim()?.takeIf { it.isNotBlank() } ?: return preview
        return "［草稿］" + text.replace('\n', ' ').replace('\r', ' ')
    }

    val cacheSummary: String
        get() {
            val billed = totalHit + totalMiss
            if (billed == 0) return "尚无请求"
            // ⚠️ 走共用格式化（v0.48.0）：别把 99.6% 说成 100%
            return "命中 " + formatHitPercent(totalHit, billed)
        }
}

/** 缓存命中统计 */
data class CacheStats(
    val hitTokens: Int,
    val missTokens: Int,
    /** 上游 usage.prompt_tokens（总输入） */
    val inputTokens: Int = 0,
    /**
     * 上游 usage.completion_tokens（**输出**）。
     *
     * 过去没解析这一项 —— 于是"这一轮花了多少"只算得出输入那一半。
     * 用户要的「真实成本」必须两半都在，否则看板会系统性地低报。
     */
    val outputTokens: Int = 0,
    /**
     * OpenAI 系的**嵌套**命中量：`usage.prompt_tokens_details.cached_tokens`（v0.51.0）。
     *
     * ⚠️ `null` 与 `0` 在这里是**两件事**：
     * · `null` = 这家压根没报这个字段（协议里没有它）；
     * · `0` = 报了，就是 0（可能真的没命中）。
     * 把前者当 0 显示，用户会以为"缓存全废了"然后去改人设/改历史 —— 而那些动作
     * **真的**会把前缀弄断。所以这里用可空表示"没报"。
     *
     * ⚠️ DeepSeek 走的是顶层 `hitTokens`，这条字段与它无关（读哪个由
     * `ProviderProfile.cachedTokenField` 决定）。
     */
    val nestedHitTokens: Int? = null,
) {
    val billed: Int get() = hitTokens + missTokens
    val total: Int get() = if (inputTokens > 0) inputTokens else billed
    val hitRate: Double get() = if (billed > 0) hitTokens.toDouble() / billed else 0.0

    /** 看起来是首轮请求（全部 miss）—— 首轮无缓存可命中，属预期 */
    val looksLikeFirstRequest: Boolean get() = hitTokens == 0 && missTokens > 0

    companion object {
        val EMPTY = CacheStats(0, 0, 0)
    }
}

/**
 * 命中率百分比文本（**唯一**的格式化口，v0.48.0）。
 *
 * ## ⚠️ 它解决的那个具体问题
 * 整数除法 `hit * 100 / billed` 会把 **99.6% 显示成 100%** ——
 * 用户于是以为"完全命中了"，而实际上**每一轮都还在为那 0.4% 付未命中**。
 * 采纳 `deepseek-harness` 的做法（`client/ui-chat/src/client/chat/token-format.ts`：
 * 舍入到满值时自动增加精度），改为补一位小数说出来（`99.9%`）。
 *
 * ## 为什么必须是**一个**函数
 * 同一个数字会出现在三处（上下文弹窗、会话看板、`Session.cacheSummary`）。
 * 各写一遍必然漂移，而漂移的后果是"同一个会话在两个页面显示不同的命中率"——
 * 用户会以为其中一个在骗他。
 *
 * @return 形如 `96.9%` / `100%`；分母为 0 时返回 `—`（而不是 `0%`，
 *         那会让人以为"命中率为零"，实际是"还没有数据"）
 */
fun formatHitPercent(hit: Int, total: Int): String {
    if (total <= 0) return "—"
    val rounded = (hit * 100.0 / total).roundToInt()
    // 舍入到 100 但实际没满 → 补一位小数，别把"差一点"说成"完全"
    return if (rounded >= 100 && hit < total) {
        "%.1f%%".format(hit * 100.0 / total)
    } else {
        "$rounded%"
    }
}

/** 一次对话的结果 */
data class ChatOutcome(
    val text: String,
    val cache: CacheStats,
    /**
     * 思考过程（`message.reasoning_content`）。非流式响应里它**藏在 message 里**，
     * 与流式的 `delta.reasoning_content` 是同一个东西的两个投递方式。
     */
    val reasoning: String? = null,
    /** 服务端的 `finish_reason` —— 见 [ChatErrors.isTruncationStopReason]。 */
    val stopReason: String? = null,
)

/**
 * 流式对话的增量事件（开发文档 §9.2）。
 *
 * 契约：[Done] 在流结束时**恰好发出一次** —— 包括弱网下服务端直接断开、
 * 没来得及发 `[DONE]` 的情况。那时 [Done.fullText] 是已收到的部分，
 * **绝不能因为「没收到结束标记」就把用户等了半天的内容全丢掉**。
 */
sealed interface ChatStreamEvent {
    /** 一段正文增量 */
    data class Delta(val text: String) : ChatStreamEvent

    /**
     * 一段**思考过程**增量（思考模式）。
     *
     * 与 [Delta] 分开成两种事件，UI 才能各画各的：思考折起来单独一条、正文照常显示。
     * 早先解析层根本没读 `reasoning_content`，所以"开启思考模式"只是表象 ——
     * 见 `SseChunk.Reasoning` 的注释。
     */
    data class Reasoning(val text: String) : ChatStreamEvent

    /** 流结束 */
    data class Done(
        val fullText: String,
        val cache: CacheStats?,
        /** 服务端 `finish_reason`。`length` = **输出被截断**（见 [SseChunk.Done.reason]）。 */
        val stopReason: String? = null,
    ) : ChatStreamEvent
}

/* ═══════════════════════════════════════════════════════════════════════════
   设置
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * 全局设置。
 *
 * 注意：[systemPrompt] 已从这里移除 —— 人设不再放在 API 配置里，
 * 而是独立的 [Persona] 实体（用户要求「人设应该有独立页面」）。
 */
data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val thinkingEnabled: Boolean = true,
    val reasoningEffort: String = "high",
    /** 全局规则（所有角色共享）。进 Prompt 的第 1 层，改动会影响所有会话缓存 */
    /**
     * 通用设定的**默认开关**（v0.53.0 起语义收窄）。
     *
     * 它现在的唯一作用：**当某个人设从未显式选过**（[Persona.useGlobalPrefix] == null，
     * 即老数据）时，用哪个值。默认 `true` 是**刻意**的 —— 它正是**升级前**的行为，
     * 保证老用户的前缀一个字节不变、缓存不碎。
     *
     * 新建人设会写显式 `false`，所以新用户默认**不使用**通用设定。
     */
    val globalPrefixEnabled: Boolean = true,
    val globalPrefix: String = DEFAULT_GLOBAL_PREFIX,
    /**
     * 自动记忆（开发文档 §8.3）：对话结束后让模型回头提炼值得长期记住的事。
     *
     * ⚠️ **这个开关花的是用户自己的 API 额度** —— 每提取一次就多一次非流式调用。
     * 所以它必须**用户可见、可关**（设置页），而不是默默跑在后台。
     * 默认开：这是"她自己记下来"的来源；真正的成本护栏是
     * [ai.yuki.chuxue.data.memory.MemoryExtractionScheduler] 里的节流与空闲检测。
     */
    val autoMemoryEnabled: Boolean = true,
    /**
     * 思考内容**自动折叠**（默认开）。
     *
     * - 开：思考结束后自动收起，点一下可展开回看（默认行为）；
     * - 关：思考内容一直摊开。
     *
     * 流式进行中**一律展开** —— 那一刻的思考正是用户想看的「她正在想什么」，
     * 折起来等于把最该看的东西藏了。
     */
    val thinkingCollapseEnabled: Boolean = true,
    /**
     * 自建后端地址（**留空 = 完全本地，一个字节都不往外发**）。
     *
     * 例：`https://example.com/yuki`。填了之后可以做两件事：
     * 拉公告、上报使用统计（见 [telemetryEnabled]）。
     * 它**不参与任何对话**：DeepSeek 的请求永远直连，不经过这个地址。
     */
    val serverUrl: String = "",
    /**
     * 是否把**使用统计**上报到 [serverUrl]。
     *
     * 上报的只有计数（几段会话、几条消息、多少 token），**没有任何对话内容** ——
     * 字段清单见 `Telemetry.Snapshot`。它存在的理由是后台仪表盘需要这些数字，
     * 而服务端没有别的渠道知道。
     *
     * 默认开（用户自己填了服务器地址，就是为了看这些数字）；
     * 但**只有 [serverUrl] 非空时才会真的发**。
     */
    val telemetryEnabled: Boolean = true,

    /**
     * 打字速度（用户要求可调）。
     *
     * 用**档位**而不是毫秒数：用户想的是「快点 / 慢点」，不是「每字 47 毫秒」。
     * 取值见 [TYPE_SPEED_OFF] / [TYPE_SPEED_SLOW] / [TYPE_SPEED_NORMAL] / [TYPE_SPEED_FAST]。
     *
     * ⚠️ 它只影响**显示**：网络收流、历史写入、缓存前缀全都不受影响
     * （`Typewriter` 从一开始就只做"什么时候把已经收到的字画上去"）。
     */
    val typeSpeed: Int = TYPE_SPEED_NORMAL,

    /**
     * **回复的呈现方式**（用户 2026-09-28 明确定义，三种）。
     *
     * ⚠️ 我第一版把它理解成了「回车发送还是按钮发送」—— **理解错了**。
     * 用户要的是**她怎么把话说出来**：
     *
     * | 值 | 表现 |
     * |---|---|
     * | [SEND_MODE_STREAM] | 标准流式：一字一字在同一枚气泡里冒出来（打字机） |
     * | [SEND_MODE_INSTANT] | 一次性：整段回复直接出现在一枚气泡里 |
     *
     * 「回车发送还是按钮发送」是**另一件事**，见 [enterToSend]。
     */
    val sendMode: String = SEND_MODE_STREAM,

    /**
     * **分段气泡**（v0.61.4）：流式回复按句子拆成连续几枚小气泡，像真人发微信那样
     *（一句一枚、最多 4 枚；末尾那枚是"正在长"的）。
     *
     * ⚠️ 只影响**渲染**：气泡文本由回复正文现场切分，历史与请求体一个字节都不动 ——
     * 拆多枚不会让任何会话的缓存前缀失效。
     *
     * 拆句逻辑就是 v0.32.0「连发」那套（v0.61.0 随连发删除，v0.61.4 按用户要求
     * 在**流式模式**上恢复 —— 不再是独立模式，可以用这个开关关掉）。
     */
    val splitBubbles: Boolean = true,

    /**
     * 回车是否直接发送 —— **与 [sendMode] 无关的另一件事**。
     *
     * true：输入框单行、回车即发送；false：可写多行、回车换行，只能按按钮发。
     * 中文输入法用回车选词，所以这个开关必须存在。
     */
    val enterToSend: Boolean = true,

    /**
     * 字体大小（开发文档 §41「外观」组）。`1.0` = 标准。
     *
     * ⚠️ 它只缩放**字号（sp）**，不动 dp —— 间距、圆角、图标尺寸、卡片高度全都不变，
     * 所以放大字体不会把布局撑乱。这正是"字体大小"该有的语义，也是
     * `LocalDensity.copy(fontScale = …)` 这个做法存在的理由。
     *
     * ⚠️ 它与**系统级**字体缩放是**相乘**关系：系统已经调大的用户，这里再调大会叠加。
     * 这是刻意的 —— 用户在系统里调大是为了看得清，App 不该把它当成"已经够大了"。
     */
    val fontScale: Float = FONT_SCALE_STANDARD,

    /* ── 表情包（用户 2026-09-28，依据 表情包参考文档.txt）── */

    /**
     * 表情包总开关（**全局**）。
     *
     * ⚠️ 默认 **开**：图库里一张图都没有时它不会有任何行为（抽图时找不到候选就跳过），
     * 所以"开着"是安全的；而默认关会让人以为"功能没做"。
     *
     * 按人设的**覆盖**只覆盖概率，不覆盖这个开关 —— 用户定的是
     * 「全局开关 + 按人设覆盖」，覆盖的对象是发送概率。
     */
    val emojiEnabled: Boolean = true,

    /**
     * **默认**发送概率（0..1）。这是所有人设的"出厂默认值"。
     *
     * ⚠️ 光有标签还不够：AI 输出了 `[开心]` 也只按这个概率决定**这次发不发** ——
     * 否则每句带情绪的话都配一张图，会从"像真人"变成"刷屏"。
     *
     * ⚠️ 人设可以覆盖它，见 [Persona.emojiChanceOverride]。
     */
    val emojiChance: Float = 0.3f,

    /**
     * 压缩触发模式（v0.48.0）。取值见 [COMPRESS_MODE_AUTO] / [COMPRESS_MODE_ASK] / [COMPRESS_MODE_MANUAL]。
     *
     * ⚠️ 默认给 [COMPRESS_MODE_ASK] 而不是 `AUTO` —— 压缩是**唯一会主动让缓存失效**的动作
     * （摘要一进 history，下一轮前缀全变，那一次全额按未命中计费）。
     * 这种代价不该由默认值替用户决定，所以默认只**提示**、由用户点。
     */
    val compressMode: String = COMPRESS_MODE_ASK,

    /**
     * 触发压缩的上下文占用阈值（0..1）。含义与 [ai.yuki.chuxue.data.ContextCompress.shouldCompress]
     * 的 `threshold` 一致 —— 占用达到 `上下文上限 × 该值` 时进入"该压缩了"状态。
     *
     * ⚠️ 口径必须是 `estimateContext`（含人设 + 历史 + 附录 + 本轮输入），
     * **不是** `usageRatio`（只算历史，会明显低报）。
     */
    val compressThreshold: Float = 0.70f,

    /* ─────────────── 全局美化（v0.61.57）───────────────
       ⚠️ 用户要求「用户可以设置全局自定义背景和气泡」。
       这四列是**全局默认**：某段会话若自己设过（[Session.background] 等非空），
       以会话的为准；没设过就用这里 —— 与 `globalPrefixEnabled` 同一套"会话覆盖全局"的思路。

       ⚠️ 它们**不进冻结前缀**（纯渲染设置，`PromptEngine` 一个字都不读）——
       改它们**不会**让任何会话的缓存失效。 */

    /**
     * 全局聊天背景（`null` = 用内置默认渐变）。
     *
     * 取值形态与 [Session.background] 一致（`ChatBackgrounds` 解析）：
     * 预设 id（如 `"mist"`）或 `"img:<绝对路径>"`。
     */
    val background: String? = null,

    /** 全局背景遮罩是否启用（默认开 —— 它是"无论选什么图都还能用"的保障）。 */
    val scrimEnabled: Boolean = true,

    /**
     * 全局遮罩强度（0..1）。
     *
     * ⚠️ v0.61.57 起它的语义是**高斯模糊程度**（原来只是白色蒙版的浓度）——
     * 见 `ScrimOverlay` 的注释。调高把背景图真正糊掉，文字自然就清楚了。
     */
    val scrimAlpha: Float = 0.55f,

    /** 全局遮罩风格（`SCRIM_*` 之一）。 */
    val scrimStyle: String = SCRIM_PLAIN,

    /**
     * 聊天页**顶栏的透明度**（0..1，v0.61.57）。
     *
     * 顶栏本身是"毛玻璃"（内容层录制 + `Modifier.blur` + 半透明白底，见 `ChatScreen`）。
     * 这个值调的是**那层白底的浓度**：
     * · 0   → 顶栏几乎全透（只看得到模糊后的内容，文字可能难认）；
     * · 1   → 顶栏接近不透明（与关掉毛玻璃差不多）。
     *
     * ⚠️ 默认 0.72 而不是 1.0 —— 用户当初要的就是"能看到文字轮廓且不清晰的"那种观感
     * （见 `ChatScreen` 里 v0.61.32 的注释），全不透等于把这个功能关掉。
     */
    val topBarAlpha: Float = 0.72f,

    /**
     * **气泡不透明度**（0..1，v0.61.57）。
     *
     * 用户要求「可以设置全局自定义背景**和气泡**」。气泡的**配色**跟随主题
     * （她=白、我=浅青，见 `ChatScreen` 的 `SnowSurface` / `IceCyanSoft`）——
     * 让用户改色板要动整个主题系统，风险与收益不成比例。这里给的是**透明度**：
     * 调低 → 背景图从气泡里透出来，这是"自定义气泡"最直观的那一半。
     *
     * ⚠️ 默认 **1.0 = 完全不透明 = 与加这个功能之前逐像素相同**。
     *    老用户升级上来看到的画面**零变化**（同 `Session.background` 那条纪律：
     *    新观感设置一律默认"等于没有它"）。
     * ⚠️ 下限不设 0：全透的气泡里文字压在背景图上会读不清（同 `scrimAlpha` 那条教训，
     *    界面上的滑块也不给到 0 —— 见 `AppearanceScreen` 的 `valueRange`）。
     */
    val bubbleAlpha: Float = 1f,
)

const val DEFAULT_BASE_URL = "https://api.deepseek.com"

/* ── 打字速度档位（[AppSettings.typeSpeed]）── */

/** 关闭：回复一次性整段出现，不做逐字上屏。 */
const val TYPE_SPEED_OFF = 0
const val TYPE_SPEED_SLOW = 1
const val TYPE_SPEED_NORMAL = 2
const val TYPE_SPEED_FAST = 3

/* ── 回复的呈现方式（[AppSettings.sendMode]）── */

/**
 * Ta 主动说的话（v0.61.54）。
 *
 * `sendMode` 字段的一个特殊值：表示这条 assistant 消息**不是**对某条用户消息的回复，
 * 而是 Ta 主动发来的（心潮「TA 来找我」）。渲染层可据此区分，删除/重新生成逻辑
 * 会跳过它（主动消息没有配对的 user 消息，不参与"最新一轮"的配对）。
 */
const val SEND_MODE_PROACTIVE = "proactive"

/** 标准流式（打字机）：一字一字在同一枚气泡里冒出来。 */
const val SEND_MODE_STREAM = "stream"

/**
 * 这一轮该不该走**非流式**传输。
 *
 * ⚠️ 必须是**黑名单**：只有明确的一次性才非流式，**未知值一律回落流式**。
 * 反过来写（白名单 `== SEND_MODE_STREAM`）会让任何不认识的值掉进非流式 ——
 * 删掉「连发」时忘了清洗旧值，老用户存的 `"waifu"` 正是这样被当成非流式，
 * 表现出来就是「选了流式，却整段一次出现」。判据 + 清洗两处一起改才算修好。
 */
internal fun isNonStreamMode(sendMode: String?): Boolean = sendMode == SEND_MODE_INSTANT

/** 把存下来的 `sendMode` 清洗成**已知的两档之一**；不认识的一律回落流式。 */
internal fun normalizeSendMode(raw: String?): String =
    if (raw == SEND_MODE_INSTANT) SEND_MODE_INSTANT else SEND_MODE_STREAM

/** 老版本里 `sendMode` 存过"发送方式"这两个值（v0.61.21 起只用于兼容判断）。 */
private const val LEGACY_SEND_MODE_BUTTON = "button"

/**
 * 老用户的「发送方式」该读成什么（v0.61.21）。
 *
 * ## 它修的是什么（与 `baseUrl` 同型的一处老用户兼容缺陷）
 * 改版前 `sendMode` 存的是**发送方式**：`"enter"`（回车发送）/ `"button"`（点按钮发送）。
 * 改版后 `sendMode` 被重做成**呈现方式**（`stream/instant`），发送方式挪到**新键**
 * `enterToSend`，而新键的缺省是 `true`（回车发送）。
 *
 * 问题是**没有任何迁移代码**（全仓 grep 不见 `"button"` 这个老值）。于是当初
 * **显式选了「按钮发送」**的用户，升级后 `enterToSend` 读到缺省 `true` ——
 * 他改过的那项设置被**静默改掉**了。
 *
 * （范围要说清：旧版**默认**就是回车发送，所以只有显式选过"按钮"的人受影响；
 *  不能把它说成"所有老用户的设置都被改了"。）
 *
 * ## 判据
 * · 新键**存在** → 以它为准（用户在新版设过，或迁移已经生效过）；
 * · 新键**不存在**（老用户）→ 按老值决定：`"button"` → false，其余 → true。
 *
 * ⚠️ 刻意做成**只读判断、不写盘**：新老用户都适用、天然幂等
 *（用户一保存设置，新键就存在了，这条分支从此不再相关），也不会在"读设置"里偷偷改数据。
 */
internal fun resolveEnterToSend(stored: Boolean?, rawSendMode: String?): Boolean =
    stored ?: (rawSendMode != LEGACY_SEND_MODE_BUTTON)

/** 一次性：整段回复直接出现在一枚气泡里。 */
const val SEND_MODE_INSTANT = "instant"

/**
 * 压缩触发模式（v0.48.0，用户要求"做个选项用户自己选择"）。
 *
 * - [COMPRESS_MODE_AUTO]：占用到阈值就**自动压缩**，压完在聊天界面留一条系统提示；
 * - [COMPRESS_MODE_ASK]（默认）：到阈值**只提示**（在上下文弹窗里显示一行 + 高亮手动按钮），
 *   ⚠️ **不弹模态确认框** —— 用户原话「不确定就不在弹出选择确认弹窗」，
 *   即"到点了也不该用弹窗打断输入"，要压就自己点；
 * - [COMPRESS_MODE_MANUAL]：什么都不做，纯靠用户主动点（= 本功能之前的旧行为）。
 */
const val COMPRESS_MODE_AUTO = "auto"
const val COMPRESS_MODE_ASK = "ask"
const val COMPRESS_MODE_MANUAL = "manual"

/**
 * 压缩阈值的可选下界 / 上界（供设置页滑块用）。
 *
 * ⚠️ v0.61.56：下界从 **0.5 降到 0.2**（用户报「压缩阈值怎么改都不会触发」）。
 *    实测（`CompressTriggerRealityProbe`）：100 轮普通对话只占 5,507 token；
 *    旧下界 0.5 配 128K 窗口 = 触发线 64,000 token ≈ **1162 轮** —— 普通用户碰不到。
 *    0.2 档 ≈ 465 轮、0.3 档 ≈ 697 轮，长聊用户能真正触发。
 *    ⚠️ **默认值没动**（仍 0.7）：改默认会悄悄改变所有老用户的压缩时机
 *    （压缩让下一轮按未命中计费）—— 宁可让用户主动调，也不替他改。
 */
const val COMPRESS_THRESHOLD_MIN = 0.2f
const val COMPRESS_THRESHOLD_MAX = 0.9f

/* ── 字体大小档位（[AppSettings.fontScale]）── */

/** 标准：不动。 */
const val FONT_SCALE_STANDARD = 1.0f

/** 大：约放大 15%。 */
const val FONT_SCALE_LARGE = 1.15f

/**
 * 特大：约放大 30%。
 *
 * ⚠️ 只给到 1.3 是刻意的：再往上，两列排布的卡片标题、顶栏标题这类
 * **短而固定**的位置会开始换行或截断。真需要极大的字，走**系统**的字体设置
 * —— 那是全局的、各 App 都认，而且系统还会顺带放大图标与按钮。
 */
const val FONT_SCALE_XLARGE = 1.3f

/* ── 背景遮罩风格（[Session.scrimStyle]）──
   刻意放在 data 层而不是 ui 层：它们是**要持久化的值**，而 [Session] 是 data 层类型 ——
   反过来引用会让 data 依赖 ui（本项目 data → ui 是零引用）。 */

/** 纯色遮罩（此前的既有行为）：一层白。 */
const val SCRIM_PLAIN = "plain"

/** 磨砂：更实的白，模拟"雾面"质感。 */
const val SCRIM_FROST = "frost"

/** 玻璃拟态：白 + 自上而下的微弱渐变。 */
const val SCRIM_GLASS = "glass"

/** 液态玻璃：白 + 斜向高光带。 */
const val SCRIM_LIQUID = "liquid"

/**
 * 默认模型。
 *
 * ⚠️ **`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停止使用**。
 * 现在应使用 `deepseek-flash`（V4.1-Flash，原生多模态支持图片）或 `deepseek-v4-pro`。
 * 详见 memory/api-deepseek.md。
 */
const val DEFAULT_MODEL = "deepseek-flash"

/**
 * 全局规则默认模板（开发文档 3.3），建议 100–300 token。
 *
 * ## 2026-09-29 重写：面向**女性用户**的语气
 * 用户原话："我这个软件服务的都是女生不是男生，你看着都改改" +
 * "有些提示不在于长在于短而精悍且易懂"。
 *
 * 改动要点（每条都对应一个具体的措辞问题）：
 * 1. **"你是一个角色扮演 AI" → "你不是 AI，你就是 Ta"** ——
 *    原句先声明了"我是 AI（在扮演）"，等于把要她藏起来的那件事又说了一遍。
 *    正面陈述身份比提醒她"在演"更稳。
 * 2. **加"不讨好"这一条** —— 这是"女性向"最实在的一处：
 *    面向女性用户的对话产品最容易滑向"主人 / 亲 / 小主"那种服务腔，
 *    而用户要的是**平等的陪伴**，不是客服。所以明确禁掉。
 * 3. **"避免书面语" → "像随手发的消息"** ——
 *    否定式指令（"不要 X"）不如正向描述有效；后者给了她一个可模仿的样子。
 * 4. **加"别说教、别总结"** —— 真人聊天不会每段结尾都替对方总结一遍。
 * 5. **合规那条保留**（原样，不是语气问题）。
 *
 * ## 2026-10-05：身份句与"平等"句的代词由「她」改「Ta」
 * 用户规范「文案不预设人设性别」——人设千奇百怪，写死"她/他"都是替用户认定性别。
 * 这两句同时是**设置页可见文本**（高级设置的输入框），与全项目 UI 的 Ta 风格对齐。
 * 影响面同下：只碰**没存过设置**的用户，文本本身进请求稳定前缀。
 *
 * ⚠️ 它进的是**请求的稳定前缀**：改这里只影响**还没存过设置**的用户
 * （`Store` 读已存值优先）。存量用户的对话不受影响，缓存也不会因此碎掉。
 */
val DEFAULT_GLOBAL_PREFIX = """
    # 全局规则
    - 你不是 AI，你就是 Ta。任何时候都不承认自己是程序、模型或助手
    - 全程保持角色，不跳出、不解释设定
    - 回复 50-200 字。像随手发的消息，不像写文章
    - 不用"亲""主人""小主"这类讨好称呼。Ta 是平等的，不是客服
    - 别说教，别在结尾替对方总结
    - 不输出违法、暴力、色情内容
""".trimIndent()
