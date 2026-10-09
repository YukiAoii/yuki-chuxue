package ai.yuki.chuxue.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.Persona
import ai.yuki.chuxue.data.UserPersona
import ai.yuki.chuxue.ui.components.EmptyHint
import ai.yuki.chuxue.ui.components.LineDivider
import ai.yuki.chuxue.ui.components.SettingsGroup
import ai.yuki.chuxue.ui.components.YukiCard
import ai.yuki.chuxue.ui.components.YukiConfirmDeleteDialog
import ai.yuki.chuxue.ui.components.YukiDialog
import ai.yuki.chuxue.ui.components.YukiTextField
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.CardGap
import ai.yuki.chuxue.ui.theme.DangerRose
import ai.yuki.chuxue.ui.theme.FieldCorner
import ai.yuki.chuxue.ui.theme.IceCyanSoft
import ai.yuki.chuxue.ui.theme.NavSpaceForContent
import ai.yuki.chuxue.ui.theme.SkyBlueDeep
import ai.yuki.chuxue.ui.theme.SnowSurface
import ai.yuki.chuxue.ui.theme.SnowSurfaceDim
import ai.yuki.chuxue.ui.theme.SnowWhite
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import java.util.UUID

/**
 * 「我的角色」—— 用户人设的列表 / 编辑 / 新建（v0.61.43）。
 *
 * ## 这是什么（给用户的心智）
 * 用户在角色扮演里**自己的身份**：名字、身份、性格、说话方式。
 * 写一个"你"，绑到某个 Ta 身上，Ta 就会把你当成这个人来相处。
 *
 * ## ⚠️ 两条设计红线（改这个文件前先读）
 * 1. **别让用户把 Ta 的性格写到这里**：说明块与提示语都要明确
 *    「这里写**你**；Ta 是谁，写在 Ta 的角色设定里」；
 * 2. **改内容 = 碎缓存**：正文进冻结前缀（用户拍板的取舍），说明块必须如实
 *    提示代价 —— 与通用设定的提示同一条纪律。
 */

/* ═══════════════════ 列表 ═══════════════════ */

/**
 * 「我（扮演）」段的列表。`onNew` 在空态与顶栏「+」里都能触达。
 */
@Composable
fun UserPersonaPane(
    list: List<UserPersona>,
    /** AI 角色列表 —— 只为统计"被几个角色在用"，不做别的。 */
    personas: List<Persona>,
    onEdit: (UserPersona) -> Unit,
    onNew: () -> Unit,
) {
    if (list.isEmpty()) {
        EmptyHint(
            icon = YukiIcons.Person,
            title = "还没有「你」",
            desc = "写一个你在角色扮演里的身份 —— 比如名字、性格、和 Ta 的关系。\n" +
                "绑给某个 Ta 之后，Ta 就会把你当成这个人。",
            actionLabel = "创建我的角色",
            onAction = onNew,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        items(list, key = { it.id }) { up ->
            UserPersonaRow(
                up = up,
                boundCount = personas.count { it.userPersonaId == up.id },
                onClick = { onEdit(up) },
            )
        }
    }
}

@Composable
private fun UserPersonaRow(up: UserPersona, boundCount: Int, onClick: () -> Unit) {
    YukiCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                up.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            if (up.roleText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    up.roleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (boundCount > 0) "$boundCount 个角色在用" else "还没有角色在用",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/* ═══════════════════ 编辑 / 新建 ═══════════════════ */

/** 新的空白用户人设（id 先落定，保存前不做任何持久化）。 */
fun blankUserPersona(): UserPersona = UserPersona(id = UUID.randomUUID().toString())

/**
 * 「我的角色」的编辑屏（新建 / 编辑共用）。
 *
 * ## ⚠️ 系统返回键**由调用方接住**（2026-10-06 修 bug 时定的约定）
 * 用户报过「新建/编辑我的角色时按返回键直接退出到桌面」—— 根因是调用点
 * （`PersonaScreen`）只给 AI 角色编辑器装了 `BackHandler`，漏了这一个。
 *
 * 现在**不在这里**装 `BackHandler`，而是由调用点装（`enabled = editingUserPersona != null`）：
 * · 这里装会与调用点的**重复**（本屏只在那个条件成立时渲染，两个都会 enabled）；
 * · Compose 同时有多个 enabled 的 BackHandler 时只跑**最后注册**的那个 ——
 *   重复装不会更安全，只会让"到底谁在处理返回"变得不可预测。
 *
 * 所以：**新增调用点时，记得在那边装 BackHandler**（照 `PersonaScreen` 的样子）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPersonaEditor(
    initial: UserPersona,
    isNew: Boolean,
    onSave: (UserPersona) -> Unit,
    onCancel: () -> Unit,
    /** 删除（仅编辑已存在的角色时提供）。 */
    onDelete: (() -> Unit)? = null,
) {
    var u by remember { mutableStateOf(initial) }
    var pendingDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SnowWhite,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SnowSurface,
                    titleContentColor = TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(YukiIcons.Back, contentDescription = if (isNew) "放弃" else "取消")
                    }
                },
                title = {
                    Text(
                        if (isNew) "新建我的角色" else "我的角色",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { onSave(u) },
                        // 名字和正文填一个就能存 —— 不拦人（最少必填原则）
                        enabled = u.name.isNotBlank() || u.roleText.isNotBlank(),
                    ) {
                        Text(if (isNew) "创建" else "保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = NavSpaceForContent)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            // ── 说明块：挡住两个误解（写错地方 / 不知道有缓存代价）──
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = IceCyanSoft,
                shape = RoundedCornerShape(FieldCorner),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "这里写的是「你」",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ta 是谁，写在 Ta 的设定里。\n改这里会让已有对话的缓存失效一次。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            SettingsGroup("身份") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    YukiTextField(
                        value = u.name,
                        onValueChange = { u = u.copy(name = it) },
                        label = "名字",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "你在这个身份里叫什么",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                LineDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    YukiTextField(
                        value = u.roleText,
                        onValueChange = { u = u.copy(roleText = it) },
                        label = "你在这个角色里是谁",
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // ⚠️ 2026-10-06：这里**明确提性别** —— 因为「你的性别」输入框
                        //    已从角色编辑页移除（用户要求"让用户直接在他的设定里写"），
                        //    用户需要知道该写哪儿。这是那条改动的**配套引导**，不能省。
                        "性别、身份、性格、说话习惯，都写这儿。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                LineDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    YukiTextField(
                        value = u.note,
                        onValueChange = { u = u.copy(note = it) },
                        label = "备注",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "只给你自己看，不发给 Ta",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            if (!isNew && onDelete != null) {
                YukiCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingDelete = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            "删除这个角色",
                            style = MaterialTheme.typography.bodyLarge,
                            color = DangerRose,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (pendingDelete && onDelete != null) {
        YukiConfirmDeleteDialog(
            title = "删掉这个角色？",
            message = "「${u.name.ifBlank { "未命名" }}」删掉后，用了它的 Ta 会自动解除绑定 —— " +
                "以后你又是「现在的你」了。",
            onConfirm = {
                pendingDelete = false
                onDelete()
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

/* ═══════════════════ 选择器（角色编辑页里用） ═══════════════════ */

/**
 * 「Ta 面前的我」选择器 —— 给某个角色挑一个用户人设（或取消绑定）。
 *
 * ⚠️ 与 `PersonaPickerDialog`（选 AI 角色）同构但更简单：**没有创建入口** ——
 *    用户人设的新建在「人设 → 我（扮演）」里做，选择器只管挑。
 */
@Composable
fun UserPersonaPickerDialog(
    all: List<UserPersona>,
    /** 当前绑定的 id（null / 空 = 没绑定）。 */
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    YukiDialog(
        title = "Ta 面前的我",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        confirmText = "好了",
        // 只需一个出口：选了就关，没选也可以直接关
        dismissText = "",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (all.isEmpty()) {
                Text(
                    "还没有你的角色。去「人设 → 我（扮演）」里先建一个。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            } else {
                UserPersonaPickRow(
                    title = "不用（就是现在的我）",
                    subtitle = "Ta 只知道你的昵称与性别",
                    selected = current.isNullOrBlank(),
                    onClick = { onPick(null) },
                )
                all.forEach { up ->
                    UserPersonaPickRow(
                        title = up.name.ifBlank { "未命名" },
                        subtitle = up.roleText.lineSequence().firstOrNull().orEmpty().take(40),
                        selected = current == up.id,
                        onClick = { onPick(up.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserPersonaPickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        // 当前绑定项用浅蓝底标出来；其余是中性底 —— 与全 App 的选择器同一套观感
        color = if (selected) IceCyanSoft else SnowSurfaceDim,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = TextPrimary,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
