package com.wangzan101.mika.settings

import android.content.Context
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 文件与计算环境(容器)的网络代理,形如 `http://[用户:密码@]主机:端口`;空串表示不用,直连。
 * 地址里可能带账号密码,所以和服务商的 Key 一样经 [KeyCipher] 加密后单独存一个文件,不进明文的偏好设置。
 * 只给容器里的命令用,而且只在允许容器联网时才给(见 `AndroidSandbox`);Mika 自己调模型不走它。
 */
class ContainerProxyStore(private val dir: File, private val cipher: KeyCipher) {
    private val file = File(dir, "container-proxy.bin")
    @Volatile private var cached: String? = null

    /** 存着的代理;没有、或解不开(换过机、密钥没了)时为空串。 */
    @Synchronized fun get(): String {
        cached?.let { return it }
        val stored = runCatching { if (file.isFile) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) else "" }.getOrDefault("")
        return stored.also { cached = it }
    }

    /** 存下 [proxy](去掉两头空白);空的就删掉文件。调用方先用 [valid] 核对过。 */
    @Synchronized fun set(proxy: String) {
        val v = proxy.trim()
        require(valid(v)) { "not a proxy address" }
        if (v.isEmpty()) {
            file.delete()
        } else {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(cipher.encrypt(v.toByteArray(Charsets.UTF_8)))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        cached = v
    }

    companion object {
        private val SCHEMES = setOf("http", "https", "socks5", "socks5h")

        /** 空串(不用),或带协议、主机、端口的代理地址(http、https、socks5、socks5h),后面不带路径。 */
        fun valid(proxy: String): Boolean {
            val v = proxy.trim()
            if (v.isEmpty()) return true
            if (v.any { it.isWhitespace() }) return false
            val u = runCatching { URI(v) }.getOrNull() ?: return false
            return u.scheme?.lowercase() in SCHEMES && !u.host.isNullOrEmpty() && u.port in 1..65535 &&
                (u.rawPath.isNullOrEmpty() || u.rawPath == "/") && u.rawQuery == null && u.rawFragment == null
        }

        /** 给人看的写法:不带账号密码(那一行在设置页上随时看得到)。 */
        fun display(proxy: String): String {
            val u = runCatching { URI(proxy.trim()) }.getOrNull() ?: return ""
            if (u.host == null) return ""
            return "${u.scheme}://${u.host}:${u.port}"
        }

        @Volatile private var instance: ContainerProxyStore? = null

        fun get(context: Context): ContainerProxyStore = instance ?: synchronized(this) {
            instance ?: ContainerProxyStore(File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(KEY_ALIAS)).also { instance = it }
        }

        private const val KEY_ALIAS = "mika-container-proxy"
    }
}
