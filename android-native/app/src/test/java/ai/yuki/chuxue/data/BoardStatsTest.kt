package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看板滚动统计的测试（v0.54.0）。
 *
 * ⚠️ 这组测试的存在理由是"上一版没有"：`BoardStats` 是纯逻辑（编码/解码/环形缓冲/聚合），
 * 本该在第一版就钉住，而我先只写了实现就去编译了 —— 那意味着"能被记录与读回"
 * 这句话当时**没有任何证据**。纯函数不写测试，等于把"我以为对"当成"它对"。
 *
 * 这里钉的四件事都是真机上极难发现、而坏了又很难看出来的：
 * 1. **环形缓冲只留最近 20 轮** —— 不裁的话偏好文件会一直长，最后读写成慢动作；
 * 2. **坏 JSON 必须降级为空**（不抛）—— 它跑在看板打开的路径上；
 * 3. **同名模型在不同服务商下分开计** —— 这正是用户要的"标注属于哪个分组"；
 * 4. **`hitRatio` 在无计费 token 时是 null 而不是 0** —— 0 会被读成"全没命中"。
 */
class BoardStatsTest {

    private fun turn(at: Long, hit: Int = 10, miss: Int = 90, fb: Long = 500) =
        BoardStats.TurnStat(at = at, hit = hit, miss = miss, firstByteMs = fb)

    /* ─────────── 环形缓冲 ─────────── */

    @Test
    fun `超过 20 轮时丢掉最旧的 —— 偏好文件不能无限长`() {
        var list = emptyList<BoardStats.TurnStat>()
        (1..25).forEach { list = BoardStats.appendTurn(list, turn(at = it.toLong())) }
        assertEquals(BoardStats.MAX_TURNS, list.size)
        assertEquals("留下的应该是最新的那 20 轮", 6L, list.first().at)
        assertEquals(25L, list.last().at)
    }

    @Test
    fun `不足 20 轮时原样追加`() {
        var list = emptyList<BoardStats.TurnStat>()
        repeat(3) { list = BoardStats.appendTurn(list, turn(at = it.toLong())) }
        assertEquals(3, list.size)
    }

    /* ─────────── 编解码 ─────────── */

    @Test
    fun `往返不丢字段`() {
        val src = listOf(turn(at = 111L, hit = 7, miss = 13, fb = 250L), turn(at = 222L))
        val back = BoardStats.decodeTurns(BoardStats.encodeTurns(src))
        assertEquals(src, back)
    }

    @Test
    fun `坏 JSON 与空值都降级为空列表 —— 不抛`() {
        assertTrue(BoardStats.decodeTurns(null).isEmpty())
        assertTrue(BoardStats.decodeTurns("").isEmpty())
        assertTrue(BoardStats.decodeTurns("这不是 JSON").isEmpty())
        assertTrue(BoardStats.decodeTurns("""[{"at":"x"}]""").isEmpty())
    }

    @Test
    fun `单条坏记录被跳过，其余照常读出`() {
        val raw = """[{"at":1,"hit":1,"miss":1,"fb":0},{"at":"坏"},{"at":3,"hit":2,"miss":2,"fb":9}]"""
        val list = BoardStats.decodeTurns(raw)
        assertEquals(2, list.size)
        assertEquals(1L, list[0].at)
        assertEquals(3L, list[1].at)
    }

    /* ─────────── 命中率 ─────────── */

    @Test
    fun `没有计费 token 时命中率是 null 而不是 0`() {
        // ⚠️ 0 会被界面读成"全都没命中"，那是把一个"没有数据"说成了一件坏事
        assertNull(turn(at = 1, hit = 0, miss = 0).hitRatio)
        assertEquals(1.0, turn(at = 1, hit = 10, miss = 0).hitRatio!!, 1e-9)
        assertEquals(0.0, turn(at = 1, hit = 0, miss = 10).hitRatio!!, 1e-9)
        assertEquals(0.5, turn(at = 1, hit = 5, miss = 5).hitRatio!!, 1e-9)
    }

    /* ─────────── 模型维度 ─────────── */

    @Test
    fun `同名模型在不同服务商下分开计 —— 这正是「属于哪个分组」`() {
        var map = emptyMap<String, BoardStats.ModelUsage>()
        map = BoardStats.bumpModel(map, providerKey = "https://api.deepseek.com", model = "deepseek-chat", hit = 10, miss = 5)
        map = BoardStats.bumpModel(map, providerKey = "https://proxy.example.com", model = "deepseek-chat", hit = 1, miss = 2)

        assertEquals("两个分组各一行，不能合并", 2, map.size)
        val a = map[BoardStats.modelKey("https://api.deepseek.com", "deepseek-chat")]!!
        val b = map[BoardStats.modelKey("https://proxy.example.com", "deepseek-chat")]!!
        assertEquals(15, a.billed)
        assertEquals(3, b.billed)
        assertEquals(1, a.requests)
    }

    @Test
    fun `同一个键累加而不是覆盖`() {
        var map = emptyMap<String, BoardStats.ModelUsage>()
        repeat(3) {
            map = BoardStats.bumpModel(map, providerKey = "p", model = "m", hit = 1, miss = 2)
        }
        val u = map.values.single()
        assertEquals(3, u.requests)
        assertEquals(3, u.hit)
        assertEquals(6, u.miss)
        assertEquals(9, u.billed)
    }

    @Test
    fun `模型用量往返不丢字段`() {
        val map = BoardStats.bumpModel(
            emptyMap(),
            providerKey = "https://x", model = "m1", hit = 3, miss = 4,
        )
        val back = BoardStats.decodeModels(BoardStats.encodeModels(map))
        assertEquals(map, back)
    }

    @Test
    fun `模型用量的坏 JSON 也降级为空`() {
        assertTrue(BoardStats.decodeModels(null).isEmpty())
        assertTrue(BoardStats.decodeModels("坏").isEmpty())
        assertTrue(BoardStats.decodeModels("""[{"p":"","m":""}]""").isEmpty())
    }

    @Test
    fun `模型数量超上限时丢掉最不常请求的 —— 偏好文件不能被撑爆`() {
        var map = emptyMap<String, BoardStats.ModelUsage>()
        // 前 5 个各请求 10 次
        repeat(5) { i ->
            repeat(10) { map = BoardStats.bumpModel(map, "p", "hot-$i", 1, 1) }
        }
        // 再来 60 个只请求 1 次的，把总量顶到上限之上
        repeat(60) { i -> map = BoardStats.bumpModel(map, "p", "cold-$i", 1, 1) }

        assertTrue("总量必须被压回上限内", map.size <= 60)
        assertTrue(
            "常请求的必须还在",
            map.containsKey(BoardStats.modelKey("p", "hot-0")),
        )
    }
}
