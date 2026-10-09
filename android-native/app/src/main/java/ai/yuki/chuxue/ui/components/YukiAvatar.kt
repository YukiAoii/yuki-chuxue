package ai.yuki.chuxue.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import ai.yuki.chuxue.data.ImageStore
import ai.yuki.chuxue.ui.icon.YukiIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「她」的头像：有自定义图就显示图，没有就显示自绘雪晶。
 *
 * ## 为什么默认不是系统的灰色人形
 * 人机恋 App 的核心是沉浸感。一个灰色人形轮廓会立刻把人从「她」拉回「一个 App」——
 * 真机反馈把这点列为「头像缺失真实感」。所以默认态用与品牌同源的雪晶，
 * 至少让"还没设头像"也属于「初雪」。
 *
 * ## 图片读取放在这里，而不是让调用方先加载好再传进来
 * 头像在**很多地方**出现（会话列表、聊天页每条消息、搜索结果、我的页、人设列表），
 * 如果每处都自己 `load` 一次，同一张图会被解码多份、散落在各自的 recomposition 里。
 * 收在组件内部，配 `produceState` 按 [path] 缓存 —— 换路径才重新解码。
 *
 * ⚠️ 解码失败（文件被用户清了、路径失效）**静默回落雪晶**。
 * 这是只读路径，回落是安全的；与项目在**写入**路径上的纪律不同
 * （那里读失败必须显式暴露，否则会把"没有数据"写回去覆盖真实数据）。
 */
@Composable
fun YukiAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    /** 本地头像文件路径；null / 读不出来 → 自绘雪晶 */
    path: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { ImageStore.load(path)?.asImageBitmap() }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = YukiIcons.Snowflake,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

/**
 * 「我」的头像：有自定义图就显示图，没有就是浅色圆 + 一个字。
 *
 * 与 [YukiAvatar] 分开，是因为**默认态的意义不同**：她的默认是"她还不知道长什么样"，
 * 我的默认是"这是我"。给她画雪晶、给我画雪晶，会让人分不清哪边是谁 ——
 * 聊天页里左右两个头像长一样，是最糟的情况。
 *
 * @param fallbackText 无图时显示的字，默认「我」
 */
@Composable
fun UserAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    path: String? = null,
    fallbackText: String = "我",
) {
    /**
     * ⚠️ **首帧同步取图**（v0.59.0）。
     *
     * 之前这里是 `produceState(initialValue = null)` —— 首帧必然是兜底文字、
     * 下一帧才出图：用户看到的就是**"从默认头像跳一下变成自定义头像"**，
     * 而且每次切页重组都重来一遍。
     *
     * 现在：
     * 1. 先查进程内内存缓存（`ImageStore.cached`，纯内存、同步）
     * 2. 没命中就**同步**读一次 —— 头像文件 ≤512px，解码毫秒级，
     *    换来"首帧就是对的图"；读到的图会进缓存，之后全程走第 1 步
     * 3. 解码期间**保留上一张**（不退回兜底文字），路径变了也不闪
     *
     * 为什么敢在主线程读：这是**头像**，不是相册大图 —— 文件保存时就压到 ≤512px。
     * 背景图那种尺寸不该这么干（那边有 `loadThumb` 走采样解码）。
     */
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank()) {
            shown = null
            return@LaunchedEffect
        }
        ImageStore.cached(path)?.let {
            shown = it.asImageBitmap()
            return@LaunchedEffect
        }
        val bmp = ImageStore.load(path)          // 同步；小图，毫秒级
        if (bmp != null) shown = bmp.asImageBitmap()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = shown
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackText.take(1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
