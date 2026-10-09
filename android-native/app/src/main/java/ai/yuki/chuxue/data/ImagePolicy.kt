package ai.yuki.chuxue.data

/**
 * 图片使用限制与校验（依据官方文档 2「图像理解」）。
 *
 * 这些限制**不是我们的选择，是服务的硬约束**：
 * 违反它们不会得到"部分成功"，而是整个请求 400 或超时。
 * 因此必须在**发送前**拦住，给出人能看懂的原因，
 * 而不是让请求在网络上默默失败、用户只看到一句"请求失败"。
 */
object ImagePolicy {

    /** 单张图 base64 内联上限 32 MiB */
    const val MAX_SINGLE_BYTES: Long = 32L * 1024 * 1024

    /** 整个请求体上限 48 MiB */
    const val MAX_REQUEST_BYTES: Long = 48L * 1024 * 1024

    /**
     * 一次最多带几张图（用户定的上限：**多选最多 9 张**）。
     *
     * ⚠️ 与 [MAX_SINGLE_BYTES] / [MAX_REQUEST_BYTES] 不是同一类限制：
     * 那两个是**服务端的硬约束**（超了整请求 400），这个只是产品取舍 ——
     * 超了只是不许再加图，不是"发不出去"。
     */
    const val MAX_IMAGES = 9

    /** 官方支持的四种格式（按文件实际内容判断，不看扩展名） */
    private val ALLOWED_PREFIXES = listOf(
        "data:image/jpeg;base64,",
        "data:image/png;base64,",
        "data:image/gif;base64,",
        "data:image/webp;base64,",
    )

    /** 单张图是否是我们支持的格式 */
    fun isSupportedFormat(dataUrl: String): Boolean =
        ALLOWED_PREFIXES.any { dataUrl.startsWith(it) }

    /** 从 data URL 推断 MIME（供 UI 显示等用途） */
    fun mimeOf(dataUrl: String): String? {
        val prefix = ALLOWED_PREFIXES.firstOrNull { dataUrl.startsWith(it) } ?: return null
        return prefix.removePrefix("data:").removeSuffix(";base64,")
    }

    /**
     * 估算 base64 解码后的字节数。
     * base64 每 4 个字符编码 3 个字节，故 `len/4*3`。
     */
    fun estimateBytes(dataUrl: String): Long {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return dataUrl.length.toLong()
        val b64Len = (dataUrl.length - comma - 1).toLong()
        return (b64Len / 4) * 3
    }

    /**
     * 发送前校验。
     *
     * 最容易被忘掉的一条：**图片只能出现在 user 消息里**。
     * 官方明确规定 system / assistant 消息携带图片会返回 400 ——
     * 而历史里如果混进了带图的 assistant 消息（例如将来做"她发图给你"），
     * 就会在用户完全不知情的情况下让后续每一次请求都失败。
     */
    fun check(role: String, images: List<String>): Check {
        if (images.isEmpty()) return Check.Ok

        if (role != "user") {
            return Check.Rejected(
                "图片只能出现在你发送的消息里（官方限制：system / assistant 带图会返回 400）。" +
                    "当前角色是「$role」。",
            )
        }

        if (images.size > MAX_IMAGES) {
            return Check.Rejected(
                "一次最多发 $MAX_IMAGES 张图，先去掉 ${images.size - MAX_IMAGES} 张。",
            )
        }

        images.forEachIndexed { index, url ->
            if (!isSupportedFormat(url)) {
                return Check.Rejected(
                    "第 ${index + 1} 张图的格式不支持。" +
                        "只支持 JPEG / PNG / GIF / WebP，且需以 data:image/...;base64, 的内联形式提供。",
                )
            }
            val bytes = estimateBytes(url)
            if (bytes > MAX_SINGLE_BYTES) {
                return Check.Rejected(
                    "第 ${index + 1} 张图约 ${bytes / 1024 / 1024} MB，" +
                        "超过单张 32 MB 的限制。请先压缩或裁剪。",
                )
            }
        }

        val total = images.sumOf { estimateBytes(it) }
        if (total > MAX_REQUEST_BYTES) {
            return Check.Rejected(
                "图片合计约 ${total / 1024 / 1024} MB，超过单次请求 48 MB 的限制。" +
                    "请减少张数或压缩后再发。",
            )
        }

        return Check.Ok
    }

    /** 校验结果 */
    sealed interface Check {
        data object Ok : Check
        data class Rejected(val reason: String) : Check
    }
}
