package ai.yuki.chuxue.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.yuki.chuxue.data.ServerConfig
import ai.yuki.chuxue.ui.icon.YukiIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 网络图片（目前只用于「关于」页的作者头像）。
 *
 * ## 为什么不引 Coil / Glide
 * 全项目只有**这一处**需要从网上加载图片。为它引一个图片库，代价是：
 * 一条新依赖、一套新 API、以及一次 2GB 内存机器上的构建时间 —— 收益是省下这 40 行。
 * 不划算。（如果将来要加载**列表**级的网图，那才是引库的时候；那时这个文件应当被替换掉。）
 *
 * ## 磁盘缓存（v0.58.x 人设市场优化）
 * 给图片请求挂一个 **200MB 的 OkHttp 磁盘缓存**（`cacheDir/img`）。
 * 后端 `/uploads/` 的文件名是**内容哈希**且带 `immutable` 长缓存头，所以同一张图
 * 第二次进入列表/详情时**一个字节都不用重新下** —— 服务器出口只有约 300 KB/s，
 * 这是市场加载速度的直接收益。
 *
 * ⚠️ 图片请求用**自己的** OkHttp 客户端，不碰 `defaultClient`（流式，120s readTimeout）
 *    也不碰短请求那个：图片是"多、小、可缓存"，与那两者的取舍不同。
 *
 * ## 失败不报错，回落
 * 加载失败（没网、URL 失效、图不是图片）时显示 [fallback]（默认是品牌雪晶）。
 * 这是展示层，**拿不到图不该弹任何东西** —— 与"写入路径上的失败必须显式暴露"不同。
 *
 * ⚠️ 解码结果用 `produceState(初始值, url)` 缓存，**key 是 url** ——
 *    换 URL 才重新解码；列表滑出视口时 Compose 自动 dispose、协程自动取消，
 *    所以天然就是懒加载（不要在这里用无 key 的 `remember` 去缓存 url 相关的东西）。
 */
@Composable
fun RemoteImage(
    url: String?,
    /**
     * 强制成 `size × size` 的**正方形**。传 `null` = "尺寸交给 [modifier]" ——
     * 多图图集 / 全屏查看器要的是**矩形**（或填满父容器），正方形会把画面裁成方块。
     */
    size: Dp? = null,
    modifier: Modifier = Modifier,
    circular: Boolean = true,
    /**
     * 默认 [ContentScale.Crop]（缩略图/头像：填满，允许裁）。
     * 看「原图」时必须传 [ContentScale.Fit]，否则内容会被裁掉一半。
     */
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * 覆盖默认裁剪形状。默认按 [circular] 取圆形或圆角矩形；
     * 图集/全屏由外层容器负责圆角时传 [RectangleShape]，避免叠一层圆角。
     */
    imageShape: Shape? = null,
    fallback: @Composable (() -> Unit)? = null,
) {
    val shape = imageShape ?: if (circular) CircleShape else RoundedCornerShape(16.dp)
    val context = LocalContext.current

    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { download(context, url)?.asImageBitmap() }
        }
    }

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallback != null) {
            fallback()
        } else {
            Icon(
                YukiIcons.Snowflake,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size((size ?: 24.dp) * 0.52f),
            )
        }
    }
}

/** 图片专用的 OkHttp（带 200MB 磁盘缓存）。首帧拿不到 Context，所以懒建。 */
@Volatile
private var imageClient: OkHttpClient? = null

private fun clientFor(context: Context): OkHttpClient {
    imageClient?.let { return it }
    return synchronized(RemoteImageClientLock) {
        imageClient ?: OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "img"), 200L * 1024 * 1024))
            .build()
            .also { imageClient = it }
    }
}

private object RemoteImageClientLock

/**
 * 返回 Android 的 `Bitmap`（不是 Compose 的 `ImageBitmap`）——
 * `BitmapFactory.decodeStream` 给的就是它，转换交给调用处的 `asImageBitmap()`。
 * 在这里提前转会让类型对不上（第一版就栽在这上面）。
 */
/**
 * 把后端给的 url 补成**可请求的绝对地址**。
 *
 * ⚠️ 后端一律返回**相对**路径（`/uploads/xxx.png`）—— 它不硬编码自己的外网域名。
 *    而 OkHttp 的 `Request.Builder().url()` 收到相对路径会**直接抛**，
 *    所以不补这一下的话，头像与封面**永远加载不出来**（看起来像"图片挂了"，
 *    其实是地址不合法）。2026-10-01 加市场封面时踩到。
 */
private fun absolute(url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) url
    else ServerConfig.url(url)

private fun download(context: Context, url: String): android.graphics.Bitmap? = runCatching {
    val req = Request.Builder().url(absolute(url)).get().build()
    clientFor(context).newCall(req).execute().use { res ->
        if (!res.isSuccessful) return null
        res.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
    }
}.getOrNull()
