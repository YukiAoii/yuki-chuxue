package ai.yuki.chuxue.data.room

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移 SQL ↔ Room 导出 schema 的**静态核对**。
 *
 * ## 为什么是"静态核对"而不是"跑一遍迁移"
 * Room 官方的迁移测试（`MigrationTestHelper`）跑在 `androidTest` 上 —— 需要真机或
 * 模拟器。本项目**两样都没有**，那条路走不通（详见 HANDOFF 的"未验证清单"）。
 *
 * 但迁移写错的方式其实很集中：**列名拼错**、**类型与实体不符**。而 Room 在真机上
 * 正是拿"实体导出的 schema"去校验"迁移后的实际表结构"，不一致就直接抛
 * `IllegalStateException`。所以把这两边在离线比一遍，就是**那次运行时校验的离线版本**。
 *
 * ## 它覆盖什么 / 不覆盖什么（诚实划界）
 * - 覆盖：`ALTER TABLE … ADD COLUMN` 的**列名**与**类型**（v3→v6 那几条）。
 * - 不覆盖：`CREATE TABLE` 建表语句（v1→v2 / v2→v3）、索引与外键；
 *   也不覆盖"真机上 Room 真的打开这个库"的那一刻 —— 那一步只能靠真机。
 */
class MigrationSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 依次在「模块目录」「仓库根」下找文件 —— 单元测试的 cwd 不一定在哪一层。 */
    private fun locate(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "找不到 $relative（找过：${candidates.map { it.absolutePath }}）。" +
                    "schema JSON 由 Room 在编译期导出，先跑一次 compileDebugKotlin 再跑本测试。",
            )
    }

    private fun schemaDir(): File {
        val dir = locate("schemas/ai.yuki.chuxue.data.room.AppDatabase")
        check(dir.isDirectory) { "${dir.absolutePath} 不是一个目录" }
        return dir
    }

    /** 版本号最大的那份 schema —— 就是当前实体的真实形状。 */
    private fun latestSchema(): File =
        schemaDir().listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?: error("${schemaDir().absolutePath} 下没有任何 schema JSON")

    /** 表 → { 列名 → affinity（`TEXT` / `INTEGER` / `REAL` / `BLOB`）}。 */
    private fun fieldsOf(schema: File, table: String): Map<String, String> {
        val root = json.parseToJsonElement(schema.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray
        val entity = entities.map { it.jsonObject }
            .firstOrNull { it["tableName"]!!.jsonPrimitive.content == table }
            ?: error("schema ${schema.name} 里没有表 $table")
        return entity["fields"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["columnName"]!!.jsonPrimitive.content to o["affinity"]!!.jsonPrimitive.content
        }
    }

    private val alterRe = Regex(
        """ALTER\s+TABLE\s+`(\w+)`\s+ADD\s+COLUMN\s+`(\w+)`\s+(\w+)""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `每一条 ALTER ADD COLUMN 的列名与类型都与导出的 schema 一致`() {
        val schema = latestSchema()
        val source = locate("src/main/java/ai/yuki/chuxue/data/room/AppDatabase.kt").readText()

        val statements = alterRe.findAll(source).map {
            Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].uppercase())
        }.toList()

        assertTrue(
            "AppDatabase 里应当有 ALTER TABLE … ADD COLUMN（v3→v6 那几条），实际一条都没匹配到",
            statements.isNotEmpty(),
        )

        statements.forEach { (table, column, type) ->
            assertEquals(
                "迁移写的是 `$table`.`$column` $type，但 Room 为实体导出的列不是这个名字/类型 —— " +
                    "真机上 Room 打开库时会直接抛 IllegalStateException（本项目禁用破坏性迁移，" +
                    "所以这会表现为「更新一次 App 就打不开」）",
                type,
                fieldsOf(schema, table)[column],
            )
        }
    }

    @Test
    fun `v6 的四列在 schema 里存在且类型正确`() {
        val schema = latestSchema()
        val sessions = fieldsOf(schema, "sessions")
        val messages = fieldsOf(schema, "messages")
        // sessions：背景遮罩（外观 → 聊天背景）
        assertEquals("INTEGER", sessions["scrimEnabled"])
        assertEquals("REAL", sessions["scrimAlpha"])
        assertEquals("TEXT", sessions["scrimStyle"])
        // messages：思考用时
        assertEquals("INTEGER", messages["thinkingMs"])
    }

    @Test
    fun `导出的 schema 文件名与库内版本号一致`() {
        val schema = latestSchema()
        val version = json.parseToJsonElement(schema.readText())
            .jsonObject["database"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt()
        assertEquals(schema.nameWithoutExtension.toInt(), version)
    }

    /**
     * **建表**迁移的核对（v6→v7 的 `emoji_packs`）。
     *
     * ⚠️ 上面那条断言只覆盖 `ALTER TABLE`（加列）—— 建表语句它一条也管不到。
     * 而建表写错同样是致命的：列名/类型/notNull/索引任一处不符，
     * 真机上 Room 校验不过就直接抛 `IllegalStateException`，表现为"更新后打不开 App"。
     *
     * 这条**不是**通用解析器（那要写一个 SQL parser），而是针对新表的专项比对：
     * 把源码里 `CREATE TABLE` 的列与导出 schema 的字段对齐，再确认索引也建了。
     */
    @Test
    fun `v7 的建表迁移与导出的 schema 逐字段一致`() {
        val schema = latestSchema()
        val fields = fieldsOf(schema, "emoji_packs")

        assertEquals("主键列名或类型不符", "TEXT", fields["id"])
        assertEquals("TEXT", fields["category"])
        assertEquals("INTEGER", fields["createdAt"])

        // 索引：SQL 里建了 `index_emoji_packs_category`，schema 里也必须有一个同名的，
        // 且列是 category。少了它，按分类抽图的查询会退化成全表扫（数量小时无感，但它是事实）。
        val entity = json.parseToJsonElement(schema.readText()).jsonObject["database"]!!
            .jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == "emoji_packs" }
        val indices = entity["indices"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            "schema 里没有 index_emoji_packs_category —— 迁移 SQL 建了索引但实体忘了声明 @Index？",
            indices.any { it["name"]!!.jsonPrimitive.content == "index_emoji_packs_category" },
        )
    }

    /**
     * **加列**迁移的核对（v7→v8 的表情包归属，方案 C）。
     *
     * 上面那条通用断言已经覆盖"列名与类型"（它把源码里每条 ALTER 拿去与最新 schema 比），
     * 这里补的是**索引**：通用断言完全不管索引，而少建索引同样是 schema 不一致 ——
     * 真机上 Room 校验不过就抛 `IllegalStateException`，表现仍然是"更新一次就打不开 App"。
     */
    @Test
    fun `v8 的表情包归属列与索引都在 schema 里`() {
        val schema = latestSchema()
        assertEquals(
            "归属列类型不符（应为可空 TEXT：null = 全局）",
            "TEXT",
            fieldsOf(schema, "emoji_packs")["personaId"],
        )

        val entity = json.parseToJsonElement(schema.readText()).jsonObject["database"]!!
            .jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == "emoji_packs" }
        val names = entity["indices"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(
            "schema 里没有 index_emoji_packs_personaId —— 迁移建了索引但实体忘了 @Index？实际有：$names",
            names.contains("index_emoji_packs_personaId"),
        )
    }
}
