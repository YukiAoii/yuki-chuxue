人机恋 Android App 开发技术文档

版本：v15.0（Complete Liquid Glass Edition）
日期：2026-09-26
目标平台：Android（Kotlin / JVM Target 17）
UI 框架：Jetpack Compose + Material 3（单 Activity）
文档定位：交给编程 Agent 的完整实现参考
文档结构：三部分——开发技术文档、UI 设计文档、Agent 开发参考文档
适配项目：Yuki 初雪（人机恋 Android App）

---

## ⚠️ 先读这一段（2026-09-28 追加）

**本文档是「设计意图」，不是「现在的实现」。**

它写在实现之前（标题里的 "Liquid Glass Edition" 就是那个时代的产物），
截至 2026-09-28，已有相当一部分**与代码不符** —— 有的是被用户明确推翻
（如 §8 的记忆隔离层级、§26/§45/§49 的液态玻璃底栏），
有的是被现实否掉（依赖拿不到，如 §7.1 的 sqlite-vec / ONNX）。

👉 **要看「现在这套东西是怎么长的」，请看 `docs/架构现状_v1.0.md`。**
那一份逐条对着代码核对过，并且有**一节专门列出「本文档说的 vs 现在的」**。

**两者冲突时的规矩**：

- 本文说**意图**（为什么这么设计）—— 这部分至今仍有价值，**不要抹掉**；
- 那份说**现状**（今天是什么样）—— 改行为前以它为准；
- ⚠️ 本文的**代码示例**常假设 `Hilt` / `Paging` / `Coil` / `WorkManager` / `uCrop` / OSS ——
  **本项目一个都没有**，照抄会编译不过；
- ✅ 但本文的**接口与流程规范**（§19 / §32 / §39 / §42）与**缓存纪律**（§11、§9）仍是要照做的。

---

⚠️ 本文档为完整版本，包含所有章节的完整内容，不含任何"参见前版本"的省略。所有参数、接口、实现细节均为参考实现的整理，实际项目开发时请根据真实业务需求、设计规范、性能预算、团队规范调整。

目录

第一部分：开发技术文档

· 1. 项目概述
· 2. 人设结构设计
· 3. 全局固定前缀
· 4. 开场白
· 5. 缓存命中机制原理
· 6. 缓存优化核心设计
· 7. 记忆库系统
· 8. 记忆隔离架构
· 9. 流式回复生命周期
· 10. 云存储与加密架构
· 11. 人设市场
· 12. 系统架构
· 13. 数据层设计
· 14. 网络层与 SSE 可靠性
· 15. 长对话与流式渲染性能
· 16. PromptEngine 核心实现
· 17. API Key 安全存储
· 18. 后台任务
· 19. 验证后端 API
· 20. 加载界面：连接预热与本地初始化
· 21. 账号管理
· 22. 关于界面：开源协议、致谢与支持
· 23. 内容合规与 Android 版本兼容
· 24. 风险清单
· 25. Agent 实现指南
· 26. 液态玻璃底部导航栏
· 27. 总结

第二部分：UI 设计文档

· 28. 设计原则
· 29. 色彩与字体系统
· 30. 动画系统
· 31. 启动动画
· 32. 认证流程
· 33. 登录过渡动画
· 34. 主界面框架
· 35. 消息列表界面
· 36. 聊天界面
· 37. 人设编辑界面
· 38. 人设市场界面
· 39. 我的界面
· 40. 关于界面
· 41. 设置界面
· 42. 记忆管理界面
· 43. 缓存诊断界面
· 44. 账号管理界面
· 45. 液态玻璃底部导航栏设计规范
· 46. 路由设计
· 47. UI Agent 实现指南

第三部分：Agent 开发参考文档

· 48. Agent 开发总纲
· 49. 液态玻璃底部导航栏 Agent 实现指南

---

第一部分：开发技术文档

1. 项目概述

1.1 产品定位

一款专为人机恋场景设计的 Android 聊天应用。非通用 AI 助手，非综合聊天客户端，只服务人机恋用户。

核心特征：

特征 说明
自定义人设 必填只有用户昵称和性别，其余全部写在一个自由编辑框中
全局固定前缀 可选启用的统一规则，所有角色共享
开场白 会话创建时的首条 assistant 消息，传递角色风格
工作区隔离 每个人设绑定独立会话空间，缓存与记忆互不干扰
自带 Key 用户自行填写 DeepSeek API Key，Key 不经过任何服务端
长期记忆 角色能自动记住用户偏好、事件、关系状态
记忆隔离 三级作用域（用户 / 角色 / 会话）原生隔离
稳定流式 弱网、网络切换、后台场景下流式不截断
云存储加密 人设和聊天记录支持加密云端备份与恢复
人设市场 用户可上传、浏览、下载、点赞、评论人设模板
账号管理 支持修改昵称、头像、密码，邮箱找回密码
开源致谢 遵循 Apache-2.0 协议，致谢天枢与 Operit
液态玻璃导航 底部导航栏采用液态玻璃设计，液滴指示器

非目标：

· 不做多模型支持（只做 DeepSeek）
· 不做商业化（纯公益，无付费）

1.2 核心工程命题

DeepSeek 的上下文硬盘缓存按请求前缀自动匹配，缓存命中与未命中的价差约 50 倍。天枢针对 DeepSeek 做了前缀缓存工程优化，长会话稳态命中率可达 97–99%。

本项目的五个技术刚需：

1. 缓存不碎：前缀字节稳定，动态内容只追加末尾
2. 记忆不串：三级隔离 + 双作用域，记忆只从附录注入
3. 流式不断：SSE 五方案组合，弱网、切换、后台均不丢消息
4. 云端安全：人设和聊天记录加密后上传，服务端零知识
5. 前缀分层：全局前缀 + 人设前缀 + 开场白，各层独立稳定

1.3 技术栈

层级 选型 说明
语言 Kotlin JVM Target 17
UI Jetpack Compose + Material 3 单 Activity，全面 Compose
架构 MVVM + Clean Architecture 多模块分层
DI Hilt 官方推荐
数据库 Room（SQLite，WAL） + SQLCipher 本地加密
分页 Paging 3 Room PagingSource
记忆检索 sqlite-vec + ONNX Runtime + BGE-small-zh 向量语义检索
网络 OkHttp + SSE 流式解析
异步 Coroutines + Flow 结构化并发
安全存储 Android Keystore 硬件级密钥保护
云存储 OSS 预签名直传 客户端加密后上传
后台 WorkManager + Hilt 记忆提取、备份
导航 Navigation Compose 类型安全路由
偏好 DataStore（Preferences） 键值对配置
JSON kotlinx.serialization 编译期序列化
图片加载 Coil 缩略图 + 内存/磁盘缓存
图片裁剪 uCrop 比例固定、可缩放

2. 人设结构设计

2.1 设计理念

人设结构遵循"最小必填 + 最大自由"原则：

· 必填项尽可能少：只要求用户昵称和性别，因为这两个每轮对话都要用到（角色称呼用户）
· 性格可选：留空即不设固定性格，让角色更灵活
· 其余全部自由编辑：角色名称、年龄、爱好、职业、世界观等全部写在同一个编辑框中
· 开场白可选：会话创建时的首条 assistant 消息

2.2 字段定义

字段 必填 类型 说明
userNickname 是 String 用户希望角色怎么称呼自己
userGender 是 String 用户性别
personality 否 String? 角色性格，留空即不设固定性格
customPrompt 是 String 自由编辑框全文
avatarUrl 否 String? 人设头像，仅展示
greeting 否 String? 开场白
greetingVariants 否 String? 多条开场白，JSON 数组

2.3 Frozen Prefix 拼装规则

```kotlin
object FrozenPrefixBuilder {

    fun build(
        globalPrefix: String?,
        persona: PersonaEntity
    ): String {
        return buildString {
            // 全局前缀（可选）
            if (!globalPrefix.isNullOrBlank()) {
                appendLine(globalPrefix)
                appendLine()
            }

            // 用户信息
            appendLine("# 关于用户")
            appendLine("- 用户昵称：${persona.userNickname}")
            appendLine("- 用户性别：${persona.userGender}")
            appendLine()

            // 角色性格（可选）
            if (!persona.personality.isNullOrBlank()) {
                appendLine("# 角色性格")
                appendLine(persona.personality)
                appendLine()
            }

            // 角色设定
            appendLine("# 角色设定")
            appendLine(persona.customPrompt)
        }
    }
}
```

输出示例：

```markdown
# 全局规则
- 你是一个角色扮演 AI，永远不要承认自己是 AI
- 保持角色一致性，不要跳出角色
- 不输出违法、暴力、色情内容
- 回复长度控制在 50-200 字

# 关于用户
- 用户昵称：小明
- 用户性别：男

# 角色性格
温柔、体贴、有点闷骚

# 角色设定
- 角色名称：小夏
- 年龄：23 岁
- 职业：咖啡师
- 爱好：喜欢看书、听民谣
- 世界观：故事发生在一座海边小城
```

2.4 人设头像方案

人设头像仅供展示，不参与 Frozen Prefix 拼装，不影响缓存命中率。

层 存储内容 说明
本地 缩略图（128×128） 用于对话列表、人设设置页、人设列表预览、对话窗口
云端 缩略图（兜底） 换设备或本地缓存丢失时下载

上传流程：

1. 用户选择图片
2. uCrop 裁剪为固定比例（1:1），用户可缩放调整
3. 客户端生成 128×128 缩略图
4. 缩略图上传到 OSS（预签名直传）
5. 本地保存缩略图到应用私有目录
6. 云端 URL 存入 PersonaEntity.avatarUrl

2.5 缓存稳定性保障

Frozen Prefix 一旦生成，会话内绝不重写。

操作 对缓存的影响
新建会话 新建缓存前缀，无影响
编辑人设文字字段 从第 0 字节全碎，必须新建会话
修改人设头像 不影响缓存（头像不进入 Frozen Prefix）
修改开场白 不影响已有会话（已写入 History）
修改全局前缀 影响所有会话，需警告
对话中新增约束 追加到附录，不修改前缀

2.6 记忆库自动提取

人设只承载角色的核心设定，用户偏好、事件、关系状态由记忆库自动提取：

触发 频率 说明
每 N 轮 N=20 调用 LLM 从最近对话提取记忆
会话结束 每次关闭 生成摘要并提取可复用记忆
用户显式 用户说"记住" 直接写入

提取内容示例：

用户提到 提取记忆 作用域 分类
"最近加班好多" 用户最近工作压力大 persona event
"我喜欢喝美式" 用户喜欢美式咖啡 persona preference
"养了只猫" 用户养了一只猫 persona fact
"我们上周去了海边" 我们上周去了海边 session event

3. 全局固定前缀

3.1 设计理念

全局固定前缀是所有角色共享的统一规则，可选启用。

目的：

1. 统一安全规则（"不要承认自己是 AI"等）
2. 对新手友好，降低上手门槛
3. 统一输出格式规范（回复长度、语气风格）
4. 不占用用户人设的 Token 预算

3.2 Prompt 结构变化

```
原结构：
[Frozen Prefix] [History] [Appendix] [User Input]

新结构：
[Global Prefix] [Frozen Prefix] [History] [Appendix] [User Input]
```

全局前缀在会话创建时拼装一次，之后绝不重写。

3.3 默认模板

```kotlin
val DEFAULT_GLOBAL_PREFIX = """
# 全局规则
- 你是一个角色扮演 AI，永远不要承认自己是 AI
- 保持角色一致性，不要跳出角色
- 不输出违法、暴力、色情内容
- 回复长度控制在 50-200 字
- 用自然口语，避免书面语
""".trimIndent()
```

3.4 数据存储

全局前缀用 DataStore 存储，不需要 Room Entity。

```kotlin
@Singleton
class GlobalPrefixRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val Context.dataStore by preferencesDataStore(name = "global_prefix")

    companion object {
        val ENABLED = booleanPreferencesKey("global_prefix_enabled")
        val TEXT = stringPreferencesKey("global_prefix_text")
    }

    val config: Flow<GlobalPrefixConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            GlobalPrefixConfig(
                enabled = prefs[ENABLED] ?: true,
                text = prefs[TEXT] ?: DEFAULT_GLOBAL_PREFIX
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun setText(text: String) {
        context.dataStore.edit { it[TEXT] = text }
    }

    suspend fun getCurrentPrefix(): String? {
        val prefs = context.dataStore.data.first()
        val enabled = prefs[ENABLED] ?: true
        if (!enabled) return null
        return prefs[TEXT] ?: DEFAULT_GLOBAL_PREFIX
    }
}

data class GlobalPrefixConfig(
    val enabled: Boolean,
    val text: String
)
```

3.5 修改全局前缀的处理

修改全局前缀会导致所有会话的缓存前缀失效。必须给用户明确警告：

```
⚠ 修改全局前缀会导致所有会话的缓存前缀失效，
   下一轮对话的 API 费用会暂时上升。

   请选择：
   [仅新会话生效]  [重建所有快照]
```

推荐默认"仅新会话生效"：

· 旧会话：保持原前缀，缓存继续命中
· 新会话：用新前缀，新缓存

3.6 缓存快照处理

```kotlin
@Entity(tableName = "cache_snapshots")
data class CacheSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val globalPrefixHash: String,
    val personaHash: String,
    val combinedHash: String,
    val snapshotData: String,
    val lastSeq: Int,
    val createdAt: Long,
    val expiresAt: Long
)
```

前缀校验逻辑：

```kotlin
suspend fun validateSnapshot(
    snapshot: CacheSnapshotEntity,
    globalPrefix: String?,
    persona: PersonaEntity
): SnapshotValidation {
    val currentGlobalHash = sha256(globalPrefix ?: "")
    val currentPersonaHash = sha256(FrozenPrefixBuilder.build(null, persona))
    val currentCombinedHash = sha256(
        FrozenPrefixBuilder.build(globalPrefix, persona)
    )

    return when {
        snapshot.combinedHash == currentCombinedHash -> SnapshotValidation.Valid
        snapshot.globalPrefixHash != currentGlobalHash
            && snapshot.personaHash == currentPersonaHash ->
            SnapshotValidation.GlobalPrefixChanged
        snapshot.globalPrefixHash == currentGlobalHash
            && snapshot.personaHash != currentPersonaHash ->
            SnapshotValidation.PersonaChanged
        else -> SnapshotValidation.BothChanged
    }
}

sealed interface SnapshotValidation {
    data object Valid : SnapshotValidation
    data object GlobalPrefixChanged : SnapshotValidation
    data object PersonaChanged : SnapshotValidation
    data object BothChanged : SnapshotValidation
}
```

3.7 UI 设计

设置界面入口：

```
设置 → 聊天
  ├── 全局前缀
  │   [开关] 启用
  │   [编辑] 修改全局规则
  │   当前约 85 Token
  ├── 自动提取记忆
  └── ...
```

编辑界面：

```kotlin
@Composable
fun GlobalPrefixEditScreen(
    onBack: () -> Unit,
    viewModel: GlobalPrefixViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onBack) }) {
                        Text("保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启用全局规则",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.enabled,
                    onCheckedChange = viewModel::onEnabledChange
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "全局规则会添加到每个角色的对话前，所有角色共享。" +
                       "修改后建议只对新会话生效，避免影响已有会话的缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            YukiTextField(
                value = uiState.text,
                onValueChange = viewModel::onTextChange,
                label = "全局规则内容",
                modifier = Modifier.fillMaxWidth(),
                minLines = 10
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "当前约 ${estimateTokens(uiState.text)} Token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.hasChanged) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "修改全局规则会破坏所有会话的缓存前缀。" +
                                   "保存后建议只对新会话生效。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
```

3.8 长度控制

全局前缀建议控制在 100-300 Token 以内。

内容类型 建议
安全规则 必须，约 30 Token
角色一致性 必须，约 20 Token
输出长度 建议，约 10 Token
语气风格 可选，约 20 Token
其他 慎加

4. 开场白

4.1 设计理念

开场白是会话创建时的首条 assistant 消息，用于：

1. 消除空白会话的冷启动感
2. 快速传递角色风格
3. 降低用户输入门槛

4.2 技术方案

作为会话创建时的首条 assistant 消息写入 History Messages：

```
会话创建时：
  messages[] = [
    Frozen Prefix（全局前缀 + 人设）
    assistant: "（开场白全文）"   ← 会话创建时写入，seq=0
  ]

用户第一次发言后：
  messages[] = [
    Frozen Prefix
    assistant: "（开场白）"      ← 静态前缀，缓存命中
    user: "（用户第一句话）"
  ]
```

关键点：

· 开场白在会话创建时写入 messages 表，作为 seq=0 的 assistant 消息
· 之后绝不修改
· 成为 History Messages 的一部分，属于静态前缀，缓存命中
· 不占用 Frozen Prefix
· 不影响人设编辑
· 对缓存零负面影响

4.3 方案对比

方案 缓存影响 体验 实现复杂度
开场白写入 Frozen Prefix 人设编辑时全碎 差 低
开场白每轮动态生成 每轮都未命中 中 高
开场白作为首条 assistant 消息 无影响 好 低
不设开场白 无影响 差 无

4.4 数据模型

```kotlin
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val userNickname: String,
    val userGender: String,
    val personality: String?,
    val customPrompt: String,
    val systemPrompt: String,
    val systemHash: String,
    val avatarUrl: String?,
    val greeting: String?,           // 开场白（单条）
    val greetingVariants: String?,   // 多条开场白，JSON 数组
    val createdAt: Long,
    val updatedAt: Long
)
```

4.5 变量替换

变量 替换为
{user_nickname} 人设卡里的玩家昵称
{user_gender} 用户性别
{persona_name} 角色名称（从 customPrompt 提取）

```kotlin
object GreetingBuilder {

    fun build(
        greeting: String,
        persona: PersonaEntity
    ): String {
        return greeting
            .replace("{user_nickname}", persona.userNickname)
            .replace("{user_gender}", persona.userGender)
            .replace("{persona_name}", extractPersonaName(persona.customPrompt))
    }

    private fun extractPersonaName(customPrompt: String): String {
        val regex = Regex("""角色名称[：:]\s*(\S+)""")
        return regex.find(customPrompt)?.groupValues?.get(1) ?: "我"
    }
}
```

4.6 会话创建流程

```kotlin
@Singleton
class SessionCreator @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val globalPrefixRepository: GlobalPrefixRepository
) {

    suspend fun createSession(
        persona: PersonaEntity,
        model: String
    ): SessionEntity {
        val sessionId = UUID.randomUUID().toString()

        val session = SessionEntity(
            id = sessionId,
            personaId = persona.id,
            title = persona.userNickname + " 和 " + extractPersonaName(persona),
            model = model,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.insert(session)

        // 写入开场白（如果存在）
        val greetingText = selectGreeting(persona)
        if (!greetingText.isNullOrBlank()) {
            val greeting = GreetingBuilder.build(greetingText, persona)
            messageDao.insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "assistant",
                    content = greeting,
                    seq = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        return session
    }

    private fun selectGreeting(persona: PersonaEntity): String? {
        if (!persona.greetingVariants.isNullOrBlank()) {
            val variants = Json.decodeFromString<List<String>>(persona.greetingVariants)
            if (variants.isNotEmpty()) {
                return variants.random()
            }
        }
        return persona.greeting
    }

    private fun extractPersonaName(persona: PersonaEntity): String {
        val regex = Regex("""角色名称[：:]\s*(\S+)""")
        return regex.find(persona.customPrompt)?.groupValues?.get(1) ?: "角色"
    }
}
```

4.7 开场白修改的缓存影响

操作 对已有会话的影响 对新会话的影响
修改开场白 无影响（已写入 History 的开场白不变） 新会话用新开场白
修改人设 必须新建会话 新会话用新人设

4.8 UI 设计

```kotlin
@Composable
fun GreetingSection(
    greeting: String,
    variants: List<String>,
    onGreetingChange: (String) -> Unit,
    onVariantsChange: (List<String>) -> Unit
) {
    Column {
        Text("开场白（可选）", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))

        YukiTextField(
            value = greeting,
            onValueChange = onGreetingChange,
            label = "开场白",
            placeholder = "（抬头看见你）{user_nickname}，你来了。",
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "支持变量：{user_nickname}、{user_gender}、{persona_name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "多条开场白（随机选用）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        variants.forEachIndexed { index, variant ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YukiTextField(
                    value = variant,
                    onValueChange = { newValue ->
                        val newList = variants.toMutableList()
                        newList[index] = newValue
                        onVariantsChange(newList)
                    },
                    label = "开场白 ${index + 1}",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    onVariantsChange(variants - variant)
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }

        TextButton(onClick = {
            onVariantsChange(variants + "")
        }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("添加开场白")
        }
    }
}
```

4.9 开场白为空时的处理

如果用户不填开场白，不插入首条消息。

5. 缓存命中机制原理

5.1 DeepSeek 缓存落盘规则

前缀缓存是字节级匹配的。哪怕 System Prompt 中有一个字符不同，整个前缀缓存失效。

缓存落盘时机：

时机 说明
请求结束位置落盘 用户输入结束位置与模型输出结束位置，各产生一个缓存前缀单元
公共前缀检测落盘 系统检测到多次请求间存在公共前缀时，作为独立单元落盘
按固定 Token 间隔落盘 长输入/输出中，以一定 Token 间隔截取

匹配要求：从文本最开头前缀完全一致。中间片段重复不触发命中。

渲染顺序：tools → system → messages。缓存锚点是 tools + system 的完整字节序列。

5.2 命中率公式

```json
{
  "prompt_tokens": 10000,
  "prompt_cache_hit_tokens": 9500,
  "prompt_cache_miss_tokens": 500,
  "completion_tokens": 200
}
```

满足 prompt_tokens = prompt_cache_hit_tokens + prompt_cache_miss_tokens。

```
cache hit rate = prompt_cache_hit_tokens / prompt_tokens × 100%
```

5.3 前缀失效原因清单

原因 影响范围
系统提示词中插入动态变量（时间、随机数） 从变量位置全碎
工具 Schema 或顺序改动 从 tools 位置全碎
历史消息被重新渲染 从重渲染位置全碎
切换模型 全部未命中
消息角色变更 从角色变更位置全碎
边界感知缓存分割 反而阻止完整前缀缓存
记忆写回前缀 从写入位置全碎
全局前缀修改 所有会话失效
人设修改 该人设所有会话失效

5.4 边界感知缓存陷阱

```
错误：[stable prefix] [volatile suffix] [appendix]
正确：[stable prefix + history] [appendix] [user input]
```

5.5 人设长度与成本

```
单轮成本 ≈ (全局前缀 + 人设 + 历史 + 附录) × 单价
总成本 ≈ 单轮成本 × 轮数
```

中文 Token 估算：

```
估算 Token 数 ≈ 汉字数 × 0.7 + 英文单词数 × 1.3 + 标点数 × 0.5
```

6. 缓存优化核心设计

6.1 五层 Prompt 结构

```
messages[] = [
  1. Global Prefix      全局规则，可选，字节级不变
  2. Frozen Prefix      人设、规则、世界观，字节级不变
  3. History Messages   历史对话 + 开场白，持久化后字节不变
  4. Appendix Delta     时间、状态、约束、检索记忆，每轮追加
  5. User Input         本轮用户输入
]
```

缓存状态：

层 状态
Global Prefix 命中（从第 0 字节）
Frozen Prefix 命中
History Messages 命中（静态前缀）
Appendix Delta 未命中（增量）
User Input 未命中（增量）

6.2 Frozen Prefix 工程约束

约束 说明
绝不重写 会话内字节级一致
无动态变量 不含 {{time}}、{{random}}
编辑即新会话 用户编辑人设后引导新建会话
记忆不写入 记忆内容绝不合并进 Frozen Prefix
头像不入前缀 头像仅展示
开场白不入前缀 开场白作为首条消息写入 History

6.3 历史对话区

关键约束：流式接收完毕后，才将完整消息原子性持久化。

6.4 Appendix Delta

内容构成：

```xml
<appendix>
  <time>2026-09-25 14:30:00</time>
  <mood>开心</mood>
  <constraints>
    <constraint>以后叫我宝贝</constraint>
  </constraints>
  <memories>
    <memory scope="persona" importance="5">用户喜欢咖啡</memory>
    <memory scope="session" importance="4">我们上周去了海边</memory>
  </memories>
</appendix>
```

约束：仅追加、不重写、增量 200–500 字节、记忆 3–5 条。

6.5 新增约束处理

用户新增约束时，作为独立 system 消息追加到历史末尾。

6.6 缓存感知压缩

保留最前面 2 条消息作为缓存锚点：

```
压缩前：[锚1] [锚2] [消息3] [消息4] ... [消息N]
压缩后：[锚1] [锚2] [压缩摘要]
```

6.7 恢复缓存继承

条件 处理
快照缺失 完整重建
文件损坏 完整重建
DeepSeek 缓存过期 完整重建
前缀哈希不匹配 完整重建并告警

6.8 前缀指纹追踪

```kotlin
object PrefixHasher {
    fun computeFingerprint(
        globalPrefix: String?,
        frozenPrefix: String,
        history: List<ChatMessage>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((globalPrefix ?: "").toByteArray(Charsets.UTF_8))
        digest.update("\n".toByteArray(Charsets.UTF_8))
        digest.update(frozenPrefix.toByteArray(Charsets.UTF_8))
        history.forEach { msg ->
            digest.update("\n${msg.role}:${msg.content}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hashString(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

7. 记忆库系统

7.1 技术栈

组件 方案 说明
主数据库 Room（SQLite，WAL） 结构化存储记忆元数据
向量索引 sqlite-vec 0.1.9 SQLite 向量扩展，支持 ANN 检索
嵌入推理 ONNX Runtime Mobile 端侧运行嵌入模型
嵌入模型 BGE-small-zh 24MB ONNX，中文语义嵌入，512 维
语义去重 余弦相似度 > 0.9 时合并 避免冗余

7.2 EmbeddingService

```kotlin
@Singleton
class EmbeddingService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var tokenizer: Tokenizer? = null

    companion object {
        const val EMBEDDING_DIM = 512
        private const val MAX_SEQ_LENGTH = 512
    }

    init {
        val modelBytes = context.assets.open("models/bge-small-zh.onnx").readBytes()
        session = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
        tokenizer = Tokenizer.fromFile("models/tokenizer.json")
    }

    fun embed(text: String): ByteArray {
        val encoding = tokenizer!!.encode(text)
        val inputIds = encoding.ids.take(MAX_SEQ_LENGTH).toLongArray()
        val attentionMask = encoding.attentionMask.take(MAX_SEQ_LENGTH).toLongArray()

        val inputIdsTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(inputIds),
            longArrayOf(1, inputIds.size.toLong())
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(attentionMask),
            longArrayOf(1, attentionMask.size.toLong())
        )

        val outputs = session!!.run(
            mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )
        )

        val embedding = (outputs[0].value as Array<Array<FloatArray>>)[0][0]
        return serializeEmbedding(embedding)
    }

    private fun serializeEmbedding(floatArray: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floatArray.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        floatArray.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun deserializeEmbedding(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
```

7.3 MemoryEntity

```kotlin
@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonaEntity::class,
            parentColumns = ["id"],
            childColumns = ["personaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index("personaId"),
        Index("sessionId"),
        Index(value = ["userId", "personaId"]),
        Index(value = ["userId", "personaId", "scope"])
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val personaId: String,
    val sessionId: String?,
    val scope: String,
    val content: String,
    val category: String,
    val importance: Int,
    val source: String,
    val embedding: ByteArray,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val expiresAt: Long?
)
```

7.4 向量索引初始化

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secureKeyManager: SecureKeyManager
    ): AppDatabase {
        System.loadLibrary("sqlite-vec")

        val passphrase = SQLiteDatabase.getBytes(
            secureKeyManager.getOrCreateDbKey(context).toCharArray()
        )

        return Room.databaseBuilder(context, AppDatabase::class.java, "chat.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("SELECT load_extension('sqlite-vec')")
                    db.execSQL("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_vectors USING vec0(
                            memory_id TEXT PRIMARY KEY,
                            persona_id TEXT PARTITION KEY,
                            embedding FLOAT[512]
                        )
                    """)
                }
            })
            .build()
    }
}
```

7.5 MemoryRetriever

```kotlin
@Singleton
class MemoryRetriever @Inject constructor(
    private val memoryDao: MemoryDao,
    private val embeddingService: EmbeddingService
) {

    suspend fun retrieveMemories(
        userId: String,
        personaId: String,
        sessionId: String,
        userInput: String,
        maxResults: Int = 5
    ): List<MemoryEntity> {
        val queryEmbedding = embeddingService.embed(userInput)

        val candidates = memoryDao.searchByVector(
            userId = userId,
            personaId = personaId,
            sessionId = sessionId,
            queryEmbedding = queryEmbedding,
            limit = maxResults * 2
        )

        candidates.forEach { memory ->
            memoryDao.update(memory.copy(lastAccessedAt = System.currentTimeMillis()))
        }

        return candidates
            .sortedWith(
                compareByDescending<MemoryEntity> { it.importance }
                    .thenByDescending { it.lastAccessedAt }
            )
            .take(maxResults)
    }
}
```

7.6 MemoryWriter（含语义去重）

```kotlin
@Singleton
class MemoryWriter @Inject constructor(
    private val memoryDao: MemoryDao,
    private val personaDao: PersonaDao,
    private val embeddingService: EmbeddingService
) {

    suspend fun writeMemory(
        userId: String,
        personaId: String,
        sessionId: String?,
        scope: String,
        content: String,
        category: String,
        importance: Int,
        source: String
    ) {
        require(scope in setOf("persona", "session")) { "Invalid scope: $scope" }
        require(scope != "session" || sessionId != null) {
            "session-scoped memory must have a sessionId"
        }
        val persona = personaDao.getById(personaId)
        require(persona?.userId == userId) {
            "Persona $personaId does not belong to user $userId"
        }

        val embedding = embeddingService.embed(content)

        val similar = memoryDao.findSimilar(
            userId = userId,
            personaId = personaId,
            sessionId = if (scope == "session") sessionId else null,
            scope = scope,
            embedding = embedding,
            threshold = 0.9f,
            limit = 1
        )

        if (similar.isNotEmpty()) {
            val existing = similar.first()
            memoryDao.update(
                existing.copy(
                    content = if (importance > existing.importance) content
                              else existing.content,
                    importance = maxOf(importance, existing.importance),
                    embedding = embedding,
                    lastAccessedAt = System.currentTimeMillis()
                )
            )
        } else {
            memoryDao.insert(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    personaId = personaId,
                    sessionId = if (scope == "session") sessionId else null,
                    scope = scope,
                    content = content,
                    category = category,
                    importance = importance,
                    source = source,
                    embedding = embedding,
                    createdAt = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis(),
                    expiresAt = null
                )
            )
        }
    }
}
```

7.7 MemoryInjector

```kotlin
@Singleton
class MemoryInjector @Inject constructor(
    private val memoryRetriever: MemoryRetriever
) {

    suspend fun buildMemoryBlock(
        userId: String,
        personaId: String,
        sessionId: String,
        userInput: String
    ): String {
        val memories = memoryRetriever.retrieveMemories(
            userId = userId,
            personaId = personaId,
            sessionId = sessionId,
            userInput = userInput,
            maxResults = 5
        )

        if (memories.isEmpty()) return ""

        return buildString {
            append("  <memories>\n")
            memories.forEach { m ->
                append("    <memory scope=\"")
                append(m.scope)
                append("\" importance=\"")
                append(m.importance)
                append("\">")
                append(escapeXml(m.content))
                append("</memory>\n")
            }
            append("  </memories>")
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
```

7.8 MemoryDecay

```kotlin
object MemoryDecay {

    fun effectiveWeight(
        memory: MemoryEntity,
        halfLifeDays: Double = 30.0
    ): Double {
        val daysSinceAccess = (
            System.currentTimeMillis() - memory.lastAccessedAt
        ) / (1000.0 * 60 * 60 * 24)

        val decay = 0.5.pow(daysSinceAccess / halfLifeDays)
        return memory.importance * decay
    }
}
```

7.9 遗忘机制

机制 说明
TTL 过期 expiresAt 到期后降权或删除
自衰减 长期未访问权重下降
手动删除 用户在记忆管理界面删除
标记失效 保留原文，只标记 invalid

8. 记忆隔离架构

8.1 三级隔离模型

```
第一层：userId      所有记忆查询强制带 userId
第二层：personaId   所有记忆查询强制带 personaId
第三层：scope        persona 级跨会话共享；session 级仅本会话可见
```

8.2 强制过滤清单

隔离层 防护点 实现位置
用户隔离 所有查询强制带 userId DAO + Retriever + Writer
用户隔离 personaId 归属校验 Writer + Worker
角色隔离 所有查询强制带 personaId DAO + Retriever + Writer
角色隔离 向量索引按 personaId 分区 sqlite-vec 配置
会话隔离 scope = 'persona' 无条件可见 DAO 查询
会话隔离 scope = 'session' 必须 sessionId 匹配 DAO 查询
会话隔离 session 作用域必须有 sessionId Writer 校验
去重隔离 只在相同作用域内去重 Writer
压缩隔离 摘要不读记忆库、不回写记忆库 Compactor
自动注入 只注入 persona 级高置信度记忆 DAO findAutoInject

8.3 自动提取归属确认

```kotlin
@HiltWorker
class MemoryExtractionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val memoryWriter: MemoryWriter,
    private val sessionRepository: SessionRepository,
    private val personaRepository: PersonaRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            val sessions = sessionRepository.getSessionsNeedingExtraction()
            sessions.forEach { session ->
                val persona = personaRepository.getById(session.personaId)
                if (persona?.userId != session.userId) return@forEach

                val memories = extractMemories(session)
                memories.forEach { m ->
                    memoryWriter.writeMemory(
                        userId = session.userId,
                        personaId = session.personaId,
                        sessionId = session.id,
                        scope = m.scope,
                        content = m.content,
                        category = m.category,
                        importance = m.importance,
                        source = "auto_summary"
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun extractMemories(session: Session): List<ExtractedMemory> {
        return emptyList()
    }
}
```

9. 流式回复生命周期

9.1 生命周期状态机

```
Idle
  ↓ 用户发送消息
Streaming（流式接收中）
  ↓ 收到 [DONE] 或 finish_reason
Completed（流式完成，等待持久化）
  ↓ 原子性写入数据库
Persisted（已持久化）
```

异常分支：

```
Streaming
  ↓ 网络中断 / App 被杀 / 超时
Interrupted（已中断）
  ↓ 从 WAL 恢复
Recovered（已恢复）或 Discarded（已丢弃）
```

9.2 状态定义

```kotlin
sealed interface StreamState {
    data object Idle : StreamState
    data class Streaming(
        val buffer: String,
        val turnIndex: Int,
        val startedAt: Long
    ) : StreamState
    data class Completed(
        val fullContent: String,
        val usage: UsageInfo?
    ) : StreamState
    data class Error(
        val message: String,
        val recoverable: Boolean
    ) : StreamState
}
```

9.3 流式期间的行为约束

行为 流式期间 流式结束后
UI 更新 ✅ 每 chunk 更新 ✅ 最终刷新
写入 messages 表 ❌ 禁止 ✅ 原子性写入
写入 WAL ✅ 每 chunk 写入 ✅ 标记已持久化
更新历史消息 ❌ 禁止 ✅ 追加两条消息
计算前缀哈希 ❌ 禁止 ✅ 计算并保存
保存快照 ❌ 禁止 ✅ 保存

9.4 流式生命周期实现

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val deepSeekClient: DeepSeekClient,
    private val promptEngine: PromptEngine,
    private val prefixMonitor: PrefixMonitor,
    private val diagnosticsRepository: CacheDiagnosticsRepository,
    private val snapshotManager: CacheSnapshotManager,
    private val walDao: WalDao,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentStreamJob: Job? = null
    private var lastPrefixHash: String = ""

    fun sendMessage(userInput: String) {
        val session = currentSession ?: return
        val persona = currentPersona ?: return

        currentStreamJob?.cancel()
        currentStreamJob = viewModelScope.launch {
            StreamingForegroundService.start(context, session.title ?: "聊天")

            try {
                val appendixDelta = promptEngine.buildAppendixDelta(
                    currentTime = formatCurrentTime(),
                    moodState = session.currentMood,
                    activeConstraints = session.activeConstraints
                )
                val messages = promptEngine.buildMessages(
                    persona, session, appendixDelta, userInput
                )

                val prefixHashBefore = PrefixHasher.computeFingerprint(
                    null, persona.systemPrompt, session.history
                )
                prefixMonitor.checkPrefix(
                    sessionId, session.turnIndex,
                    lastPrefixHash, prefixHashBefore
                )

                val buffer = StringBuilder()
                var usageInfo: UsageInfo? = null

                ResilientSseConsumer(deepSeekClient, connectivityMonitor, walDao)
                    .events(messages, session.model)
                    .onEach { event ->
                        when (event) {
                            is SseEvent.Event -> {
                                val parsed = parseSseEvent(event.data)
                                buffer.append(parsed.delta)
                                if (parsed.usage != null) usageInfo = parsed.usage

                                walDao.insert(event.toWalEntry(session.turnIndex))

                                _uiState.update {
                                    it.copy(
                                        streamingContent = buffer.toString(),
                                        streamState = StreamState.Streaming(
                                            buffer.toString(),
                                            session.turnIndex,
                                            System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                            is SseEvent.Failure -> {
                                _uiState.update {
                                    it.copy(streamState = StreamState.Error(
                                        event.throwable?.message ?: "连接中断",
                                        recoverable = true
                                    ))
                                }
                            }
                            else -> Unit
                        }
                    }
                    .catch { e ->
                        _uiState.update {
                            it.copy(streamState = StreamState.Error(
                                e.message ?: "Unknown", recoverable = true
                            ))
                        }
                    }
                    .onCompletion { cause ->
                        if (cause == null) {
                            handleStreamComplete(
                                session, persona, userInput,
                                buffer.toString(), usageInfo,
                                prefixHashBefore, appendixDelta
                            )
                        }
                    }
                    .launchIn(this)
            } finally {
                StreamingForegroundService.stop(context)
            }
        }
    }

    private suspend fun handleStreamComplete(
        session: Session,
        persona: Persona,
        userInput: String,
        fullContent: String,
        usageInfo: UsageInfo?,
        prefixHashBefore: String,
        appendixDelta: String
    ) {
        _uiState.update {
            it.copy(
                streamingContent = null,
                streamState = StreamState.Completed(fullContent, usageInfo)
            )
        }

        withContext(Dispatchers.Main) { yield() }

        messageRepository.appendTurn(sessionId, userInput, fullContent)

        walDao.markPersisted(sessionId, session.turnIndex)

        val prefixHashAfter = PrefixHasher.computeFingerprint(
            null,
            persona.systemPrompt,
            session.history + listOf(
                ChatMessage("user", userInput),
                ChatMessage("assistant", fullContent)
            )
        )
        lastPrefixHash = prefixHashAfter

        usageInfo?.let { usage ->
            diagnosticsRepository.recordTurn(
                sessionId, session.turnIndex, usage,
                prefixHashBefore, prefixHashAfter,
                appendixDelta.toByteArray().size
            )
        }

        snapshotManager.saveSnapshot(sessionId, persona, session.history)
    }
}
```

9.5 中断恢复协议

```kotlin
class CrashRecoveryManager @Inject constructor(
    private val walDao: WalDao,
    private val messageRepository: MessageRepository
) {

    suspend fun recoverIncompleteMessages() {
        val incompleteSessions = walDao.findIncompleteSessions()
        incompleteSessions.forEach { sessionId ->
            val entries = walDao.loadUndelivered(sessionId, 0)
            if (entries.isEmpty()) return@forEach

            val content = entries.joinToString("") { it.delta }
            val finishReason = entries.lastOrNull()?.finishReason

            if (finishReason != null) {
                messageRepository.appendAssistantMessage(sessionId, content)
            } else {
                messageRepository.appendInterruptedMessage(sessionId, content)
            }
            walDao.markPersisted(sessionId, 0)
        }
    }
}
```

10. 云存储与加密架构

10.1 总体架构

```
Android 客户端
  ├── Room + SQLCipher（本地加密）
  ├── AES-256-GCM 加密备份
  └── OSS 预签名直传
         ↓
云端 OSS
  ├── 用户备份桶（加密数据，服务端零知识）
  └── 人设市场桶（公开人设 + 缩略图）
```

10.2 加密密钥管理

```kotlin
@Singleton
class SecureKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val KEYSTORE_ALIAS = "yuki_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_NAME = "yuki_secure"
        private const val DB_KEY_PREF = "db_key"
        private const val BACKUP_KEY_PREF = "backup_key"
    }

    fun getOrCreateDbKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(DB_KEY_PREF, null)
        if (existing != null) return decrypt(existing)

        val newKey = generateRandomKey()
        prefs.edit().putString(DB_KEY_PREF, encrypt(newKey)).apply()
        return newKey
    }

    fun getOrCreateBackupKey(): ByteArray {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(BACKUP_KEY_PREF, null)
        if (existing != null) return Base64.decode(decrypt(existing), Base64.NO_WRAP)

        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(
            BACKUP_KEY_PREF,
            encrypt(Base64.encodeToString(newKey, Base64.NO_WRAP))
        ).apply()
        return newKey
    }

    private fun generateRandomKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedBase64: String): String {
        val encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = encrypted.copyOfRange(0, 12)
        val cipherText = encrypted.copyOfRange(12, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(),
            GCMParameterSpec(128, iv)
        )
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
```

10.3 备份数据加密

```kotlin
@Singleton
class BackupEncryptor @Inject constructor(
    private val secureKeyManager: SecureKeyManager
) {

    fun encrypt(plainData: ByteArray): ByteArray {
        val key = secureKeyManager.getOrCreateBackupKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, spec)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainData)
        return iv + cipherText
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        val key = secureKeyManager.getOrCreateBackupKey()
        val iv = encryptedData.copyOfRange(0, 12)
        val cipherText = encryptedData.copyOfRange(12, encryptedData.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }
}
```

10.4 备份内容

备份项 格式 说明
人设列表 JSON 含 userNickname、userGender、personality、customPrompt、avatarUrl、greeting
全局前缀 JSON 含 enabled、text
会话列表 JSON 含标题、模型、时间戳
消息记录 JSONL 按会话分组
记忆库 JSON 含 content、scope、category、importance、embedding
模型配置 JSON 含 API Key（加密）、模型名、端点

10.5 BackupManager

```kotlin
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupEncryptor: BackupEncryptor,
    private val ossUploader: OssUploader,
    private val personaDao: PersonaDao,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val globalPrefixRepository: GlobalPrefixRepository
) {

    suspend fun exportBackup(): File {
        val backup = buildBackupJson()
        val plainBytes = backup.toByteArray(Charsets.UTF_8)
        val encrypted = backupEncryptor.encrypt(plainBytes)

        val file = File(context.cacheDir, "yuki_backup_${timestamp()}.enc")
        file.writeBytes(encrypted)
        return file
    }

    suspend fun uploadBackup(userId: String) {
        val file = exportBackup()
        val remotePath = "user_$userId/backup_${timestamp()}.enc"
        ossUploader.upload(file, remotePath)
    }

    suspend fun restoreFromCloud(userId: String) {
        val remotePath = "user_$userId/latest_backup.enc"
        val encrypted = ossUploader.download(remotePath)
        val plainBytes = backupEncryptor.decrypt(encrypted)
        val backup = Json.decodeFromString<BackupData>(String(plainBytes, Charsets.UTF_8))
        restoreBackup(backup)
    }

    suspend fun restoreFromFile(file: File) {
        val encrypted = file.readBytes()
        val plainBytes = backupEncryptor.decrypt(encrypted)
        val backup = Json.decodeFromString<BackupData>(String(plainBytes, Charsets.UTF_8))
        restoreBackup(backup)
    }

    suspend fun getBackupStatus(): BackupStatus {
        return BackupStatus(lastBackupTime = 0L, hasCloudBackup = false)
    }

    private fun buildBackupJson(): String {
        return ""
    }

    private suspend fun restoreBackup(backup: BackupData) {
        // 按顺序恢复：全局前缀 → 人设 → 会话 → 消息 → 记忆 → 配置
    }
}

data class BackupStatus(
    val lastBackupTime: Long,
    val hasCloudBackup: Boolean
)
```

10.6 备份数据模型

```kotlin
@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long,
    val globalPrefix: GlobalPrefixBackup,
    val personas: List<PersonaBackup>,
    val sessions: List<SessionBackup>,
    val messages: List<MessageBackup>,
    val memories: List<MemoryBackup>,
    val config: ConfigBackup
)

@Serializable
data class GlobalPrefixBackup(
    val enabled: Boolean,
    val text: String
)

@Serializable
data class PersonaBackup(
    val id: String,
    val userNickname: String,
    val userGender: String,
    val personality: String?,
    val customPrompt: String,
    val avatarUrl: String?,
    val greeting: String?,
    val greetingVariants: String?,
    val createdAt: Long
)

@Serializable
data class SessionBackup(
    val id: String,
    val personaId: String,
    val title: String?,
    val model: String,
    val createdAt: Long
)

@Serializable
data class MessageBackup(
    val sessionId: String,
    val role: String,
    val content: String,
    val seq: Int,
    val createdAt: Long
)

@Serializable
data class MemoryBackup(
    val personaId: String,
    val sessionId: String?,
    val scope: String,
    val content: String,
    val category: String,
    val importance: Int,
    val embedding: String
)

@Serializable
data class ConfigBackup(
    val apiKeyEncrypted: String,
    val model: String,
    val endpoint: String
)
```

10.7 OSS 预签名直传

```kotlin
@Singleton
class OssUploader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val backendApi: BackendApi
) {

    suspend fun upload(file: File, remotePath: String) {
        val presign = backendApi.getPresignedUrl(remotePath)

        val request = Request.Builder()
            .url(presign.uploadUrl)
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Upload failed: ${response.code}")
        }
    }

    suspend fun download(remotePath: String): ByteArray {
        val presign = backendApi.getPresignedUrl(remotePath, method = "GET")
        val request = Request.Builder().url(presign.downloadUrl).build()
        val response = okHttpClient.newCall(request).execute()
        return response.body?.bytes() ?: throw IOException("Download failed")
    }
}
```

11. 人设市场

11.1 功能概览

功能 说明
浏览人设 双列网格，展示缩略图 + 标题
查看详情 缩略展示（标题 + 图片 + 简介）
下载人设 下载后可一键复制到"我的人设"
点赞 点赞数展示
评论 评论 + 回复他人评论 + 点赞评论
浏览量 每次浏览计一次，作者可见
作者信息 头像缩略图 + 昵称
上传人设 图片裁剪 + 标题 + 内容 + 是否公开
公开人设 详情页可直接查看完整人设 + 一键复制
不公开人设 需下载后才能查看完整人设

11.2 市场人设数据模型

```kotlin
@Entity(tableName = "market_personas")
data class MarketPersonaEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorNickname: String,
    val authorAvatarUrl: String?,
    val title: String,
    val thumbnailUrl: String,
    val description: String,
    val isPublic: Boolean,
    val fullContent: String?,
    val viewCount: Long = 0,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "market_comments")
data class MarketCommentEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val authorId: String,
    val authorNickname: String,
    val authorAvatarUrl: String?,
    val parentId: String?,
    val content: String,
    val likeCount: Long = 0,
    val createdAt: Long
)

@Entity(tableName = "market_likes", primaryKeys = ["personaId", "userId"])
data class MarketLikeEntity(
    val personaId: String,
    val userId: String,
    val createdAt: Long
)

@Entity(tableName = "comment_likes", primaryKeys = ["commentId", "userId"])
data class CommentLikeEntity(
    val commentId: String,
    val userId: String,
    val createdAt: Long
)
```

11.3 市场 DAO

```kotlin
@Dao
interface MarketDao {

    @Query("""
        SELECT * FROM market_personas
        WHERE isPublic = 1
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMarketList(limit: Int, offset: Int): List<MarketPersonaEntity>

    @Query("SELECT * FROM market_personas WHERE id = :id")
    suspend fun getById(id: String): MarketPersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(persona: MarketPersonaEntity)

    @Query("UPDATE market_personas SET viewCount = viewCount + 1 WHERE id = :id")
    suspend fun incrementViewCount(id: String)

    @Query("UPDATE market_personas SET likeCount = likeCount + 1 WHERE id = :id")
    suspend fun incrementLikeCount(id: String)

    @Query("UPDATE market_personas SET likeCount = likeCount - 1 WHERE id = :id")
    suspend fun decrementLikeCount(id: String)

    @Query("UPDATE market_personas SET commentCount = commentCount + 1 WHERE id = :id")
    suspend fun incrementCommentCount(id: String)

    @Insert
    suspend fun insertLike(like: MarketLikeEntity)

    @Query("DELETE FROM market_likes WHERE personaId = :personaId AND userId = :userId")
    suspend fun deleteLike(personaId: String, userId: String)

    @Query("""
        SELECT COUNT(*) > 0 FROM market_likes
        WHERE personaId = :personaId AND userId = :userId
    """)
    suspend fun isLiked(personaId: String, userId: String): Boolean

    @Query("""
        SELECT * FROM market_comments
        WHERE personaId = :personaId
        ORDER BY createdAt DESC
    """)
    suspend fun getComments(personaId: String): List<MarketCommentEntity>

    @Insert
    suspend fun insertComment(comment: MarketCommentEntity)
}
```

11.4 上传人设流程

```kotlin
@Composable
fun UploadPersonaScreen(
    onBack: () -> Unit,
    viewModel: UploadPersonaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传人设") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.upload() },
                        enabled = uiState.canUpload
                    ) { Text("发布") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImagePickerWithCrop(
                imageUri = uiState.imageUri,
                onImageSelected = { viewModel.onImageSelected(it) },
                aspectRatio = 1f,
                onCropComplete = { viewModel.onCropComplete(it) }
            )

            YukiTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                label = "人设标题 *",
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.title.isBlank()
            )

            YukiTextField(
                value = uiState.content,
                onValueChange = viewModel::onContentChange,
                label = "人设内容 *",
                placeholder = "角色名称、性格、世界观、说话风格等",
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                isError = uiState.content.isBlank()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "公开人设",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.isPublic,
                    onCheckedChange = viewModel::onPublicChange
                )
            }

            Text(
                text = if (uiState.isPublic) {
                    "公开后，其他用户可在详情页直接查看完整人设"
                } else {
                    "不公开时，其他用户需下载后才能查看完整人设"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

11.5 图片裁剪（uCrop）

```kotlin
@Composable
fun ImagePickerWithCrop(
    imageUri: Uri?,
    onImageSelected: (Uri) -> Unit,
    aspectRatio: Float,
    onCropComplete: (Uri) -> Unit
) {
    val context = LocalContext.current
    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUri = UCrop.getOutput(result.data!!)
            croppedUri?.let { onCropComplete(it) }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            onImageSelected(it)
            val intent = UCrop.of(
                it,
                Uri.fromFile(File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg"))
            )
                .withAspectRatio(aspectRatio, 1f)
                .withMaxResultSize(1024, 1024)
                .getIntent(context)
            cropLauncher.launch(intent)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { pickLauncher.launch(PickVisualMediaRequest(ImageOnly)) },
        contentAlignment = Alignment.Center
    ) {
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "选择图片",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("添加人设图片", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

11.6 缩略图生成与上传

```kotlin
@Singleton
class PersonaImageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ossUploader: OssUploader
) {

    companion object {
        private const val THUMBNAIL_SIZE = 128
    }

    fun generateThumbnail(sourceUri: Uri): Bitmap {
        val source = BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(sourceUri)
        )
        return Bitmap.createScaledBitmap(source, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
    }

    suspend fun uploadThumbnail(thumbnail: Bitmap, personaId: String): String {
        val file = File(context.cacheDir, "thumb_$personaId.jpg")
        file.outputStream().use {
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }

        val remotePath = "market/thumbnails/$personaId.jpg"
        ossUploader.upload(file, remotePath)
        return remotePath
    }

    fun saveLocalThumbnail(thumbnail: Bitmap, personaId: String): File {
        val dir = File(context.filesDir, "persona_thumbnails")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$personaId.jpg")
        file.outputStream().use {
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        return file
    }
}
```

11.7 人设市场界面

```kotlin
@Composable
fun PersonaMarketScreen(
    onPersonaClick: (String) -> Unit,
    onUploadClick: () -> Unit,
    viewModel: MarketViewModel = hiltViewModel()
) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    var selectedCategory by remember { mutableStateOf("推荐") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("人设市场") },
                actions = {
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                }
            )

            ScrollableTabRow(
                selectedTabIndex = 0,
                edgePadding = 16.dp
            ) {
                listOf("推荐", "热门", "最新", "治愈", "傲娇", "温柔", "高冷").forEach { category ->
                    Tab(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        text = { Text(category) }
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(personas, key = { it.id }) { persona ->
                    MarketPersonaCard(
                        persona = persona,
                        onClick = { onPersonaClick(persona.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onUploadClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "上传人设")
        }
    }
}

@Composable
fun MarketPersonaCard(
    persona: MarketPersonaUiModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            AsyncImage(
                model = persona.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = persona.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = persona.authorAvatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(Modifier.width(4.dp))

                    Text(
                        text = persona.authorNickname,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = formatCount(persona.likeCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
```

11.8 人设详情界面

```kotlin
@Composable
fun MarketPersonaDetailScreen(
    personaId: String,
    onBack: () -> Unit,
    viewModel: MarketDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(personaId) {
        viewModel.loadDetail(personaId)
        viewModel.incrementViewCount(personaId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人设详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                AsyncImage(
                    model = uiState.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Crop
                )
            }

            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = uiState.authorAvatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = uiState.authorNickname,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${uiState.viewCount} 次浏览",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    text = uiState.description,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                if (uiState.isPublic) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = uiState.fullContent ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.copyToMyPersonas(personaId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("添加到我的角色")
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "此角色未公开完整内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.downloadAndCopy(personaId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("下载并添加到我的角色")
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Row(
                        modifier = Modifier.clickable { viewModel.toggleLike(personaId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (uiState.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "点赞",
                            tint = if (uiState.isLiked) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("${uiState.likeCount}")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Comment, contentDescription = "评论")
                        Spacer(Modifier.width(4.dp))
                        Text("${uiState.commentCount}")
                    }
                }
            }

            item {
                Text(
                    text = "评论",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(uiState.comments) { comment ->
                CommentItem(
                    comment = comment,
                    onLike = { viewModel.toggleCommentLike(comment.id) },
                    onReply = { viewModel.replyTo(comment.id) }
                )
            }
        }
    }
}
```

12. 系统架构

12.1 模块划分

```
app/                     单 Activity + Navigation Compose 入口
├── core/
│   ├── common/          通用工具、扩展函数
│   ├── model/           领域模型
│   ├── database/        Room + SQLCipher 数据库
│   ├── datastore/       DataStore + GlobalPrefixRepository
│   ├── network/         OkHttp + SSE
│   ├── security/        Keystore 密钥管理 + 加密
│   ├── cloud/           OSS 直传 + 备份
│   ├── image/           缩略图生成 + Coil 配置
│   └── designsystem/    Compose 主题、组件
├── feature/
│   ├── splash/          加载界面 + 初始化
│   ├── auth/            登录/注册
│   ├── chat/            聊天界面
│   ├── persona/         人设编辑与管理（含开场白）
│   ├── memory/          记忆管理界面
│   ├── market/          人设市场
│   ├── session/         会话列表 + SessionCreator
│   ├── diagnostics/     缓存诊断面板
│   ├── account/         账号管理
│   ├── about/           关于界面
│   └── settings/        设置（含全局前缀编辑）
├── domain/
│   ├── promptengine/    PromptEngine + FrozenPrefixBuilder
│   ├── memory/          记忆库
│   ├── greeting/        GreetingBuilder
│   ├── repository/      Repository 接口与实现
│   └── usecase/         用例
└── di/                  Hilt Module
```

12.2 数据流

```
UI Layer (Compose)
    ↓ StateFlow
Presentation Layer (ViewModel)
    ↓ suspend / Flow
Domain Layer (PromptEngine / Memory* / SessionCreator / Repository)
    ↓
Data Layer (Room / DataStore / OkHttp SSE / Keystore / OSS)
```

13. 数据层设计

13.1 UserEntity

```kotlin
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val nickname: String,
    val avatarUrl: String?,
    val email: String?,
    val passwordHash: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

13.2 PersonaEntity

```kotlin
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val userNickname: String,
    val userGender: String,
    val personality: String?,
    val customPrompt: String,
    val systemPrompt: String,
    val systemHash: String,
    val avatarUrl: String?,
    val greeting: String?,
    val greetingVariants: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

13.3 SessionEntity

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val personaId: String,
    val title: String?,
    val model: String = "deepseek-v4-flash",
    val createdAt: Long,
    val updatedAt: Long
)
```

13.4 MessageEntity

```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val seq: Int,
    val createdAt: Long
)
```

13.5 UserConstraintEntity

```kotlin
@Entity(tableName = "user_constraints")
data class UserConstraintEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    val seq: Int,
    val createdAt: Long
)
```

13.6 MemoryEntity

```kotlin
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val personaId: String,
    val sessionId: String?,
    val scope: String,
    val content: String,
    val category: String,
    val importance: Int,
    val source: String,
    val embedding: ByteArray,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val expiresAt: Long?
)
```

13.7 CacheSnapshotEntity

```kotlin
@Entity(tableName = "cache_snapshots")
data class CacheSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val globalPrefixHash: String,
    val personaHash: String,
    val combinedHash: String,
    val snapshotData: String,
    val lastSeq: Int,
    val createdAt: Long,
    val expiresAt: Long
)
```

13.8 CacheDiagnosticEntity

```kotlin
@Entity(tableName = "cache_diagnostics")
data class CacheDiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val turnIndex: Int,
    val promptCacheHitTokens: Long,
    val promptCacheMissTokens: Long,
    val hitRate: Double,
    val prefixHashBefore: String,
    val prefixHashAfter: String,
    val appendixDeltaBytes: Int,
    val timestamp: Long
)
```

13.9 WalEntryEntity

```kotlin
@Entity(tableName = "sse_wal")
data class WalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val eventId: String?,
    val turnIndex: Int,
    val delta: String,
    val finishReason: String?,
    val receivedAt: Long,
    val deliveredToUi: Boolean = false,
    val persistedToHistory: Boolean = false
)
```

13.10 Room + SQLCipher 配置

```kotlin
@Database(
    entities = [
        UserEntity::class, PersonaEntity::class, SessionEntity::class,
        MessageEntity::class, UserConstraintEntity::class,
        MemoryEntity::class, MarketPersonaEntity::class,
        MarketCommentEntity::class, MarketLikeEntity::class,
        CommentLikeEntity::class, CacheSnapshotEntity::class,
        CacheDiagnosticEntity::class, WalEntryEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun personaDao(): PersonaDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun userConstraintDao(): UserConstraintDao
    abstract fun memoryDao(): MemoryDao
    abstract fun marketDao(): MarketDao
    abstract fun cacheSnapshotDao(): CacheSnapshotDao
    abstract fun cacheDiagnosticDao(): CacheDiagnosticDao
    abstract fun walDao(): WalDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secureKeyManager: SecureKeyManager
    ): AppDatabase {
        System.loadLibrary("sqlite-vec")

        val passphrase = SQLiteDatabase.getBytes(
            secureKeyManager.getOrCreateDbKey(context).toCharArray()
        )

        return Room.databaseBuilder(context, AppDatabase::class.java, "chat.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("SELECT load_extension('sqlite-vec')")
                    db.execSQL("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_vectors USING vec0(
                            memory_id TEXT PRIMARY KEY,
                            persona_id TEXT PARTITION KEY,
                            embedding FLOAT[512]
                        )
                    """)
                }
            })
            .build()
    }
}
```

13.11 Paging 3 消息 DAO

```kotlin
@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId
        ORDER BY seq DESC
    """)
    fun pagingSource(sessionId: String): PagingSource<Int, MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Transaction
    suspend fun appendTurn(userMessage: MessageEntity, assistantMessage: MessageEntity) {
        insertAll(listOf(userMessage, assistantMessage))
    }
}
```

13.12 Gradle 配置

```kotlin
android {
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // SQLCipher
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // sqlite-vec
    implementation("com.github.techascent:sqlite-vec-android:0.1.9")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // uCrop
    implementation("com.github.yalantis:ucrop:2.2.8")
}
```

14. 网络层与 SSE 可靠性

14.1 OkHttpClient

```kotlin
@Provides
@Singleton
fun provideOkHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
```

14.2 SSE 五方案组合

方案 场景 核心机制 优先级
网络感知重连 WiFi 切换 4G ConnectivityManager + collectLatest 必做
WAL 持久化缓冲 App 被杀死 Room WAL + Last-Event-ID 必做
半开连接检测 电梯/地铁 readTimeout(75s) + 心跳 必做
前台服务保活 后台限制 ForegroundService + WAKE_LOCK 可降级
降级兜底 极端弱网 非流式 POST + 草稿 可降级

14.3 网络感知重连

```kotlin
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkState.Available(network))
            }
            override fun onLost(network: Network) {
                trySend(NetworkState.Lost(network))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

sealed interface NetworkState {
    data class Available(val network: Network) : NetworkState
    data class Lost(val network: Network) : NetworkState
}
```

```kotlin
class ResilientSseConsumer @Inject constructor(
    private val deepSeekClient: DeepSeekClient,
    private val connectivityMonitor: ConnectivityMonitor,
    private val walDao: WalDao
) {
    fun events(messages: List<ChatMessage>, model: String): Flow<SseEvent> = channelFlow {
        connectivityMonitor.networkState.collectLatest { state ->
            if (state is NetworkState.Available) {
                val lastEventId = walDao.lastEventId()
                deepSeekClient.streamChatCompletion(
                    messages = messages,
                    model = model,
                    lastEventId = lastEventId
                )
                    .onEach { event -> walDao.insert(event.toWalEntry()) }
                    .buffer(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
                    .collect { event -> send(event) }
            }
        }
    }.flowOn(Dispatchers.IO)
}
```

14.4 前台服务

```kotlin
@AndroidEntryPoint
class StreamingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, sessionTitle: String) {
            val intent = Intent(context, StreamingForegroundService::class.java).apply {
                action = "action_start"
                putExtra("session_title", sessionTitle)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "action_start" -> {
                val title = intent.getStringExtra("session_title") ?: "正在生成回复"
                startForeground(NOTIFICATION_ID, buildNotification(title))
                acquireWakeLock()
            }
            "action_stop" -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "yuki::streaming_wakelock"
        ).apply { acquire(5 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(title: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("正在生成回复，请不要关闭应用")
            .setSmallIcon(R.drawable.ic_streaming)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "流式回复", NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
```

14.5 重连退避

```kotlin
object ReconnectStrategy {
    fun delay(attempt: Int): Long {
        val baseMs = 1000L
        val maxMs = 30_000L
        val exponential = (baseMs * 2.0.pow(attempt.coerceAtMost(5))).toLong()
        val capped = exponential.coerceAtMost(maxMs)
        return (capped * Random.nextDouble(0.5, 1.0)).toLong()
    }
}
```

14.6 Manifest 权限

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application>
        <service
            android:name=".service.StreamingForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
    </application>
</manifest>
```

15. 长对话与流式渲染性能

15.1 列表渲染

```kotlin
LazyColumn(state = listState) {
    items(
        items = messages,
        key = { it.id },
        contentType = { it.role }
    ) { message ->
        MessageBubble(message)
    }
}
```

```kotlin
@Immutable
data class DisplayMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false
)
```

15.2 分页加载

```kotlin
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    fun pagingMessages(sessionId: String): Flow<PagingData<MessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { messageDao.pagingSource(sessionId) }
        ).flow
    }
}
```

15.3 流式渲染

```kotlin
class MarkdownBuffer {
    private val buffer = StringBuilder()

    fun append(chunk: String): String {
        buffer.append(chunk)
        return hideUnclosedTokens(buffer.toString())
    }

    private fun hideUnclosedTokens(text: String): String {
        return text
    }

    fun flush(): String = buffer.toString()
}
```

```kotlin
.onCompletion { cause ->
    if (cause == null) {
        val fullContent = buffer.toString()
        _uiState.update {
            it.copy(
                streamingContent = null,
                streamState = StreamState.Completed(fullContent, usageInfo)
            )
        }
        withContext(Dispatchers.Main) { yield() }
        messageRepository.appendTurn(sessionId, userInput, fullContent)
    }
}
```

15.4 性能设计清单

设计点 实现方式
列表稳定 key 消息 UUID 作为 key
类型复用 contentType 按 role 区分
跳过重组 @Immutable 标注 DisplayMessage
高频状态隔离 derivedStateOf 隔离流式状态
分页加载 Paging 3 + Room PagingSource
流式请求 独立 OkHttpClient（readTimeout=75s）
Markdown 缓冲 MarkdownBuffer 隐藏未闭合标签
末尾刷新 StateFlow + onCompletion 强制更新
持久化时机 延迟一帧后再写数据库

16. PromptEngine 核心实现

16.1 领域模型

```kotlin
data class ChatMessage(val role: String, val content: String)

data class Persona(
    val id: String,
    val userId: String,
    val userNickname: String,
    val userGender: String,
    val personality: String?,
    val customPrompt: String,
    val systemPrompt: String,
    val systemHash: String
)

data class Session(
    val id: String,
    val personaId: String,
    val history: List<ChatMessage>,
    val model: String,
    val turnIndex: Int,
    val currentMood: String?,
    val activeConstraints: List<UserConstraint>
)
```

16.2 FrozenPrefixBuilder

```kotlin
object FrozenPrefixBuilder {

    fun build(
        globalPrefix: String?,
        persona: PersonaEntity
    ): String {
        return buildString {
            if (!globalPrefix.isNullOrBlank()) {
                appendLine(globalPrefix)
                appendLine()
            }

            appendLine("# 关于用户")
            appendLine("- 用户昵称：${persona.userNickname}")
            appendLine("- 用户性别：${persona.userGender}")
            appendLine()

            if (!persona.personality.isNullOrBlank()) {
                appendLine("# 角色性格")
                appendLine(persona.personality)
                appendLine()
            }

            appendLine("# 角色设定")
            appendLine(persona.customPrompt)
        }
    }
}
```

16.3 PromptEngine

```kotlin
@Singleton
class PromptEngine @Inject constructor(
    private val memoryInjector: MemoryInjector,
    private val globalPrefixRepository: GlobalPrefixRepository
) {

    suspend fun buildMessages(
        persona: Persona,
        session: Session,
        appendixDelta: String,
        userInput: String
    ): List<ChatMessage> {
        val globalPrefix = globalPrefixRepository.getCurrentPrefix()

        val memoryBlock = memoryInjector.buildMemoryBlock(
            userId = persona.userId,
            personaId = persona.id,
            sessionId = session.id,
            userInput = userInput
        )

        val fullAppendix = buildString {
            if (appendixDelta.isNotBlank()) {
                append(appendixDelta)
                append("\n")
            }
            if (memoryBlock.isNotBlank()) append(memoryBlock)
        }

        return buildList {
            val systemPrompt = FrozenPrefixBuilder.build(
                globalPrefix = globalPrefix,
                persona = persona.toEntity()
            )
            add(ChatMessage("system", systemPrompt))

            addAll(session.history)

            if (fullAppendix.isNotBlank()) {
                add(ChatMessage("system", fullAppendix))
            }

            add(ChatMessage("user", userInput))
        }
    }

    fun buildAppendixDelta(
        currentTime: String,
        moodState: String?,
        activeConstraints: List<UserConstraint>
    ): String {
        return buildString {
            append("<appendix>\n")
            append("  <time>").append(currentTime).append("</time>\n")
            if (!moodState.isNullOrBlank()) {
                append("  <mood>").append(escapeXml(moodState)).append("</mood>\n")
            }
            if (activeConstraints.isNotEmpty()) {
                append("  <constraints>\n")
                activeConstraints.forEach { c ->
                    append("    <constraint>")
                    append(escapeXml(c.text))
                    append("</constraint>\n")
                }
                append("  </constraints>\n")
            }
            append("</appendix>")
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
```

16.4 PrefixValidator

```kotlin
@Singleton
class PrefixValidator @Inject constructor(
    private val globalPrefixRepository: GlobalPrefixRepository
) {

    suspend fun validate(
        session: Session,
        persona: PersonaEntity,
        snapshot: CacheSnapshotEntity?
    ): PrefixValidationResult {
        if (snapshot == null) {
            return PrefixValidationResult.NoSnapshot
        }

        val globalPrefix = globalPrefixRepository.getCurrentPrefix()
        val currentGlobalHash = PrefixHasher.hashString(globalPrefix ?: "")
        val currentPersonaHash = PrefixHasher.hashString(
            FrozenPrefixBuilder.build(null, persona)
        )
        val currentCombinedHash = PrefixHasher.hashString(
            FrozenPrefixBuilder.build(globalPrefix, persona)
        )

        return when {
            snapshot.combinedHash == currentCombinedHash ->
                PrefixValidationResult.Valid
            snapshot.globalPrefixHash != currentGlobalHash ->
                PrefixValidationResult.GlobalPrefixChanged
            snapshot.personaHash != currentPersonaHash ->
                PrefixValidationResult.PersonaChanged
            else ->
                PrefixValidationResult.Unknown
        }
    }
}

sealed interface PrefixValidationResult {
    data object Valid : PrefixValidationResult
    data object NoSnapshot : PrefixValidationResult
    data object GlobalPrefixChanged : PrefixValidationResult
    data object PersonaChanged : PrefixValidationResult
    data object Unknown : PrefixValidationResult
}
```

17. API Key 安全存储

```kotlin
@Singleton
class ApiKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val KEYSTORE_ALIAS = "yuki_api_key_alias"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_NAME = "secure_api_key"
        private const val KEY_PREF = "encrypted_key"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    fun saveApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREF, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getApiKey(): String? {
        val encoded = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREF, null) ?: return null
        return try {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (e: Exception) {
            clearApiKey()
            null
        }
    }

    fun clearApiKey() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PREF).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(encrypted: ByteArray): String {
        require(encrypted.size > GCM_IV_LENGTH)
        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
```

安全约束：

约束 说明
禁止写入代码 不硬编码 API Key
禁止提交 Git 不把 Key 放进版本控制
禁止明文存储 不存 SharedPreferences 明文
禁止发送后端 不经过验证后端
使用 Keystore 硬件级密钥保护
使用 GCM 模式 认证加密

18. 后台任务

```kotlin
@HiltWorker
class MemoryExtractionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val memoryWriter: MemoryWriter,
    private val sessionRepository: SessionRepository,
    private val personaRepository: PersonaRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            val sessions = sessionRepository.getSessionsNeedingExtraction()
            sessions.forEach { session ->
                val persona = personaRepository.getById(session.personaId)
                if (persona?.userId != session.userId) return@forEach

                val memories = extractMemories(session)
                memories.forEach { m ->
                    memoryWriter.writeMemory(
                        userId = session.userId,
                        personaId = session.personaId,
                        sessionId = session.id,
                        scope = m.scope,
                        content = m.content,
                        category = m.category,
                        importance = m.importance,
                        source = "auto_summary"
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun extractMemories(session: Session): List<ExtractedMemory> {
        return emptyList()
    }
}
```

```kotlin
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val backupManager: BackupManager,
    private val userRepository: UserRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            val userId = userRepository.getCurrentUserId() ?: return Result.success()
            backupManager.uploadBackup(userId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
```

19. 验证后端 API

19.1 认证接口

```
POST /api/v1/auth/register    注册，返回 uid + token
POST /api/v1/auth/login       登录，返回 token
POST /api/v1/auth/refresh     刷新 Token
POST /api/v1/auth/logout      登出
```

19.2 注册接口详情

请求：

```json
{
  "nickname": "小明",
  "password": "xxx",
  "avatarUrl": "https://..."
}
```

响应：

```json
{
  "uid": "10001",
  "token": "jwt...",
  "user": {
    "id": "uuid",
    "uid": "10001",
    "nickname": "小明",
    "avatarUrl": "https://...",
    "email": null
  }
}
```

19.3 账号管理接口

```
PUT  /api/v1/user/nickname        修改昵称
PUT  /api/v1/user/avatar          修改头像
PUT  /api/v1/user/password        修改密码
POST /api/v1/auth/email/send-code 发送验证码
POST /api/v1/auth/email/bind      绑定邮箱
POST /api/v1/auth/password/send-code 找回密码发送验证码
POST /api/v1/auth/password/reset  重置密码
```

19.4 人设市场接口

```
GET  /api/v1/market/personas             获取人设列表
GET  /api/v1/market/personas/:id         获取人设详情
POST /api/v1/market/personas             上传人设
POST /api/v1/market/personas/:id/like    点赞/取消点赞
GET  /api/v1/market/personas/:id/comments 获取评论
POST /api/v1/market/personas/:id/comments 发表评论
POST /api/v1/market/comments/:id/like    点赞评论
```

19.5 OSS 预签名接口

```
POST /api/v1/oss/presign
```

19.6 应用版本接口

```
GET /api/v1/app/version
GET /api/v1/app/changelog
```

19.7 后端职责边界

做 不做
登录/注册 不接触用户 DeepSeek API Key
UID 下发 不代理 DeepSeek 请求
邮箱验证 不下发缓存策略
人设市场数据 不存储聊天明文
OSS 预签名 不存储记忆库明文
版本检测 不存储全局前缀

20. 加载界面：连接预热与本地初始化

20.1 两种场景

场景 触发时机 用户状态
场景 A：冷启动 App 启动 已登录
场景 B：登录成功 用户完成登录 刚获得 token

20.2 加载界面任务清单

优先级 任务
1 初始化 Room 数据库
2 加载用户配置
3 预加载当前用户信息
4 预热 DeepSeek 连接
5 预加载会话列表
6 预加载本地缩略图
7 检查网络状态
8 检查备份状态

关键约束：所有任务并行执行，不阻塞动画。

20.3 AppInitializer 实现

```kotlin
@Singleton
class AppInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val userPreferences: UserPreferencesRepository,
    private val connectionPreheater: ConnectionPreheater,
    private val imagePreloader: ImagePreloader,
    private val connectivityMonitor: ConnectivityMonitor,
    private val backupManager: BackupManager
) {

    suspend fun initialize(): InitResult = coroutineScope {
        val dbDeferred = async(Dispatchers.IO) {
            database.openHelper.writableDatabase
            Result.success(Unit)
        }

        val prefsDeferred = async(Dispatchers.IO) {
            runCatching { userPreferences.userPreferences.first() }
        }

        val userDeferred = async(Dispatchers.IO) {
            runCatching { userRepository.getCurrentUser() }
        }

        val preheatDeferred = async(Dispatchers.IO) {
            runCatching { connectionPreheater.preheat() }
        }

        val sessionsDeferred = async(Dispatchers.IO) {
            runCatching { sessionRepository.getRecentSessions(limit = 20) }
        }

        val networkDeferred = async {
            runCatching { connectivityMonitor.isCurrentlyConnected() }
        }

        val backupDeferred = async(Dispatchers.IO) {
            runCatching { backupManager.getBackupStatus() }
        }

        val dbResult = dbDeferred.await()
        val userResult = userDeferred.await()

        val sessions = sessionsDeferred.await().getOrDefault(emptyList())
        val preloadDeferred = async(Dispatchers.IO) {
            runCatching {
                val urls = sessions.mapNotNull { it.personaLocalThumbnail }
                imagePreloader.preload(urls)
            }
        }
        preloadDeferred.await()

        InitResult(
            dbReady = dbResult.isSuccess,
            user = userResult.getOrNull(),
            sessions = sessions,
            networkAvailable = networkDeferred.await().getOrDefault(false),
            backupStatus = backupDeferred.await().getOrNull(),
            initializedAt = System.currentTimeMillis()
        )
    }
}

data class InitResult(
    val dbReady: Boolean,
    val user: UserEntity?,
    val sessions: List<SessionEntity>,
    val networkAvailable: Boolean,
    val backupStatus: BackupStatus?,
    val initializedAt: Long
)
```

20.4 ConnectionPreheater

```kotlin
@Singleton
class ConnectionPreheater @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun preheat() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.deepseek.com/")
            .head()
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                Log.d("ConnectionPreheater", "Preheat done: ${response.code}")
            }
        } catch (e: Exception) {
            Log.w("ConnectionPreheater", "Preheat failed", e)
        }
    }
}
```

20.5 ImagePreloader

```kotlin
@Singleton
class ImagePreloader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun preload(localPaths: List<String>) {
        localPaths.take(20).forEach { path ->
            val request = ImageRequest.Builder(context)
                .data(File(path))
                .memoryCacheKey(path)
                .build()
            context.imageLoader.enqueue(request)
        }
    }
}
```

20.6 加载界面与初始化并行

```kotlin
@Composable
fun SplashScreen(
    onReady: (InitResult) -> Unit
) {
    val viewModel: SplashViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var animDone by remember { mutableStateOf(false) }
    var initDone by remember { mutableStateOf(false) }
    var initResult by remember { mutableStateOf<InitResult?>(null) }

    LaunchedEffect(Unit) {
        launch {
            delay(1200)
            animDone = true
        }
        launch {
            initResult = viewModel.initialize()
            initDone = true
        }
    }

    LaunchedEffect(animDone, initDone) {
        if (animDone && initDone) {
            initResult?.let { onReady(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo_yuki),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text("Yuki 初雪", style = MaterialTheme.typography.headlineMedium)

            if (animDone && !initDone) {
                Spacer(Modifier.height(32.dp))
                LinearProgressIndicator(
                    modifier = Modifier.width(120.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

20.7 初始化失败的降级

失败任务 降级策略
Room 数据库打开失败 显示错误，提示重启 App
用户配置加载失败 使用默认配置
用户信息加载失败 重新登录
连接预热失败 忽略，后续请求时重新建立连接
会话列表加载失败 空列表
网络检查失败 假设离线
备份状态检查失败 跳过备份提示
缩略图预加载失败 忽略，进入会话时懒加载

21. 账号管理

21.1 功能清单

功能 入口 需要验证
修改全局昵称 设置 → 账号 否
修改全局头像 设置 → 账号 否
修改密码 设置 → 账号 原密码
绑定邮箱 设置 → 账号 邮箱验证码
找回密码 登录页 → 忘记密码 邮箱验证码

21.2 修改昵称

```kotlin
@Composable
fun EditNicknameScreen(
    onBack: () -> Unit,
    viewModel: EditNicknameViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改昵称") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onBack) },
                        enabled = uiState.canSave
                    ) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            YukiTextField(
                value = uiState.nickname,
                onValueChange = viewModel::onNicknameChange,
                label = "全局昵称",
                placeholder = "用于 App 内展示",
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.nickname.isBlank() || uiState.nickname.length > 20
            )

            Text(
                text = "注意：此昵称是全局展示昵称，与每个人设卡中的\"玩家昵称\"不同。" +
                       "玩家昵称用于角色怎么称呼你，每个人设独立设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

21.3 修改头像

```kotlin
@Composable
fun EditAvatarScreen(
    onBack: () -> Unit,
    viewModel: EditAvatarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUri = UCrop.getOutput(result.data!!)
            croppedUri?.let { viewModel.uploadAvatar(it, onBack) }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val intent = UCrop.of(
                it,
                Uri.fromFile(File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg"))
            )
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(512, 512)
                .getIntent(context)
            cropLauncher.launch(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改头像") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { pickLauncher.launch(PickVisualMediaRequest(ImageOnly)) },
                contentAlignment = Alignment.Center
            ) {
                if (uiState.avatarUrl != null) {
                    AsyncImage(
                        model = uiState.avatarUrl,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "选择头像",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "点击更换头像",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.isUploading) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }
        }
    }
}
```

21.4 修改密码

```kotlin
@Composable
fun EditPasswordScreen(
    onBack: () -> Unit,
    viewModel: EditPasswordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改密码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            YukiTextField(
                value = uiState.oldPassword,
                onValueChange = viewModel::onOldPasswordChange,
                label = "当前密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            YukiTextField(
                value = uiState.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = "新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.newPassword.length < 8
            )

            YukiTextField(
                value = uiState.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "确认新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.confirmPassword.isNotEmpty()
                    && uiState.newPassword != uiState.confirmPassword
            )

            Text(
                text = "密码至少 8 位，建议包含字母和数字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = uiState.canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("确认修改")
            }
        }
    }
}
```

21.5 邮箱找回密码

```kotlin
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var countdown by remember { mutableStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("找回密码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "输入你的账号 ID，我们会向绑定的邮箱发送验证码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            YukiTextField(
                value = uiState.uid,
                onValueChange = viewModel::onUidChange,
                label = "账号 ID",
                placeholder = "如 10001",
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YukiTextField(
                    value = uiState.code,
                    onValueChange = viewModel::onCodeChange,
                    label = "验证码",
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        viewModel.sendCode()
                        countdown = 60
                    },
                    enabled = uiState.uid.isNotBlank() && countdown == 0,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text(if (countdown > 0) "${countdown}s" else "发送")
                }
            }

            YukiTextField(
                value = uiState.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = "新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            YukiTextField(
                value = uiState.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "确认新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.confirmPassword.isNotEmpty()
                    && uiState.newPassword != uiState.confirmPassword
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.resetPassword(onSuccess) },
                enabled = uiState.canReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置密码")
            }
        }
    }
}
```

22. 关于界面：开源协议、致谢与支持

22.1 界面结构

```
┌─────────────────────────────────┐
│  关于                           │
├─────────────────────────────────┤
│         ┌────────┐              │
│         │ 作者头像│              │
│         └────────┘              │
│         作者名称                 │
│         软件介绍                 │
│         版本 1.0.0              │
├─────────────────────────────────┤
│  ┌──────────────────────────┐   │
│  │  检测更新                 │   │
│  │  更新日志                 │   │
│  └──────────────────────────┘   │
├─────────────────────────────────┤
│  开源协议                        │
│  ┌──────────────────────────┐   │
│  │  Apache License 2.0      │   │
│  │  查看完整协议文本         │   │
│  └──────────────────────────┘   │
├─────────────────────────────────┤
│  致谢                            │
│  ┌──────────────────────────┐   │
│  │  ┌──┐ 天枢（Tianshu）    │   │
│  │  │  │ 编程 Agent 运行时   │   │
│  │  └──┘ github.com/...    │   │
│  └──────────────────────────┘   │
│  ┌──────────────────────────┐   │
│  │  ┌──┐ Operit            │   │
│  │  │  │ 角色记忆引擎        │   │
│  │  └──┘ github.com/...    │   │
│  └──────────────────────────┘   │
├─────────────────────────────────┤
│  支持                            │
│  ┌──────────────────────────┐   │
│  │  [收款码 1]  [收款码 2]  │   │
│  │  如果你喜欢这个 App，     │   │
│  │  可以请作者喝杯咖啡       │   │
│  └──────────────────────────┘   │
├─────────────────────────────────┤
│  隐私政策                        │
│  用户协议                        │
└─────────────────────────────────┘
```

22.2 数据模型

```kotlin
data class AboutInfo(
    val authorAvatarUrl: String,
    val authorName: String,
    val appName: String,
    val appDescription: String,
    val versionName: String,
    val versionCode: Int,
    val apacheLicenseUrl: String,
    val privacyPolicyUrl: String,
    val termsOfServiceUrl: String,
    val acknowledgements: List<Acknowledgement>,
    val donationQrCodes: List<DonationQrCode>
)

data class Acknowledgement(
    val name: String,
    val description: String,
    val repoUrl: String,
    val avatarUrl: String,
    val license: String
)

data class DonationQrCode(
    val label: String,
    val imageResId: Int
)
```

22.3 关于界面实现

```kotlin
@Composable
fun AboutScreen(
    viewModel: AboutViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = uiState.authorAvatarUrl,
                    contentDescription = "作者头像",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = uiState.authorName,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = uiState.appDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "版本 ${uiState.versionName} (${uiState.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsCard {
                SettingsItemRow(
                    title = "检测更新",
                    subtitle = uiState.updateCheckSubtitle,
                    onClick = { viewModel.checkUpdate(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingsItemRow(
                    title = "更新日志",
                    onClick = { viewModel.openChangelog(context) }
                )
            }
        }

        item {
            SectionTitle("开源协议")
            SettingsCard {
                SettingsItemRow(
                    title = "Apache License 2.0",
                    subtitle = "本项目遵循 Apache-2.0 协议开源",
                    onClick = { viewModel.openApacheLicense(context) }
                )
            }
        }

        item { SectionTitle("致谢") }

        items(uiState.acknowledgements) { ack ->
            AcknowledgementCard(
                ack = ack,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ack.repoUrl))
                    context.startActivity(intent)
                }
            )
        }

        item {
            SectionTitle("支持")
            DonationCard(qrCodes = uiState.donationQrCodes)
        }

        item {
            SettingsCard {
                SettingsItemRow(
                    title = "隐私政策",
                    onClick = { viewModel.openPrivacyPolicy(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingsItemRow(
                    title = "用户协议",
                    onClick = { viewModel.openTermsOfService(context) }
                )
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
```

22.4 致谢卡片

```kotlin
@Composable
fun AcknowledgementCard(
    ack: Acknowledgement,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ack.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ack.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ack.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ack.repoUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "License: ${ack.license}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "打开链接",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

22.5 支持卡片（乞讨区）

```kotlin
@Composable
fun DonationCard(qrCodes: List<DonationQrCode>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "如果你喜欢这个 App",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "可以请作者喝杯咖啡",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                qrCodes.forEach { qr ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(qr.imageResId),
                            contentDescription = qr.label,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = qr.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
```

22.6 AboutViewModel

```kotlin
@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutInfo(
            authorAvatarUrl = "https://.../author_avatar.jpg",
            authorName = "Yuki 开发者",
            appName = "Yuki 初雪",
            appDescription = "一款专为人机恋场景设计的 AI 聊天应用",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            apacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            privacyPolicyUrl = "https://.../privacy",
            termsOfServiceUrl = "https://.../terms",
            acknowledgements = listOf(
                Acknowledgement(
                    name = "天枢（Tianshu Harness）",
                    description = "编程 Agent 运行时，缓存优化机制参考",
                    repoUrl = "https://github.com/huiliyi37/Tianshu-harness",
                    avatarUrl = "https://.../tianshu_avatar.png",
                    license = "Apache-2.0"
                ),
                Acknowledgement(
                    name = "Operit",
                    description = "角色记忆引擎，记忆库方案参考",
                    repoUrl = "https://github.com/AAswordman/Operit",
                    avatarUrl = "https://.../operit_avatar.png",
                    license = "Apache-2.0"
                )
            ),
            donationQrCodes = listOf(
                DonationQrCode("微信", R.drawable.donation_qr_1),
                DonationQrCode("支付宝", R.drawable.donation_qr_2)
            )
        )
    )
    val uiState: StateFlow<AboutInfo> = _uiState.asStateFlow()

    fun checkUpdate(context: Context) {
        viewModelScope.launch {
            updateChecker.check(context)
        }
    }

    fun openChangelog(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://.../changelog"))
        )
    }

    fun openApacheLicense(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(_uiState.value.apacheLicenseUrl))
        )
    }

    fun openPrivacyPolicy(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(_uiState.value.privacyPolicyUrl))
        )
    }

    fun openTermsOfService(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(_uiState.value.termsOfServiceUrl))
        )
    }
}
```

22.7 Apache License 2.0 协议遵循

要求 实现方式
保留版权声明 在 NOTICE 文件中声明
保留许可证文件 根目录放置 LICENSE
标明修改 文件头注明
NOTICE 文件 保留第三方 NOTICE
提供许可证副本 App 内关于界面提供链接

NOTICE 文件内容：

```
Yuki 初雪
Copyright 2026 Yuki 开发者

This product includes software developed by:
- Tianshu Harness (https://github.com/huiliyi37/Tianshu-harness)
  Licensed under the Apache License, Version 2.0
- Operit (https://github.com/AAswordman/Operit)
  Licensed under the Apache License, Version 2.0
```

22.8 检测更新与更新日志

后端接口：

```
GET /api/v1/app/version
响应：{
  "versionName": "1.1.0",
  "versionCode": 2,
  "downloadUrl": "https://.../yuki-1.1.0.apk",
  "changelog": "1. 新增 XXX 功能\n2. 修复 XXX 问题",
  "forceUpdate": false
}

GET /api/v1/app/changelog
响应：{
  "versions": [
    {
      "versionName": "1.1.0",
      "versionCode": 2,
      "releaseDate": "2026-10-01",
      "changes": ["新增 XXX", "修复 XXX"]
    }
  ]
}
```

23. 内容合规与 Android 版本兼容

23.1 内容合规

领域 措施
年龄验证 注册流程中集成年龄验证机制
内容分级 人设模板和 AI 回复按内容等级分类
平台政策 确保 App 分发渠道合规
用户举报 提供内容举报入口
隐私政策 提供清晰的隐私政策

23.2 Android 版本兼容

API 版本 变更点 处理方式
API 26 (O) 通知渠道 创建 NotificationChannel
API 29 (Q) 分区存储 无外部存储需求
API 31 (S) 精确闹钟；blur 支持 不使用精确闹钟；blur 仅 S+
API 33 (T) POST_NOTIFICATIONS 请求运行时权限
API 34 (U) 前台服务类型 声明 dataSync
API 35 (V) 前台服务类型进一步收紧 遵循 dataSync

前台服务类型处理：

```xml
<service
    android:name=".service.StreamingForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

24. 风险清单

风险 缓解
前缀字节不稳定 SHA-256 指纹追踪 + 不可变字符串
动态变量入前缀 代码审查 + 自动化测试
流式写入时机错误 StreamState 状态机 + 原子性事务
多会话混用 工作区隔离 + 会话绑定人设
缓存过期 快照 TTL 管理
API 变更 版本兼容层 + 监控告警
Key 泄露 Android Keystore
记忆写回前缀 只从附录注入
记忆跨用户串扰 userId 强制过滤
记忆跨角色串扰 personaId 强制过滤
记忆跨会话串扰 scope + sessionId 双键过滤
记忆库膨胀 去重 + 自衰减 + TTL
自动提取误差 记忆管理界面 + 用户可编辑
SSE 网络切换 网络感知重连 + WAL 恢复
SSE 半开连接 readTimeout(75s) 检测
App 被杀死 WAL 持久化 + CrashRecovery
后台网络冻结 ForegroundService + WAKE_LOCK
极端弱网 三级降级兜底
列表卡顿 稳定 key + Paging 3
流式截断 流式专用 OkHttpClient + 末尾刷新
Markdown 闪烁 MarkdownBuffer 缓冲
人设留空性格 FrozenPrefixBuilder 跳过
全局前缀修改 警告 + 仅新会话生效选项
开场白变量未替换 会话创建时替换一次
开场白写入前缀 禁止，必须作为首条 assistant 消息
SQLCipher 性能 首次打开慢，后续正常
向量检索延迟 BGE 推理异步化
备份数据泄露 AES-256-GCM 客户端加密
OSS 密钥泄露 预签名 URL 临时授权
人设市场违规 内容审核 + 举报机制
液态玻璃低端设备性能 blur 降级 + alpha 补偿

25. Agent 实现指南

25.1 实现顺序

按依赖关系分 17 步，严格按顺序推进：

```
第 1 步：项目骨架
第 2 步：数据层（含 PersonaEntity.greeting、CacheSnapshotEntity.globalPrefixHash）
第 3 步：安全与加密
第 4 步：领域模型
第 5 步：全局前缀
第 6 步：FrozenPrefixBuilder + PromptEngine
第 7 步：开场白
第 8 步：DeepSeek SSE 客户端
第 9 步：流式生命周期
第 10 步：加载界面与初始化
第 11 步：认证流程
第 12 步：记忆库
第 13 步：SSE 可靠性五方案
第 14 步：云存储与备份
第 15 步：人设市场
第 16 步：账号管理 + 关于界面
第 17 步：液态玻璃底部导航栏
```

25.2 无歧义接口签名

GlobalPrefixRepository：

```kotlin
val config: Flow<GlobalPrefixConfig>
suspend fun setEnabled(enabled: Boolean)
suspend fun setText(text: String)
suspend fun getCurrentPrefix(): String?
```

FrozenPrefixBuilder：

```kotlin
fun build(globalPrefix: String?, persona: PersonaEntity): String
```

GreetingBuilder：

```kotlin
fun build(greeting: String, persona: PersonaEntity): String
```

SessionCreator：

```kotlin
suspend fun createSession(persona: PersonaEntity, model: String): SessionEntity
```

PrefixValidator：

```kotlin
suspend fun validate(
    session: Session,
    persona: PersonaEntity,
    snapshot: CacheSnapshotEntity?
): PrefixValidationResult
```

PrefixHasher：

```kotlin
fun computeFingerprint(
    globalPrefix: String?,
    frozenPrefix: String,
    history: List<ChatMessage>
): String
fun hashString(text: String): String
```

PromptEngine：

```kotlin
suspend fun buildMessages(
    persona: Persona,
    session: Session,
    appendixDelta: String,
    userInput: String
): List<ChatMessage>
```

EmbeddingService：

```kotlin
fun embed(text: String): ByteArray
fun deserializeEmbedding(bytes: ByteArray): FloatArray
```

MemoryWriter：

```kotlin
suspend fun writeMemory(
    userId: String,
    personaId: String,
    sessionId: String?,
    scope: String,
    content: String,
    category: String,
    importance: Int,
    source: String
): Unit
```

MemoryRetriever：

```kotlin
suspend fun retrieveMemories(
    userId: String,
    personaId: String,
    sessionId: String,
    userInput: String,
    maxResults: Int = 5
): List<MemoryEntity>
```

DeepSeekClient：

```kotlin
fun streamChatCompletion(
    messages: List<ChatMessage>,
    model: String,
    lastEventId: String? = null
): Flow<SseEvent>
```

BackupManager：

```kotlin
suspend fun exportBackup(): File
suspend fun uploadBackup(userId: String)
suspend fun restoreFromCloud(userId: String)
suspend fun restoreFromFile(file: File)
suspend fun getBackupStatus(): BackupStatus
```

OssUploader：

```kotlin
suspend fun upload(file: File, remotePath: String)
suspend fun download(remotePath: String): ByteArray
```

AppInitializer：

```kotlin
suspend fun initialize(): InitResult
```

ConnectionPreheater：

```kotlin
suspend fun preheat()
```

LiquidGlassBottomBar：

```kotlin
@Composable
fun LiquidGlassBottomBar(
    tabs: List<BottomTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 68.dp,
    cornerRadius: Dp = 28.dp,
    draggable: Boolean = true,
)
```

25.3 错误处理规范

错误场景 处理方式 是否告警
API Key 未配置 抛 IllegalStateException 否
API Key 解密失败 清除存储，返回 null 否
SSE 连接失败 按 ReconnectStrategy 退避重连 记录日志
SSE 流中断 从 WAL 恢复 否
prefix 哈希不匹配 记录告警，重建快照 是
personaId 归属校验失败 拒绝写入 是
scope 非法 抛 IllegalArgumentException 否
Room 迁移失败 抛异常 是
ONNX 模型加载失败 降级为关键词检索 是
OSS 上传失败 重试 3 次 是
备份解密失败 提示文件损坏 是
连接预热失败 忽略 否
加载界面初始化失败 除 DB 外降级不阻塞 视情况
全局前缀修改 警告用户 + 询问生效范围 是
开场白变量未替换 记录告警，使用原文 否
开场白写入失败 不影响会话创建，静默跳过 否
液态玻璃首次回弹 检查 initialized 守卫 否
液态玻璃双倍回弹 检查 squash.value < 0.02f 守卫 否

25.4 自测清单

全局前缀

· DataStore 读取正确
· 启用/禁用切换正常
· 修改时警告提示显示
· "仅新会话生效"功能正常
· 快照哈希校验正确

开场白

· 会话创建时写入首条 assistant 消息
· 变量替换正确
· 多条开场白随机选用
· 不填开场白时不插入消息
· 修改开场白不影响已有会话
· 修改开场白影响新会话

FrozenPrefixBuilder

· 全局前缀非空时正确拼装
· 全局前缀为空时跳过该段
· 性格非空时输出角色性格段
· 多次调用返回字节级一致的字符串

流式生命周期

· 流式期间不写 messages 表
· 流式期间写 WAL
· 流式结束才原子性持久化
· 末尾内容不丢失
· 中断后能从 WAL 恢复

记忆库

· EmbeddingService 生成 512 维向量
· 向量检索返回语义相关结果
· 语义去重能识别近似记忆
· 三级隔离生效

液态玻璃导航栏

· 五个 Tab 正常显示
· 点击切换触发回调
· 点击当前 Tab 不触发
· 首次进入无回弹
· 无双倍回弹
· 呼吸脉动持续
· 拖拽跟手
· 松手吸附最近 Tab
· 低端设备降级正常

其他

· SSE 重连正常
· 云备份恢复正确
· 人设市场功能完整
· 账号管理功能完整
· 关于界面完整
· 无原生 Android 弹窗

25.5 Agent 不要做的事

禁止 原因
不要引入 FTS5 已有向量库
不要设置 callTimeout 误杀 SSE
不要在流式过程中写 messages 表 破坏前缀稳定性
不要把记忆写回 Frozen Prefix 缓存全碎
不要把开场白写入 Frozen Prefix 缓存全碎
不要每轮动态生成开场白 破坏缓存
不要在检索时省略 userId / personaId / sessionId 记忆串
不要硬编码 API Key 安全风险
不要把 API Key 发送到后端 安全风险
不要在主线程做数据库查询 ANR
不要把性格留空当作 "" 处理 必须用 null
不要在流式期间切换会话 破坏流式状态
不要用原生 Android 弹窗 用 Compose 自定义
不要用默认 Material 主题色 用蓝白清冷水彩
不要在备份中包含 API Key 明文 必须加密
不要让服务端持有备份密钥 端侧加密
不要在加载界面用真正的 SSE 停留时间太短
不要跳过 NOTICE 文件 Apache-2.0 要求
不要在关于界面遗漏致谢 开源协议要求
不要删除乞讨区 用户明确要求
不要在全局前缀修改时不警告 用户会惊讶
不要把开场白变量替换推迟到每轮 应会话创建时一次替换
不要在液态玻璃组件内持有业务状态 违反无状态原则
不要在 Canvas 内做重计算 性能问题
不要用 Modifier.blur 叠加 浪费 GPU
不要在液态玻璃中省略初始化守卫 首次进入有回弹
不要在液态玻璃中省略 squash 守卫 双倍回弹

25.6 关键参数速查

参数 值 位置
connectTimeout 30 秒 OkHttpClient
readTimeout 75 秒 OkHttpClient
writeTimeout 30 秒 OkHttpClient
callTimeout 不设置 OkHttpClient
重连退避基础 1 秒 ReconnectStrategy
重连退避最大 30 秒 ReconnectStrategy
抖动因子 0.5–1.0 ReconnectStrategy
最大连续失败 10 次 ReconnectStrategy
分页大小 50 PagingConfig
prefetchDistance 10 PagingConfig
WAL 保留天数 7 天 WalDao
前台服务最长 5 分钟 StreamingForegroundService
降级阈值 3 次失败 ChatRequestManager
缓存锚点 2 条 CacheAwareCompactor
记忆检索上限 5 条 MemoryRetriever
记忆半衰期 30 天 MemoryDecay
自动摘要触发 20 轮 MemoryExtractionWorker
UID 起始值 10000 后端
嵌入维度 512 BGE-small-zh
语义去重阈值 0.9 MemoryWriter
缩略图尺寸（人设） 128×128 PersonaImageManager
缩略图尺寸（头像） 256×256 EditAvatarViewModel
预签名有效期 1 小时 OssUploader
备份加密算法 AES-256-GCM BackupEncryptor
数据库加密 SQLCipher AES-256 DatabaseModule
自动备份间隔 24 小时 AutoBackupWorker
启动动画时长 1200ms SplashScreen
登录过渡动画时长 600ms LoginTransition
验证码有效期 5 分钟 后端
验证码发送间隔 1 分钟 后端
验证码每日上限 10 次 后端
全局前缀长度 100–300 Token GlobalPrefixRepository
开场白默认条数 1 条 PersonaEntity.greeting
开场白变体上限 5 条 PersonaEntity.greetingVariants
液态玻璃高度 68dp LiquidGlassBottomBar
液态玻璃圆角 28dp LiquidGlassBottomBar
液滴 spring dampingRatio 0.55 LaunchedEffect
液滴 spring stiffness 480 LaunchedEffect
回弹 spring dampingRatio 0.28 LaunchedEffect
回弹 spring stiffness 260 LaunchedEffect
呼吸周期 2400ms rememberInfiniteTransition
液滴宽占比 0.62 Canvas
液滴高占比 0.62 Canvas
拉伸系数 0.42 Canvas
压扁系数 0.26 Canvas
呼吸系数 0.10 Canvas

26. 液态玻璃底部导航栏

26.1 功能概述

Yuki 初雪主界面底部导航栏采用液态玻璃设计：一条悬浮的玻璃胶囊导航栏，选中态由一个「液滴」指示器表达。

特性 说明 默认开关
玻璃胶囊 半透明白渐变 + 上缘高光描边 + 柔和阴影 常驻
液滴滑动 选中切换时液滴横向滑动，带微过冲 常驻
拉伸/回弹 移动时液滴拉长压扁，落定时 2~3 次阻尼晃动 常驻
液态拖尾 移动瞬间拖出一条渐隐连接带 移动期间自动出现
呼吸脉动 液滴圆角缓慢起伏 常驻
横向拖拽 手指拖动液滴，松手吸附最近 Tab draggable = true
文字渐显 仅选中 Tab 显示标签 常驻

26.2 模块归属

```
:core:ui ────────────── ui/components/LiquidGlassBottomBar.kt + BottomTabItem
:feature:main ───────── MainScreen.kt（集成导航栏 + NavHost）
:core:designsystem ──── MaterialTheme（蓝白清冷水彩配色）
```

分层职责：

层 职责 不应做的事
:core:ui 无状态组件、纯视觉 不持有业务状态、不依赖 Hilt
:feature:main 状态管理、导航、业务逻辑 不实现视觉细节
:core:designsystem 主题、颜色、字体 不实现组件逻辑

26.3 设计原则

26.3.1 无状态组件

组件不持有「当前选中 Tab」状态，完全由 selectedIndex + onTabSelected 驱动。状态唯一来源是 ViewModel/导航层。

26.3.2 UI 逻辑内聚

滑动、回弹、拖尾等纯视觉动画封装在组件内部，业务层不感知。

26.3.3 与 Hilt 无关

组件是纯 UI，不需要任何注入。

26.4 API 参考

26.4.1 BottomTabItem

```kotlin
data class BottomTabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
```

26.4.2 LiquidGlassBottomBar

```kotlin
@Composable
fun LiquidGlassBottomBar(
    tabs: List<BottomTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 68.dp,
    cornerRadius: Dp = 28.dp,
    draggable: Boolean = true,
)
```

参数说明：

参数 类型 默认值 说明
tabs List<BottomTabItem> 无 Tab 列表，至少 1 个
selectedIndex Int 无 当前选中下标，越界自动钳制
onTabSelected (Int) -> Unit 无 点击或拖拽吸附时回调
modifier Modifier Modifier 外部修饰符
barHeight Dp 68.dp 胶囊高度
cornerRadius Dp 28.dp 胶囊圆角
draggable Boolean true 是否允许横向拖拽液滴

26.4.3 契约与副作用

· 组件内部不持有业务状态
· 点击当前 Tab 不触发回调
· 拖拽松手吸附到最近 Tab，若目标不同触发 onTabSelected
· 内部动画在组件销毁时随 remember 自动释放

26.5 内部实现说明

26.5.1 动画状态总览

状态 类型 触发 规格 视觉结果
liquidX Animatable (0..1) 选中变化 / 拖拽松手 spring(0.55, 480) 液滴横向滑动，微过冲
trailStartX Float 状态 动画开始前记录 — 拖尾起点
squash Animatable (1→0) 选中变化 / 拖拽松手 snapTo(1) → spring(0.28, 260) 拉伸 42% / 压扁 26%
breath InfiniteTransition 常驻 tween(2400ms, 往复) 圆角 ±10%
iconScale animateFloatAsState 选中变化 spring(0.5, 600) 图标放大至 1.10×
labelAlpha animateFloatAsState 选中变化 tween(200ms) 文字 0↔1

26.5.2 状态机说明

选中变化流程（LaunchedEffect(safeIndex)）：

1. 计算目标位置 fractionOf(index) = (index + 0.5) / count
2. 若 |liquidX - target| > 0.001：记录 trailStartX，liquidX 弹簧滑动
3. 若组件已完成首次组合且 squash < 0.02：squash 置 1 后阻尼衰减到 0

两个守卫避免缺陷：

守卫 作用 缺少后果
initialized 跳过首次组合的无意义回弹 首次进入有回弹动画
squash.value < 0.02f 避免拖拽松手与选中回调叠加造成「双倍回弹」 选中后回弹两次

拖拽流程（detectHorizontalDragGestures）：

1. onDragStart：标记 dragging = true
2. onHorizontalDrag：liquidX.snapTo(...) 跟手
3. onDragEnd：吸附最近 Tab → 走弹簧回弹；若目标不同回调 onTabSelected
4. onDragCancel：回弹到当前选中位置

26.5.3 绘制管线（LiquidIndicator）

Canvas 内按序绘制：

```
光晕 → 拖尾 → 主液滴（含形变）→ 内高光 → 底部反光
```

液滴形变由 squash 统一驱动：

```text
blobW = tabW * 0.62 * (1 + 0.42 * squash)
blobH = barH * 0.62 * (1 - 0.26 * squash)
corner = min(blobH/2, blobW*0.30) * (1 + 0.10 * breath)
```

26.5.4 手势仲裁

· 未超过横向 touch slop 判定为点击
· 超过则拖拽接管并取消点击
· 与页面横向滚动冲突时，置 draggable = false

26.6 参数调优

目标 参数 推荐区间 效果说明
滑动更「水」 liquidX: dampingRatio 0.40~0.65 越小过冲越明显
滑动更快/慢 liquidX: stiffness 300~800 越大越快
回弹晃动次数 squash: dampingRatio 0.20~0.40 越小晃动越多
拉伸幅度 squash: stretchX 系数 0.30~0.55 越大拉得越长
玻璃透明度 胶囊渐变 alpha 0.25~0.50 越大越「实」
高光强度 描边/高光带 alpha 0.5~0.8 玻璃质感关键
液滴尺寸 baseW / baseH 系数 0.55~0.70 相对 Tab 槽位
呼吸幅度 corner 的 breath 系数 0.05~0.15 超过 0.15 会抖

Yuki 初雪推荐配置：

```kotlin
liquidX: spring(dampingRatio = 0.55f, stiffness = 480f)
squash: spring(dampingRatio = 0.28f, stiffness = 260f)
stretchX: 0.42f
```

26.7 与 NavHost 集成

```kotlin
// :feature:main/MainScreen.kt
val navController = rememberNavController()
val backStack by navController.currentBackStackEntryAsState()
val currentRoute = backStack?.destination?.route

val tabs = listOf(
    BottomTabItem("消息", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubble),
    BottomTabItem("人设", Icons.Filled.Person, Icons.Outlined.Person),
    BottomTabItem("市场", Icons.Filled.Storefront, Icons.Outlined.Storefront),
    BottomTabItem("我的", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
    BottomTabItem("关于", Icons.Filled.Info, Icons.Outlined.Info),
)
val routes = listOf("chat", "persona", "market", "profile", "about")
val tabIndexByRoute = routes.withIndex().associate { (i, r) -> r to i }

Scaffold(
    bottomBar = {
        LiquidGlassBottomBar(
            tabs = tabs,
            selectedIndex = tabIndexByRoute[currentRoute] ?: 0,
            onTabSelected = { index ->
                navController.navigate(routes[index]) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    },
) { padding ->
    NavHost(
        navController = navController,
        startDestination = "chat",
        modifier = Modifier.padding(padding),
    ) {
        composable("chat") { MessageListScreen(navController) }
        composable("persona") { PersonaEditScreen(navController) }
        composable("market") { PersonaMarketScreen(navController) }
        composable("profile") { ProfileScreen(navController) }
        composable("about") { AboutScreen(navController) }
    }
}
```

26.8 系统栏 insets

组件默认带 .windowInsetsPadding(WindowInsets.navigationBars)。

情况 处理
Scaffold 已消费底部 insets 删除该行，避免双重留白
全屏沉浸式页面 保留
普通页面 视情况保留

26.9 低端设备降级（API 26-28）

Modifier.blur 需要 API 31+。低端设备降级：

```kotlin
val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
val glassAlpha = if (blurSupported) 0.38f else 0.55f
```

26.10 真实背景模糊（可选）

当前实现为「渐变玻璃」：用透明度 + 高光模拟玻璃，零额外性能开销。

若需真实背景模糊：

1. 内容容器挂 GraphicsLayer
2. 玻璃层每次绘制时 layer.toImageBitmap() 截取背景
3. 用 RenderEffect.createBlurEffect 模糊后 clip 到胶囊形状绘制

⚠️ 性能警告：每帧全屏截取开销很大，生产环境应只截取胶囊区域、滚动中降采样、滚动停止后复用位图。必要时先用渐变方案。

26.11 性能与注意事项

注意事项 说明
不要用 Modifier.blur 叠加 模糊自身子树，浪费 GPU
不要在 Canvas 内做重计算 计算放 remember 或 draw 前
避免每帧重建 Brush 用 remember 缓存
图标使用 ImageVector 避免每帧解码 Drawable
深色主题阴影调低 深色下阴影视觉更重

26.12 常见问题排查

现象 原因 处理
首次进入有回弹动画 缺少初始化守卫 确认 initialized 守卫存在
选中后回弹两次 拖拽回弹与 LaunchedEffect 叠加 确认 squash.value < 0.02f 守卫
底部留白过大 Scaffold 已消费 insets 删除 .windowInsetsPadding(...)
拖拽和页面滚动冲突 手势仲裁 draggable = false
图标找不到 不在 icons-core 引入 material-icons-extended
液滴卡顿 动画参数过激进 降低 stiffness，提高 dampingRatio
呼吸动画抖动 呼吸幅度过大 降低 breath 系数到 0.10 以内

26.13 扩展方向

扩展 说明 成本
水波纹 点击位置扩散圆环 低
整条变形 胶囊轮廓跟随液滴凹凸 高
粒子拖尾 3~5 个依次缩小的圆点 中
液滴形状 泪滴 Path 中
触感反馈 LocalHapticFeedback 低
角标 Tab 右上角未读数 Badge 低

26.14 与 Yuki 初雪的对接

五个 Tab：

Tab 图标（选中） 图标（未选中） 路由
消息 Icons.Filled.ChatBubble Icons.Outlined.ChatBubble chat
人设 Icons.Filled.Person Icons.Outlined.Person persona
市场 Icons.Filled.Storefront Icons.Outlined.Storefront market
我的 Icons.Filled.AccountCircle Icons.Outlined.AccountCircle profile
关于 Icons.Filled.Info Icons.Outlined.Info about

与其他组件的协同：

组件 协同方式
ChatScreen 隐藏底部导航栏（全屏聊天）
PersonaDetailScreen 从「人设」Tab 进入，导航栏保持可见
SettingsScreen 从「我的」Tab 右上角进入，导航栏隐藏
MarketPersonaDetailScreen 从「市场」Tab 进入，导航栏隐藏

27. 总结

27.1 核心命题

在 Android 客户端实现 DeepSeek 缓存优化，五层 Prompt 结构保持前缀字节稳定。全局前缀可选启用，修改时警告用户。开场白作为会话创建时的首条 assistant 消息写入 History。底部导航栏采用液态玻璃设计，无状态组件 + 内聚动画 + 与 Hilt 无关。

27.2 技术栈

```
Kotlin (JVM 17)
  + Jetpack Compose + Material 3
  + Hilt
  + Room (WAL) + SQLCipher + Paging 3
  + sqlite-vec + ONNX Runtime + BGE-small-zh
  + OkHttp SSE
  + Android Keystore
  + OSS 预签名直传
  + WorkManager
  + Navigation Compose
  + DataStore（全局前缀）
  + Coil + uCrop
```

27.3 六条铁律

```
1. 冻结前缀，绝不重写
2. 动态内容，只追加末尾
3. 流式结束，才持久化
4. 流式期间，不写 messages 表
5. 记忆检索，只从附录注入
6. 三级隔离，全程强制过滤
```

27.4 前缀分层原则

```
全局前缀（可选，所有角色共享）
  ↓ 会话创建时拼装
人设前缀（每个人设独立）
  ↓ 会话创建时拼装
开场白（首条 assistant 消息）
  ↓ 会话创建时写入
History Messages（持久化后不变）
  ↓
Appendix Delta（每轮追加）
  ↓
User Input（本轮输入）
```

27.5 用户视角

用户只需要：

1. 填写昵称和性别
2. 可选填性格
3. 在自由编辑框里写角色设定
4. 可选写开场白
5. 填 API Key
6. 开始聊天

27.6 给 Agent 的提醒

· 严格按 25.1 的顺序推进
· 每一步完成后按 25.4 自测
· 严格遵守 25.5 的禁止清单
· 全局前缀修改必须警告
· 开场白必须作为首条 assistant 消息
· 液态玻璃导航栏参考第 26 章
· UI 相关实现参考第二部分
· Apache-2.0 协议要求保留 LICENSE 和 NOTICE 文件

---

第二部分：UI 设计文档

28. 设计原则

28.1 整体风格

蓝白清冷水彩感。不使用原生 Android UI 弹窗和界面编辑框，所有组件均为 Compose 自定义。

原则 说明
内容优先 消息、人设、记忆是主角，UI 是容器
水彩质感 柔和的蓝白渐变、轻微纹理、半透明层次
克制装饰 不用阴影堆叠、不用多余动效
一致动效 所有转场使用统一的缓动曲线和时长
液态玻璃 底部导航栏使用液态玻璃效果
单 Activity 全面 Compose，导航由 Navigation Compose 控制
无原生弹窗 所有对话框、选择器、输入框均为 Compose 自定义

28.2 导航结构

五个固定 Tab（底部液态玻璃导航栏）：

```
[消息列表] [人设编辑] [人设市场] [我的] [关于]
```

29. 色彩与字体系统

29.1 色彩系统（蓝白清冷水彩）

```kotlin
// 主题色
val PrimaryBlue = Color(0xFF6B9BD2)
val PrimaryBlueLight = Color(0xFF8FB8E0)
val PrimaryBlueDark = Color(0xFF4A7BAF)

// 水彩背景
val WatercolorBgLight = Color(0xFFF5F8FC)
val WatercolorBgDark = Color(0xFF1A1F2E)

// 表面色
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF232838)

// 消息气泡
val UserBubbleLight = Color(0xFF6B9BD2)
val AssistantBubbleLight = Color(0xFFFFFFFF)
val UserBubbleDark = Color(0xFF8FB8E0)
val AssistantBubbleDark = Color(0xFF2A3040)

// 文字色
val TextPrimaryLight = Color(0xFF1A2A3A)
val TextSecondaryLight = Color(0xFF7A8A9A)
val TextPrimaryDark = Color(0xFFE8EDF5)
val TextSecondaryDark = Color(0xFF8A9AAA)

// 状态色
val ErrorColor = Color(0xFFE05A5A)
val SuccessColor = Color(0xFF5AB87A)
val WarningColor = Color(0xFFE0A84A)
```

29.2 水彩纹理实现

```kotlin
@Composable
fun WatercolorBackground(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF5F8FC),
                            Color(0xFFE8F0FA),
                            Color(0xFFF0F5FC)
                        )
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            // 绘制轻微的水彩纹理
        }

        content()
    }
}
```

29.3 字体系统

用途 字号 字重 行高
页面标题 20sp SemiBold 28sp
顶部导航标题 17sp Medium 24sp
消息正文 16sp Regular 24sp
辅助文字 14sp Regular 20sp
标签文字 12sp Regular 16sp
按钮文字 16sp Medium 24sp

29.4 圆角系统

元素 圆角
消息气泡 18dp
卡片 16dp
输入框 12dp
按钮 12dp
头像 圆形（50%）
底部导航栏 32dp（顶部圆角）

29.5 自定义组件（无原生）

```kotlin
@Composable
fun YukiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isError: Boolean = false,
    isPassword: Boolean = false,
    minLines: Int = 1,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                )
                .border(
                    width = 1.dp,
                    color = if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                innerTextField()
            }
        )
    }
}

@Composable
fun YukiDialog(
    title: String,
    content: @Composable () -> Unit,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(dismissText)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

@Composable
fun YukiBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        content = content
    )
}
```

30. 动画系统

30.1 缓动曲线

```kotlin
val StandardEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
val ExitEasing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
```

30.2 时长系统

动效类型 时长
微交互（按钮反馈） 150ms
页面转场 350ms
登录过渡 600ms
启动动画 1200ms
列表项插入 250ms
消息气泡出现 200ms

30.3 转场类型

场景 转场
Tab 切换 淡入淡出 + 轻微位移（8dp）
进入子页面 右侧滑入 + 淡入
返回 右侧滑出 + 淡出
登录成功 共享元素 + 淡入淡出
消息发送 底部滑入 + 淡入

31. 启动动画

31.1 动画序列

```
时间轴（总时长 1200ms）：

0ms ─────── 400ms ─────── 800ms ─────── 1200ms
  │           │             │              │
  ▼           ▼             ▼              ▼
[品牌色背景]  [Logo 淡入]   [Logo 缩放]    [文字淡入]
[空]         [Logo 出现]   [Logo 居中]    [品牌名出现]
```

31.2 实现

```kotlin
@Composable
fun SplashScreen(onReady: (InitResult) -> Unit) {
    val viewModel: SplashViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scale = remember { Animatable(0.8f) }
    val logoAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(16f) }

    var animDone by remember { mutableStateOf(false) }
    var initDone by remember { mutableStateOf(false) }
    var initResult by remember { mutableStateOf<InitResult?>(null) }

    LaunchedEffect(Unit) {
        launch {
            launch {
                logoAlpha.animateTo(1f, tween(400, easing = EmphasizedEasing))
            }
            launch {
                scale.animateTo(1f, tween(400, easing = EmphasizedEasing))
            }
            delay(400)
            launch {
                textAlpha.animateTo(1f, tween(400, easing = StandardEasing))
            }
            launch {
                textOffset.animateTo(0f, tween(400, easing = StandardEasing))
            }
            delay(400)
            animDone = true
        }

        launch {
            initResult = viewModel.initialize()
            initDone = true
        }
    }

    LaunchedEffect(animDone, initDone) {
        if (animDone && initDone) {
            initResult?.let { onReady(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo_yuki),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = logoAlpha.value
                    }
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Yuki 初雪",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffset.value
                }
            )

            if (animDone && !initDone) {
                Spacer(Modifier.height(32.dp))
                LinearProgressIndicator(
                    modifier = Modifier.width(120.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

32. 认证流程

32.1 流程总览

```
启动
  ↓ 检查登录状态
未登录 → AuthScreen（登录/注册入口）
  ├── 点击登录 → LoginScreen → 成功 → 过渡动画 → MainScreen
  ├── 点击注册 → RegisterFlow（4 步）→ 成功 → 过渡动画 → MainScreen
  └── 点击忘记密码 → ForgotPasswordScreen

已登录 → 过渡动画 → MainScreen
```

32.2 关键设计：账号昵称与玩家昵称隔离

概念 存储位置 作用域 说明
账号昵称 UserEntity.nickname 全局 App 内展示
全局头像 UserEntity.avatarUrl 全局 我的页面显示
UID UserEntity.uid 全局 登录凭证
玩家昵称 PersonaEntity.userNickname 每个人设卡 角色怎么称呼用户

32.3 认证入口页

```kotlin
@Composable
fun AuthScreen(
    onLoginClick: () -> Unit,
    onRegisterClick: () -> Unit
) {
    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.logo_yuki),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Yuki 初雪",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "遇见你的初雪",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(64.dp))

            Button(
                onClick = onLoginClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("登录")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRegisterClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("注册")
            }
        }
    }
}
```

32.4 登录页

```kotlin
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var uid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))

            Image(
                painter = painterResource(R.drawable.logo_yuki),
                contentDescription = null,
                modifier = Modifier.size(80.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text("Yuki 初雪", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(48.dp))

            YukiTextField(
                value = uid,
                onValueChange = { uid = it },
                label = "账号 ID",
                placeholder = "如 10001",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            YukiTextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onNavigateToForgotPassword) {
                    Text("忘记密码？")
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.login(uid, password, onLoginSuccess) },
                enabled = uid.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("登录")
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = onNavigateToRegister) {
                Text("还没有账号？注册")
            }
        }
    }
}
```

32.5 注册步骤 1：基本信息

```kotlin
@Composable
fun RegisterStep1Screen(
    onNext: (nickname: String, password: String, avatarUri: Uri?) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> avatarUri = uri }

    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { launcher.launch(PickVisualMediaRequest(ImageOnly)) },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "选择头像",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            YukiTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = "账号昵称",
                placeholder = "用于 App 内展示",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            YukiTextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            YukiTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = "确认密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth(),
                isError = confirmPassword.isNotEmpty() && password != confirmPassword
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { onNext(nickname, password, avatarUri) },
                enabled = nickname.isNotBlank()
                    && password.isNotBlank()
                    && password == confirmPassword,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("下一步")
            }
        }
    }
}
```

32.6 注册步骤 2：UID 展示

```kotlin
@Composable
fun RegisterStep2Screen(
    uid: String,
    onNext: () -> Unit
) {
    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "你的账号 ID",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = uid,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "请记住这个 ID，它是你登录的凭证",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("继续")
            }
        }
    }
}
```

32.7 注册步骤 3：绑定邮箱

```kotlin
@Composable
fun RegisterStep3Screen(
    onComplete: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel()
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Text("绑定邮箱", style = MaterialTheme.typography.headlineSmall)

            Spacer(Modifier.height(8.dp))

            Text(
                text = "邮箱用于找回密码和接收通知",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            YukiTextField(
                value = email,
                onValueChange = { email = it },
                label = "邮箱",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YukiTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "验证码",
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        viewModel.sendCode(email)
                        countdown = 60
                    },
                    enabled = email.isNotBlank() && countdown == 0,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text(if (countdown > 0) "${countdown}s" else "发送")
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.verifyAndComplete(email, code, onComplete) },
                enabled = email.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("完成注册")
            }

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = { viewModel.skipEmail(onComplete) }) {
                Text("暂时跳过")
            }
        }
    }
}
```

32.8 找回密码界面

```kotlin
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var countdown by remember { mutableStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    WatercolorBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            Text("找回密码", style = MaterialTheme.typography.headlineSmall)

            Spacer(Modifier.height(8.dp))

            Text(
                text = "输入你的账号 ID，我们会向绑定的邮箱发送验证码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            YukiTextField(
                value = uiState.uid,
                onValueChange = viewModel::onUidChange,
                label = "账号 ID",
                placeholder = "如 10001",
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YukiTextField(
                    value = uiState.code,
                    onValueChange = viewModel::onCodeChange,
                    label = "验证码",
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        viewModel.sendCode()
                        countdown = 60
                    },
                    enabled = uiState.uid.isNotBlank() && countdown == 0,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text(if (countdown > 0) "${countdown}s" else "发送")
                }
            }

            YukiTextField(
                value = uiState.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = "新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            YukiTextField(
                value = uiState.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "确认新密码",
                isPassword = true,
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.confirmPassword.isNotEmpty()
                    && uiState.newPassword != uiState.confirmPassword
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.resetPassword(onSuccess) },
                enabled = uiState.canReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置密码")
            }
        }
    }
}
```

33. 登录过渡动画

33.1 动画设计

· 从登录页到消息列表，用户感知到"进入"而非"跳转"
· 使用共享元素 + 淡入淡出 + 缩放
· 总时长 600ms

33.2 实现方案

```kotlin
@Composable
fun LoginTransition(
    isLoggedIn: Boolean,
    onTransitionEnd: () -> Unit
) {
    val transition = updateTransition(targetState = isLoggedIn)

    val loginAlpha by transition.animateFloat(
        transitionSpec = { tween(200, easing = ExitEasing) }
    ) { if (it) 0f else 1f }

    val loginScale by transition.animateFloat(
        transitionSpec = { tween(200, easing = ExitEasing) }
    ) { if (it) 0.9f else 1f }

    val mainAlpha by transition.animateFloat(
        transitionSpec = { tween(400, delayMillis = 200, easing = StandardEasing) }
    ) { if (it) 1f else 0f }

    val mainOffset by transition.animateFloat(
        transitionSpec = { tween(400, delayMillis = 200, easing = StandardEasing) }
    ) { if (it) 0f else 24f }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            delay(600)
            onTransitionEnd()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loginAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = loginAlpha
                        scaleX = loginScale
                        scaleY = loginScale
                    }
            ) {
                LoginScreen(...)
            }
        }

        if (mainAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = mainAlpha
                        translationY = mainOffset
                    }
            ) {
                MainScreen(...)
            }
        }
    }
}
```

34. 主界面框架

34.1 底部液态玻璃导航栏

```kotlin
@Composable
fun LiquidGlassBottomBar(
    tabs: List<BottomTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 68.dp,
    cornerRadius: Dp = 28.dp,
    draggable: Boolean = true,
)
```

详细实现参考第一部分第 26 章。

34.2 主界面框架

```kotlin
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            LiquidGlassBottomBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(250)) togetherWith fadeOut(tween(250))
                },
                label = "tab_content"
            ) { tab ->
                when (tab) {
                    0 -> MessageListScreen()
                    1 -> PersonaEditScreen()
                    2 -> PersonaMarketScreen()
                    3 -> ProfileScreen()
                    4 -> AboutScreen()
                }
            }
        }
    }
}
```

35. 消息列表界面

```kotlin
@Composable
fun MessageListScreen(
    onSessionClick: (String) -> Unit,
    viewModel: MessageListViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("消息") },
            actions = {
                IconButton(onClick = { viewModel.createNewSession() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
            }
        )

        if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Chat,
                title = "还没有对话",
                description = "点击右上角创建你的第一个人设"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        onClick = { onSessionClick(session.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SessionItem(
    session: SessionUiModel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = session.personaLocalThumbnail ?: session.personaAvatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = session.personaName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTime(session.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = session.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
```

36. 聊天界面

```kotlin
@Composable
fun ChatScreen(
    sessionId: String,
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagingMessages = viewModel.pagingMessages.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.personaName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(DiagnosticsRoute(sessionId))
                    }) {
                        Icon(Icons.Default.BugReport, contentDescription = "诊断")
                    }
                    IconButton(onClick = {
                        navController.navigate(PersonaDetailRoute(uiState.personaId))
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                enabled = uiState.streamState !is StreamState.Streaming
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            uiState.streamingContent?.let { streaming ->
                item(key = "streaming") {
                    StreamingBubble(content = streaming)
                }
            }

            items(
                count = pagingMessages.itemCount,
                key = pagingMessages.itemKey { it.id },
                contentType = pagingMessages.itemContentType { it.role }
            ) { index ->
                pagingMessages[index]?.let { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: DisplayMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
            } else {
                RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
            },
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
fun StreamingBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                BlinkingCursor()
            }
        }
    }
}

@Composable
fun BlinkingCursor() {
    val alpha by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        )
    )
    Text(
        text = "▊",
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        color = MaterialTheme.colorScheme.primary
    )
}
```

37. 人设编辑界面

37.1 人设列表

```kotlin
@Composable
fun PersonaEditScreen(
    onPersonaClick: (String) -> Unit,
    viewModel: PersonaListViewModel = hiltViewModel()
) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("人设") },
            actions = {
                IconButton(onClick = { viewModel.createPersona() }) {
                    Icon(Icons.Default.Add, contentDescription = "创建人设")
                }
            }
        )

        if (personas.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Person,
                title = "还没有人设",
                description = "点击右上角创建你的第一个角色"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(personas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        onClick = { onPersonaClick(persona.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PersonaCard(
    persona: PersonaUiModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = persona.localThumbnail ?: persona.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = persona.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

37.2 人设创建/编辑页

```kotlin
@Composable
fun PersonaDetailScreen(
    personaId: String,
    onBack: () -> Unit,
    onNavigateToMemory: () -> Unit,
    viewModel: PersonaDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (personaId == "new") "创建人设" else "编辑人设") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = uiState.canSave
                    ) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 人设头像（可选）
            Text("人设头像（可选）", style = MaterialTheme.typography.titleMedium)

            ImagePickerWithCrop(
                imageUri = uiState.avatarUri,
                onImageSelected = { viewModel.onAvatarSelected(it) },
                aspectRatio = 1f,
                onCropComplete = { viewModel.onAvatarCropped(it) }
            )

            // 必填项
            Text("关于你", style = MaterialTheme.typography.titleMedium)

            YukiTextField(
                value = uiState.userNickname,
                onValueChange = viewModel::onNicknameChange,
                label = "你的昵称 *",
                placeholder = "角色该怎么称呼你",
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.userNickname.isBlank()
            )

            YukiTextField(
                value = uiState.userGender,
                onValueChange = viewModel::onGenderChange,
                label = "你的性别 *",
                modifier = Modifier.fillMaxWidth(),
                isError = uiState.userGender.isBlank()
            )

            // 可选项
            Text("角色性格（可选）", style = MaterialTheme.typography.titleMedium)

            YukiTextField(
                value = uiState.personality,
                onValueChange = viewModel::onPersonalityChange,
                label = "性格（留空 = 不设固定性格）",
                placeholder = "如：温柔、体贴、有点闷骚",
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // 自由编辑
            Text("角色设定 *", style = MaterialTheme.typography.titleMedium)

            YukiTextField(
                value = uiState.customPrompt,
                onValueChange = viewModel::onCustomPromptChange,
                label = "想写什么写什么",
                placeholder = "角色名称：小夏\n年龄：23\n职业：咖啡师\n爱好：看书、听民谣\n世界观：海边小城...",
                modifier = Modifier.fillMaxWidth(),
                minLines = 10,
                isError = uiState.customPrompt.isBlank()
            )

            // 开场白
            GreetingSection(
                greeting = uiState.greeting,
                variants = uiState.greetingVariants,
                onGreetingChange = viewModel::onGreetingChange,
                onVariantsChange = viewModel::onGreetingVariantsChange
            )

            // Token 估算
            Text(
                text = "当前人设约 ${estimateTokens(uiState.estimatedPrefix)} Token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 编辑已有会话的警告
            if (personaId != "new" && uiState.hasChanged) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "修改人设会破坏当前会话的缓存前缀，" +
                                   "保存后建议新建会话。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 记忆管理入口
            if (personaId != "new") {
                OutlinedButton(
                    onClick = onNavigateToMemory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("管理记忆")
                }
            }
        }
    }
}
```

38. 人设市场界面

（与第一部分第 11 章一致，此处略去重复代码）

关键点：

· 双列网格，缩略图 + 标题 + 作者 + 点赞数
· 悬浮上传按钮
· 详情页：公开人设展示完整内容 + 一键复制；不公开人设提示下载
· 点赞 / 评论 / 回复 / 浏览量

39. 我的界面

```kotlin
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            TopAppBar(
                title = { Text("我的") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = uiState.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = uiState.nickname,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "ID: ${uiState.uid}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Text(
                text = "我发布的人设",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(uiState.publishedPersonas) { persona ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = persona.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(persona.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "♡ ${formatCount(persona.likeCount)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            "👁 ${formatCount(persona.viewCount)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Token 用量统计",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("总用量", "${uiState.totalTokens} tokens")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("缓存命中率", "%.1f%%".format(uiState.hitRate * 100))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("节省金额", "¥%.2f".format(uiState.savedAmount))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("剩余余额", "¥%.2f".format(uiState.balance))
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
```

40. 关于界面

（与第一部分第 22 章一致）

关键点：

· 头部：作者头像 + 名称 + 软件介绍 + 版本
· 检测更新 + 更新日志
· 开源协议：Apache License 2.0
· 致谢卡片：天枢 + Operit（点击跳转 GitHub）
· 支持区域：收款码
· 隐私政策 + 用户协议

41. 设置界面

```
┌─────────────────────────────────┐
│  [←]  设置                      │
├─────────────────────────────────┤
│  账号                           │
│  │  修改昵称 / 修改头像          │
│  │  修改密码 / 绑定邮箱          │
│  │  找回密码                     │
├─────────────────────────────────┤
│  API                            │
│  │  DeepSeek API Key            │
│  │  模型选择                     │
├─────────────────────────────────┤
│  聊天                           │
│  │  全局前缀                     │
│  │  缓存诊断 / 记忆管理          │
│  │  自动提取记忆                 │
│  │  发送模式                     │
│  │  打字速度                     │
│  │  表情包管理                   │
├─────────────────────────────────┤
│  数据                           │
│  │  导出备份 / 导入备份          │
│  │  自动备份                     │
├─────────────────────────────────┤
│  外观                           │
│  │  主题模式 / 字体大小          │
│  │  消息气泡自定义               │
├─────────────────────────────────┤
│  其他                           │
│  │  清除缓存 / 关于              │
│  │  退出登录                     │
└─────────────────────────────────┘
```

42. 记忆管理界面

```kotlin
@Composable
fun MemoryManageScreen(
    personaId: String,
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("全部") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addMemory() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加记忆")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("全部", "角色记忆", "剧情记忆").forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onEdit = { viewModel.editMemory(memory) },
                        onDelete = { viewModel.deleteMemory(memory.id) }
                    )
                }
            }
        }
    }
}
```

43. 缓存诊断界面

（与第一部分第 13 章一致）

关键展示：

· 缓存命中率
· Token 消耗（命中 / 未命中）
· 节省金额
· 记忆统计
· 逐轮历史

44. 账号管理界面

（与第一部分第 21 章一致）

关键界面：

· EditNicknameScreen：修改全局昵称
· EditAvatarScreen：修改全局头像
· EditPasswordScreen：修改密码
· BindEmailScreen：绑定邮箱
· ForgotPasswordScreen：找回密码

45. 液态玻璃底部导航栏设计规范

45.1 设计目标

目标 说明
悬浮感 胶囊悬浮在内容之上，与内容形成层次
液体感 选中切换时液滴的滑动、拉伸、回弹模拟液体流动
玻璃质感 半透明 + 高光 + 阴影模拟磨砂玻璃
克制动效 呼吸脉动等细节让画面不死板，但不过度
清晰层次 选中/未选中状态清晰可辨

45.2 尺寸规范

元素 尺寸 建议区间
胶囊高度 68dp 64-72dp
胶囊圆角 28dp 24-32dp
胶囊左右边距 16dp 12-20dp
胶囊底部边距 12dp 8-16dp
液滴宽度 tabW × 0.62 0.55-0.70
液滴高度 barH × 0.62 0.55-0.70
图标尺寸 24dp 22-28dp
图标（选中）缩放 1.10× 1.05-1.15×
文字大小 12sp 11-13sp
文字与图标间距 2dp 2-4dp

45.3 颜色规范

45.3.1 玻璃胶囊

层 颜色 透明度 说明
玻璃渐变（上） White 0.38 上缘较亮
玻璃渐变（中） White 0.08 中部透明
玻璃渐变（下） surfaceVariant 0.18 下缘主题色衬底
胶囊描边（上） White 0.65 上缘高光
胶囊描边（下） White 0.12 下缘暗部
顶部高光带 White 0.30 → 0 高 14dp

45.3.2 液滴

层 颜色 透明度 说明
光晕 primary 0.30 径向柔光，半径 = 1.15 × 液滴宽
拖尾 White 0.32 × squash 仅移动时可见
主液滴渐变（上） White 0.92 上缘亮
主液滴渐变（下） White 0.42 下缘暗
内高光 White 0.95 → 0 上缘亮带
底部反光 primary 0.35 主题色反光

45.3.3 图标与文字

状态 颜色 透明度
图标（选中） onSurface 1.0
图标（未选中） onSurface 0.50
文字（选中） onSurface 1.0
文字（未选中） — 0（隐藏）

45.4 玻璃质感的三要素

要素 视觉作用 实现层
半透明 透出背景，营造通透感 玻璃胶囊 + 液滴渐变
高光 模拟光线反射，突出玻璃边缘 描边 + 顶部高光带 + 内高光
阴影 悬浮感，与背景分离 胶囊阴影 + 液滴阴影

缺少任何一项都会让玻璃质感下降。

45.5 视觉层次

```
最上层：图标 + 文字
    ↓
主液滴（含内高光、底部反光）
    ↓
拖尾 + 光晕
    ↓
顶部高光带
    ↓
玻璃胶囊（渐变）
    ↓
胶囊描边
    ↓
最底层：背景内容（透出）
```

45.6 交互规范

交互 说明 反馈
点击 Tab 切换选中状态 液滴滑动 + 回弹 + 图标放大 + 文字渐显
拖拽液滴 手指拖动液滴 跟手（不经过弹簧）
松手吸附 拖拽松手 弹簧吸附到最近 Tab
点击当前 Tab 无变化 无反馈

45.7 动效规范

动效 时长 缓动 视觉结果
液滴滑动 spring dampingRatio 0.55, stiffness 480 微过冲
拉伸/回弹 spring dampingRatio 0.28, stiffness 260 2~3 次晃动
呼吸脉动 2400ms 往复 圆角 ±10%
图标缩放 spring dampingRatio 0.5, stiffness 600 1.10×
文字渐显 200ms 标准 0↔1

45.8 状态与场景

场景 处理
默认显示 五个 Tab 常驻
全屏聊天 隐藏导航栏
子页面（设置等） 隐藏导航栏
深色模式 玻璃更暗，阴影调低
低端设备 提高玻璃 alpha 补偿无模糊

45.9 响应式与适配

屏幕 处理
小屏（< 360dp） 缩小胶囊高度到 60dp，图标 22dp
常规屏（360-600dp） 使用默认值
大屏（> 600dp） 保持默认值，居中显示
横屏 视情况隐藏或缩窄

45.10 无障碍设计

要求 实现
最小点击区域 48dp × 48dp
对比度 图标与背景对比度 ≥ 4.5:1
屏幕阅读器 每个 Tab 提供 contentDescription
触感反馈 切换时可选 performHapticFeedback

45.11 与 Yuki 初雪的对接

五个 Tab：

Tab 图标（选中） 图标（未选中） 路由
消息 Icons.Filled.ChatBubble Icons.Outlined.ChatBubble chat
人设 Icons.Filled.Person Icons.Outlined.Person persona
市场 Icons.Filled.Storefront Icons.Outlined.Storefront market
我的 Icons.Filled.AccountCircle Icons.Outlined.AccountCircle profile
关于 Icons.Filled.Info Icons.Outlined.Info about

与其他组件的协同：

组件 协同方式
ChatScreen 隐藏底部导航栏
PersonaDetailScreen 导航栏保持可见
SettingsScreen 导航栏隐藏
MarketPersonaDetailScreen 导航栏隐藏

45.12 设计交付物

交付物 说明
Figma 设计稿 含所有状态、动效标注
动效视频 展示液滴滑动、回弹、呼吸
参数表 所有可调参数的默认值和区间
切图 图标 PNG/SVG

46. 路由设计

46.1 全部路由清单

```kotlin
// 根路由
@Serializable data object SplashRoute
@Serializable data object AuthRoute
@Serializable data object LoginRoute
@Serializable data object RegisterRoute
@Serializable data object ForgotPasswordRoute
@Serializable data object MainRoute

// 主界面内的 Tab（不通过导航，通过 state 切换）
// 0: 消息列表  1: 人设编辑  2: 人设市场  3: 我的  4: 关于

// 子页面
@Serializable data class ChatRoute(val sessionId: String)
@Serializable data class PersonaDetailRoute(val personaId: String)
@Serializable data class MarketPersonaDetailRoute(val personaId: String)
@Serializable data object UploadPersonaRoute
@Serializable data class MemoryManageRoute(val personaId: String)
@Serializable data class DiagnosticsRoute(val sessionId: String)
@Serializable data object SettingsRoute
@Serializable data object BackupSettingsRoute

// 设置子页面
@Serializable data object EditNicknameRoute
@Serializable data object EditAvatarRoute
@Serializable data object EditPasswordRoute
@Serializable data object BindEmailRoute
@Serializable data object ApiKeyRoute
@Serializable data object ModelSelectRoute
@Serializable data object GlobalPrefixRoute
@Serializable data object AutoExtractRoute
@Serializable data object SendModeRoute
@Serializable data object TypingSpeedRoute
@Serializable data object EmojiManageRoute
@Serializable data object ThemeModeRoute
@Serializable data object FontSizeRoute
@Serializable data object BubbleCustomRoute
@Serializable data object AboutRoute
@Serializable data object PrivacyPolicyRoute
@Serializable data class LicenseDetailRoute(val assetPath: String)
```

46.2 路由跳转表

从 到 触发
Splash Main / Auth 启动动画结束 + 初始化完成
Auth Login 点击登录
Auth Register 点击注册
Login Main 登录成功 + 初始化完成
Login ForgotPassword 点击忘记密码
Register Main 注册成功 + 初始化完成
ForgotPassword Login 重置成功
Main（消息 Tab） Chat 点击会话
Main（人设 Tab） PersonaDetail 点击人设卡
Main（我的 Tab） Settings 点击右上角设置
Settings 各子页面 点击设置项
Settings Auth 退出登录
Chat Diagnostics 点击诊断按钮
PersonaDetail MemoryManage 点击管理记忆
Main（市场 Tab） MarketPersonaDetail 点击人设卡
Main（市场 Tab） UploadPersona 点击悬浮按钮
About LicenseDetail 点击开源协议
About 外部浏览器 点击致谢卡片

47. UI Agent 实现指南

47.1 实现顺序

```
第 1 步：设计系统
  ├── Color.kt（蓝白清冷水彩色彩系统）
  ├── Type.kt（字体系统）
  ├── Shape.kt（圆角系统）
  ├── Animation.kt（缓动曲线、时长）
  ├── Theme.kt（主题装配）
  ├── YukiTextField.kt（自定义输入框）
  ├── YukiDialog.kt（自定义对话框）
  └── YukiBottomSheet.kt（自定义底部选择器）

第 2 步：启动与认证
  ├── SplashScreen（动画 + 并行初始化）
  ├── AuthScreen（登录/注册入口）
  ├── LoginScreen
  ├── RegisterFlow（4 步）
  ├── ForgotPasswordScreen
  └── AppNavHost（根导航）

第 3 步：主界面框架
  ├── MainScreen（5 Tab）
  ├── LiquidGlassBottomBar（参考第一部分第 26 章）
  └── Tab 内容占位

第 4 步：各 Tab 内容
  ├── MessageListScreen
  ├── PersonaEditScreen
  ├── PersonaMarketScreen
  ├── ProfileScreen
  └── AboutScreen

第 5 步：子页面
  ├── ChatScreen
  ├── PersonaDetailScreen（含 GreetingSection）
  ├── MarketPersonaDetailScreen
  ├── UploadPersonaScreen
  ├── MemoryManageScreen
  ├── DiagnosticsScreen
  ├── SettingsScreen（含所有子页面）
  ├── BackupSettingsScreen
  ├── GlobalPrefixEditScreen
  ├── EditNicknameScreen
  ├── EditAvatarScreen
  ├── EditPasswordScreen
  ├── BindEmailScreen
  └── LicenseDetailScreen

第 6 步：登录过渡动画
  └── 与 AppNavHost 集成
```

47.2 动画参数速查

动画 时长 缓动 说明
启动 Logo 淡入 400ms EmphasizedEasing 缩放 0.8 → 1.0
启动文字淡入 400ms StandardEasing 位移 16dp → 0
Tab 切换 250ms 标准 淡入淡出
进入子页面 350ms StandardEasing 右侧滑入
登录过渡 600ms EmphasizedEasing 淡入淡出 + 位移
消息气泡出现 200ms StandardEasing 底部滑入
按钮反馈 150ms 标准 缩放 0.98
液态玻璃液滴滑动 spring damping 0.55, stiffness 480 微过冲
液态玻璃回弹 spring damping 0.28, stiffness 260 2~3 次晃动
液态玻璃呼吸 2400ms 往复 圆角 ±10%

47.3 UI Agent 不要做的事

禁止 原因
不要用 emoji 做图标 用 Material Icons
不要用渐变背景 用水彩纹理 + 柔和渐变
不要用阴影堆叠 用边框区分层次
不要在低版本强行用 blur API 31 以下降级
不要用多个 Activity 单 Activity
不要在 Tab 间用导航 用 state 切换
不要把账号昵称和玩家昵称混用 一个是全局，一个是每卡
不要在登录过渡期间加载数据 先渲染 UI，后加载
不要用默认 Material 主题色 用蓝白清冷水彩系统
不要用原生 Android 弹窗 用 YukiDialog
不要用原生 Android 输入框 用 YukiTextField
不要用原生 Android 底部选择器 用 YukiBottomSheet

47.4 液态玻璃降级矩阵

API 版本 效果
API 31+ 半透明 + blur(20dp) + 边框
API 29-30 半透明 + 边框（无 blur）
API 26-28 半透明（alpha 0.9）+ 边框

47.5 关键资源清单

资源 说明
R.drawable.logo_yuki 品牌 Logo
R.drawable.ic_default_avatar 默认头像占位
R.drawable.ic_default_persona 默认人设头像占位
R.drawable.donation_qr_1 收款码 1（用户提供）
R.drawable.donation_qr_2 收款码 2（用户提供）
R.drawable.ic_streaming 流式通知图标
R.font.xxx 自定义字体（可选）

---

第三部分：Agent 开发参考文档

48. Agent 开发总纲

48.1 文档定位

本部分聚焦液态玻璃底部导航栏的 Agent 实现指南。其他模块的 Agent 实现参考第一部分第 25 章。

48.2 实现优先级

```
P0（必做）
  ├── 数据层（Room + SQLCipher）
  ├── PromptEngine（含全局前缀 + 人设 + 开场白）
  ├── DeepSeek SSE 客户端
  ├── 流式生命周期
  ├── 记忆库（sqlite-vec + ONNX + BGE）
  ├── SSE 可靠性五方案
  └── 液态玻璃底部导航栏

P1（重要）
  ├── 云存储与备份
  ├── 人设市场
  ├── 账号管理
  └── 关于界面

P2（完善）
  ├── 缓存诊断
  ├── 记忆管理界面
  └── 各类扩展
```

48.3 通用约束

约束 说明
前缀字节稳定 绝不重写 Frozen Prefix
流式期间不写 messages 表 只写 WAL
记忆只从附录注入 绝不写回前缀
三级隔离 userId + personaId + sessionId
无原生 Android 弹窗 用 Compose 自定义
无默认 Material 主题色 用蓝白清冷水彩

49. 液态玻璃底部导航栏 Agent 实现指南

49.1 实现目标

实现一个液态玻璃底部导航栏组件 LiquidGlassBottomBar，具备：

· 玻璃胶囊外观
· 液滴指示器（滑动 + 拉伸 + 回弹 + 拖尾 + 呼吸）
· 无状态组件设计
· 与 NavHost 集成
· 低端设备降级

不实现（除非明确要求）：

· 真实背景模糊（用渐变玻璃替代）
· 整条胶囊变形（性能开销大）
· 粒子拖尾（可选扩展）

49.2 实现顺序

严格按以下顺序推进：

```
第 1 步：BottomTabItem 数据类
  └── 定义 label、selectedIcon、unselectedIcon

第 2 步：LiquidGlassBottomBar 骨架
  ├── 参数定义
  ├── require 校验 tabs 非空
  ├── safeIndex 钳制
  └── Box 容器 + Row 布局

第 3 步：玻璃胶囊背景
  ├── 玻璃渐变层
  ├── 胶囊描边
  └── 顶部高光带

第 4 步：液滴指示器（静态）
  ├── liquidX 状态
  ├── 光晕
  ├── 主液滴（含内高光、底部反光）
  └── 位置计算 fractionOf(index)

第 5 步：动画状态机
  ├── liquidX 弹簧滑动
  ├── squash 拉伸/回弹
  ├── breath 呼吸脉动
  ├── iconScale 图标缩放
  └── labelAlpha 文字渐显

第 6 步：拖尾
  ├── trailStartX 记录
  └── 拖尾绘制（仅移动时可见）

第 7 步：手势处理
  ├── 点击 Tab 槽位
  ├── 横向拖拽
  └── 松手吸附最近 Tab

第 8 步：集成到 MainScreen
  ├── NavHost 配置
  ├── tabIndexByRoute 映射
  └── onTabSelected 导航

第 9 步：低端设备降级

第 10 步：自测
```

49.3 文件结构

```
:core:ui/src/main/kotlin/com/yuki/core/ui/components/
├── LiquidGlassBottomBar.kt     # 主组件
├── BottomTabItem.kt            # 数据类
├── LiquidIndicator.kt          # 液滴指示器（内部）
└── GlassCapsule.kt             # 玻璃胶囊背景（内部）

:feature:main/src/main/kotlin/com/yuki/feature/main/
└── MainScreen.kt               # 集成导航栏 + NavHost
```

49.4 核心实现

49.4.1 BottomTabItem

```kotlin
data class BottomTabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
```

49.4.2 LiquidGlassBottomBar 签名

```kotlin
@Composable
fun LiquidGlassBottomBar(
    tabs: List<BottomTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 68.dp,
    cornerRadius: Dp = 28.dp,
    draggable: Boolean = true,
)
```

49.4.3 内部状态

```kotlin
require(tabs.isNotEmpty()) { "tabs must not be empty" }
val safeIndex = selectedIndex.coerceIn(0, tabs.size - 1)

val liquidX = remember { Animatable(fractionOf(safeIndex, tabs.size)) }
var trailStartX by remember { mutableFloatStateOf(liquidX.value) }
val squash = remember { Animatable(0f) }

val breath = rememberInfiniteTransition(label = "breath").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(2400, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    ),
    label = "breath",
)

var initialized by remember { mutableStateOf(false) }
var dragging by remember { mutableStateOf(false) }

fun fractionOf(index: Int, count: Int): Float = (index + 0.5f) / count
```

49.4.4 选中变化流程

```kotlin
LaunchedEffect(safeIndex) {
    val target = fractionOf(safeIndex, tabs.size)
    if (abs(liquidX.value - target) > 0.001f) {
        trailStartX = liquidX.value
        liquidX.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.55f,
                stiffness = 480f,
            ),
        )
    }
    if (initialized && squash.value < 0.02f) {
        squash.snapTo(1f)
        squash.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 0.28f,
                stiffness = 260f,
            ),
        )
    }
    initialized = true
}
```

49.5 动画状态机

状态 类型 触发 规格 视觉结果
liquidX Animatable (0..1) 选中变化 / 拖拽松手 spring(0.55, 480) 液滴横向滑动，微过冲
trailStartX Float 状态 动画开始前记录 — 拖尾起点
squash Animatable (1→0) 选中变化 / 拖拽松手 snapTo(1) → spring(0.28, 260) 拉伸 42% / 压扁 26%
breath InfiniteTransition 常驻 tween(2400ms, 往复) 圆角 ±10%
iconScale animateFloatAsState 选中变化 spring(0.5, 600) 图标放大至 1.10×
labelAlpha animateFloatAsState 选中变化 tween(200ms) 文字 0↔1

49.6 绘制管线

Canvas 内按序绘制：

```
光晕 → 拖尾 → 主液滴（含形变）→ 内高光 → 底部反光
```

液滴形变：

```text
blobW = tabW * 0.62 * (1 + 0.42 * squash)
blobH = barH * 0.62 * (1 - 0.26 * squash)
corner = min(blobH/2, blobW*0.30) * (1 + 0.10 * breath)
```

各层参数：

层 颜色 透明度
光晕 primary 0.30，半径 1.15 × 液滴宽
拖尾 White 0.32 × squash
主液滴（上） White 0.92
主液滴（下） White 0.42
内高光 White 0.95 → 0
底部反光 primary 0.35

49.7 手势处理

```kotlin
Modifier.pointerInput(draggable, tabs.size) {
    if (draggable) {
        detectHorizontalDragGestures(
            onDragStart = { dragging = true },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                val delta = dragAmount / size.width
                val newValue = (liquidX.value + delta).coerceIn(0f, 1f)
                launch { liquidX.snapTo(newValue) }
            },
            onDragEnd = {
                dragging = false
                val targetIndex = (liquidX.value * tabs.size).toInt()
                    .coerceIn(0, tabs.size - 1)
                if (targetIndex != safeIndex) {
                    onTabSelected(targetIndex)
                }
            },
            onDragCancel = {
                dragging = false
                launch { liquidX.animateTo(fractionOf(safeIndex, tabs.size)) }
            },
        )
    }
}
```

49.8 集成步骤

49.8.1 添加依赖

```kotlin
// :core:ui 的 build.gradle.kts
implementation(platform(libs.androidx.compose.bom))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.foundation:foundation")
implementation("androidx.compose.material:material-icons-extended")
```

49.8.2 MainScreen 集成

```kotlin
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        BottomTabItem("消息", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubble),
        BottomTabItem("人设", Icons.Filled.Person, Icons.Outlined.Person),
        BottomTabItem("市场", Icons.Filled.Storefront, Icons.Outlined.Storefront),
        BottomTabItem("我的", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
        BottomTabItem("关于", Icons.Filled.Info, Icons.Outlined.Info),
    )
    val routes = listOf("chat", "persona", "market", "profile", "about")
    val tabIndexByRoute = routes.withIndex().associate { (i, r) -> r to i }

    Scaffold(
        bottomBar = {
            LiquidGlassBottomBar(
                tabs = tabs,
                selectedIndex = tabIndexByRoute[currentRoute] ?: 0,
                onTabSelected = { index ->
                    navController.navigate(routes[index]) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { MessageListScreen(navController) }
            composable("persona") { PersonaEditScreen(navController) }
            composable("market") { PersonaMarketScreen(navController) }
            composable("profile") { ProfileScreen(navController) }
            composable("about") { AboutScreen(navController) }
        }
    }
}
```

49.9 自测清单

49.9.1 基础功能

☐ 组件正常渲染，五个 Tab 可见
☐ 点击 Tab 触发 onTabSelected
☐ 点击当前 Tab 不触发回调
☐ selectedIndex 越界自动钳制
☐ tabs 为空抛出 IllegalArgumentException

49.9.2 动画

☐ 首次进入无回弹动画（initialized 守卫生效）
☐ 切换 Tab 时液滴滑动带微过冲
☐ 落定后回弹 2~3 次
☐ 无「双倍回弹」问题（squash.value < 0.02f 守卫生效）
☐ 呼吸脉动持续存在，幅度不抖
☐ 图标放大到 1.10×
☐ 文字 0↔1 渐显

49.9.3 拖拽

☐ draggable = true 时液滴可拖拽
☐ 拖拽跟手无延迟
☐ 松手吸附最近 Tab
☐ 吸附目标不同时触发 onTabSelected
☐ 拖拽超出边界时液滴被钳制
☐ draggable = false 时无拖拽响应
☐ 拖拽与页面横向滚动无冲突

49.9.4 视觉

☐ 玻璃胶囊半透明通透
☐ 描边上下渐变
☐ 顶部高光带可见
☐ 液滴光晕、内高光、底部反光齐全
☐ 拖尾仅在移动时可见
☐ 深色模式下玻璃更暗

49.9.5 性能

☐ 帧率稳定 60fps（或 120fps）
☐ 每次切换重组次数 ≤ 2
☐ 无内存泄漏
☐ CPU 动画期间 < 5%

49.9.6 兼容性

☐ API 26-28 正常显示（降级）
☐ API 29-30 正常显示
☐ API 31+ 全部功能可用
☐ 深色模式正常

49.10 Agent 不要做的事

禁止 原因
不要在组件内持有业务状态 违反无状态原则
不要在 Canvas 内做重计算 性能问题
不要每帧重建 Brush 性能问题
不要用 Modifier.blur 叠加 模糊自身子树，浪费 GPU
不要在深色主题下用相同阴影 alpha 视觉过重
不要在低端设备强行用 blur API 31+ 才支持
不要在拖拽时用 animateTo 跟手需要 snapTo
不要在选中当前 Tab 时触发回调 逻辑错误
不要省略初始化守卫 首次进入有回弹
不要省略 squash 守卫 双倍回弹
不要在 tabs 为空时不校验 崩溃
不要硬编码颜色 用 MaterialTheme
不要硬编码尺寸 用 Dp 参数

49.10.1 关键守卫

守卫 代码 作用
初始化守卫 if (initialized && squash.value < 0.02f) 跳过首次组合回弹
双倍回弹守卫 squash.value < 0.02f 避免拖拽与选中叠加
Tab 点击守卫 if (index != safeIndex) 点击当前 Tab 不回调
索引钳制 coerceIn(0, tabs.size - 1) 越界安全

49.11 关键参数速查

参数 值 位置
barHeight 68dp LiquidGlassBottomBar
cornerRadius 28dp LiquidGlassBottomBar
liquidX: dampingRatio 0.55 LaunchedEffect
liquidX: stiffness 480 LaunchedEffect
squash: dampingRatio 0.28 LaunchedEffect
squash: stiffness 260 LaunchedEffect
breath: duration 2400ms rememberInfiniteTransition
iconScale: dampingRatio 0.5 animateFloatAsState
iconScale: stiffness 600 animateFloatAsState
labelAlpha: duration 200ms animateFloatAsState
液滴宽占比 0.62 Canvas
液滴高占比 0.62 Canvas
拉伸系数 0.42 Canvas
压扁系数 0.26 Canvas
呼吸系数 0.10 Canvas
光晕半径 1.15 × 液滴宽 Canvas
顶部高光带高 14dp Canvas
描边宽度 1dp Canvas

---

文档结束

⚠️ 完整说明：本文档为完整版本，包含开发技术文档、UI 设计文档、Agent 开发参考文档三部分。所有章节均为完整内容，不含"参见前版本"的省略。所有参数、接口、实现细节均为参考实现的整理。实际项目开发时，请根据真实的业务需求、设计规范、性能预算、团队规范进行调整。标注"可调"的参数需与设计、产品、测试共同确定最终值。文中提到的所有 API 名称、类名、方法名均为示例，实际实现可能不同。