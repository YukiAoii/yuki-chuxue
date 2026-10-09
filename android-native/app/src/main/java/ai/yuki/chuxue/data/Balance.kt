package ai.yuki.chuxue.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 账户余额（DeepSeek 官方 `GET /user/balance`，用户给的接口）。
 *
 * ## ⚠️ 它是**账号级**的信息，不是"这段对话"的
 * 看板的入口在**每段对话的对话设置**里，但余额是整账号的 ——
 * 界面上必须写清楚，否则用户会以为"这段对话还剩这么多钱"。
 *
 * ## 解析是纯函数
 * 依赖上游返回格式，而格式异常时必须**抛错或降级**，不能静默给个 0 ——
 * 一个假的"余额 0"会让用户以为钱没了。
 */
data class Balance(
    /** 当前账户是否还有余额可供调用 */
    val isAvailable: Boolean,
    /** 按币种分组；通常是 CNY 或 USD */
    val infos: List<Info>,
) {
    data class Info(
        val currency: String,
        /** 总可用余额（含赠金与充值） */
        val total: String,
        /** 未过期的赠金 */
        val granted: String,
        /** 充值余额 */
        val toppedUp: String,
    )

    /** 给界面用的一行短描述（多币种时用 `/` 连接）。 */
    val summary: String
        get() = infos.joinToString(" / ") { "${it.total} ${it.currency}" }
            .ifBlank { "—" }
}

object BalanceParser {

    /**
     * @throws IllegalArgumentException 结构不符预期时（调用方据此降级成"暂时读不到"）
     */
    fun parse(raw: String): Balance {
        val root = Json.parseToJsonElement(raw).jsonObject
        val available = root["is_available"]?.jsonPrimitive?.booleanOrNull ?: false
        val infos = root["balance_infos"]?.jsonArray?.map { el ->
            val o = el.jsonObject
            fun field(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Balance.Info(
                currency = field("currency"),
                total = field("total_balance"),
                granted = field("granted_balance"),
                toppedUp = field("topped_up_balance"),
            )
        }.orEmpty()
        return Balance(isAvailable = available, infos = infos)
    }
}
