package ai.yuki.chuxue.data

/**
 * 「新建分组」第一步要选的**模型供应商**（用户 2026-10-04 提出）。
 *
 * ## 为什么要有这一步
 * 原来新建分组的第一个框是「服务地址」，而且**预填好了 `api.deepseek.com`** ——
 * 用户只要填个密钥就能跑。听起来省事，实际是把"我到底连哪一家"这个问题
 * 悄悄替他回答了：换一家服务商的人会一直用着预填的官方地址发请求，然后看着 401 发懵。
 *
 * 所以改成：**先选供应商**（选完自动带出地址），再填密钥。
 * 这也是用户要求"把预填的 api.deepseek.com 删掉、留空"的配套 ——
 * 地址不再凭空出现，而是**由用户的一次明确选择**带出来。
 *
 * ## 「其他 / 自定义」是逃生口
 * 地址会变、小众服务商永远列不完。选它则不填地址，让用户自己粘。
 * 所以这个目录**不许**做成"只能从这几个里挑"。
 *
 * ⚠️ 这里的地址是**便利默认值**，不是权威数据。服务商改了域名只能靠更新 App，
 *    而"其他 / 自定义"保证了那条路永远走得通。
 */
data class ProviderVendor(
    /** 稳定 id（存不进用户数据，只用于回填与选中态） */
    val id: String,
    /** 给用户看的名字 */
    val label: String,
    /** 选中后自动填进「服务地址」的值；空串 = 让用户自己填 */
    val baseUrl: String,
    /** 一句话说明，帮用户认出这是哪一家 */
    val hint: String,
)

object ProviderVendors {

    val ALL: List<ProviderVendor> = listOf(
        ProviderVendor(
            id = "deepseek",
            label = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com",
            hint = "官方直连，前缀缓存命中最好，也最省",
        ),
        ProviderVendor(
            id = "siliconflow",
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            hint = "聚合平台：一个密钥用多家模型",
        ),
        ProviderVendor(
            id = "dashscope",
            label = "阿里云百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            hint = "阿里云，兼容模式端点",
        ),
        ProviderVendor(
            id = "ark",
            label = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            hint = "字节跳动，豆包系列模型",
        ),
        ProviderVendor(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            hint = "需要能连出去的网",
        ),
        ProviderVendor(
            id = "custom",
            label = "其他 / 自定义",
            baseUrl = "",
            hint = "自己填服务商给的地址",
        ),
    )

    fun byId(id: String): ProviderVendor? = ALL.firstOrNull { it.id == id }
}
