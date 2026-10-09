package ai.yuki.chuxue.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 人设同步的**端到端加密**（v0.52.0 方案 B）。
 *
 * ## 密钥只由密码派生 —— 从不离开设备
 * 服务端只见到一团 hex 密文；换设备时用户**输入密码**即可派生出同一把密钥。
 * 这是"服务端也看不到人设"的全部代价与收益：
 *   · 收益：拖库、内鬼、运维都拿不到任何人设；
 *   · 代价：**改密码会让旧密文解不开**（密钥变了）—— 届时重新上传一次即可。
 *
 * ## 参数
 * - PBKDF2-HMAC-SHA256，120000 轮；盐 = `"yuki-persona-v1:" + uid`（uid 稳定，不必额外存盐）
 * - AES-256-GCM，96 位随机 nonce，认证标签 128 位（完整性由 GCM 自带）
 * - 输出编码用 **hex** 而不是 Base64：不依赖任何平台 API（`android.util.Base64` 在
 *   JVM 单测里不可用），于是"真机行为"与"单测行为"跑的是同一份代码。
 *
 * ## ⚠️ 所有函数都**不抛异常**
 * 加密失败返回 null；解密失败（密钥不对 / 密文损坏 / 不是 hex）也返回 null。
 * 调用方据 null 决定"跳过这次同步"，而不是让同步把 App 弄崩。
 */
object PersonaCrypto {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SALT_PREFIX = "yuki-persona-v1:"

    /** 由「密码 + uid」派生 32 字节对称密钥。 */
    fun deriveKey(password: String, uid: String): ByteArray {
        val salt = (SALT_PREFIX + uid).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    /** 加密：输出 `hex(nonce || ciphertext || tag)`；失败返回 null。 */
    fun encrypt(key: ByteArray, plaintext: String): String? = runCatching {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        toHex(nonce + ct)
    }.getOrNull()

    /** 解密：输入 `hex(nonce || ciphertext || tag)`；密钥不对 / 密文损坏一律返回 null。 */
    fun decrypt(key: ByteArray, blob: String): String? = runCatching {
        val raw = fromHex(blob)
        if (raw.size <= NONCE_BYTES) {
            null
        } else {
            val nonce = raw.copyOfRange(0, NONCE_BYTES)
            val ct = raw.copyOfRange(NONCE_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    }.getOrNull()

    private val HEX = "0123456789abcdef".toCharArray()

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex 长度必须是偶数" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "非法的 hex 字符" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
