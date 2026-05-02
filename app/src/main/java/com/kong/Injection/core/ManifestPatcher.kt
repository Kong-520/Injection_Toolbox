package com.kong.Injection.core

object ManifestPatcher {

    /**
     * 利用底层字节流原地替换 AXML 字符串池的内容
     * 此方案完美绕过 AXML 偏移量重计算，前提是：新字符串长度 <= 旧字符串长度
     */
    fun patchComponentFactory(manifestBytes: ByteArray): ByteArray {
        val oldFactory = "androidx.core.app.CoreComponentFactory"
        val newFactory = "com.vdl.kong520.MyComponentFactory"

        if (newFactory.length > oldFactory.length) {
            throw Exception("AXML修改失败：新包名长度(${newFactory.length})大于原包名(${oldFactory.length})，无法使用原地替换法！")
        }

        // Android 的 AXML 字符串池可能有 UTF-16LE 和 UTF-8 两种编码方式
        // 我们依次尝试两种底层编码的特征替换

        // 1. 尝试 UTF-16LE 模式替换
        var patchedBytes = patchUtf16(manifestBytes, oldFactory, newFactory)
        if (patchedBytes != null) {
            println("AXML Patch: 成功匹配并替换 UTF-16LE 编码的 AppComponentFactory！")
            return patchedBytes
        }

        // 2. 尝试 UTF-8 模式替换 (现代编译的 APK 通常是这种)
        patchedBytes = patchUtf8(manifestBytes, oldFactory, newFactory)
        if (patchedBytes != null) {
            println("AXML Patch: 成功匹配并替换 UTF-8 编码的 AppComponentFactory！")
            return patchedBytes
        }

        println("AXML Patch: [警告] 未在 Manifest 中找到 $oldFactory，文件保持原样。")
        return manifestBytes
    }

    /**
     * 处理 UTF-16LE 编码的字符串池替换
     */
    private fun patchUtf16(bytes: ByteArray, oldStr: String, newStr: String): ByteArray? {
        val oldBytes = oldStr.toByteArray(Charsets.UTF_16LE)
        val newBytes = newStr.toByteArray(Charsets.UTF_16LE)
        val result = bytes.copyOf()

        val index = findBytes(result, oldBytes)
        if (index == -1 || index < 2) return null

        // UTF-16LE AXML 字符串长度前缀是 16位 字符数
        val len1 = result[index - 2].toInt() and 0xFF
        val len2 = result[index - 1].toInt() and 0xFF
        val storedLen = len1 or (len2 shl 8)

        if (storedLen == oldStr.length) {
            // 1. 修改长度前缀
            result[index - 2] = (newStr.length and 0xFF).toByte()
            result[index - 1] = ((newStr.length shr 8) and 0xFF).toByte()

            // 2. 覆盖写入新字符串内容
            for (i in newBytes.indices) {
                result[index + i] = newBytes[i]
            }

            // 3. 核心：在紧接着的位置写入双字节结束符 \0\0，让解析器提前截断
            result[index + newBytes.size] = 0
            result[index + newBytes.size + 1] = 0
            return result
        }
        return null
    }

    /**
     * 处理 UTF-8 编码的字符串池替换
     */
    private fun patchUtf8(bytes: ByteArray, oldStr: String, newStr: String): ByteArray? {
        val oldBytes = oldStr.toByteArray(Charsets.UTF_8)
        val newBytes = newStr.toByteArray(Charsets.UTF_8)
        val result = bytes.copyOf()

        val index = findBytes(result, oldBytes)
        if (index == -1 || index < 2) return null

        // UTF-8 AXML 通常有两个长度字节 (字符数, 字节数)
        val charLen = result[index - 2].toInt() and 0xFF
        val byteLen = result[index - 1].toInt() and 0xFF

        // 仅当两个长度标识都匹配时才进行修改，防误伤
        if (charLen == oldStr.length && byteLen == oldBytes.size) {
            // 1. 修改长度前缀
            result[index - 2] = (newStr.length and 0xFF).toByte()
            result[index - 1] = (newBytes.size and 0xFF).toByte()

            // 2. 覆盖写入新字符串内容
            for (i in newBytes.indices) {
                result[index + i] = newBytes[i]
            }

            // 3. 核心：写入单字节结束符 \0
            result[index + newBytes.size] = 0
            return result
        }
        return null
    }

    /**
     * 字节数组搜索算法 (类似 String.indexOf)
     */
    private fun findBytes(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty()) return -1
        for (i in 0..source.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}