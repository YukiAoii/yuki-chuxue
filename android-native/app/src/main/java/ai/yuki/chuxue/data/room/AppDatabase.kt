package ai.yuki.chuxue.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库（开发文档 §13.10 的**最小子集**）。
 *
 * ## 为什么只装正在用的表
 * 文档 §13 列了 13 张表（含 users / market_* / memory_vectors / cache_*）。但本版本
 * **暂缓了账号、市场、云存储**，所以这里只装**当前真实需要**的：会话 / 消息 /
 * 流式 WAL / 长期记忆。其余表随各自功能分批加入，每次升 `version` 并写好迁移 ——
 * 一次建全只会得到一个「大部分表永远空着」的库，却要一次性承担全部迁移风险，
 * 那是最糟的取舍。
 *
 * ## 与 DataStore 的分工
 * - **Room**：sessions / messages / sse_wal / memories（本文件）
 * - **DataStore**：设置、人设（`Store.kt`，完全不动）
 *
 * 会话是数据量最大、也最需要事务与级联的那部分，先把它迁到 Room 收益最高、
 * 爆炸半径最小。
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        WalEntryEntity::class,
        MemoryEntity::class,
        EmojiPackEntity::class,
        ProviderUsageEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun walDao(): WalDao
    abstract fun memoryDao(): MemoryDao
    abstract fun emojiDao(): EmojiDao
    abstract fun providerUsageDao(): ProviderUsageDao

    companion object {
        private const val DB_NAME = "yuki.db"

        /**
         * v1 → v2：新增 `sse_wal` 表（流式预写日志）。
         *
         * ⚠️ **绝不能用 `fallbackToDestructiveMigration()`** —— 那会在升级时
         * 把用户已有的全部对话删掉。开发期图省事的这一行，到了用户手机上
         * 就是「更新一次 App，聊天记录全没了」。
         *
         * 表结构必须与 Room 为 [WalEntryEntity] 生成的 schema **逐字段一致**，
         * 否则 Room 启动时会抛 `IllegalStateException` 校验失败。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sse_wal` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `eventId` TEXT,
                        `turnIndex` INTEGER NOT NULL,
                        `delta` TEXT NOT NULL,
                        `finishReason` TEXT,
                        `receivedAt` INTEGER NOT NULL,
                        `persistedToHistory` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sse_wal_sessionId` ON `sse_wal` (`sessionId`)",
                )
            }
        }

        /**
         * v2 → v3：新增 `memories` 表（长期记忆，开发文档 §7.3）。
         *
         * 同样是**手写迁移**，同样**不用 `fallbackToDestructiveMigration()`** ——
         * 那会在升级时清空用户已有的全部数据，而记忆是这个产品最不该丢的东西
         * （「她记得你」就是产品本身）。
         *
         * SQL 必须与 Room 为 [MemoryEntity] 生成的 schema **逐字段一致**
         * （列名 / 类型 / nullability / 索引名 / 外键），否则真机上 Room 启动时
         * 会抛 `IllegalStateException`。核对依据是构建后导出的
         * `app/schemas/ai.yuki.chuxue.data.room.AppDatabase/3.json`。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `personaId` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `scope` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `embedding` BLOB,
                        `createdAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_userId` ON `memories` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_personaId` ON `memories` (`personaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_sessionId` ON `memories` (`sessionId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_userId_personaId` " +
                        "ON `memories` (`userId`, `personaId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_userId_personaId_scope` " +
                        "ON `memories` (`userId`, `personaId`, `scope`)",
                )
            }
        }

        /**
         * v3 → v4：`messages` 增加 `reasoning` 列（思考过程）。
         *
         * 背景：`SseParser` 过去只读 `delta.content`，把 `reasoning_content` 整个丢掉 ——
         * 于是"开启思考模式"在界面上没有任何可见结果（**表象假开启**）。
         * 补上解析之后，思考内容还需要能**回看**，所以给它一列。
         *
         * ⚠️ 可空、无默认值：老消息的思考过程无从追溯，NULL 就是正确答案
         * （界面据此不画折叠块，而不是画一个空的）。
         *
         * 仍是**手写迁移、不用 `fallbackToDestructiveMigration()`** —— SQL 必须与
         * Room 为 [MessageEntity] 导出的 schema 逐字段一致（核对
         * `app/schemas/…/4.json`）。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
            }
        }

        /**
         * v4 → v5：`sessions` 增加会话级设置（对话设置页用）。
         *
         * 五列**一次性加完**，而不是做设置页时一列一列地加 —— 每次迁移都是真机上的
         * 风险点（Room 启动时 schema 校验不过就直接崩），而本项目禁用破坏性迁移，
         * 所以迁移次数越少越好。
         *
         * ⚠️ 全部**可空、不带 DEFAULT**：非空列要求 DEFAULT，而
         * `@ColumnInfo(defaultValue = …)` 会让 Room 的 KSP 处理器去反序列化 schema
         * bundle，与项目的 kotlinx-serialization 版本冲突（实测 AbstractMethodError，
         * 表现为编译器内部错误）。默认值统一在 SessionMapper 里落定。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `muted` INTEGER")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `pinned` INTEGER")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `background` TEXT")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `thinkingEnabled` INTEGER")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `reasoningEffort` TEXT")
            }
        }

        /**
         * v5 → v6：聊天背景遮罩（sessions 三列）+ 思考用时（messages 一列）。
         *
         * 两件事**合并成一次迁移**：迁移次数就是真机上的风险点次数
         * （Room 启动时 schema 校验不过会直接崩，而本项目禁用破坏性迁移），
         * 所以能一趟加完的列就不要分两趟。
         *
         * ## ⚠️ 这一版没能在本机验证（诚实记录）
         * 本机无 adb 设备、无模拟器 —— 迁移的**运行时行为**（Room 打开库时按
         * 实际表结构校验）跑不了。能做的是**静态核对**：
         *   · 四列全部 `ALTER TABLE … ADD COLUMN` **可空、无 DEFAULT**
         *     （非空列必须带 DEFAULT，而 `@ColumnInfo(defaultValue=…)` 会让 Room 的
         *      KSP 处理器反序列化 schema bundle，与项目的 kotlinx-serialization
         *      版本冲突 → AbstractMethodError，见 MIGRATION_4_5 的注释）；
         *   · 列名 / 类型（`INTEGER` / `REAL` / `TEXT`）与构建后导出的
         *     `app/schemas/…/6.json` 逐字段一致 —— 有 `MigrationSchemaTest` 钉住。
         *
         * 只**加列**、不动既有列、不重建表：老数据一行不丢。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 会话级背景遮罩（外观 → 聊天背景）
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `scrimEnabled` INTEGER")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `scrimAlpha` REAL")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `scrimStyle` TEXT")
                // 每条回复的生成耗时（「她想了多久」）
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `thinkingMs` INTEGER")
            }
        }

        /**
         * v6 → v7：新增 `emoji_packs` 表（表情包，用户 2026-09-28 要求）。
         *
         * ⚠️ 这是**建表**迁移（`CREATE TABLE`），不是加列 —— 与 v3 那条同型。
         * 表结构必须与 Room 为 [EmojiPackEntity] 导出的 schema **逐字段一致**
         *（列名 / 类型 / notNull / 主键 / 索引名与列序），否则真机上 Room 校验不过、
         * 直接抛 `IllegalStateException`。核对依据：构建后导出的
         * `app/schemas/ai.yuki.chuxue.data.room.AppDatabase/7.json`。
         *
         * ⚠️ 为什么 `id` 同时是主键和文件路径：路径由 `ImageStore` 生成（文件名带时间戳），
         * 天然唯一且确定性 —— 重复加同一张图走 `@Upsert` 幂等，不会插出两行。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `emoji_packs` (
                        `id` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emoji_packs_category` " +
                        "ON `emoji_packs` (`category`)",
                )
            }
        }

        /**
         * v7 → v8：表情包加**归属**列（方案 C：专属库 + 全局库回退）。
         *
         * ⚠️ 这是**加列**迁移（与 v3→v6 那几条同型），不是重建表：老图一行不丢，
         * 且统一落成 `NULL`（= 全局）—— 升级后每个人设都还看得到它们，
         * 行为与升级前完全一致。归谁留给用户在管理页里指定。
         *
         * 列类型 `TEXT`（可空）必须与 Room 为 [EmojiPackEntity] 导出的 schema 一致，
         * 核对依据：`app/schemas/ai.yuki.chuxue.data.room.AppDatabase/8.json`。
         * `MigrationSchemaTest` 会把源码里每一条 ALTER 的列名与类型拿去和最新 schema 比；
         * 对不上时真机上的表现是「更新一次 App 就打不开」（本项目禁用破坏性迁移）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `emoji_packs` ADD COLUMN `personaId` TEXT")
                // 抽图时要按归属筛，索引跟着列一起加 —— 少了它，抽图退化成全表扫
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_emoji_packs_personaId` " +
                        "ON `emoji_packs` (`personaId`)",
                )
            }
        }

        /**
         * v8 → v9：会话加**上下文压缩摘要**（用户 2026-09-29 要的压缩注入）。
         *
         * ⚠️ 加列迁移（与 v3→v6 那几条同型），不是重建表：老对话一行不丢，
         * 且统一落成 `NULL`（= 还没压缩过），行为与加这一列之前完全一致。
         *
         * ⚠️ 库里存的只是**摘要**；`messages` 表一个字都没动 ——
         * 压缩只发生在组装请求那一步（见 `ContextCompress` 的类注释）。
         * 列类型 `TEXT`（可空）要与导出的 schema 逐字段对上（`MigrationSchemaTest` 会核）。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `summary` TEXT")
            }
        }

        /**
         * v9 → v10：消息加 `sendMode` 与 `emojiPath`（用户报的三个现象的正解）。
         *
         * ⚠️ 加列迁移；老消息统一落成 `NULL` —— 渲染时 NULL 按"不拆"处理，
         * 所以升级后**老记录的样子一个像素都不会变**（这正是用户要的"不会被改变"）。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `sendMode` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `emojiPath` TEXT")
            }
        }

        /**
         * v10 → v11：会话加**摘要切点**（v0.45.6）。
         *
         * ⚠️ 加列；老数据落成 `NULL`（= 没压缩过），行为与之前一致。
         * 没有它的话，"摘要之后保留哪些消息"只能用滑动窗口取 —— 而那会让
         * **每一轮的请求前缀都不同**，缓存从窗口开头就 miss（用户报的"压完命中率上不去"）。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `summaryUpTo` INTEGER")
            }
        }

        /**
         * v11 → v12：会话加**压缩时刻的命中快照**（v0.46.1）。
         *
         * ⚠️ 加列；老数据落成 `NULL`（= 那次压缩没记快照），看板会退回"只看累计"。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `hitAtCompress` INTEGER")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `missAtCompress` INTEGER")
            }
        }

        /**
         * v12 → v13：会话加**摘要覆盖的条数**（v0.46.5）。
         *
         * ⚠️ 老数据落成 `NULL`（= 没记条数），此时退回"按时间戳"的老路径，
         * 行为与升级前一致；重新压缩一次就会写入条数。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `summaryCount` INTEGER")
            }
        }

        /**
         * v13 → v14：会话加**它自己用哪个连接分组 / 哪个模型**（v0.51.0「分组」功能）。
         *
         * 用户要求「聊天途中可以切换为选择的分组的模型，也可以切换分组然后再选择哪个模型」——
         * 这两件事必须**跟着会话**存，所以是新列，不是全局设置。
         *
         * ⚠️ 两列都**可空、不带 DEFAULT**（项目纪律，见 MIGRATION_10_11 的注释与
         * `SessionEntity.summary` 那段）：DEFAULT 会触发 Room × kotlinx-serialization
         * 的 KSP 类加载器地雷。
         *
         * ⚠️ 老数据落成 `NULL` —— 那**恰好就是正确语义**：
         * `null` = 跟着全局当前分组 / 分组里勾选的第一个模型，
         * 也就是"升级前它是怎么用的，升级后还是怎么用"。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `providerGroupId` TEXT")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `model` TEXT")
            }
        }

        /**
         * v14 → v15：新增 `provider_usage` 表（按服务商分桶的用量，v0.51.0）。
         *
         * ⚠️ 与 [MIGRATION_6_7] 同型：**建表**迁移，不动任何已有列 ——
         * 用户的会话/消息/记忆一个字节都不受影响。
         *
         * ⚠️ 为什么需要它：`sessions.totalHit/totalMiss` 是按会话累加的，
         * 不记"这一轮用的哪家"。用户可以在一段对话中途换服务商之后，
         * 那个百分比就成了**混合口径**（不同家的命中字段/粒度都不一样），
         * 谁都不对应。分桶之后看板才能说清每一家各自怎么样。
         *
         * 表结构必须与 Room 为 [ProviderUsageEntity] 生成的 schema 逐字段一致
         * （由 `MigrationSchemaTest` 兜住）；`PRIMARY KEY` 的列顺序也必须一致。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `provider_usage` (
                        `sessionId` TEXT NOT NULL,
                        `providerKey` TEXT NOT NULL,
                        `hitTokens` INTEGER NOT NULL,
                        `missTokens` INTEGER NOT NULL,
                        `inputTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `requests` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`, `providerKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v15 → v16：`messages` 加**分段气泡开关快照**（v0.61.6）。
         *
         * 用户要求「老消息保持原样」——「分段气泡」是 v0.61.4 的新功能，若不冻结在消息上，
         * 老消息会随着这个开关"突然长出几枚本来没有的气泡"（就是 [ChatMessage.sendMode]
         * 那段注释里写过的同一种错：把已经发生过的事改写了）。
         *
         * 可空、**不带 DEFAULT**（项目纪律，见 [MIGRATION_10_11] 与 [MIGRATION_13_14]）：
         * 老数据落成 `NULL` = 「这个字段出现之前的消息」= 渲染**完全保持原样**。
         * Room 的 `Boolean?` → `INTEGER`（可空）。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `splitBubbles` INTEGER")
            }
        }

        /**
         * v16 → v17：`messages` 加**历史版本**（v0.61.11）。
         *
         * 「重新生成」把旧回复推进这里，气泡下方的左右按钮可以切回去看
         *（用户要求：「只是本地留了、不进缓存，仅供复制查看」）。
         *
         * 可空、**不带 DEFAULT**（项目纪律，见 [MIGRATION_10_11]）：
         * 老数据落成 `NULL` = "没有历史版本"，与加这个字段之前的行为完全一致。
         * Room 的 `String?` → `TEXT`。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `supersededJson` TEXT")
            }
        }

        /**
         * v17 → v18：`memories` 增加 `cloudBucketId` 列（云端记忆桶 id，v0.61.38）。
         *
         * 为什么需要它：记忆上云之后，"删除"必须**两边都删** —— 而本地记忆和云端（OB）
         * 的记忆之间原本没有任何映射关系，删本地那条时**不知道云端删哪条**。
         * 上云成功时把 OB 返回的桶 id 记在这一列，删除时就能对上号。
         *
         * ⚠️ 可空、**不带 DEFAULT**（项目纪律，见 [MIGRATION_10_11] 与 [MIGRATION_13_14]）：
         *    老数据落 `NULL` = "没上过云"，删除路径遇到 NULL 就跳过云端那一步，行为与加它之前一致。
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `cloudBucketId` TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 单例。Room 的实例持有连接池，重复创建会泄漏文件句柄 ——
         * `@Volatile` + 双重检查是官方给的写法，别简化成裸 lazy。
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
