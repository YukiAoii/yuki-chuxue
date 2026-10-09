package ai.yuki.chuxue.data

import ai.yuki.chuxue.data.room.MemoryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **导出格式**（v0.54.0，用户要求五种）。
 *
 * ⚠️ `JSON` 排在第一并单独标注"可再导入" —— 五种里只有它是**往返**格式；
 * 其余四种是"拿出去给人看"的（Markdown 阅读、HTML 带样式、txt 最通用、CSV 进表格）。
 * 把它们并列而不加区分，用户会以为"导出的都能导回来"。
 */
enum class ExportFormat(
    val label: String,
    val ext: String,
    /** 给系统文件选择器用的 MIME。 */
    val mime: String,
    /** 能不能再导入回来。 */
    val roundTrippable: Boolean,
) {
    // ⚠️ v0.61.21：JSON 这一项原来是 `true` + 标签写「可再导入」，但**根本导不回来** ——
    //    它写的是 `format: yuki-chat-v1`，而全 App 唯一的导入路径 `Backup.decode`
    //    要的是 `formatVersion`，没看到就直接拒收。也就是说：用户以为有份能恢复的备份，
    //    真到要恢复的那天才发现它「不是本 App 的备份文件」。
    //    这是备份功能里最坏的失败形态（导出时看着成功、恢复时才知道没用），
    //    所以先把承诺改成实话。要真做到"能导回来"，得让它走 `Backup.encode` 那套 codec。
    JSON("JSON（自己读 / 给别人看）", "json", "application/json", false),
    MARKDOWN("Markdown（适合阅读）", "md", "text/markdown", false),
    HTML("网页（按 App 样式排版）", "html", "text/html", false),
    TEXT("纯文本", "txt", "text/plain", false),
    CSV("表格（Excel 能打开）", "csv", "text/csv", false),
    ;

    companion object {
        /** 能导入的格式（目前只有 JSON）。 */
        val importable: List<ExportFormat> get() = entries.filter { it.roundTrippable }
    }
}

/**
 * **把数据编成可导出的文本**（v0.54.0）。
 *
 * ## 它是纯函数
 * 五种格式的每一段拼接都在这里，**不碰 Context、不碰文件系统** ——
 * 于是"导出的内容对不对"能直接在 JVM 上钉死。文件写入与进度是调用方的事。
 *
 * ## ⚠️ 图片一律换成 [IMAGE_PLACEHOLDER]
 * 用户要求。理由不止是"省地方"：图片是 `filesDir` 下的本地文件，
 * 导出成 md/html 时给出绝对路径对别人毫无意义，而把 base64 塞进去会让文件大到打不开。
 * 一句 `[图片]` 至少把"这里本来有张图"这个事实保留住了。
 *
 * ## ⚠️ CSV 必须转义
 * 对话里出现逗号、引号、换行是**常态**（"他说：\"好，走吧\""）。
 * 不转义的话，Excel 打开会错列，而用户只会觉得"导出坏了"。
 */
object Exporter {

    /** 图片在导出文本里的占位（用户要求）。 */
    const val IMAGE_PLACEHOLDER = "[图片]"

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /* ─────────────── 文件名 ─────────────── */

    /**
     * 给一份导出起个**一眼能认出是什么**的文件名（用户要求）。
     *
     * 例：`与初雪的对话-20260930-1430.md`、`人设-初雪.json`、`记忆库-全部.csv`。
     *
     * ⚠️ 会剔除文件名里的非法字符（`/ \ : * ? " < > |`）——
     * 标题是用户自己起的，里面出现斜杠完全可能，而那一刻的失败会非常难懂。
     */
    fun fileNameFor(prefix: String, suffix: String, at: Long, format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        val head = sanitize(prefix)
        val tail = sanitize(suffix).take(24)
        val mid = if (tail.isBlank()) "" else "-$tail"
        return "$head$mid-$stamp.${format.ext}"
    }

    /** 去掉文件名里不能用的字符（保留中文、空格换成下划线）。 */
    fun sanitize(s: String): String =
        s.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("\\s+"), "_")
            .ifBlank { "未命名" }

    /* ─────────────── 聊天记录 ─────────────── */

    /**
     * 导出**一段会话**的聊天记录。
     *
     * @param session 要导的会话
     * @param persona 它属于谁（决定"对方"的显示名；null = 人设已删）
     */
    fun chat(session: Session, persona: Persona?, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> chatJson(session, persona)
        ExportFormat.MARKDOWN -> chatMarkdown(session, persona)
        ExportFormat.HTML -> chatHtml(session, persona)
        ExportFormat.TEXT -> chatText(session, persona)
        ExportFormat.CSV -> chatCsv(session)
    }

    private fun chatJson(s: Session, p: Persona?) = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("format", "yuki-chat-v1")
            put("title", s.title.ifBlank { "未命名对话" })
            put("personaId", s.personaId)
            put("personaName", personaName(p))
            put("createdAt", s.createdAt)
            put("updatedAt", s.updatedAt)
            putJsonArray("messages") {
                s.messages.forEach { m ->
                    add(
                        buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                            // 图片只留下"有几张"这个事实（见类注释）
                            put("images", m.images.size)
                            put("createdAt", m.createdAt)
                            m.reasoning?.let { put("reasoning", it) }
                        },
                    )
                }
            }
        },
    )

    private fun chatMarkdown(s: Session, p: Persona?) = buildString {
        val who = personaName(p)
        appendLine("# ${s.title.ifBlank { "未命名对话" }}")
        appendLine()
        appendLine("> 与 $who 的对话 · 导出于 ${human(s.exportedNow())}")
        appendLine()
        s.messages.forEach { m ->
            appendLine("**${speaker(m.role, who)}**")
            appendLine()
            appendLine(renderBody(m))
            appendLine()
        }
    }

    private fun chatText(s: Session, p: Persona?) = buildString {
        val who = personaName(p)
        appendLine("${s.title.ifBlank { "未命名对话" }}（与 $who）")
        appendLine()
        s.messages.forEach { m ->
            appendLine("${speaker(m.role, who)}：${renderBody(m)}")
        }
    }

    /**
     * HTML：**按 App 的样式排版**（用户要求）。
     *
     * ⚠️ 用内联 `<style>` 而不是外链 CSS —— 导出的文件是要被单独打开/发出去的，
     * 外链一断就变成一坨没有样式的裸文本。配色取的是 App 自己的雪白+蓝调。
     */
    private fun chatHtml(s: Session, p: Persona?) = buildString {
        val who = personaName(p)
        val title = s.title.ifBlank { "未命名对话" }
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>${esc(title)}</title>")
        appendLine("<style>")
        appendLine("body{margin:0;padding:24px 16px;background:#F5F7FA;color:#1F2937;")
        appendLine("font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;line-height:1.6}")
        appendLine(".wrap{max-width:760px;margin:0 auto}")
        appendLine("h1{font-size:20px;margin:0 0 4px}")
        appendLine(".sub{color:#6B7280;font-size:13px;margin-bottom:20px}")
        appendLine(".m{display:flex;margin:14px 0}")
        appendLine(".m.me{justify-content:flex-end}")
        appendLine(".b{max-width:78%;padding:10px 14px;border-radius:14px;white-space:pre-wrap;word-break:break-word}")
        appendLine(".me .b{background:#3B82F6;color:#fff;border-bottom-right-radius:4px}")
        appendLine(".ta .b{background:#fff;border:1px solid #E5E7EB;border-bottom-left-radius:4px}")
        appendLine(".who{font-size:12px;color:#6B7280;margin-bottom:4px}")
        appendLine(".img{color:#6B7280;font-style:italic}")
        appendLine("footer{margin-top:28px;color:#9CA3AF;font-size:12px;text-align:center}")
        appendLine("</style></head><body><div class=\"wrap\">")
        appendLine("<h1>${esc(title)}</h1>")
        appendLine("<div class=\"sub\">与 ${esc(who)} 的对话 · 导出于 ${esc(human(s.exportedNow()))}</div>")
        s.messages.forEach { m ->
            val mine = m.role == "user"
            appendLine("<div class=\"m ${if (mine) "me" else "ta"}\"><div>")
            appendLine("<div class=\"who\">${esc(speaker(m.role, who))}</div>")
            appendLine("<div class=\"b\">${bodyHtml(m)}</div>")
            appendLine("</div></div>")
        }
        appendLine("<footer>由 Yuki 初雪 导出</footer>")
        appendLine("</div></body></html>")
    }

    /** CSV：`时间,角色,内容`。**每个格子都转义**（见类注释）。 */
    private fun chatCsv(s: Session) = buildString {
        appendLine("时间,角色,内容")
        s.messages.forEach { m ->
            appendLine(
                listOf(
                    csvCell(human(m.createdAt)),
                    csvCell(m.role),
                    csvCell(renderBody(m)),
                ).joinToString(","),
            )
        }
    }

    /* ─────────────── 人设 ─────────────── */

    fun personas(list: List<Persona>, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> personaJson(list)
        ExportFormat.MARKDOWN -> list.joinToString("\n\n---\n\n") { personaBlock(it) }
        ExportFormat.HTML -> personaHtml(list)
        ExportFormat.TEXT -> list.joinToString("\n\n") { personaBlock(it) }
        ExportFormat.CSV -> buildString {
            appendLine("名称,称呼,性别,性格,角色设定,开场白,创建时间")
            list.forEach { p ->
                appendLine(
                    listOf(
                        csvCell(p.customPrompt.substringBefore('\n').ifBlank { p.id }),
                        csvCell(p.userNickname),
                        csvCell(p.userGender),
                        csvCell(p.personality.orEmpty()),
                        csvCell(p.customPrompt),
                        csvCell(p.greeting.orEmpty()),
                        csvCell(human(p.createdAt)),
                    ).joinToString(","),
                )
            }
        }
    }

    private fun personaJson(list: List<Persona>) = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("format", "yuki-personas-v1")
            putJsonArray("personas") {
                list.forEach { p ->
                    add(
                        buildJsonObject {
                            put("id", p.id)
                            put("userNickname", p.userNickname)
                            put("userGender", p.userGender)
                            put("personality", p.personality)
                            put("customPrompt", p.customPrompt)
                            put("greeting", p.greeting)
                            put("useGlobalPrefix", p.useGlobalPrefix)
                            put("xinchaoEnabled", p.xinchaoEnabled)
                            put("cloudMemoryEnabled", p.cloudMemoryEnabled)
                            // 「记忆方式」（v0.61.48）—— 与 Codec / Backup 保持同一份口径
                            put("memoryMode", p.memoryMode)
                            put("createdAt", p.createdAt)
                            put("updatedAt", p.updatedAt)
                        },
                    )
                }
            }
        },
    )

    private fun personaBlock(p: Persona) = buildString {
        appendLine("## ${p.customPrompt.substringBefore('\n').ifBlank { p.id }}")
        appendLine()
        appendLine("- 称呼你为：${p.userNickname}")
        appendLine("- 性别：${p.userGender}")
        p.personality?.takeIf { it.isNotBlank() }?.let { appendLine("- 性格：$it") }
        appendLine()
        appendLine(p.customPrompt)
        p.greeting?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("**开场白**：$it")
        }
    }

    private fun personaHtml(list: List<Persona>) = buildString {
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<title>人设导出</title><style>")
        appendLine("body{margin:0;padding:24px 16px;background:#F5F7FA;color:#1F2937;")
        appendLine("font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;line-height:1.6}")
        appendLine(".wrap{max-width:760px;margin:0 auto}")
        appendLine(".card{background:#fff;border:1px solid #E5E7EB;border-radius:14px;padding:16px;margin:14px 0}")
        appendLine("h2{margin:0 0 8px;font-size:17px} .meta{color:#6B7280;font-size:13px}")
        appendLine("pre{white-space:pre-wrap;font-family:inherit;margin:10px 0 0}")
        appendLine("</style></head><body><div class=\"wrap\">")
        appendLine("<h1>人设导出（${list.size} 个）</h1>")
        list.forEach { p ->
            appendLine("<div class=\"card\">")
            appendLine("<h2>${esc(p.customPrompt.substringBefore('\n').ifBlank { p.id })}</h2>")
            appendLine("<div class=\"meta\">称呼你为 ${esc(p.userNickname)} · ${esc(p.userGender)}</div>")
            appendLine("<pre>${esc(p.customPrompt)}</pre>")
            appendLine("</div>")
        }
        appendLine("</div></body></html>")
    }

    /* ─────────────── 记忆库 ─────────────── */

    fun memories(
        list: List<MemoryEntity>,
        personaNameOf: (String) -> String,
        format: ExportFormat,
    ): String = when (format) {
        ExportFormat.JSON -> memoryJson(list)
        ExportFormat.MARKDOWN -> memoryTable(list, personaNameOf, html = false)
        ExportFormat.HTML -> memoryHtml(list, personaNameOf)
        ExportFormat.TEXT -> list.joinToString("\n") { "- ${it.content}" }
        ExportFormat.CSV -> buildString {
            appendLine("内容,属于,作用域,分类,重要度,记录时间")
            list.forEach { m ->
                appendLine(
                    listOf(
                        csvCell(m.content),
                        csvCell(personaNameOf(m.personaId)),
                        csvCell(m.scope),
                        csvCell(m.category),
                        csvCell(m.importance.toString()),
                        csvCell(human(m.createdAt)),
                    ).joinToString(","),
                )
            }
        }
    }

    private fun memoryJson(list: List<MemoryEntity>) = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("format", "yuki-memories-v1")
            putJsonArray("memories") {
                list.forEach { m ->
                    add(
                        buildJsonObject {
                            put("id", m.id)
                            put("personaId", m.personaId)
                            put("sessionId", m.sessionId)
                            put("scope", m.scope)
                            put("content", m.content)
                            put("category", m.category)
                            put("importance", m.importance)
                            put("createdAt", m.createdAt)
                        },
                    )
                }
            }
        },
    )

    private fun memoryTable(list: List<MemoryEntity>, nameOf: (String) -> String, html: Boolean) =
        buildString {
            if (html) appendLine("# 记忆库（${list.size} 条）") else appendLine("# 记忆库（${list.size} 条）")
            appendLine()
            list.forEach { m ->
                appendLine("- [${nameOf(m.personaId)}] ${m.content}（重要度 ${m.importance}）")
            }
        }

    private fun memoryHtml(list: List<MemoryEntity>, nameOf: (String) -> String) = buildString {
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<title>记忆库</title><style>")
        appendLine("body{margin:0;padding:24px 16px;background:#F5F7FA;color:#1F2937;")
        appendLine("font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;line-height:1.6}")
        appendLine(".wrap{max-width:760px;margin:0 auto}")
        appendLine(".m{background:#fff;border:1px solid #E5E7EB;border-radius:12px;padding:12px;margin:10px 0}")
        appendLine(".who{color:#3B82F6;font-size:13px} .t{color:#9CA3AF;font-size:12px}")
        appendLine("</style></head><body><div class=\"wrap\">")
        appendLine("<h1>记忆库（${list.size} 条）</h1>")
        list.forEach { m ->
            appendLine("<div class=\"m\">")
            appendLine("<div class=\"who\">${esc(nameOf(m.personaId))}</div>")
            appendLine("<div>${esc(m.content)}</div>")
            appendLine("<div class=\"t\">${esc(human(m.createdAt))} · 重要度 ${m.importance}</div>")
            appendLine("</div>")
        }
        appendLine("</div></body></html>")
    }

    /* ─────────────── 小工具 ─────────────── */

    /** `user` → 我；`assistant` → 人设名。 */
    private fun speaker(role: String, personaName: String): String = when (role) {
        "user" -> "我"
        "assistant" -> personaName
        else -> role
    }

    private fun personaName(p: Persona?): String =
        p?.customPrompt?.substringBefore('\n')?.trim()?.takeIf { it.isNotBlank() }
            ?: "Ta"

    /** 消息正文：图片换成占位（用户要求），推理内容不导出（那是过程不是对话）。 */
    private fun renderBody(m: ChatMessage): String {
        val body = m.content.ifBlank { if (m.images.isNotEmpty()) IMAGE_PLACEHOLDER else "" }
        return if (m.images.isNotEmpty() && !body.contains(IMAGE_PLACEHOLDER)) {
            if (body.isBlank()) IMAGE_PLACEHOLDER else "$body $IMAGE_PLACEHOLDER"
        } else {
            body
        }
    }

    /** HTML 正文：转义 + 换行保留（`white-space:pre-wrap` 已在样式里）。 */
    private fun bodyHtml(m: ChatMessage): String = esc(renderBody(m))

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** CSV 格子转义：含逗号/引号/换行就加引号，内部引号翻倍。 */
    internal fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    private fun human(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))

    /** 导出的时刻。抽出来是为了 `chat*` 几个函数的签名保持干净。 */
    private fun Session.exportedNow(): Long = System.currentTimeMillis()
}
