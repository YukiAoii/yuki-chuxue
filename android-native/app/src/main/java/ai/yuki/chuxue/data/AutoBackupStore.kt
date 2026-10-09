package ai.yuki.chuxue.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **自动备份的落盘**（v0.54.0，取代 v0.53.0 的数据库 zip 快照）。
 *
 * ## 为什么从「打包数据库」改成「全量 JSON」
 * v0.53.0 那版把 `yuki.db` + `-wal` + `-shm` 打成 zip。它有两个绕不过去的问题：
 * 1. **导入不回来**——想恢复得关库、替换三个文件、重开，任何一步做错用户拿到的是
 *    一个打不开的 App；所以那版**只敢做备份、不敢做恢复**，等于半截功能；
 * 2. **格式绑死数据库**——用户拿到的 zip 只有本 App 认，换台设备都未必读得出。
 *
 * 而**全量 JSON** 走的是和手动导出**完全相同**的那条路（`Backup.encode`）：
 * 能读、能改、能导入回来、跨版本兼容（缺字段走默认值）、而且**不碰数据库文件**。
 * 用户要求"自动备份是全量把所有可导出的数据以 json 格式导出，并且可以导入"
 * —— 这条路正好满足，而且省掉了一整套"关库/加锁/一致性"的麻烦。
 *
 * ## 代价（说清楚）
 * JSON 比 zip 大（没压缩）、导出时要读一遍全部会话与记忆。
 * 所以：**默认关闭**（用户要求）、有**份数上限**、并保留 [AutoBackupPolicy] 的节流。
 */
object AutoBackupStore {

    /** 目录（`filesDir/auto-backups/`）。⚠️ 与 `filesDir/backup/`（导入前的撤销点）不是一回事。 */
    const val DIR = "auto-backups"

    private const val PREFIX = "auto-backup-"
    private const val EXT = ".json"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 全部备份文件，**新 → 旧**。目录不存在时返回空列表（不抛）。 */
    fun list(context: Context): List<File> =
        (dir(context).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun totalBytes(context: Context): Long = list(context).sumOf { it.length() }

    /**
     * 写一份自动备份。
     *
     * ⚠️ **先写临时文件、成功后再原子改名**（同 v0.53.0 的教训）：
     * 写一半失败留下的那份，会被 [list] 统计进份数、被用户当成可用备份 ——
     * 而它其实是个截断的 JSON（导入时会解析失败，那时才发现）。
     */
    fun create(context: Context, json: String, at: Long): File? {
        val folder = dir(context).apply { mkdirs() }
        folder.listFiles { f -> f.isFile && f.name.startsWith("tmp-") }
            ?.forEach { runCatching { it.delete() } }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        val target = File(folder, "$PREFIX$stamp$EXT")
        val tmp = File(folder, "tmp-" + target.name)
        return runCatching {
            tmp.writeText(json, Charsets.UTF_8)
            if (tmp.renameTo(target)) target else null
        }.getOrElse {
            runCatching { tmp.delete() }
            null
        }
    }

    /** 删一份（用户在软件内点删除）。返回是否真删掉了。 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /**
     * 按上限清理（**删最旧的**）。返回删掉几份。
     *
     * ⚠️ 夹取交给 [AutoBackupPolicy.excessCount]（填 0 时至少留一份）——
     * "把用户的备份全删了"是这里最不能犯的错。
     */
    fun enforceLimit(context: Context, maxFiles: Int): Int {
        val files = list(context)
        val excess = AutoBackupPolicy.excessCount(files.size, maxFiles)
        if (excess <= 0) return 0
        files.takeLast(excess).forEach { runCatching { it.delete() } }
        return excess
    }
}
