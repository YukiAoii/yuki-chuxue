package ai.yuki.chuxue.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.ui.icon.YukiIcons
import ai.yuki.chuxue.ui.theme.TextMuted
import ai.yuki.chuxue.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「从相册选择」的动作行 —— **头像 / 聊天背景 / 表情包共用**。
 *
 * ## 为什么抽出来
 * 三处的流程一字不差：调系统选择器 → 拿 Uri → 解码缩放 → 存进私有目录 → 回调路径。
 * 抄三遍的话，将来改一次压缩参数，就有三个地方要同步改 —— 而漏改的那一处
 * **不会有任何报错**，只是行为悄悄不一致。
 *
 * ## ⚠️ 相机已删（用户 2026-09-30 要求「全部删拍一张」）
 * 这里原来还有一行「拍一张」—— 走 `ActivityResultContracts.TakePicture`，
 * 把 `cache/captures/` 下一个临时文件经 FileProvider 交给系统相机写入。
 * 用户要求**全 App** 删除相机入口，所以那一行、它的 launcher、它的临时文件变量
 * 一并删掉了；`res/xml/file_paths.xml` 里的 `captures` 子目录也一起删（没有消费者了）。
 * manifest 里的 `FileProvider` **保留** —— 它还被 `ApkInstaller`（装更新包）用着。
 *
 * ⚠️ 组件名仍叫 `ImageSourceButtons`（复数）：改名会牵动 4 处调用方，
 *    而它现在只剩一个动作。等真给它加第二个来源时一起改名更划算。
 *
 * ## 失败必须说话
 * 每一步失败都走 [onError] 回报一句人话。用户点了"挑一张"却毫无反应，
 * 只会以为按钮坏了 —— 这类静默失败在本项目里被明确禁止。
 *
 * @param dir 私有目录里的子目录（`ImageStore.AVATAR_DIR` / `BACKGROUND_DIR` / `EMOJI_DIR`）
 * @param namePrefix 落地文件名前缀。**实际文件名会带上时间戳** —— 这是必要的：
 *        头像/背景的渲染用 `produceState(path)` 按路径缓存解码结果，
 *        若换图后路径不变，Compose 不会重新解码，用户会**换了图却还看到旧图**。
 *        每次生成新路径，就自然绕开了这个坑。
 * @param maxSide 长边上限：头像 512 够，背景要 1440
 */
@Composable
fun ImageSourceButtons(
    dir: String,
    namePrefix: String,
    maxSide: Int,
    onPicked: (String) -> Unit,
    onError: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    galleryTitle: String = "从相册选择",
    galleryDesc: String = "挑一张图片",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 把拿到的 Uri 落盘成私有文件，然后回调给调用方。 */
    fun adopt(source: Uri) {
        onBusyChange(true)
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                ImageStore.saveFromUri(
                    context, source, dir,
                    "${namePrefix}_${System.currentTimeMillis()}",
                    maxSide,
                )
            }
            onBusyChange(false)
            if (path == null) onError("这张图读不出来，换一张试试") else onPicked(path)
        }
    }

    // 相册：系统 Photo Picker，不需要任何存储权限
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { adopt(it) }
    }

    Column(Modifier.fillMaxWidth()) {
        ActionLine(galleryTitle, galleryDesc) {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

@Composable
private fun ActionLine(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Icon(
            YukiIcons.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}
