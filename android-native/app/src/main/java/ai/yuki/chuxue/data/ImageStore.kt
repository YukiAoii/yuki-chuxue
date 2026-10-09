package ai.yuki.chuxue.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 用户选的图片往哪儿放 —— 头像与聊天背景共用这一套。
 *
 * ## 为什么复制进应用私有目录，而不是记住相册的 Uri
 * 相册 Uri 的读取权限是**临时的**（随这次授权结束而失效），也可能指向一个之后
 * 被删除或移动的文件。把它存进数据库，等于把"图片还能不能显示"交给外部状态决定：
 * 用户清理一次相册，头像就变成空白，而且**看不出来是坏了**（就一个空白圆）。
 *
 * 所以选中的图一律**解码→缩放→压缩→写进 `filesDir/<dir>/`**，库里只存本地路径。
 * 代价是占一份应用存储（一张 1440px 的 JPEG 通常 100–300 KB），换来的是不会失效。
 *
 * ⚠️ 本项目**不引入图片加载库**（Coil / Glide 都不在依赖里）。这些图都是本地
 * 小文件、且一屏最多出现几张，用 `BitmapFactory` 直接解就够了 —— 为它加一个
 * 依赖（以及它带来的构建时间）不划算。
 *
 * 本类依赖 Android 运行时（`Bitmap` / `Context`），**不可 JVM 单测** ——
 * 所以这里刻意不放任何判断逻辑：路径与标识的解析在
 * [ChatBackgrounds] / `AvatarRef` 那些纯函数里，可测的部分都放在那边。
 */
object ImageStore {

    /** 人设头像 */
    const val AVATAR_DIR = "avatars"

    /** 聊天背景 */
    const val BACKGROUND_DIR = "backgrounds"

    /** 表情包（用户 2026-09-28）。与背景图同样"复制进私有目录"——
     *  相册 Uri 的授权是临时的，直接存 Uri 的话用户清一次相册图就全没了。 */
    const val EMOJI_DIR = "emoji"

    /**
     * 从相册/相机给的 Uri 读进来、压好、存到私有目录。
     *
     * @param maxSide 长边上限。头像用 512（一屏最多 44–80dp 显示，512 足够清晰且省内存），
     *                背景用 1440（要铺满屏，太小会糊）。
     * @return 落地后的**绝对路径**；失败返回 null（调用方负责提示，不要静默）
     */
    fun saveFromUri(
        context: Context,
        uri: Uri,
        dir: String,
        name: String,
        maxSide: Int,
        quality: Int = 88,
    ): String? {
        // ⚠️⚠️ 这里**必须先量尺寸、再按 inSampleSize 解**，不能直接 decodeStream 原图。
        //     手机相册里一张 48MP 的照片解成 ARGB_8888 就是 8000×6000×4 ≈ **183MB** ——
        //     而 `OutOfMemoryError` 是 **Error 不是 Exception**，项目里那些
        //     `catch (e: Exception)` / `catch (e: Throwable)` 之外的兜底都拦不住它，
        //     表现就是**点一下选图，App 直接闪退**（用户 2026-10-01 报的那个人设市场封面崩溃）。
        //
        //     同一个文件里的 [loadThumb] 早就这么做了，注释还写着"这不是优化，是别崩"——
        //     偏偏挑图这条路漏了。现在两条路用同一个范式，别再漂。
        //
        //     先把字节读进来（字节数组不是位图，几 MB 无所谓），
        //     再从同一份字节解两次：一次只读尺寸，一次按采样率真解。
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize 只能取 2 的幂：先逼近，再交给 scaleDown 精确收尾
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }.getOrNull() ?: return null

        return saveBitmap(context, decoded, dir, name, maxSide, quality)
    }

    /** 已经拿在手里的 Bitmap（相机回传的小图走这条）。 */
    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        dir: String,
        name: String,
        maxSide: Int,
        quality: Int = 88,
    ): String? = runCatching {
        // ⚠️ 整个函数体套 runCatching（它捕 **Throwable**，含 OutOfMemoryError）。
        //    原来只有最后那次 compress 有 `catch (e: Exception)`，而
        //    **scaleDown / createScaledBitmap 在 try 之外** —— 一张大图在缩放那一步 OOM，
        //    就直接冒到顶、闪退。用户报的「选封面直接崩溃」这正是其中一条路径。
        //    这里改成"任何失败都回落到 null"，让调用方去提示，而不是把 App 带走。
        val scaled = scaleDown(bitmap, maxSide)
        if (scaled !== bitmap) bitmap.recycle()

        val folder = File(context.filesDir, dir)
        if (!folder.exists() && !folder.mkdirs()) {
            scaled.recycle()
            return@runCatching null
        }
        val target = File(folder, "$name.jpg")

        try {
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            scaled.recycle()
            target.absolutePath
        } catch (e: Exception) {
            scaled.recycle()
            null
        }
    }.getOrNull()

    /**
     * **进程内**内存缓存（v0.59.0）。
     *
     * ## 它解决的是一个"看得见"的问题
     * 头像显示件原先用 `produceState(initialValue = null)` 异步解码 —— 首帧必然是
     * 兜底文字、下一帧才出图。用户看到的就是**"从默认头像跳一下变成自定义头像"**。
     * 每次切页/重组都重来一遍。
     *
     * 有了它，[cached] 能**同步**答出"这张图我知道"，首帧直接画图，不再有兜底那一帧。
     *
     * ⚠️ 只缓存**小图**（见 [CACHE_MAX_BYTES]）。头像、缩略图这类进缓存是净赚；
     *    原尺寸大图不进 —— 那会把内存吃光，而它们本来也不该反复出现在首帧上。
     */
    private val memCache = object : LruCache<String, Bitmap>(CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 缓存上限 8MB：装得下几十张头像，又不至于让 App 常驻内存明显变大。 */
    private const val CACHE_MAX_BYTES = 8 * 1024 * 1024
    private const val CACHE_ENTRIES = 64

    /**
     * **只查内存、不碰磁盘**（同步返回）。
     *
     * 给"首帧就要出图"的场景用 —— 命中就直接画，不命中再走 [load] 去解。
     */
    fun cached(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching { memCache.get(path) }.getOrNull()
    }

    /**
     * 按路径读回。
     *
     * **失败一律返回 null，不抛异常**：图片存不存在、能不能解，不该让整个页面崩掉 ——
     * 拿不到就让调用方画默认底。这与项目在**持久化**场景的纪律不同
     * （那里读失败必须显式暴露，因为下游会把"没有数据"写回去覆盖真实数据）；
     * 这里只读、不写回，所以回落到默认是安全的。
     *
     * 读到的图会进 [memCache]（只进小图），下次同路径可以走 [cached] 同步拿。
     */
    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        cached(path)?.let { return it }
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        // ⚠️ 只把**小图**放进内存缓存：大图进去会把内存吃光，
        //    而大图本来也不该出现在"必须首帧就出"的位置上。
        if (bmp.byteCount <= CACHE_MAX_BYTES) {
            runCatching { memCache.put(path, bmp) }
        }
        return bmp
    }

    /** 换图时删旧文件，免得私有目录里攒下一堆再也没人用的图。 */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { memCache.remove(path) }
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** 塞进云端快照的头像边长（v0.61.21）。256 在列表/详情那种尺寸下够清晰，体积也小。 */
    const val SYNC_AVATAR_MAX_SIDE = 256
    private const val SYNC_AVATAR_QUALITY = 80

    /**
     * 把一张头像压成**小 JPEG 字节**（用于内嵌进云端加密快照）。
     *
     * ⚠️ 刻意**不 recycle** 任何 bitmap：`load()` 返回的可能是 `memCache` 里的那一份，
     *    recycle 它会把缓存弄坏（表现为别处突然画不出图）。
     *    一次同步只调几十次、每次几百 KB，交给 GC 就好。
     *
     * @return 压好的字节；取不到图返回 null（头像没有不该阻断整次同步）
     */
    fun compressedJpegBytes(
        context: Context,
        path: String?,
        maxSide: Int = SYNC_AVATAR_MAX_SIDE,
        quality: Int = SYNC_AVATAR_QUALITY,
    ): ByteArray? {
        if (path.isNullOrBlank()) return null
        val src = load(path) ?: return null
        return runCatching {
            val scaled = scaleDown(src, maxSide)
            ByteArrayOutputStream().also { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }.toByteArray()
        }.getOrNull()
    }

    /**
     * 把字节写成**按内容命名**的文件：`av_<sha256 前 16 位>.jpg`。
     *
     * ## 这是"换设备/重新同步之后头像不再闪一下"的关键
     * 头像组件按 `path` 缓存解码结果。若每次同步都落一个新路径（时间戳命名），
     * 同一张图也会被当成新图**重新解码一次** —— 用户看到的就是"切回界面头像闪一下"。
     * 让路径由**内容**决定之后：同一张图在任何设备、任何一次同步后都是**同一个路径**，
     * 缓存直接命中，一次重解码都不会发生。
     *
     * @return 落地后的绝对路径；失败返回 null
     */
    fun writeContentAddressed(
        context: Context,
        bytes: ByteArray,
        dir: String = AVATAR_DIR,
    ): String? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val name = "av_" + digest.joinToString("") { "%02x".format(it) }.take(16) + ".jpg"
            val folder = File(context.filesDir, dir).also { it.mkdirs() }
            val file = File(folder, name)
            // 同内容的图已经在盘上 → 直接复用，不重写（少一次 IO，也让 mtime 稳定）
            if (!file.exists() || file.length() == 0L) file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 读回一张**缩略图**（长边不超过 [maxSide]，默认 200）。
     *
     * ## 为什么需要它（这不是"优化"，是"别崩"）
     * 图库里一屏可能摆十几张背景图。背景图长边 1440px，直接 `decodeFile` 一张就是
     * 1440×1440×4 ≈ **8 MB** —— 十几张就是 100 MB 级，真机上很容易 OOM。
     * 用 `inSampleSize` 在**解码阶段**就缩下来，一张缩略图只有几百 KB。
     *
     * `inSampleSize` 只能取 2 的幂，所以先按 2 的幂逼近，再用 [scaleDown] 精确收尾。
     */
    fun loadThumb(path: String?, maxSide: Int = 200): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        // 第一遍只读尺寸，不分配像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val decoded = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }.getOrNull() ?: return null

        val scaled = scaleDown(decoded, maxSide)
        // scaleDown 返回新对象时，旧的那张必须回收 —— 否则图库里翻几屏就把内存漏光
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * 列出某个目录里**已经存下来的**图片，最新的在前。
     *
     * ## 为什么需要它
     * 「聊天背景」此前只能存**一张**（换图就把上一张删了）。真正缺的是**图库**：
     * 挑过的图应该留着，想换回去不必重新进相册翻。
     *
     * ⚠️ 返回**绝对路径**（与 `Session.background` 里 `file:<路径>` 的后半段同形）。
     * 目录不存在就返回空表 —— 读不到图不该让页面打不开。
     */
    fun list(context: Context, dir: String): List<String> {
        val folder = File(context.filesDir, dir)
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            // 文件名带时间戳、mtime 也是挑图那一刻 —— 两种排序等价，用 mtime 更稳
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    /** 长边缩到不超过 [maxSide]。已经够小就原样返回（此时调用方**不能** recycle 它）。 */
    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide || longest <= 0) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
