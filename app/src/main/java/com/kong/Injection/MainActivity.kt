package com.kong.Injection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

import com.kong.Injection.core.ApkUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainAppUI()
                }
            }
        }
    }
}

@Composable
fun MainAppUI() {
    val context = LocalContext.current
    var selectedTabIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(context, "请授予所有文件访问权限以修改 APK！", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("XP 模块配置") },
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, null) },
                    label = { Text("组件工厂注入") },
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTabIndex) {
                0 -> XpConfigScreen()
                1 -> ComponentFactoryInjectionScreen()
            }
        }
    }
}

class ModConfigState {
    var devId by mutableStateOf("15")
    var enableSo by mutableStateOf(true)
    var colorA by mutableStateOf("#FFFFFF")
    var colorB by mutableStateOf("#FFFFFF")
    var cleanRules by mutableStateOf("")
    var music by mutableStateOf("http://music.infinitex.icu/")
}

// ==========================================
// 页面 1：XP 模块配置 (纯 JSON 模式)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XpConfigScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { ModConfigState() }

    var displayPath by remember { mutableStateOf("未选择文件") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var logText by remember { mutableStateOf("就绪。请选择目标 APK 并配置 JSON 参数。") }
    var isProcessing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch {
            selectedFile = copyUriToWorkDir(context, it, "xp_source.apk")
            displayPath = selectedFile?.name ?: "文件读取失败"

            val jsonStr = ApkUtils.readStringFromApk(selectedFile!!, "assets/kong.json")
            if (jsonStr != null) {
                try {
                    val jsonObj = JSONObject(jsonStr)
                    config.devId = jsonObj.optString("TARGET_DEV_ID_STR", "15")
                    config.enableSo = jsonObj.optString("Im_GUI", "False") == "True"
                    config.colorA = jsonObj.optString("Watermark_Color_A", "#FFFFFF")
                    config.colorB = jsonObj.optString("Watermark_Color_B", "#FFFFFF")
                    config.cleanRules = jsonObj.optString("CLEAN_RULES", "")
                    config.music = jsonObj.optString("Music", "http://music.infinitex.icu/")
                    logText = "成功读取并解析包内 assets/kong.json 默认配置！"
                } catch (e: Exception) { logText = "解析 kong.json 失败: ${e.message}" }
            } else {
                logText = "警告：目标包内未找到 assets/kong.json！"
            }
        } }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        ElevatedCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("目标包选择", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                FileRow("模块包", displayPath) { picker.launch("*/*") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        CategorizedConfigUI(config)

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                isProcessing = true
                scope.launch {
                    try {
                        val output = File(Environment.getExternalStorageDirectory(), "Download/XP_Patched.apk")

                        logText = "1. 读取并修改 JSON 配置..."
                        val jsonStr = ApkUtils.readStringFromApk(selectedFile!!, "assets/kong.json")
                            ?: throw Exception("未找到 assets/kong.json！")

                        val jsonObj = JSONObject(jsonStr)
                        val oldSoName = jsonObj.optString("Im_GUI_so", "DuckMod")

                        val randomSoName = generateRandomName()

                        jsonObj.put("TARGET_DEV_ID_STR", config.devId)
                        jsonObj.put("Im_GUI", if (config.enableSo) "True" else "False")
                        jsonObj.put("Im_GUI_so", randomSoName)
                        if (config.colorA.isNotBlank()) jsonObj.put("Watermark_Color_A", config.colorA)
                        if (config.colorB.isNotBlank()) jsonObj.put("Watermark_Color_B", config.colorB)
                        if (config.cleanRules.isNotBlank()) jsonObj.put("CLEAN_RULES", config.cleanRules)
                        if (config.music.isNotBlank()) jsonObj.put("Music", config.music)

                        // 【核心修复】：强行剥除 JSONObject 自动添加的正斜杠转义符！
                        val newJsonStr = jsonObj.toString(4).replace("\\/", "/")

                        logText = "2. 极速生成防检测 APK (物理改名并重装)..."
                        ApkUtils.copyApkWithNewJson(
                            srcApk = selectedFile!!,
                            newJsonStr = newJsonStr,
                            dstApk = output,
                            isEnableSo = config.enableSo,
                            oldSoName = oldSoName,
                            randomSoName = randomSoName
                        )

                        logText = ">>> XP JSON动态配置成功！<<<\n保存至: Download/XP_Patched.apk"
                    } catch (e: Exception) {
                        logText = "错误: ${e.message}"
                        e.printStackTrace()
                    } finally { isProcessing = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing && selectedFile != null
        ) {
            Icon(Icons.Default.Create, null)
            Spacer(Modifier.width(8.dp))
            Text("生成配置后的 APK")
        }

        Spacer(modifier = Modifier.height(16.dp))
        LogDisplay(logText)
    }
}

// ==========================================
// 页面 2：组件工厂注入 (纯 JSON 模式)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentFactoryInjectionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { ModConfigState() }

    var modulePath by remember { mutableStateOf("未选择模块包") }
    var targetPath by remember { mutableStateOf("未选择目标包") }
    var moduleFile by remember { mutableStateOf<File?>(null) }
    var targetFile by remember { mutableStateOf<File?>(null) }

    var logText by remember { mutableStateOf("请配置参数并依次选择需要注入的文件。") }
    var isProcessing by remember { mutableStateOf(false) }

    val mPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch {
            moduleFile = copyUriToWorkDir(context, it, "module_raw.apk")
            modulePath = moduleFile?.name ?: ""
            val jsonStr = ApkUtils.readStringFromApk(moduleFile!!, "assets/kong.json")
            if (jsonStr != null) {
                try {
                    val jsonObj = JSONObject(jsonStr)
                    config.devId = jsonObj.optString("TARGET_DEV_ID_STR", "15")
                    config.enableSo = jsonObj.optString("Im_GUI", "False") == "True"
                    config.colorA = jsonObj.optString("Watermark_Color_A", "#FFFFFF")
                    config.colorB = jsonObj.optString("Watermark_Color_B", "#FFFFFF")
                    config.cleanRules = jsonObj.optString("CLEAN_RULES", "")
                    config.music = jsonObj.optString("Music", "http://music.infinitex.icu/")
                } catch (e: Exception) {}
            }
        } }
    }
    val tPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { targetFile = copyUriToWorkDir(context, it, "target_raw.apk"); targetPath = targetFile?.name ?: "" } }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        ElevatedCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("注入文件选择", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                FileRow("模块包", modulePath) { mPicker.launch("*/*") }
                FileRow("目标包", targetPath) { tPicker.launch("*/*") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        CategorizedConfigUI(config)

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                isProcessing = true
                scope.launch {
                    try {
                        val workDir = File(Environment.getExternalStorageDirectory(), "Download/work")
                        val modTempApk = File(workDir, "mod_temp.apk")
                        val finalApk = File(Environment.getExternalStorageDirectory(), "Download/Injection_Final.apk")

                        logText = "1. 读取并重写模块 JSON 配置..."
                        val jsonStr = ApkUtils.readStringFromApk(moduleFile!!, "assets/kong.json") ?: throw Exception("未找到 assets/kong.json！")
                        val jsonObj = JSONObject(jsonStr)

                        val oldSoName = jsonObj.optString("Im_GUI_so", "DuckMod")
                        val randomSoName = generateRandomName()

                        jsonObj.put("TARGET_DEV_ID_STR", config.devId)
                        jsonObj.put("Im_GUI", if (config.enableSo) "True" else "False")
                        jsonObj.put("Im_GUI_so", randomSoName)
                        if (config.colorA.isNotBlank()) jsonObj.put("Watermark_Color_A", config.colorA)
                        if (config.colorB.isNotBlank()) jsonObj.put("Watermark_Color_B", config.colorB)
                        if (config.cleanRules.isNotBlank()) jsonObj.put("CLEAN_RULES", config.cleanRules)
                        if (config.music.isNotBlank()) jsonObj.put("Music", config.music)

                        // 【核心修复】：强行剥除 JSONObject 自动添加的正斜杠转义符！
                        val newJsonStr = jsonObj.toString(4).replace("\\/", "/")

                        logText = "2. 物理改名 SO 库，生成防检测资源包..."
                        ApkUtils.copyApkWithNewJson(
                            srcApk = moduleFile!!,
                            newJsonStr = newJsonStr,
                            dstApk = modTempApk,
                            isEnableSo = config.enableSo,
                            oldSoName = oldSoName,
                            randomSoName = randomSoName
                        )

                        logText = "3. 完美过 VM 壳，执行目标 APK 深度注入与合并..."
                        withContext(Dispatchers.IO) {
                            ApkUtils.injectComponentFactory(modTempApk, targetFile!!, finalApk)
                        }

                        logText = ">>> 组件工厂无损极速注入成功！<<<\n保存至: Download/Injection_Final.apk\n\n(请使用 MT 管理器进行 V2+Zipalign 签名)"
                    } catch (e: Exception) {
                        logText = "注入意外中断:\n${e.message}"
                        e.printStackTrace()
                    } finally { isProcessing = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing && moduleFile != null && targetFile != null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Icon(Icons.Default.Build, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("执行极速无损注入")
        }

        Spacer(modifier = Modifier.height(16.dp))
        LogDisplay(logText)
    }
}

// ==========================================
// 全新重构的分类卡片式 UI 组件
// ==========================================
@Composable
fun CategorizedConfigUI(config: ModConfigState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1. 基础设置模块
        ConfigSectionCard(title = "基础配置", icon = Icons.Default.Info) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.devId, onValueChange = { config.devId = it },
                    label = { Text("开发者ID (DevID)") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = config.music, onValueChange = { config.music = it },
                label = { Text("背景音乐外链 (URL)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }

        // 2. 视觉 UI 设置模块
        ConfigSectionCard(title = "UI 视觉设置", icon = Icons.Default.Create) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.colorA, onValueChange = { config.colorA = it },
                    label = { Text("渐变色 A (#Hex)") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = config.colorB, onValueChange = { config.colorB = it },
                    label = { Text("渐变色 B (#Hex)") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
        }

        // 3. 高级与防检测设置模块
        ConfigSectionCard(title = "核心与防封设置", icon = Icons.Default.Lock) {
            OutlinedTextField(
                value = config.cleanRules, onValueChange = { config.cleanRules = it },
                label = { Text("杀en配置(正则表达式)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用外挂组件加载 (SO库)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = config.enableSo, onCheckedChange = { config.enableSo = it })
            }
        }
    }
}

// 统一风格的子模块卡片容器
@Composable
fun ConfigSectionCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

// ==========================================
// 辅助功能组件
// ==========================================
@Composable
fun FileRow(label: String, path: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = "$label: $path", onValueChange = {}, readOnly = true,
            modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = onClick) { Icon(Icons.Default.Search, contentDescription = "浏览") }
    }
}

@Composable
fun LogDisplay(text: String) {
    Surface(
        tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

suspend fun copyUriToWorkDir(context: Context, uri: Uri, name: String): File? = withContext(Dispatchers.IO) {
    try {
        val dir = File(Environment.getExternalStorageDirectory(), "Download/work")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
    } catch (e: Exception) { null }
}

fun generateRandomName(): String {
    val chars = ('a'..'z') + ('A'..'Z')
    return (1..7).map { chars.random() }.joinToString("")
}