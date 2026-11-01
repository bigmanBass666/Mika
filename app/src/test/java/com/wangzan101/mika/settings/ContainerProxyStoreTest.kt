package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 容器的网络代理:可能带账号密码,加密存成文件、不落明文;地址先核对再存;给人看的写法不带账号密码。 */
class ContainerProxyStoreTest {
    private val dir: File = Files.createTempDirectory("proxy").toFile()

    /** 测试用的「加密」:逐字节取反,够看出文件里不是原文。 */
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { plain[it].toInt().inv().toByte() }
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun storedEncryptedAndReadBack() {
        val proxy = "http://alice:s3cret@10.0.0.2:8080"
        ContainerProxyStore(dir, cipher).set(proxy)
        val file = File(dir, "container-proxy.bin")
        assertTrue(file.isFile)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("文件里不是明文", raw.contains("s3cret") || raw.contains("alice"))
        // 新开一份(像重启之后)照样读得出来
        assertEquals(proxy, ContainerProxyStore(dir, cipher).get())
    }

    @Test fun emptyRemovesTheFile() {
        val store = ContainerProxyStore(dir, cipher)
        store.set("socks5://127.0.0.1:1080")
        store.set("  ")
        assertEquals("", store.get())
        assertFalse(File(dir, "container-proxy.bin").exists())
        assertEquals("", ContainerProxyStore(dir, cipher).get())
    }

    /** 解不开(密钥没了)当没设,不让命令带着乱码去连。 */
    @Test fun unreadableCountsAsNotSet() {
        File(dir, "container-proxy.bin").writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", ContainerProxyStore(dir, broken).get())
    }

    @Test fun onlyProxyAddressesAreAccepted() {
        for (ok in listOf("", "http://127.0.0.1:7890", "https://proxy.example.com:443", "socks5h://u:p@[::1]:1080", "HTTP://host:80/")) {
            assertTrue(ok, ContainerProxyStore.valid(ok))
        }
        for (bad in listOf("127.0.0.1:7890", "ftp://h:21", "http://host", "http://host:0", "http://host:70000", "http://h:80/path", "http://h :80", "http://h:80?x=1")) {
            assertFalse(bad, ContainerProxyStore.valid(bad))
        }
    }

    @Test fun displayLeavesOutCredentials() {
        assertEquals("http://10.0.0.2:8080", ContainerProxyStore.display("http://alice:s3cret@10.0.0.2:8080"))
        assertEquals("socks5://127.0.0.1:1080", ContainerProxyStore.display("socks5://127.0.0.1:1080"))
    }
}
