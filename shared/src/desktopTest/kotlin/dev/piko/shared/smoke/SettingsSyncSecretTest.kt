package dev.piko.shared.smoke

import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SyncCipher
import dev.piko.shared.sync.SyncedSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 机密项（MetaTube 令牌、翻译 Key）在网盘里必须是密文，换一台设备用同一个账号密码能解开；
 * 密码不符时解不开，本机的值不被远端覆盖。
 *
 * 加密用测试里的简单异或实现：这里验的是「落盘不是明文、能往返」，
 * 真正的 AES 由 JvmSyncCipher 负责，另有 ArchivePasswordSync 的用例覆盖。
 */
class SettingsSyncSecretTest {
    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }

    private class FakeCipher : SyncCipher {
        override fun newSalt() = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        override fun deriveKey(secret: String, salt: ByteArray, iterations: Int) =
            (secret + ":" + salt.joinToString(",")).encodeToByteArray()

        // 与 AES-GCM 一样能识别密钥不对：密文前带一段由密钥算出的校验，对不上就当解不开
        private fun tag(key: ByteArray) =
            java.security.MessageDigest.getInstance("SHA-256").digest(key).copyOf(8)

        override fun seal(key: ByteArray, plain: ByteArray): ByteArray {
            val body = ByteArray(plain.size) { i -> (plain[i].toInt() xor key[i % key.size].toInt()).toByte() }
            return tag(key) + body
        }

        override fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
            if (sealed.size < 8) return null
            if (!sealed.copyOfRange(0, 8).contentEquals(tag(key))) return null
            val body = sealed.copyOfRange(8, sealed.size)
            return ByteArray(body.size) { i -> (body[i].toInt() xor key[i % key.size].toInt()).toByte() }
        }
    }

    private fun device(
        server: FakePikPakServer,
        remote: RemoteSettingsStore,
        scope: kotlinx.coroutines.CoroutineScope,
        token: MutableStateFlow<String>,
        password: String,
    ) = PikoSettingsSync(
        clients = server.provider(),
        remote = remote,
        preferences = MemoryPreferences(),
        cacheStore = MemoryCacheStore(),
        scope = scope,
        enabled = MutableStateFlow(true),
        settings = listOf(SyncedSetting("token", { token }, { _, value -> token.value = value }, secret = true)),
        cipher = FakeCipher(),
        passwordOf = { password },
    )

    @Test
    fun `secret values are sealed on the remote and open on another device`() = smoke { scope ->
        val server = FakePikPakServer()
        val remote = MemoryRemote()

        val tokenA = MutableStateFlow("TOKEN-A")
        assertTrue(device(server, remote, scope, tokenA, "pw").syncNow())
        assertTrue(remote.text?.contains("TOKEN-A") != true, "明文不该出现在同步文件里：${remote.text}")

        // 另一台设备：同一个账号密码，拉下来能解开并写进本机
        val tokenB = MutableStateFlow("")
        assertTrue(device(server, remote, scope, tokenB, "pw").syncNow())
        assertEquals("TOKEN-A", tokenB.value)
    }

    @Test
    fun `a device with a wrong password keeps its own value`() = smoke { scope ->
        val server = FakePikPakServer()
        val remote = MemoryRemote()

        val tokenA = MutableStateFlow("TOKEN-A")
        assertTrue(device(server, remote, scope, tokenA, "pw").syncNow())

        // 密码变了（或文件被改过）：解不开就不采用，本机的值留着
        val tokenC = MutableStateFlow("MINE")
        assertTrue(device(server, remote, scope, tokenC, "other-password").syncNow())
        assertEquals("MINE", tokenC.value)
    }

    @Test
    fun `without a cipher the secret entry does not participate at all`() = smoke { scope ->
        val server = FakePikPakServer()
        val remote = MemoryRemote()
        val token = MutableStateFlow("TOKEN-A")
        val sync = PikoSettingsSync(
            clients = server.provider(),
            remote = remote,
            preferences = MemoryPreferences(),
            cacheStore = MemoryCacheStore(),
            scope = scope,
            enabled = MutableStateFlow(true),
            settings = listOf(SyncedSetting("token", { token }, { _, value -> token.value = value }, secret = true)),
            cipher = null,
        )
        assertTrue(sync.syncNow())
        // 没有加密器就什么都不写：宁可不同步，也不能把令牌明文放上网盘
        assertTrue(remote.text?.contains("TOKEN-A") != true)
    }
}
