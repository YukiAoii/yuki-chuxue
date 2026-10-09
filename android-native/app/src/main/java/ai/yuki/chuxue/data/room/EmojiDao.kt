package ai.yuki.chuxue.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 表情包读写。
 *
 * ## 为什么它没有像 [MemoryDao] 那样强制带 userId / personaId
 * 记忆那两张表的隔离是**必须**的（记忆串了会"记错人"，且不报错）；
 * 而表情包是**纯本地的素材库**，不属于任何角色、也不参与任何按角色的查询 ——
 * 它是"这台设备上存了哪些图"，不是"谁记得什么"。
 *
 * ⚠️ 但**它同样不进请求体**：抽到的图只在本机显示（见 `EmojiPackEntity` 的类注释）。
 */
@Dao
interface EmojiDao {

    /** 管理界面用：按分类分组铺网格，所以按 `category` 排好、同类内新的在前。 */
    @Query("SELECT * FROM emoji_packs ORDER BY category ASC, createdAt DESC")
    fun observeAll(): Flow<List<EmojiPackEntity>>

    /** 抽图用：一次把某个分类的全部候选拿进内存再随机挑（数量级很小，不必下沉到 SQL 随机）。 */
    @Query("SELECT * FROM emoji_packs WHERE category = :category")
    suspend fun byCategory(category: String): List<EmojiPackEntity>

    /**
     * 某个人设的**专属**表情包（删人设时连带清理用）。
     *
     * ⚠️ `personaId = :personaId` 天然排除了全局那些（它们的 personaId 是 NULL）——
     * 删一个人设万不能把大家共用的图一起删了。
     */
    @Query("SELECT * FROM emoji_packs WHERE personaId = :personaId")
    suspend fun byPersona(personaId: String): List<EmojiPackEntity>

    /** 删掉某个人设的全部专属表情包（行；磁盘上的文件由调用方清）。 */
    @Query("DELETE FROM emoji_packs WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    /**
     * 全部表情包 —— **发图时**用它一次性算出"分类 → 候选图"的映射。
     *
     * 比"逐个分类查"好在：一次查询换来一次分组，而分类数是个位数。
     */
    @Query("SELECT * FROM emoji_packs")
    suspend fun all(): List<EmojiPackEntity>

    /** 分类下已有几张 —— 界面上用来显示计数、并决定"这一类的空状态"长什么样。 */
    @Query("SELECT COUNT(*) FROM emoji_packs WHERE category = :category")
    suspend fun countIn(category: String): Int

    @Upsert
    suspend fun upsert(pack: EmojiPackEntity)

    @Query("DELETE FROM emoji_packs WHERE id = :id")
    suspend fun delete(id: String)

    /** 清空（数据恢复用，与 `sessions` / `memories` 同一条纪律：只给恢复、且必须同事务写回）。 */
    @Query("DELETE FROM emoji_packs")
    suspend fun deleteAll()
}
