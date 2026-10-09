package ai.yuki.chuxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 人设同步（端到端加密）的纯逻辑测试。
 *
 * ⚠️ 这里钉的是**安全与判定的不变量**，不是"跑通"：
 *   · 加解密往返必须逐字节一致（否则用户换设备拿回的人设是坏的）；
 *   · **密钥不对必须解不开**（否则"端到端"名不副实）；
 *   · 密文被改一个字符也必须解不开（GCM 自带完整性，不能退化成"解出乱码"）；
 *   · 同步方向由 rev 唯一决定，四种组合各有断言。
 */
class PersonaSyncTest {

    /** 同一组密码+uid 在两次调用里必须派生出同一把密钥（换设备才能解密）。 */
    @Test
    fun `派生密钥是确定的 —— 同密码同 uid 两次结果相同`() {
        val a = PersonaCrypto.deriveKey("pw123456", "10001")
        val b = PersonaCrypto.deriveKey("pw123456", "10001")
        assertEquals(a.size, 32)
        assertTrueBytes(a, b)
    }

    /** uid 是盐的一部分：换账号必然换密钥。 */
    @Test
    fun `不同 uid 派生不同密钥`() {
        assertFalse(
            PersonaCrypto.deriveKey("pw", "10001").contentEquals(
                PersonaCrypto.deriveKey("pw", "10002"),
            ),
        )
    }

    @Test
    fun `加密再解密拿回原文 —— 含中文人设`() {
        val key = PersonaCrypto.deriveKey("pw123456", "10001")
        val plain = PersonaCodec.encode(
            listOf(
                Persona(
                    id = "p1",
                    userNickname = "阿岚",
                    userGender = "女",
                    customPrompt = "角色名称：初雪\n性格：安静、爱雪",
                    updatedAt = 1000L,
                ),
            ),
        )
        val blob = PersonaCrypto.encrypt(key, plain)
        assertNotNull(blob)
        assertEquals(plain, PersonaCrypto.decrypt(key, blob!!) ?: "NULL")
    }

    @Test
    fun `密钥不对解不开 —— 返回 null 而不是抛`() {
        val blob = PersonaCrypto.encrypt(PersonaCrypto.deriveKey("right", "10001"), "hello")!!
        val wrong = PersonaCrypto.deriveKey("wrong", "10001")
        assertNull(PersonaCrypto.decrypt(wrong, blob))
    }

    @Test
    fun `密文被篡改一个字符就解不开 —— GCM 完整性`() {
        val key = PersonaCrypto.deriveKey("pw", "10001")
        val blob = PersonaCrypto.encrypt(key, "hello")!!
        val last = blob.last()
        val tampered = blob.dropLast(1) + if (last == 'a') 'b' else 'a'
        assertNull(PersonaCrypto.decrypt(key, tampered))
    }

    @Test
    fun `非 hex 输入返回 null 而不是抛`() {
        val key = PersonaCrypto.deriveKey("pw", "10001")
        assertNull(PersonaCrypto.decrypt(key, "not-hex!!"))
        assertNull(PersonaCrypto.decrypt(key, ""))
    }

    /* ── 同步方向 ── */

    @Test
    fun `decide 两边都空不动`() {
        assertEquals(PersonaSync.Action.None, PersonaSync.decide(localRev = 0, remoteRev = 0))
    }

    @Test
    fun `decide 服务端空本地有则上传`() {
        assertEquals(PersonaSync.Action.Upload, PersonaSync.decide(localRev = 5, remoteRev = 0))
    }

    @Test
    fun `decide 本地空服务端有则下载`() {
        assertEquals(PersonaSync.Action.Download, PersonaSync.decide(localRev = 0, remoteRev = 5))
    }

    @Test
    fun `decide 都不空时谁大听谁`() {
        assertEquals(PersonaSync.Action.Download, PersonaSync.decide(localRev = 3, remoteRev = 9))
        assertEquals(PersonaSync.Action.Upload, PersonaSync.decide(localRev = 9, remoteRev = 3))
        assertEquals(PersonaSync.Action.None, PersonaSync.decide(localRev = 9, remoteRev = 9))
    }

    @Test
    fun `localRev 取集合里最大的 updatedAt`() {
        val ps = listOf(
            Persona(id = "a", updatedAt = 100L),
            Persona(id = "b", updatedAt = 300L),
            Persona(id = "c", updatedAt = 200L),
        )
        assertEquals(300L, PersonaSync.localRev(ps))
        assertEquals(0L, PersonaSync.localRev(emptyList()))
    }

    private fun assertTrueBytes(a: ByteArray, b: ByteArray) {
        assertEquals(a.toList(), b.toList())
    }
}
