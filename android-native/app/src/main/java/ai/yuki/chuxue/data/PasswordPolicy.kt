package ai.yuki.chuxue.data

/**
 * 密码格式 —— **与后端 `password_error` 同源的一份判据**（v0.59.0）。
 *
 * ## 为什么要客户端也有一份
 * 后端才是权威（curl 绕不过去），但让用户在**打字的时候**就知道哪里不对，
 * 比提交后弹一句错误好得多。两边判据必须一致 —— 所以这里的规则是照着
 * 后端 `main.py` 的 `password_error` 逐条抄的，改动要**同时改两边**。
 *
 * ## ⚠️ 一条必须说清楚的事
 * 用户提需求时的原话是「只支持数字大小写英文和符号·不支持容易被 SQL 注入的格式」。
 * 但**本项目所有 SQL 都是参数化查询**，密码里有没有引号与 SQL 注入**毫无关系**。
 * 这条白名单的作用是**体验与一致性**（免得用户设出自己都打不出来的怪字符），
 * **不是**安全补丁 —— 别把它当成"防住了注入"，那会让人在别处放松警惕。
 *
 * ## ⚠️ 只用于「设置密码」，绝不用于登录
 * 老用户的密码可能含白名单外的字符（那是在这条规则之前设的）。
 * 登录路径若也校验格式，就会把他们挡在门外 —— 后端刻意只在注册/改密/重置三处校验。
 */
object PasswordPolicy {

    const val MIN = 8
    const val MAX = 64

    /** 允许的符号集合 —— 与后端 `PASSWORD_SYMBOLS` 一字不差。 */
    /** 用户 2026-10-02：「密码格式只支持数字和英文」—— 符号位留空。
     *  ⚠️ 只卡**设置新密码**；老密码带符号的照样能登录（登录不走这套）。 */
    const val SYMBOLS = ""

    private val ALLOWED: Set<Char> =
        ("abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789" + SYMBOLS).toSet()

    /** 人话的长度与字符要求，用于输入框下方的一行提示。 */
    const val HINT = "$MIN-$MAX 位，只能用数字和英文大小写"

    /**
     * 校验密码。合规返回 `null`，否则返回**给用户看的原因**（与后端文案一致）。
     *
     * 纯函数：不碰网络、不碰磁盘 —— 单测直接钉它。
     */
    fun errorOf(password: String): String? {
        if (password.length < MIN) return "密码至少 $MIN 位"
        if (password.length > MAX) return "密码最多 $MAX 位"
        if (password.any { it !in ALLOWED }) {
            return "密码只能用数字和英文大小写"
        }
        val kinds = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
        ).count { it }
        // 符号已不允许，"两类"只可能是：小写 / 大写 / 数字 的组合
        if (kinds < 2) return "密码里至少要有两类字符（小写字母 / 大写字母 / 数字）"
        return null
    }

    /** 输入框实时提示用：还没输够长度时**不报错**（不然一边打字一边红很烦）。 */
    fun errorWhileTyping(password: String): String? =
        if (password.isEmpty() || password.length < MIN) null else errorOf(password)
}
