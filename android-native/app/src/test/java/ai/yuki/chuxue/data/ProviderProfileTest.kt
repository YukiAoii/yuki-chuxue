package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商缓存画像（v0.51.0）。
 *
 * ## 这组用例里最重要的是**[DeepSeek 那条没变]**
 * 用户明确要求「deepseek 的**不变**」，而这是本项目最贵的教训所在
 * （v0.47.0 曾从文档外推机制改错过一次，见 项目交接记录）。
 * 所以 DeepSeek 画像里的每个数都对着 `CacheMath` 与 `SseParser` 钉死 ——
 * 谁要改它，必须先改这些断言，而不是"顺手优化一下"。
 */
class ProviderProfileTest {

    /* ─────────── DeepSeek：必须与既有行为**完全一致** ─────────── */

    @Test
    fun `DeepSeek 的缓存粒度就是 CacheMath 的实测值 —— 不是别处抄来的 64`() {
        // ⚠️ Tianshu 的 profile 表写的是 64，本项目实测是 128（7 组数据）。
        //    以本地实测为准 —— 这条断言就是那笔账。
        assertEquals(CacheMath.BLOCK, ProviderProfiles.DEEPSEEK.cacheGranularity)
        assertEquals(128, ProviderProfiles.DEEPSEEK.cacheGranularity)
    }

    @Test
    fun `DeepSeek 的命中字段仍是既有的顶层 prompt_cache_hit_tokens`() {
        assertEquals(
            CachedTokenField.DEEPSEEK_TOP_LEVEL,
            ProviderProfiles.DEEPSEEK.cachedTokenField,
        )
        assertEquals("prompt_cache_hit_tokens", ProviderProfiles.DEEPSEEK.cachedTokenField)
    }

    @Test
    fun `DeepSeek 的最小命中就是一个块 —— 实测 283 输入命中了 128`() {
        // 若有 1024 那种门槛，283 的输入不可能命中任何东西。
        assertEquals(CacheMath.BLOCK, ProviderProfiles.DEEPSEEK.minCacheTokens)
    }

    @Test
    fun `DeepSeek 走隐式前缀缓存，不发任何额外字段`() {
        assertEquals(CacheKind.IMPLICIT_PREFIX, ProviderProfiles.DEEPSEEK.cacheKind)
        assertTrue(ProviderProfiles.DEEPSEEK.hasPrefixCache)
        assertTrue(ProviderProfiles.DEEPSEEK.reportsCachedTokens)
    }

    /* ─────────── 按 baseUrl 判定 ─────────── */

    @Test
    fun `官方 DeepSeek 地址判为 DeepSeek`() {
        assertSame(ProviderProfiles.DEEPSEEK, ProviderProfiles.resolve("https://api.deepseek.com/v1"))
    }

    @Test
    fun `判定不看大小写`() {
        assertSame(ProviderProfiles.DEEPSEEK, ProviderProfiles.resolve("https://API.DeepSeek.COM/v1"))
    }

    @Test
    fun `常见第三方按档位分开判 —— 不再一律算成同一档`() {
        // ⚠️ v0.51.0 订正：这张表原先只有一行（全都判成 OPENAI_COMPAT），
        //    读了参考实现的 `PROFILES` 才发现档位是**四种**，而档位决定界面怎么说话
        //（"不做缓存" vs "没报用量" vs "需要显式断点"）。分类依据：参考实现 + 官方文档，
        //    本项目**未实测**（每条都带 measured=false）。
        listOf(
            "https://api.openai.com/v1",
            "https://api.siliconflow.cn/v1",
            "https://ark.cn-beijing.volces.com/api/v3",            // 豆包
            "https://openrouter.ai/api/v1",
        ).forEach {
            assertSame("部分前缀缓存档：$it", ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.resolve(it))
        }
        listOf(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",   // 通义（要显式断点）
            "https://generativelanguage.googleapis.com/v1beta",    // Google
        ).forEach {
            assertSame("显式断点档：$it", ProviderProfiles.EXPLICIT_ONLY, ProviderProfiles.resolve(it))
        }
        listOf(
            "https://open.bigmodel.cn/api/paas/v4",                // 智谱（隐式精确前缀）
        ).forEach {
            assertSame("隐式精确前缀档：$it", ProviderProfiles.EXACT_PREFIX_THIRD, ProviderProfiles.resolve(it))
        }
        listOf(
            "https://api.moonshot.cn/v1",                          // Kimi
            "https://api.minimax.chat/v1",
        ).forEach {
            assertSame("无前缀缓存档：$it", ProviderProfiles.NO_PREFIX_CACHE, ProviderProfiles.resolve(it))
        }
    }

    @Test
    fun `认不出来的地址走保守档 —— 宁可少报也不谎报`() {
        assertSame(ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.resolve("https://my-relay.example.com/v1"))
    }

    @Test
    fun `中转地址里带 deepseek 字样也不会被误判 —— provider 看域名，不看路径`() {
        // ⚠️ **这条是最初的实现真的做错了、被它逼出来的**：
        //    第一版对整条 URL 做 `contains("deepseek")`，于是这条路被判成官方档。
        //    正确判据是**主机名** —— 路径里写什么跟服务端是谁无关。
        assertSame(ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.resolve("https://relay.example.com/deepseek/v1"))
    }

    @Test
    fun `没有 scheme 的地址也能判定（用户可能只填域名）`() {
        // 这条测的是**解析**（没有 scheme 也要能取出主机名），不是分档本身，
        // 所以两边各取一个仍然归在对应档的域名。
        assertSame(ProviderProfiles.DEEPSEEK, ProviderProfiles.resolve("api.deepseek.com/v1"))
        assertSame(ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.resolve("api.siliconflow.cn/v1"))
    }

    @Test
    fun `域名里带 openai 字样的中转判为兼容档 —— 与上面同一条道理`() {
        // 这里没有"官方 openai"可区分，但至少证明判据落在域名段上
        assertSame(ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.resolve("https://my-openai-proxy.example.com/v1"))
    }

    @Test
    fun `host 判定不吃端口与查询串`() {
        assertSame(ProviderProfiles.DEEPSEEK, ProviderProfiles.resolve("https://api.deepseek.com:443/v1?x=1"))
    }

    /* ─────────── OpenAI 兼容档的字段名 ─────────── */

    @Test
    fun `OpenAI 档读的是嵌套的 cached_tokens，不是 DeepSeek 那个顶层字段`() {
        assertEquals(
            "prompt_tokens_details.cached_tokens",
            ProviderProfiles.OPENAI_COMPAT.cachedTokenField,
        )
        assertTrue(ProviderProfiles.OPENAI_COMPAT.cachedTokenField != ProviderProfiles.DEEPSEEK.cachedTokenField)
    }

    @Test
    fun `OpenAI 档的最小命中是保守的 1024`() {
        // ⚠️ 这个数**没有实测依据**（抄自 Tianshu 的配置表）。
        //    断言它的目的是"别顺手改成 0" —— 改之前先拿真 key 打探针。
        assertEquals(1024, ProviderProfiles.OPENAI_COMPAT.minCacheTokens)
    }

    /* ─────────── 不做前缀缓存的服务商 ─────────── */

    @Test
    fun `不报缓存的档要能表达「这家没有缓存」`() {
        // v0.51.0：原先这一档叫 UNKNOWN（隐式前缀 + 字段为空）；读了参考实现的
        // 画像表之后拆成两个 —— EXPLICIT_ONLY（要断点才有缓存）与 NO_PREFIX_CACHE
        // （压根没有）。两者的 `reportsCachedTokens` 都为假。
        assertFalse(ProviderProfiles.NO_PREFIX_CACHE.reportsCachedTokens)
        assertFalse(ProviderProfiles.NO_PREFIX_CACHE.hasPrefixCache)
        assertFalse(ProviderProfiles.EXPLICIT_ONLY.reportsCachedTokens)
        assertFalse(ProviderProfiles.EXPLICIT_ONLY.hasPrefixCache)
    }

    /* ─────────── 与分组对接 ─────────── */

    @Test
    fun `按分组取画像`() {
        val ds = ProviderGroup("a", "官方", "https://api.deepseek.com/v1", "sk-1")
        assertSame(ProviderProfiles.DEEPSEEK, ProviderProfiles.forGroup(ds))

        val relay = ProviderGroup("b", "中转", "https://relay.example.com/v1", "sk-2")
        assertSame(ProviderProfiles.OPENAI_COMPAT, ProviderProfiles.forGroup(relay))
    }

    /* ══════════ v0.51.0：能力位（读哪个命中字段 / 有没有余额接口） ══════════ */

    @Test
    fun `DeepSeek 的命中与未命中都读顶层字段 —— 与既有行为一致`() {
        val stats = CacheStats(hitTokens = 1024, missTokens = 512, inputTokens = 1536)
        assertEquals(1024, ProviderProfiles.DEEPSEEK.hitTokensOf(stats))
        assertEquals(512, ProviderProfiles.DEEPSEEK.missTokensOf(stats))
    }

    @Test
    fun `OpenAI 系读嵌套命中，未命中用总量减出来`() {
        // OpenAI 语义：`prompt_tokens` 是**含缓存**的总输入
        val stats = CacheStats(
            hitTokens = 0,
            missTokens = 0,
            inputTokens = 1536,
            nestedHitTokens = 1024,
        )
        assertEquals(1024, ProviderProfiles.OPENAI_COMPAT.hitTokensOf(stats))
        assertEquals(512, ProviderProfiles.OPENAI_COMPAT.missTokensOf(stats))
    }

    @Test
    fun `这家没报命中字段时返回 null —— 不是 0`() {
        // ⚠️ 这条守的是"别把没报显示成 0"。0 会让用户以为缓存废了，
        //    然后去改人设、改历史 —— 而那些动作**真的**会把前缀弄断（越查越糟）。
        val stats = CacheStats(hitTokens = 0, missTokens = 0, inputTokens = 900)
        assertNull(ProviderProfiles.OPENAI_COMPAT.hitTokensOf(stats))
        assertNull(ProviderProfiles.OPENAI_COMPAT.missTokensOf(stats))
    }

    @Test
    fun `余额接口只有 DeepSeek 有`() {
        assertEquals("user/balance", ProviderProfiles.DEEPSEEK.balancePath)
        assertNull(ProviderProfiles.resolve("https://api.openai.com/v1").balancePath)
        // 中转也一样：域名认不出 DeepSeek 就不该去撞 `/user/balance`
        assertNull(ProviderProfiles.resolve("https://relay.example.com/v1").balancePath)
    }

    /* ══════════ v0.51.0 订正：窗口归模型；缓存分四档（取自参考实现） ══════════ */

    @Test
    fun `窗口不在服务商画像上 —— 它必须按模型算`() {
        // ⚠️ 参考实现的教训（`_refs/Tianshu/src/api/provider-profile.ts`）：
        //    静默回退 128K 会让 1M 模型继承过早的压缩档。所以窗口只认模型名，
        //    画像里**刻意没有**这个字段（我第一版把它放在画像上，是放错了层）。
        assertEquals(1_000_000, ContextCompress.contextLimit("deepseek-v4-1m"))
        assertEquals(ContextCompress.DEFAULT_CONTEXT_LIMIT, ContextCompress.contextLimit("gpt-4o"))
    }

    @Test
    fun `认不出的模型必须能被界面标成"按默认估算"`() {
        assertTrue(ContextCompress.contextLimitKnown("deepseek-chat"))
        assertTrue(ContextCompress.contextLimitKnown("whatever-long"))
        assertTrue("认不出来的模型不能假装知道窗口", !ContextCompress.contextLimitKnown("gpt-4o"))
    }

    @Test
    fun `缓存分四档 —— 非 DeepSeek 不再一律算成"隐式精确匹配"`() {
        assertEquals(CacheKind.IMPLICIT_PREFIX, ProviderProfiles.DEEPSEEK.cacheKind)
        // openai 系是 partial-prefix（只认部分前缀、有起步门槛），与 DeepSeek 不同档
        assertEquals(
            CacheKind.PARTIAL_PREFIX,
            ProviderProfiles.resolve("https://api.openai.com/v1").cacheKind,
        )
        // 通义官方端点要显式断点 —— 本项目不发 cache_control，所以等于现在拿不到缓存
        val dashscope = ProviderProfiles.resolve("https://dashscope.aliyuncs.com/compatible-mode/v1")
        assertEquals(CacheKind.EXPLICIT_BREAKPOINT, dashscope.cacheKind)
        assertTrue("需要显式断点的档不该被算成有缓存可用", !dashscope.hasPrefixCache)
        // kimi / miniMax 这类压根不做前缀缓存
        assertEquals(CacheKind.NONE, ProviderProfiles.resolve("https://api.moonshot.cn/v1").cacheKind)
        assertTrue(!ProviderProfiles.resolve("https://api.minimax.chat/v1").hasPrefixCache)
    }

    @Test
    fun `只有 DeepSeek 那行标实测，其余一律未实测`() {
        assertTrue(ProviderProfiles.DEEPSEEK.measured)
        assertTrue(!ProviderProfiles.OPENAI_COMPAT.measured)
        assertTrue(!ProviderProfiles.EXACT_PREFIX_THIRD.measured)
        assertTrue(!ProviderProfiles.EXPLICIT_ONLY.measured)
    }

    @Test
    fun `一段对话的缓存读数 —— 三种「没有数字」必须分开说`() {
        // ① 还没聊过
        assertTrue(
            ProviderProfiles.DEEPSEEK.cacheLineFor(chat()).contains("尚无请求"),
        )
        // ② 聊过但一条读数都没记下 = 这家没报这个字段（不是 0！）
        assertTrue(
            ProviderProfiles.DEEPSEEK.cacheLineFor(chat(replied = true)).contains("未提供"),
        )
        // ③ 真读数
        assertTrue(
            ProviderProfiles.DEEPSEEK.cacheLineFor(chat(replied = true, hit = 512, miss = 512))
                .contains("命中"),
        )
        // ④ 这家压根不做前缀缓存 —— 说清"无意义"，别让用户去优化
        assertEquals(
            "该服务商不做前缀缓存",
            ProviderProfiles.NO_PREFIX_CACHE.cacheLineFor(chat(replied = true)),
        )
    }

    private fun chat(
        replied: Boolean = false,
        hit: Int = 0,
        miss: Int = 0,
    ) = Session(
        totalHit = hit,
        totalMiss = miss,
        messages = if (replied) listOf(ChatMessage("assistant", "嗯")) else emptyList(),
    )
}
