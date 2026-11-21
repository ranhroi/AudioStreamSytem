package com.ranhroi.receiveaudiostream


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ranhroi.receiveaudiostream.ui.theme.ReceiveAudioStreamTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiveAudioStreamTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Observe service snapshot
    val state by AudioForegroundService.snapState.collectAsState()

    // Config form states (initialized from current snapshot or defaults)
    var portValue by remember { mutableIntStateOf(2027) }
    var selectedSampleRate by remember { mutableIntStateOf(48000) }
    var selectedChannel by remember { mutableIntStateOf(0) } // 0 = Stereo, 1 = Mono
    var rmsThresholdValue by remember { mutableFloatStateOf(30f) }
    var isForcedPlayValue by remember { mutableStateOf(false) }

    // Toggle states for expandable panels
    var showGuide by remember { mutableStateOf(true) }

    // IP address calculation
    var deviceIp by remember { mutableStateOf("0.0.0.0") }

    // Read initial snapshot state when started
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            portValue = state.port
            selectedSampleRate = state.sampleRate
            selectedChannel = if (state.isStereo) 0 else 1
            rmsThresholdValue = state.rmsThreshold
            isForcedPlayValue = state.isForcedPlay
        }
    }

    // Refresh IP address periodically
    LaunchedEffect(Unit) {
        while (true) {
            deviceIp = getLocalIpAddress(context)
            delay(5000)
        }
    }

    // Permission handling (Android 13+ Notification post permission)
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                AudioForegroundService.addLog("Notification permission approved.")
            } else {
                AudioForegroundService.addLog("Notification permission denied. Background notifications hidden.")
            }
        }
    )

    // Trigger permission requests
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Controls launcher
    val startServiceAction = {
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_START
            putExtra("extra_port", portValue)
            putExtra("extra_sample_rate", selectedSampleRate)
            putExtra("extra_is_stereo", selectedChannel == 0)
            putExtra("extra_rms_threshold", rmsThresholdValue)
            putExtra("extra_force_play", isForcedPlayValue)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Toast.makeText(context, "Khởi chạy UDP Audio Server!", Toast.LENGTH_SHORT).show()
    }

    val updateDynamicConfig = {
        if (state.isRunning) {
            val intent = Intent(context, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_START
                putExtra("extra_port", portValue)
                putExtra("extra_sample_rate", selectedSampleRate)
                putExtra("extra_is_stereo", selectedChannel == 0)
                putExtra("extra_rms_threshold", rmsThresholdValue)
                putExtra("extra_force_play", isForcedPlayValue)
            }
            context.startService(intent)
        }
    }

    val stopServiceAction = {
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_STOP
        }
        context.startService(intent)
        Toast.makeText(context, "Đã ngắt UDP Audio Server.", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "UDP Audio Server",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Hệ thống thu truyền âm thanh thô PCM ngầm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                }
            }

            // Realtime State Display Panel
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("status_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (state.receiverState) {
                            ReceiverState.PLAYING -> Color(0xFF1B3B2B)
                            ReceiverState.IDLE -> Color(0xFF3B321B)
                            ReceiverState.SLEEP -> Color(0xFF202229)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Current status row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Status Circle
                                val colorTransition = rememberInfiniteTransition(label = "status_pulse")
                                val animatedAlpha by colorTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = EaseInOutQuad),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse_alpha"
                                )

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            color = when (state.receiverState) {
                                                ReceiverState.PLAYING -> Color(0xFF00FF66).copy(
                                                    alpha = if (state.isRunning) animatedAlpha else 1f
                                                )
                                                ReceiverState.IDLE -> Color(0xFFFFCC00)
                                                ReceiverState.SLEEP -> Color(0xFF90A4AE)
                                            }
                                        )
                                )

                                Text(
                                    text = when (state.receiverState) {
                                        ReceiverState.PLAYING -> "ĐANG PHÁT (PLAYING)"
                                        ReceiverState.IDLE -> "NGẮT TIẾT KIỆM KHÔNG TẢI (IDLE)"
                                        ReceiverState.SLEEP -> "CHỜ CHẾ ĐỘ NGỦ (SLEEP)"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }

                            // Power status
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (state.isRunning) Color(0xFF00C853).copy(alpha = 0.2f)
                                        else Color(0xFFD50000).copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (state.isRunning) "RUNNING" else "STOPPED",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = if (state.isRunning) Color(0xFF69F0AE) else Color(0xFFFF5252)
                                )
                            }
                        }

                        // Status subtitle text
                        Text(
                            text = when (state.receiverState) {
                                ReceiverState.PLAYING -> "Máy chủ đang mở loa phát tín hiệu âm thanh thu được từ mạng UDP."
                                ReceiverState.IDLE -> "Trạng thái im lặng trong 1 giây thừa nhận. Paused output âm thanh để bảo vệ loa và pin."
                                ReceiverState.SLEEP -> "Quá 2 giây không phát hiện gói tin trên UDP. Đang đóng băng loa và tối ưu CPU."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )

                        // Realtime RMS energy bar graph/visualizer
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Năng lượng tín hiệu RMS: ${String.format("%.2f", state.currentRms)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )

                                Text(
                                    text = "Ngưỡng kích hoạt: ${rmsThresholdValue.roundToInt()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }

                            // Dynamic progress indicator bar
                            val animatedRmsValue by animateFloatAsState(
                                targetValue = (state.currentRms / 150.0).coerceIn(0.0, 1.0).toFloat(),
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "rms_bar_anim"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                            ) {
                                // Threshold bar placement overlay
                                val threshPercent = (rmsThresholdValue / 150f).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(threshPercent)
                                        .border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                                        )
                                )

                                // Current active RMS level bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(animatedRmsValue)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            if (state.currentRms >= rmsThresholdValue) Color(0xFF00E676)
                                            else Color(0xFFFFC107)
                                        )
                                )
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.1f))

                        // Stats counters row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "GÓI TIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "${state.packetCount}",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color.White
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "DUNG LƯỢNG",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                val formatMb = state.totalBytesReceived / (1024f * 1024f)
                                Text(
                                    text = if (formatMb > 1f) {
                                        String.format("%.2f MB", formatMb)
                                    } else {
                                        String.format("%.1f KB", state.totalBytesReceived / 1024f)
                                    },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color.White
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "BĂNG THÔNG",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "${state.port} | ${state.sampleRate}Hz",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Power Action controls
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!state.isRunning) {
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                startServiceAction()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("start_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "MỞ MÁY CHỦ (START)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                stopServiceAction()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .testTag("stop_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                "TẮT MÁY CHỦ (STOP)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Core Parameters Settings Board
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Cấu hình Socket & Thiết bị",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        // Port input block
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                            // Network IP Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "IP Server: $deviceIp",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            Text(
                                text = "Cổng UDP (UDP Port)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            OutlinedTextField(
                                value = portValue.toString(),
                                onValueChange = {
                                    val port = it.toIntOrNull() ?: 0
                                    portValue = port
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("port_input"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !state.isRunning,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { keyboardController?.hide() }
                                ),
                                placeholder = { Text("Mặc định: 5005") },
                                trailingIcon = {
                                    if (state.isRunning) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = "Chặn",
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            )
                            if (state.isRunning) {
                                Text(
                                    text = "⚠️ Hãy tắt máy chủ trước khi thay đổi Cổng mạng.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        // Sample Rate Selector (horizontal chips list)
                        val sampleRates = listOf(48000, 44100, 32000, 16000, 8000)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Tần số lấy mẫu (Sample Rate Hz)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sampleRates) { rate ->
                                    FilterChip(
                                        selected = selectedSampleRate == rate,
                                        onClick = { if (!state.isRunning) selectedSampleRate = rate },
                                        label = { Text("$rate Hz") },
                                        enabled = !state.isRunning
                                    )
                                }
                            }
                        }

                        // Audio Channel Selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Kênh đầu ra (Audio Channel)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf("Stereo (2 Kênh)", "Mono (1 Kênh)").forEachIndexed { index, label ->
                                    val isSelected = selectedChannel == index
                                    ElevatedFilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (!state.isRunning) {
                                                selectedChannel = index
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = label,
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        },
                                        modifier = Modifier.weight(1.0f),
                                        enabled = !state.isRunning
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                        // Live threshold slider
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Ngưỡng lọc âm lượng RMS ảo",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${rmsThresholdValue.roundToInt()}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                            Slider(
                                value = rmsThresholdValue,
                                onValueChange = {
                                    rmsThresholdValue = it
                                    updateDynamicConfig() // Dynamic update
                                },
                                valueRange = 1f..150f,
                                modifier = Modifier.fillMaxWidth().testTag("rms_slider")
                            )
                            Text(
                                text = "Tự động kích hoạt loa khi RMS vượt quá ngưỡng này. RMS nhỏ hơn sẽ giữ Speaker ở trạng thái tĩnh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        // Force playback toggle option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ép phát trực tiếp (Overriding Force Play)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Bỏ qua phát hiện im lặng RMS, phát tất cả gói tin không ngừng nghỉ.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                            Switch(
                                checked = isForcedPlayValue,
                                onCheckedChange = {
                                    isForcedPlayValue = it
                                    updateDynamicConfig() // Dynamic update
                                }
                            )
                        }
                    }
                }
            }

            // Realtime Terminal Logs Panel
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF0F1115) // Deep terminal dark
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Logs",
                                    tint = Color(0xFF81D4FA)
                                )
                                Text(
                                    text = "Log hệ thống thời gian thực",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }

                            IconButton(
                                onClick = { AudioForegroundService.clearLogs() },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.6f)
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Clear logs")
                            }
                        }

                        // Logs scroll terminal block
                        val lazyListState = rememberLazyListState()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            if (state.statusLogs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Chưa có log sự kiện nào. Hãy bật máy chủ hoặc truyền gói UDP.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.4f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = lazyListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(state.statusLogs) { logEntry ->
                                        Text(
                                            text = logEntry,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            ),
                                            color = when {
                                                logEntry.contains("ERROR") -> Color(0xFFFF5252)
                                                logEntry.contains("warning") || logEntry.contains("timeout") -> Color(0xFFFFD54F)
                                                logEntry.contains("Active signal") || logEntry.contains("state changed") -> Color(0xFF69F0AE)
                                                else -> Color.White.copy(alpha = 0.85f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Copier Stream Guide
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showGuide = !showGuide },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Guide",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Hướng dẫn gửi âm thanh từ máy tính (PC)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start) {
                            Text(
                                text = if (showGuide) "ẨN ▲" else "HIỆN ▼",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = showGuide) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Để truyền dữ liệu âm thanh thô trực tiếp từ PC qua mạng Wi-Fi đến điện thoại Android này, hãy sử dụng các kịch bản sau.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )

                                val sampleCode = """
# Python Script: Truyền âm thanh trực tiếp từ Micro PC
import socket, pyaudio

ANDROID_IP = "$deviceIp"  # Địa chỉ hiển thị ở góc màn hình ứng dụng
PORT = $portValue

SAMPLE_RATE = $selectedSampleRate
CHANNELS = ${if (selectedChannel == 0) 2 else 1}
CHUNK = 1024

p = pyaudio.PyAudio()
stream = p.open(format=pyaudio.paInt16, channels=CHANNELS, rate=SAMPLE_RATE, input=True, frames_per_buffer=CHUNK)
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

print(f"Đang stream micro đến {ANDROID_IP}:{PORT}...")
try:
    while True:
        data = stream.read(CHUNK, exception_on_overflow=False)
        sock.sendto(data, (ANDROID_IP, PORT))
except KeyboardInterrupt:
    pass
finally:
    stream.stop_stream()
    stream.close()
    p.terminate()
""".trimIndent()

                                val ffmpegCode = "ffmpeg -f dshow -i audio=\"Microphone\" -f s16le -ac $selectedChannel -ar $selectedSampleRate udp://$deviceIp:$portValue"

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Tùy chọn 1: Dùng Python (Phổ biến)",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = sampleCode,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(
                                            onClick = { copyToClipboard(context, sampleCode) },
                                            modifier = Modifier.align(Alignment.End),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Sao chép Code", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Tùy chọn 2: Dùng lệnh FFMPEG",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = ffmpegCode,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                        )
                                        Button(
                                            onClick = { copyToClipboard(context, ffmpegCode) },
                                            modifier = Modifier.align(Alignment.End),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Sao chép Lệnh", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getLocalIpAddress(context: Context): String {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectionInfo = wifiManager?.connectionInfo
        if (connectionInfo != null) {
            val ipAddress = connectionInfo.ipAddress
            if (ipAddress != 0) {
                return String.format(
                    Locale.getDefault(),
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }

    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && host.isNotEmpty()) {
                            return host
                        }
                    }
                }
            }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }

    return "0.0.0.0"
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard != null) {
            val clip = android.content.ClipData.newPlainText("UDP Stream Companion", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Đã sao chép mã hướng dẫn vào Clipboard!", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}

