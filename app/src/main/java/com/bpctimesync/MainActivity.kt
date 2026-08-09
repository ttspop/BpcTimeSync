package com.bpctimesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bpctimesync.audio.BpcAudioOutput
import com.bpctimesync.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BpcTimeSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BpcMainScreen()
                }
            }
        }
    }
}

@Composable
fun BpcTimeSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = Typography(),
        content = content
    )
}

@Composable
fun BpcMainScreen() {
    val viewModel: MainViewModel = viewModel()
    var currentScreen by remember { mutableStateOf("home") }

    val transmitState by viewModel.transmitState.collectAsState()

    // 发射中自动跳转到发射页面
    LaunchedEffect(transmitState) {
        if (transmitState == BpcAudioOutput.State.TRANSMITTING) {
            currentScreen = "transmit"
        }
    }

    Scaffold { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "home" -> HomeScreen(
                    viewModel = viewModel,
                    onTransmitClick = { currentScreen = "transmit" },
                    onSettingsClick = { currentScreen = "settings" }
                )
                "transmit" -> TransmitScreen(
                    viewModel = viewModel,
                    onBackClick = { currentScreen = "home" }
                )
                "settings" -> SettingsScreen(
                    viewModel = viewModel,
                    onBackClick = { currentScreen = "home" }
                )
            }
        }
    }
}

// ===== 首页 =====

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onTransmitClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val currentTime by viewModel.currentTime.collectAsState()
    val currentDate by viewModel.currentDate.collectAsState()
    val ntpServer by viewModel.ntpServer.collectAsState()
    val ntpSynced by viewModel.ntpSynced.collectAsState()
    val ntpOffset by viewModel.ntpOffset.collectAsState()
    val lastTransmit by viewModel.lastTransmitTime.collectAsState()
    val countdownSec by viewModel.countdownSec.collectAsState()
    val ntpSyncing by viewModel.ntpSyncing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // 当前时间
        Text("当前时间", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            currentTime,
            fontSize = 42.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )
        Text(
            currentDate,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // NTP 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp, 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("时间源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ntpServer, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ntpSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.size(8.dp),
                            color = if (ntpSynced) Color(0xFF639922) else Color(0xFFE24B4A)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (ntpSynced) "已同步" else "未同步",
                            fontSize = 12.sp,
                            color = if (ntpSynced) Color(0xFF639922) else Color(0xFFE24B4A)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 指标卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "NTP 偏差",
                value = "${String.format("%+.3f", ntpOffset / 1000.0)}s",
                valueColor = if (ntpSynced) Color(0xFF639922) else Color.Unspecified
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "上次发射",
                value = lastTransmit
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 发射按钮
        Button(
            onClick = {
                viewModel.startTransmit()
                onTransmitClick()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF185FA5)
            ),
            enabled = !ntpSyncing
        ) {
            if (countdownSec > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("${countdownSec}s 后开始发射", fontSize = 16.sp)
            } else {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始发射 BPC 信号", fontSize = 16.sp)
            }
        }
        Text(
            "BPC 68.5kHz / 3帧×20秒 / 请将手表靠近线圈",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 快捷按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.syncNtp() },
                modifier = Modifier.weight(1f),
                enabled = !ntpSyncing
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("同步时间", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("设置", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor
            )
        }
    }
}

// ===== 发射页 =====

@Composable
fun TransmitScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val transmitProgress by viewModel.transmitProgress.collectAsState()
    val transmitTotalSec by viewModel.transmitTotalSec.collectAsState()
    val transmitState by viewModel.transmitState.collectAsState()
    val transmitMinuteLabel by viewModel.transmitMinuteLabel.collectAsState()
    val transmitStatus by viewModel.transmitStatus.collectAsState()
    val countdownSec by viewModel.countdownSec.collectAsState()

    val isTransmitting = transmitState == BpcAudioOutput.State.TRANSMITTING
    val isWaiting = countdownSec > 0
    val isActive = isTransmitting || isWaiting  // 倒计时也算活跃状态

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 停止按钮 —— 放在最顶部, 倒计时和发射期间都可见
        if (isActive) {
            OutlinedButton(
                onClick = {
                    viewModel.stopTransmit()
                    onBackClick()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE24B4A)),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFE24B4A))
            ) {
                Text(if (isWaiting) "取消发射" else "停止发射", fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text(
            when {
                isWaiting -> "等待整分 :00 对齐 · ${countdownSec}s"
                isTransmitting -> "BPC 授时信号发射中"
                else -> "发射结束"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 脉冲动画 / 倒计时
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxSize(),
                color = when {
                    isWaiting -> Color(0xFFB8860B)
                    isTransmitting -> Color(0xFF639922)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {}
            if (isWaiting) {
                Text("${countdownSec}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            if (isTransmitting) {
                var scale by remember { mutableStateOf(1f) }
                LaunchedEffect(Unit) {
                    while (true) {
                        scale = 1.6f
                        kotlinx.coroutines.delay(1500)
                        scale = 1f
                        kotlinx.coroutines.delay(1500)
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .size(64.dp)
                        .size((64 * scale).dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF639922).copy(alpha = 1f - scale * 0.3f))
                ) {}
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (isWaiting) {
            Text(
                transmitStatus,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFB8860B)
            )
        } else {
            Text(
                "请将手表靠近线圈，距离约 2-5cm",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 进度条
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "发射进度${if (transmitMinuteLabel.isNotEmpty()) " · $transmitMinuteLabel" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$transmitProgress / $transmitTotalSec 秒",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = transmitProgress.toFloat() / transmitTotalSec.coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFF639922),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 信号参数
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(14.dp, 16.dp)) {
                ParamRow("载波", "68.5 kHz (4次谐波)")
                ParamRow("基频", "17.125 kHz")
                ParamRow("编码", "BPC 四进制 (2位/秒)")
                ParamRow("发射时间", "根据 NTP 同步时间")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 返回 / 再次发射按钮
        if (!isActive) {
            Button(
                onClick = {
                    viewModel.startTransmit()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF185FA5))
            ) {
                Text("再次发射", fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("返回首页")
            }
        }
    }
}

@Composable
fun ParamRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ===== 设置页 =====

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val ntpServer by viewModel.ntpServer.collectAsState()
    val sampleRate by viewModel.sampleRate.collectAsState()
    val repeatCount by viewModel.repeatCount.collectAsState()
    val roundMinuteTx by viewModel.roundMinuteTx.collectAsState()
    val ntpServers = viewModel.ntpServers

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // 返回
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        // NTP 设置
        Text("NTP 时间同步", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp, 16.dp)) {
                ntpServers.forEach { server ->
                    val isSelected = server == ntpServer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(server, fontSize = 13.sp)
                        if (isSelected) {
                            Text("当前", fontSize = 12.sp, color = Color(0xFF639922))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.syncNtp() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF185FA5))
                ) {
                    Text("立即同步时间")
                }
            }
        }

        // 音频设置
        Text("音频输出", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp, 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("采样率", fontSize = 13.sp)
                        Text("越高谐波能量越强", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text("${sampleRate / 1000} kHz")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf(48000, 96000, 192000).forEach { rate ->
                                DropdownMenuItem(
                                    text = { Text("${rate / 1000} kHz") },
                                    onClick = {
                                        viewModel.setSampleRate(rate)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Text("输出音量: 最大", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = 100f,
                    onValueChange = {},
                    valueRange = 50f..100f,
                    enabled = false,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "音量最大可增强削波效应，提升谐波分量",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 发射设置
        Text("发射设置", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp, 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("重复发射次数", fontSize = 13.sp)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text("${repeatCount} 次")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf(1, 3, 5).forEach { count ->
                                DropdownMenuItem(
                                    text = { Text("${count} 次") },
                                    onClick = {
                                        viewModel.setRepeatCount(count)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("正分整点发射", fontSize = 13.sp)
                    Switch(
                        checked = roundMinuteTx,
                        onCheckedChange = { viewModel.setRoundMinuteTx(it) }
                    )
                }
            }
        }

        // 关于
        Text("关于", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp, 16.dp)) {
                InfoRow("版本", "1.0.0")
                InfoRow("协议", "BPC 20秒帧/四进制编码")
                InfoRow("载波", "68.5 kHz (4次谐波)")
                InfoRow("基频", "17.125 kHz")
                InfoRow("支持手表", "GWM5610 / 电波表通用")
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp)
    }
}
