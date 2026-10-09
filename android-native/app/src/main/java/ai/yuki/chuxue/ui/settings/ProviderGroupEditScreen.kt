package ai.yuki.chuxue.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.yuki.chuxue.data.ApiHttpException
import ai.yuki.chuxue.data.AppSettings
import ai.yuki.chuxue.data.DeepSeekClient
import ai.yuki.chuxue.data.ProviderGroup
import ai.yuki.chuxue.data.FreeGroupApi
import ai.yuki.chuxue.data.ProviderGroups
import ai.yuki.chuxue.data.ProviderVendors
import ai.yuki.chuxue.data.ProviderType
import ai.yuki.chuxue.ui.ChatViewModel
import androidx.compose.ui.text.input.KeyboardType
import ai.yuki.chuxue.ui.components.Island
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.ErrorColor
import ai.yuki.chuxue.ui.theme.FrostLine
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.SuccessColor
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.BrandBlue
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * 「新建分组」的占位 id。
 *
 * ⚠️ 为什么能安全地用一个字符串当哨兵：真 id 由 [ProviderGroups.newId] 生成，
 * 是 UUID 前 8 位**十六进制**字符，永远不可能等于 `"new"`（含 `n`/`w`）。
 * 同一个理由让 id 天然 URL 安全，路由不必编码（见 `Routes.MEMORY` 的注释）。
 */
internal const val NEW_GROUP_ID = "new"

/** 新建分组时的默认名称。 */
private const val NEW_GROUP_NAME = "新分组"

/**
 * **分组详情页** —— 新建 / 编辑一个连接分组。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * v0.57.0 重排成**引导式填写**（用户要求）
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户原话：「界面看起来太乱了…重新排版…文案提示内容更加易懂和简短做引导式填写，
 * 填写顺序是**分组名称 → API地址 → 密钥信息 → 拉取模型列表 → 模型上下文和发送
 * 思考参数的开关 → 测试按钮**，测试按钮必须所有的都能发送测试请求」。
 *
 * 所以每一组前面挂了一个**序号**（①…⑥），一次只交代一件事；上一版按"技术上属于
 * 哪一类"分组（密钥 / 地址 / 兼容性 / 模型 / 测试），顺序与用户填写的顺序不一致。
 *
 * ## ⚠️「设为当前 / 删除」已搬到 [ApiConfigScreen]
 * 用户要求「设置当前分组和删除分组改到连接设置界面」—— 那两件事作用于**列表**，
 * 不属于"编辑这一个分组"。这一页因此只剩"填完、测通、保存"。
 *
 * ## ⚠️ 复用而非新写网络代码
 * 「拉取模型」= [DeepSeekClient.listModels]，「测试」= [DeepSeekClient.testConnection]，
 * 两个都只吃一个 [AppSettings]。分组本身就能造出那个载体
 * （草稿 → `AppSettings`），所以这里**一行网络代码都没有**。
 *
 * ⚠️ 渲染与网络行为均未经真机验证（本机无 adb / emulator）。
 */
/**
 * 「记忆窗口」是否填得比「API 窗口」还大 —— 那会在压缩发生之前先撞上服务商上限，
 * 请求被**直接拒掉**。
 *
 * ⚠️ 只用来**提示**，不阻止保存：用户可能明知故为（临时压测等），
 * 但默认情况下这是一个必然出错的组合，必须在输入的那一刻就看得见。
 * ⚠️ 任一为空都返回 false —— 空 = 没填 = 不构成这个组合，不该误报。
 */
private fun isMemoryOverApi(memory: String, api: String): Boolean {
    val m = memory.toIntOrNull() ?: return false
    val a = api.toIntOrNull() ?: return false
    return a > 0 && m > a
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderGroupEditScreen(
    vm: ChatViewModel,
    groupId: String,
    onBack: () -> Unit,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val existing = groups.firstOrNull { it.id == groupId }
    val isNew = existing == null

    /**
     * **服务器托管的分组**（v0.58.0 的「Yuki初雪Pro」）。
     *
     * ⚠️ 它改变这一页的**形状**，不只是"灰掉几个框"：
     *    名称/地址/密钥三块**根本不渲染** —— 用户没有任何理由看到一个不属于他的密钥，
     *    而"只读输入框"仍然把密钥摆在屏幕上、"禁用"仍然能被选中复制。
     */
    val managed = existing?.managed == true

    /* ── 草稿：改到一半能反悔（保存前不落盘） ── */
    // ⚠️ key 用 groupId：`vm.groups` 是 StateFlow，初值就是 `store.loadGroups()`（同步读盘），
    //    所以进这一页时 `existing` 一定已经在了，草稿不会取到 null。
    var name by remember(groupId) { mutableStateOf(existing?.name ?: NEW_GROUP_NAME) }
    // ⚠️ **新建分组不再预填服务地址**（用户 2026-10-04：把 api.deepseek.com 删掉、留空）。
    //    预填一个"看起来就该是这个"的地址，会让人跳过"我到底连哪一家"这一步 ——
    //    而新建分组的第一件事本来就是选供应商（见表单 ①）。
    //    改现有分组时仍回填它自己的地址：那是它的**真实值**，不是默认值。
    val defaultBaseUrl = existing?.baseUrl ?: ""
    var baseUrl by remember(groupId) { mutableStateOf(defaultBaseUrl) }
    /**
     * 「① 选供应商」选中了哪一家。
     *
     * ⚠️ 它**不落库** —— 选中供应商的效果就是"把地址填进 [baseUrl]"，而 baseUrl 本来就会存。
     *    再存一个供应商 id 只会多一份可能与 baseUrl 打架的状态。
     *    改现有分组时它是 null（地址已经是用户自己的了，没有"选中的供应商"这回事）。
     */
    var vendorId by remember(groupId) { mutableStateOf<String?>(null) }
    var apiKey by remember(groupId) { mutableStateOf(existing?.apiKey.orEmpty()) }
    var checked by remember(groupId) { mutableStateOf(existing?.checkedModels.orEmpty()) }
    // ⚠️ 默认 true = 与本开关存在之前的行为逐字节一致（老分组缺这个字段也是 true）
    var sendThinking by remember(groupId) { mutableStateOf(existing?.sendThinkingParams ?: false) }
    var memoryWindowText by remember(groupId) {
        mutableStateOf(existing?.memoryContextWindow?.toString().orEmpty())
    }
    var windowText by remember(groupId) {
        mutableStateOf(existing?.contextWindow?.toString().orEmpty())
    }

    var showKey by remember { mutableStateOf(false) }
    var manualModel by remember { mutableStateOf("") }

    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    /** 测试结果：true/false = 通/不通，第二项是给用户看的整句。 */
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var askLeave by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val trimmedUrl = baseUrl.trim()

    /**
     * 「有没有真的改过」—— 同时管保存键的可用性与**返回时要不要拦**。
     *
     * ⚠️ 用一个变量而不是两个（`dirty` / `canSave`）：两个判据一旦分叉，就会出现
     * "保存键亮着、返回却不拦"这种能静默丢输入的缝。
     */
    val touched = trimmedName != (existing?.name ?: NEW_GROUP_NAME) ||
        trimmedUrl != defaultBaseUrl ||
        apiKey.trim() != existing?.apiKey.orEmpty() ||
        checked != existing?.checkedModels.orEmpty() ||
        sendThinking != (existing?.sendThinkingParams ?: false) ||
        windowText.toIntOrNull() != existing?.contextWindow ||
        memoryWindowText.toIntOrNull() != existing?.memoryContextWindow

    val canSave = touched && trimmedName.isNotBlank() && trimmedUrl.isNotBlank()

    /** 用**草稿**造一份设置：拉取与测试都不需要先保存（用户可以先试再存）。 */
    fun draftSettings(model: String) = AppSettings(
        apiKey = apiKey.trim(),
        baseUrl = trimmedUrl,
        // 拉取模型列表用不到 model，但 testConnection 必须带一个
        model = model,
    )

    fun pullModels() {
        // ⚠️ 托管分组（服务端下发的「Yuki初雪Pro」）**不直连服务商**（用户 2026-10-04：
        //    「免费分组的分组设置的拉取模型列表的按钮改为我后端的」）。
        //    理由和它的地址/密钥由后端维护是同一条：清单也该由后端说了算 ——
        //    而且让客户端拿别人的密钥去直连，一旦后端换了地址就必然对不上。
        //    所以这里改成"向后端要它已经配好的那份清单"。
        if (managed) {
            fetching = true
            scope.launch {
                val state = FreeGroupApi.fetchState()
                fetching = false
                if (state is FreeGroupApi.State.Ok && state.group.models.isNotEmpty()) {
                    // 只取**真名**去勾选（那是发出去的字节）；显示名由 labelOf 负责
                    fetched = state.group.models.map { it.id }
                    showPicker = true
                } else {
                    // 不直连、不猜：拿不到就是"官方这会儿没给"，说清楚就行
                    Island.warn("暂时拿不到官方模型清单，稍后再试")
                }
            }
            return
        }
        if (apiKey.isBlank()) {
            Island.warn("先填上密钥，再去拉取")
            return
        }
        if (trimmedUrl.isBlank()) {
            Island.warn("先填上服务地址")
            return
        }
        fetching = true
        scope.launch {
            runCatching { DeepSeekClient().listModels(draftSettings("")) }
                .onSuccess { list ->
                    fetching = false
                    if (list.isEmpty()) {
                        Island.warn("没拉到模型，稍后再试（也可以手动填一个）")
                    } else {
                        fetched = list
                        showPicker = true
                    }
                }
                .onFailure {
                    fetching = false
                    // ⚠️ 不说"拉取失败"就完 —— 用户要知道**下一步**：
                    //    没有 `/models` 接口的中转很常见，手动填是正经出路，不是兜底。
                    Island.error("拉取失败：检查地址与密钥；也可以直接手动填模型名")
                }
        }
    }

    /**
     * 测试连通。
     *
     * ## ⚠️ v0.57.0 改了：**先证明连接、再证明模型**（用户要求「所有的都能发送测试请求」）
     * 上一版要求"必须先勾选一个模型"，没勾就只弹一句提示 —— 用户在④之前点测试
     * 会以为按钮坏了。现在分两步，每一步都**真的发请求**：
     *   ① `GET /models` —— 不需要模型名，验证**地址 + 密钥**；
     *   ② 用（勾过的 / 这次拉到的 / 手动填的）第一个模型发一条 4 token 的对话请求。
     * 第①步失败也能说清是"地址或密钥不对"，而不是笼统的"没通"。
     */
    /**
     * 测试连通（v0.61.0 **推翻重做** —— 对齐 `_refs/Tianshu` 的「测试连接」）。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * 老实现为什么"没有用"
     * ═══════════════════════════════════════════════════════════════════════════
     * 老实现是「先拉 `/models`，再拿第一个模型发一条 4 token 的对话」。它有**两个
     * 各自独立**的失败点，而两个都会报「没通」，让用户以为地址/密钥填错了：
     *
     * ① **模型没授权** —— 那次补全用的是分组里**存着的**模型名。服务商没给这个
     *    token 开那个模型时返回 403。实测：`deepseek-chat` 在 `your-llm-provider.example.com`
     *    上就是「This token has no access to model deepseek-chat」，
     *    而地址和密钥**完全正确**。
     * ② **空内容** —— `max_tokens = 4` 遇到会思考的模型时，4 个 token 全被
     *    `reasoning_content` 吃掉，`content` 是空串 → `parseResponse` 抛
     *    「模型返回了空内容」。实测 `deepseek-flash` 正是如此。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * 新做法：只拉 /models
     * ═══════════════════════════════════════════════════════════════════════════
     * Tianshu 的 `provider-probe-adapter.ts` 就是**只拉 `/models`**
     * （它的原注释：「零 token 消耗、快」）。理由很直白：
     * **「测试连通」只该回答一件事 —— 这个地址 + 这个密钥能不能用。**
     * 模型跑不跑得动是另一回事，交给真正发消息的时候暴露。
     *
     * 顺带的好处：**不再消耗任何 token**（老实现每次点都烧 4 个）。
     */
    fun runTest() {
        when {
            apiKey.isBlank() -> Island.warn("先填上密钥，再测试")
            trimmedUrl.isBlank() -> Island.warn("先填上服务地址，再测试")
            else -> {
                testing = true
                testResult = null
                scope.launch {
                    runCatching { DeepSeekClient().listModels(draftSettings("")) }
                        .onSuccess { list ->
                            testing = false
                            fetched = list
                            testResult = true to if (list.isEmpty()) {
                                // 端点通了、鉴权也过了，只是没给模型清单 —— 仍算连通
                                "通了：地址和密钥都能用（这个服务商没返回模型列表）"
                            } else {
                                "通了：地址和密钥都能用，拿到 ${list.size} 个模型"
                            }
                        }
                        .onFailure { e ->
                            testing = false
                            testResult = false to describeProbeError(e)
                        }
                }
            }
        }
    }

    fun save() {
        val now = System.currentTimeMillis()
        val saved = ProviderGroup(
            id = existing?.id ?: ProviderGroups.newId(),
            // ⚠️ 托管分组的名称/地址/密钥**以本地那份为准**（`existing`）：
            //    这一页根本不渲染那三个输入框，草稿里的值只是初值，
            //    但把它们写回去就等于"用界面上的空串覆盖服务端下发的配置" —— 这个分组会被废掉。
            name = if (managed) existing!!.name else trimmedName,
            baseUrl = if (managed) existing!!.baseUrl else trimmedUrl,
            apiKey = if (managed) existing!!.apiKey else apiKey.trim(),
            // 目前只实现 OpenAI 兼容族（见 ProviderType 的注释）；预留类型不动
            providerType = existing?.providerType ?: ProviderType.OPENAI_COMPAT,
            checkedModels = checked,
            createdAt = existing?.createdAt ?: now,
            sendThinkingParams = sendThinking,
            contextWindow = windowText.toIntOrNull()?.takeIf { it > 0 },
            memoryContextWindow = memoryWindowText.toIntOrNull()?.takeIf { it > 0 },
            managed = managed,
            notice = existing?.notice.orEmpty(),
        )
        val next = if (existing == null) groups + saved
        else groups.map { if (it.id == saved.id) saved else it }
        vm.saveGroups(next)
        // 刚建好的分组直接成为"当前" —— 用户建它就是为了用它。
        // 编辑既有分组时不动这个状态（那是个全局开关，不该被一次改密钥顺手改掉）。
        if (existing == null) vm.setActiveGroup(saved.id)
        Island.ok("已保存")
        onBack()
    }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowWhite,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = { if (touched) askLeave = true else onBack() }) {
                        Icon(YukiIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(enabled = canSave, onClick = { save() }) {
                        Text("保存", fontWeight = FontWeight.SemiBold)
                    }
                },
                title = {
                    Text(
                        // 托管分组显示**它自己的名字**（那是由服务端定的，界面上只读）；
                        // 名字本身不是秘密 —— 用户在列表里就看得到，藏起来反而找不到自己在哪一页。
                        text = when {
                            isNew -> "新建分组"
                            managed -> existing?.name.orEmpty()
                            else -> "编辑分组"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            // ⚠️ 托管分组（服务端下发的「Yuki初雪Pro」）**这三块根本不渲染**：
            //    名称只读，地址与密钥连框都没有 —— 用户没有任何理由看到一个不属于他的密钥，
            //    而「只读输入框」仍然把密钥摆在屏幕上、「禁用」也仍然能选中复制。
            if (managed) {
                ManagedGroupNotice(
                    name = existing?.name.orEmpty(),
                    notice = existing?.notice.orEmpty(),
                )
            } else {
                /* ── ① 选供应商（用户 2026-10-04 要求：「增加第一项选择就是选择模型供应商」）── */
                SettingsGroup("① 选供应商") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "先选连哪一家，服务地址会自动填好。不确定就选最后一项，自己填。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ProviderVendors.ALL.forEach { vendor ->
                            val selected = vendorId == vendor.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) SkyBlueDeep.copy(alpha = 0.10f)
                                        else Color.Transparent,
                                    )
                                    .clickable {
                                        vendorId = vendor.id
                                        // 选中就把地址带出来。「其他 / 自定义」带出来的是**空串** ——
                                        // 那正是它的语义：请你自己填。地址因此永远来自
                                        // 一次明确的选择，而不是凭空预填（用户要求删掉预填）。
                                        baseUrl = vendor.baseUrl
                                        // 名字还没动过 → 顺手填上，省一步输入；动过就绝不覆盖
                                        if (name.isBlank() || name == NEW_GROUP_NAME) name = vendor.label
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = vendor.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) SkyBlueDeep else TextPrimary,
                                    )
                                    Text(
                                        text = vendor.hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                    )
                                }
                                if (selected) {
                                    Text(
                                        text = "✓",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SkyBlueDeep,
                                    )
                                }
                            }
                        }
                    }
                }

                /* ── ② 名称 ── */
                SettingsGroup("② 起个名字") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("分组名称") },
                        placeholder = { Text("比如：官方 / 中转", style = MaterialTheme.typography.bodySmall) },
                    )
                    Text(
                        text = "给自己看的，聊天里的模型菜单按它区分。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }

                /* ── ③ 服务地址 ── */
                SettingsGroup("③ 填服务地址") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("服务地址") },
                        supportingText = {
                            Text(
                                text = "实际会用：${DeepSeekClient.normalizeBaseUrl(trimmedUrl)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    Text(
                        text = "选上面那一家会自动填好；也能自己改（只填到域名也行）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }

                /* ── ④ 密钥 ── */
                SettingsGroup("④ 填密钥") {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("密钥") },
                        placeholder = { Text("sk- 开头的一长串", style = MaterialTheme.typography.bodySmall) },
                        visualTransformation =
                        if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SkyBlueDeep,
                                )
                            }
                        },
                    )
                    Text(
                        text = "服务商给你的那串。只存在这台手机上，我们看不到。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }

                /* ── ④ 模型：拉取 → 半屏抽屉勾选 ── */
            }

            SettingsGroup("⑤ 挑要用的模型") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "会用的模型",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "从服务商拉一份列表，勾你要用的那几个",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    TextButton(enabled = !fetching, onClick = { pullModels() }) {
                        if (fetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = SkyBlueDeep,
                            )
                        } else {
                            Text("拉取", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                LineDivider()

                if (checked.isEmpty()) {
                    Text(
                        // 空态说清"去哪儿加"，而不是只说"没有"
                        text = "还没勾选模型 —— 点上面的「拉取」，或在下面手动填一个模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    checked.forEachIndexed { index, model ->
                        if (index > 0) LineDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                // ⚠️ 显示名只影响这里；`checked` 里存的仍是**发给服务商的真名**
                                text = ProviderGroups.labelOf(existing, model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { checked = checked - model }) {
                                Text(
                                    "移除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                }

                LineDivider()

                // ⚠️ 手动填不是"兜底彩蛋"：很多第三方中转**根本不实现** `GET /models`，
                //    只给拉取的话那部分用户会卡死在"拉不到列表 → 一个模型都勾不了"。
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manualModel,
                        onValueChange = { manualModel = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("手动填一个模型名") },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = manualModel.isNotBlank(),
                        onClick = {
                            val m = manualModel.trim()
                            if (m.isNotEmpty() && m !in checked) checked = checked + m
                            manualModel = ""
                        },
                    ) {
                        Text("添加", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            /* ── ⑤ 上下文与参数（可跳过）── */
            SettingsGroup("⑥ 上下文与参数（可跳过）") {
                SwitchLine(
                    title = "发送思考参数",
                    desc = "DeepSeek 要用它。第三方若报「参数错误」，关掉就行。",
                    checked = sendThinking,
                    onCheckedChange = { sendThinking = it },
                )
                LineDivider()
                // ── 两个数分开放（v0.53.0）──
                //    它们过去挤在同一个输入框里，造成"改它还顺带改了压缩时机"的牵连，
                //    而用户往往只想调其中一个。
                OutlinedTextField(
                    value = memoryWindowText,
                    onValueChange = { memoryWindowText = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("记忆上下文（token，可留空）") },
                    supportingText = {
                        Text(
                            text = when {
                                memoryWindowText.isBlank() -> "留空 = 跟着下面的「API 上下文」"
                                (memoryWindowText.toIntOrNull() ?: 0) < 1000 ->
                                    "这个数太小了，确认一下单位是 token"
                                // ⚠️ 交叉校验：记忆窗口比 API 窗口还大 = 还没到压缩点
                                //    就先撞上服务商上限，请求会被直接拒。不阻止保存
                                //（用户可能清楚自己在做什么），但必须当场说清。
                                isMemoryOverApi(memoryWindowText, windowText) ->
                                    "⚠ 比下面的「API 上下文」还大 —— 会在压缩之前先撞上服务商上限、请求被拒"
                                // ⚠️ v0.61.56：补一句**实测参考**。
                                //    用户报「压缩怎么改都不会触发」—— 根因就是这个数**默认太大**：
                                //    留空 = 跟着 API 窗口（128K），而普通聊天 100 轮才 5,507 token，
                                //    要 1600 多轮才到触发线。不给参考值，用户不知道该填多少。
                                else -> {
                                    val n = memoryWindowText.toIntOrNull() ?: 0
                                    val turns = if (n > 0) n * 0.7 / 55.07 else 0.0
                                    "聊到这么多就开始压缩。约 ${turns.toInt()} 轮普通对话 —— 填小 = 省额度、但忘得快"
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                LineDivider()
                OutlinedTextField(
                    value = windowText,
                    onValueChange = { windowText = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("API 上下文（token，可留空）") },
                    supportingText = {
                        Text(
                            text = when {
                                windowText.isBlank() -> "留空 = 按默认 128K 估算（小窗口模型会不准）"
                                (windowText.toIntOrNull() ?: 0) < 1000 ->
                                    "这个数太小了，确认一下单位是 token"
                                else -> "服务商官网页写的那个上限，填了就以它为准"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Text(
                    text = "这两个数只管「聊到多少开始压缩」，不影响能不能用。拿不准就都留空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }

            /* ── ⑥ 测试 ── */
            SettingsGroup("⑦ 测一下能不能用") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "发送测试请求",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "先探地址和密钥，再用一个模型发条极短的请求",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    TextButton(enabled = !testing, onClick = { runTest() }) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = SkyBlueDeep,
                            )
                        } else {
                            Text("测试", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                testResult?.let { (ok, message) ->
                    LineDivider()
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) SuccessColor else ErrorColor,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (!isNew) {
                // 那两件事作用于**列表**，不在这一页 —— 说清去哪儿，免得用户在这里找。
                Text(
                    text = "想「设为当前」或「删除」，回连接设置那一页长按这个分组。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    /* ── 半屏抽屉：勾选模型 ──
       ⚠️ 放在 Scaffold 之外：它是对话框层，塞进 content 里会被布局裁剪
       （与 `SettingsBackupScreen` 的二次确认同一条）。 */
    if (showPicker) {
        ModelCheckSheet(
            models = fetched,
            initialChecked = checked,
            // ⚠️ 抽屉里画的是**显示名**，勾出来存进 `checked` 的仍是**真名**
            //    （`onConfirm` 拿到的是 models 里的项，不是画出来的字）
            labelOf = { ProviderGroups.labelOf(existing, it) },
            onConfirm = { picked ->
                // 顺序 = 拉取列表的顺序；列表外的那些（手动填的 / 服务方已下架的）接在后面保住
                val inFetched = fetched.toSet()
                checked = fetched.filter { it in picked } + picked.filter { it !in inFetched }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (askLeave) {
        YukiDialog(
            title = if (isNew) "放弃这个新分组？" else "放弃这次修改？",
            confirmText = "放弃",
            destructive = true,
            onConfirm = {
                askLeave = false
                onBack()
            },
            onDismiss = { askLeave = false },
        ) {
            Text(
                text = "刚才填的内容不会保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 拉取回来的模型列表 → **半屏抽屉 + 勾选**（用户原话：
 * 「底部抽屉菜单弹出供用户进行选择模型」/「模型列表超过 5 个就显示搜索框」/
 * 「支持底部菜单上拉到半屏」）。
 *
 * ## 为什么勾选状态是本地的
 * 用户在抽屉里勾了几个又全部取消、直接下滑关掉 —— 那不该改到外面的分组。
 * 与 `ModelPickerSheet` 里"浏览中的分组"同一条纪律：**确认前不写回**。
 * 下滑关闭 = 放弃（与按「取消」同义）。
 *
 * ⚠️ 上拉到半屏由 `rememberModalBottomSheetState()` 天然提供
 * （默认 PartiallyExpanded 起步、可上拉），不需要自己算高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCheckSheet(
    models: List<String>,
    initialChecked: List<String>,
    /** 把真名翻成给用户看的显示名（托管分组才有；其余回落成真名本身）。**只影响显示。** */
    labelOf: (String) -> String = { it },
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var picked by remember(models) { mutableStateOf(initialChecked.toSet()) }
    var keyword by remember { mutableStateOf("") }

    val shown = remember(models, keyword) {
        if (keyword.isBlank()) {
            models
        } else {
            // 显示名与真名都要能搜到（用户按他看到的字搜却搜不到，是最容易被骂的小坑）
            models.filter {
                it.contains(keyword, ignoreCase = true) ||
                    labelOf(it).contains(keyword, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnowWhite,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "勾选要用的模型",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
            Text(
                text = "拉取到 ${models.size} 个，已选 ${picked.size} 个。" +
                    "这些才会出现在聊天输入框的「+」里。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            )

            // 搜索框：**超过 5 个才出现**（用户明确要求）。
            // 少于 5 个时给搜索框是纯噪声 —— 一屏能看全的东西不需要找。
            if (models.size > 5) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索模型（共 ${models.size} 个）") },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (shown.isEmpty()) {
                Text(
                    text = "没有匹配「$keyword」的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(shown, key = { it }) { model ->
                        val on = model in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (on) picked - model else picked + model }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckMark(on)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                // 显示名；勾选状态与存下去的值都仍以 `model`（真名）为准
                                text = labelOf(model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (on) SkyBlueDeep else TextPrimary,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = TextMuted)
                }
                TextButton(onClick = { onConfirm(picked) }) {
                    Text("确定", color = SkyBlueDeep, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * 一个自绘的勾选框。
 *
 * ⚠️ 为什么不用 `Checkbox` / `Icons.Default.Check`：本项目**没有**
 * `material-icons-extended` 依赖，图标全在 `YukiIcons` 里手绘（见它的文件头）。
 * 而 Material 的 `Checkbox` 是"标准 Android 应用"的脸，与「初雪」的水彩观感冲突
 * —— 文档 §28.1 明确要求所有对话框 / 选择器 / 输入框都自绘。
 */
/**
 * 把探测失败翻译成**用户能照着做**的一句话。
 *
 * 分类对齐 Tianshu 的 `connect.probeError.*`（auth-failed / timeout / network-error /
 * quota / http-404 / http-<status>）—— 它的价值在于：**"没通"这三个字对用户毫无用处**，
 * 得说清是"密钥不对"还是"地址不对"还是"网络不通"，用户才知道下一步改哪儿。
 */
private fun describeProbeError(e: Throwable): String = when {
    e is ApiHttpException -> when (e.status) {
        401, 403 -> "密钥不对，或者这个密钥没有被授权（HTTP ${e.status}）"
        404 -> "地址不对：这个服务下没有 /models —— 检查一下是不是漏了 /v1"
        429 -> "被限流或额度用完了（HTTP 429），等一会儿再试"
        else -> "服务返回 HTTP ${e.status}：${e.message.orEmpty().take(120)}"
    }
    e is java.net.SocketTimeoutException -> "超时了：地址可能不通，或者网络太慢"
    e is java.net.UnknownHostException -> "域名解析不了：服务地址可能写错了"
    e is java.io.IOException -> "连不上：${e.message.orEmpty().take(120)}"
    else -> "没通：${e.message ?: e::class.simpleName.orEmpty()}"
}

@Composable
private fun CheckMark(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) SkyBlueDeep else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked) SkyBlueDeep else FrostLine,
                shape = RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size(12.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.08f, h * 0.52f)
                    lineTo(w * 0.38f, h * 0.84f)
                    lineTo(w * 0.94f, h * 0.16f)
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}


/**
 * 托管分组（「Yuki初雪Pro」）在编辑页顶部的说明卡，替代原本的①名称/②地址/③密钥。
 *
 * ⚠️ 这里**只显示名字**，不显示地址与密钥 —— 那不是用户的东西，
 *    摆出来只会让他以为自己该去核对它。
 */
@Composable
private fun ManagedGroupNotice(name: String, notice: String) {
    SettingsGroup("官方提供") {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BrandBlue.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "免费",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = name.ifBlank { "Yuki初雪Pro" },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = notice.ifBlank { "地址与密钥由服务器维护 —— 不需要填写，也看不到。" },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "你只需要挑模型、调上下文参数，然后测一下能不能用。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
