package com.kong.Injection.core

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.ByteArrayInputStream
import java.io.File

object ApkUtils {

    /**
     * 极速读取 APK 内的文本文件 (用于优先读取 JSON 配置)
     */
    fun readStringFromApk(apkFile: File, path: String): String? {
        try {
            val zipFile = java.util.zip.ZipFile(apkFile)
            val entry = zipFile.getEntry(path) ?: return null
            val result = zipFile.getInputStream(entry).bufferedReader().use { it.readText() }
            zipFile.close()
            return result
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 纯净版模块更新引擎：只替换 JSON 并强制改名 SO (绝不删除物理文件)
     */
    fun copyApkWithNewJson(
        srcApk: File,
        newJsonStr: String,
        dstApk: File,
        oldSoName: String,
        randomSoName: String,
        isEnableSo: Boolean
    ) {
        // 1. 物理克隆文件 (瞬间完成)
        srcApk.copyTo(dstApk, overwrite = true)
        val zipFile = ZipFile(dstApk)

        // 2. 删除旧的 JSON 与签名文件
        val namesToRemove = zipFile.fileHeaders.filter {
            it.fileName == "assets/kong.json" || it.fileName.startsWith("META-INF/")
        }.map { it.fileName }

        if (namesToRemove.isNotEmpty()) {
            zipFile.removeFiles(namesToRemove)
        }

        // 3. 【修正逻辑】：无论外挂是否开启，永远保留物理文件并强制重命名防特征
        if (randomSoName != oldSoName) {
            val targetSoFileName = "lib${oldSoName}.so"
            val newSoFileName = "lib${randomSoName}.so"

            val soHeaders = zipFile.fileHeaders.filter {
                it.fileName.startsWith("lib/") && it.fileName.endsWith(targetSoFileName)
            }

            soHeaders.forEach { header ->
                val oldPath = header.fileName
                val newPath = oldPath.replace(targetSoFileName, newSoFileName)
                try {
                    zipFile.renameFile(oldPath, newPath)
                } catch (e: Exception) {}
            }
        }

        // 4. 极速追加修改后的 kong.json
        val jsonParams = ZipParameters().apply {
            fileNameInZip = "assets/kong.json"
            compressionLevel = CompressionLevel.FASTER
        }
        ByteArrayInputStream(newJsonStr.toByteArray(Charsets.UTF_8)).use { input ->
            zipFile.addStream(input, jsonParams)
        }
    }

    // ==========================================
    // 功能 2：组件工厂高级注入引擎
    // ==========================================

    fun getDexCount(apkFile: File): Int {
        val zip = ZipFile(apkFile)
        return zip.fileHeaders.count { it.fileName.startsWith("classes") && it.fileName.endsWith(".dex") }
    }

    fun injectComponentFactory(
        moduleApk: File,
        targetApk: File,
        outputApk: File
    ) {
        targetApk.copyTo(outputApk, overwrite = true)

        val targetZip = ZipFile(outputApk)
        val moduleZip = ZipFile(moduleApk)

        val manifestHeader = targetZip.getFileHeader("AndroidManifest.xml")
        val originalManifestBytes = targetZip.getInputStream(manifestHeader).use { it.readBytes() }

        // 扫描模块中的覆盖资源 (包括资产文件、动态改名后的 SO 库)
        val moduleOverrides = moduleZip.fileHeaders.filter {
            (it.fileName.startsWith("kong/") ||
                    it.fileName.startsWith("lib/") ||
                    it.fileName.startsWith("assets/")) && !it.isDirectory
        }
        val conflictNames = moduleOverrides.map { it.fileName }

        val finalToRemove = targetZip.fileHeaders.filter {
            it.fileName == "AndroidManifest.xml" ||
                    it.fileName.startsWith("META-INF/") ||
                    it.fileName in conflictNames
        }.map { it.fileName }

        if (finalToRemove.isNotEmpty()) {
            targetZip.removeFiles(finalToRemove)
        }

        // 注入修改过的 Manifest
        val modifiedBytes = ManifestPatcher.patchComponentFactory(originalManifestBytes)
        val manifestParams = ZipParameters().apply { fileNameInZip = "AndroidManifest.xml" }
        ByteArrayInputStream(modifiedBytes).use { inputStream ->
            targetZip.addStream(inputStream, manifestParams)
        }

        // 顺延合并 DEX
        val targetDexCount = getDexCount(targetApk)
        val moduleDexHeaders = moduleZip.fileHeaders.filter {
            it.fileName.startsWith("classes") && it.fileName.endsWith(".dex")
        }

        moduleDexHeaders.forEachIndexed { index, header ->
            val newDexName = if (targetDexCount + index == 0) "classes.dex"
            else "classes${targetDexCount + index + 1}.dex"

            val dexParams = ZipParameters().apply {
                fileNameInZip = newDexName
                compressionMethod = header.compressionMethod
                if (header.compressionMethod == CompressionMethod.STORE) {
                    entrySize = header.uncompressedSize
                }
            }
            moduleZip.getInputStream(header).use { inputStream ->
                targetZip.addStream(inputStream, dexParams)
            }
        }

        // 合并 /kong, /lib 和 /assets 资源 (完美继承 STORED 状态过 VM 壳校验)
        moduleOverrides.forEach { header ->
            val params = ZipParameters().apply {
                fileNameInZip = header.fileName
                compressionMethod = header.compressionMethod
                if (header.compressionMethod == CompressionMethod.STORE) {
                    entrySize = header.uncompressedSize
                }
            }
            moduleZip.getInputStream(header).use { inputStream ->
                targetZip.addStream(inputStream, params)
            }
        }
    }
}