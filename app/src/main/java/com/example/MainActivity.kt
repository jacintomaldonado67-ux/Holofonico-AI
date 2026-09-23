package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.example.audio.SpatialAudioService
import com.example.audio.MediaNotificationService
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.SpatialAudioRepository
import com.example.ui.components.MetricsPanel
import com.example.ui.components.SpatialRadar
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.GeminiUiState
import com.example.viewmodel.SpatialAudioViewModel
import com.example.viewmodel.SpatialAudioViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var playbackReceiver: BroadcastReceiver? = null
    private var viewModelRef: SpatialAudioViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // Initialize database and repository
                val context = LocalContext.current
                val database = remember { AppDatabase.getDatabase(context) }
                val repository = remember { SpatialAudioRepository(database) }
                
                // Get our view model
                val viewModel: SpatialAudioViewModel = viewModel(
                    factory = SpatialAudioViewModelFactory(repository)
                )
                viewModelRef = viewModel

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF020617) // Sleek space black background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }

        // Register dynamic broadcast receiver for intercepting active player tracks (YouTube, Disney+, etc.)
        playbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == MediaNotificationService.ACTION_PLAYBACK_DETECTED) {
                    val title = intent.getStringExtra("title") ?: ""
                    val pkg = intent.getStringExtra("package") ?: ""
                    android.util.Log.d("MainActivity", "Intercepted playback on $pkg: '$title'")
                    
                    val vm = viewModelRef
                    if (vm != null && vm.isAutoAdaptEnabled.value) {
                        vm.analyzeAcousticScene("Ajustar para video en $pkg titulado: $title")
                    }
                }
            }
        }
        val filter = IntentFilter(MediaNotificationService.ACTION_PLAYBACK_DETECTED)
        ContextCompat.registerReceiver(
            this,
            playbackReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        playbackReceiver = null
    }
}

@Composable
fun MainScreen(viewModel: SpatialAudioViewModel) {
    val context = LocalContext.current

    // Observe StateFlows
    val interpolatedX by viewModel.interpolatedX.collectAsStateWithLifecycle()
    val interpolatedY by viewModel.interpolatedY.collectAsStateWithLifecycle()
    val interpolatedZ by viewModel.interpolatedZ.collectAsStateWithLifecycle()

    val targetX by viewModel.targetX.collectAsStateWithLifecycle()
    val targetY by viewModel.targetY.collectAsStateWithLifecycle()
    val targetZ by viewModel.targetZ.collectAsStateWithLifecycle()

    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val soundType by viewModel.soundType.collectAsStateWithLifecycle()

    val itdEnabled by viewModel.itdEnabled.collectAsStateWithLifecycle()
    val ildEnabled by viewModel.ildEnabled.collectAsStateWithLifecycle()
    val reverbEnabled by viewModel.reverbEnabled.collectAsStateWithLifecycle()
    val filterEnabled by viewModel.filterEnabled.collectAsStateWithLifecycle()

    val interpolationMode by viewModel.interpolationMode.collectAsStateWithLifecycle()
    val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val geminiState by viewModel.geminiState.collectAsStateWithLifecycle()
    val workStatus by viewModel.workStatus.collectAsStateWithLifecycle()

    val cpuUsage by viewModel.simulatedCpu.collectAsStateWithLifecycle()
    val batterySaved by viewModel.simulatedBatterySaved.collectAsStateWithLifecycle()
    val soundSystemMode by viewModel.soundSystemMode.collectAsStateWithLifecycle()

    // UI Input States
    var customScenePrompt by remember { mutableStateOf("Catedral gótica de techos altos y paredes de piedra con una voz cantando gregoriano") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing // Mandated in instructions
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF030712))
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- HEADER BLOCK ---
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "HOLOFÓNICO IA",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Procesador de Audio Espacial y Acústica",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Play / Mute Master Switch
                    Button(
                        onClick = { viewModel.toggleMute() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMuted) Color(0xFFF43F5E) else Color(0xFF10B981)
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("play_mute_button"),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.PlayArrow else Icons.Default.Stop,
                            contentDescription = if (isMuted) "Play spatializer audio" else "Stop spatializer audio",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // --- 2D SPATIAL RADAR & COORDINATE BOARD ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NODO DE EMISOR ESPACIAL (PULSAR)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Arrastra para mover la fuente",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Custom interactive Canvas Radar
                        SpatialRadar(
                            interpolatedX = interpolatedX,
                            interpolatedY = interpolatedY,
                            targetX = targetX,
                            targetY = targetY,
                            onCoordinatesChanged = { nx, ny ->
                                viewModel.targetX.value = nx
                                viewModel.targetY.value = ny
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Z (Depth) slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Profundidad (Z)",
                                tint = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Profundidad Z (Distancia): %.2f m".format(interpolatedZ),
                                fontSize = 12.sp,
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.width(190.dp)
                            )
                            Slider(
                                value = targetZ,
                                onValueChange = { viewModel.targetZ.value = it },
                                valueRange = 0.1f..5.0f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("z_depth_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF38BDF8),
                                    activeTrackColor = Color(0xFF38BDF8),
                                    inactiveTrackColor = Color(0xFF334155)
                                )
                            )
                        }
                    }
                }
            }

            // --- LIVE TELEMETRY AND NDK SIMULATOR PANEL ---
            item {
                MetricsPanel(
                    x = interpolatedX,
                    y = interpolatedY,
                    z = interpolatedZ,
                    cpuUsage = cpuUsage,
                    batterySaved = batterySaved,
                    itdEnabled = itdEnabled,
                    ildEnabled = ildEnabled,
                    isMuted = isMuted
                )
            }

            // --- SYSTEM-WIDE SPATIALIZER CONTROLS (YOUTUBE / DISNEY+) ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LibraryMusic,
                                contentDescription = "Sistema",
                                tint = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SISTEMA DE ESPACIALIZACIÓN DE FONDO",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Aplica los efectos de 3D virtual, reverberación RT60 y absorción de material a nivel de sistema para aplicaciones como YouTube, Disney+, Spotify y reproductores multimedia externos.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Status Indicators and Toggles
                        val serviceActive by SpatialAudioService.isServiceRunning.collectAsStateWithLifecycle(initialValue = false)
                        val listenerConnected by MediaNotificationService.isServiceConnectedFlow.collectAsStateWithLifecycle(initialValue = false)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Motor de Fondo:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (serviceActive) "Activo (Procesando audio)" else "Inactivo",
                                    fontSize = 10.sp,
                                    color = if (serviceActive) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }
                            Button(
                                onClick = {
                                    try {
                                        if (serviceActive) {
                                            val serviceIntent = Intent(context, SpatialAudioService::class.java)
                                            context.stopService(serviceIntent)
                                        } else {
                                            val serviceIntent = Intent(context, SpatialAudioService::class.java)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.startForegroundService(serviceIntent)
                                            } else {
                                                context.startService(serviceIntent)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (serviceActive) Color(0xFFEF4444) else Color(0xFF38BDF8)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (serviceActive) "Detener" else "Activar Motor",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (serviceActive) Color.White else Color.Black
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Notification Listener Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sincronizador de Medios con IA:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (listenerConnected) "Conectado" else "Falta permiso de lectura de notificaciones",
                                    fontSize = 10.sp,
                                    color = if (listenerConnected) Color(0xFF10B981) else Color(0xFFFBBF24)
                                )
                            }
                            if (!listenerConnected) {
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Dar Permiso",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }

                        if (listenerConnected) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // Auto-adaptation toggle using Gemini
                            val isAutoAdaptEnabled by viewModel.isAutoAdaptEnabled.collectAsStateWithLifecycle()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B).copy(alpha = 0.4f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Auto-adaptación con Gemini IA",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Analiza el título de videos en reproducción en segundo plano con Gemini para adaptar el RT60 y materiales automáticamente.",
                                        fontSize = 9.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Switch(
                                    checked = isAutoAdaptEnabled,
                                    onCheckedChange = { viewModel.setAutoAdaptEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFA78BFA),
                                        uncheckedThumbColor = Color(0xFF64748B),
                                        uncheckedTrackColor = Color(0xFF0F172A)
                                    ),
                                    modifier = Modifier.scaleSwitch(0.7f)
                                )
                            }

                            // Show current playing track title and active session targets
                            val playingTitle by MediaNotificationService.currentTrackTitleFlow.collectAsStateWithLifecycle(initialValue = "")
                            val playingPkg by MediaNotificationService.currentTrackPackageFlow.collectAsStateWithLifecycle(initialValue = "")
                            if (playingTitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF059669).copy(alpha = 0.15f))
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "REPRODUCIENDO AHORA (Sincronizado):",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981)
                                        )
                                        Text(
                                            text = playingTitle,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "App: $playingPkg",
                                            fontSize = 9.sp,
                                            color = Color(0xFF38BDF8)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- SISTEMA DE CANALES VIRTUALES (ULTIMEA OPTIMIZATION) ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SpeakerGroup,
                                contentDescription = "Canales Virtuales",
                                tint = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SISTEMA DE CANALES VIRTUALES (ULTIMEA)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Optimizado para barras de sonido Ultimea, auriculares y altavoces estéreo. Utiliza algoritmos de cancelación de crosstalk (XCC), reflejos virtuales de techo (Atmos) y botes de pared para simular realismo tridimensional.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val modes = listOf(
                            Triple("Auriculares HRTF (Binaural)", Icons.Default.Headphones, "Reproducción binaural optimizada para auriculares con separación de fase."),
                            Triple("Ultimea Aura 7.1.2 (Atmos)", Icons.Default.AutoAwesome, "Simula 7 canales horizontales, subwoofer y 2 de altura (Atmos de techo)."),
                            Triple("Ultimea Poseidon 5.1 (Surround)", Icons.Default.SpeakerGroup, "Simula 5.1 canales con botes de pared virtuales y rebotes laterales."),
                            Triple("Ultimea Solo 2.1 (Extendido)", Icons.Default.Speaker, "Ensanchamiento de campo estéreo con realce de voces y delay Haas.")
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            modes.forEach { (modeName, icon, desc) ->
                                val selected = soundSystemMode == modeName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) Color(0xFF38BDF8).copy(alpha = 0.15f) else Color(0xFF1E293B).copy(alpha = 0.4f))
                                        .border(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.setSoundSystemMode(modeName) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) Color(0xFF38BDF8) else Color(0xFF64748B),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = modeName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) Color(0xFF38BDF8) else Color.White
                                        )
                                        Text(
                                            text = desc,
                                            fontSize = 9.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    RadioButton(
                                        selected = selected,
                                        onClick = { viewModel.setSoundSystemMode(modeName) },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Color(0xFF38BDF8),
                                            unselectedColor = Color(0xFF64748B)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- AUDIO ROUTING & DSP FILTER CONTROLS ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ETAPAS DSP & FILTROS ACÚSTICOS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Live audio synth selector
                        Text(
                            text = "Emisor de Sonido",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        val soundOptions = listOf("Drone Cósmico", "Voz de Robot", "Ruido de Lluvia", "Pasos Rítmicos")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(soundOptions) { option ->
                                val selected = soundType == option
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B))
                                        .border(1.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.setSoundType(option) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = if (selected) Color.Black else Color(0xFF94A3B8),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = option,
                                            fontSize = 11.sp,
                                            color = if (selected) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bypass switches for acoustic stages
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DSpToggleSwitch(
                                label = "HRTF ITD",
                                description = "Retardo de fase binaural",
                                checked = itdEnabled,
                                onCheckedChange = { viewModel.setItdEnabled(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            DSpToggleSwitch(
                                label = "HRTF ILD",
                                description = "Sombreado acústico",
                                checked = ildEnabled,
                                onCheckedChange = { viewModel.setIldEnabled(it) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DSpToggleSwitch(
                                label = "Reverberación",
                                description = "Sala RT60 Schroeder",
                                checked = reverbEnabled,
                                onCheckedChange = { viewModel.setReverbEnabled(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            DSpToggleSwitch(
                                label = "Absorción",
                                description = "Filtro LPF de Material",
                                checked = filterEnabled,
                                onCheckedChange = { viewModel.setFilterEnabled(it) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // --- GEMINI ACOUSTIC SCENE BRAIN (NODE 3) ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Cerebro IA",
                                tint = Color(0xFFA78BFA)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ANÁLISIS ACÚSTICO DE ENTORNO CON IA",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Envía una descripción de escena o frame a Gemini para extraer coeficientes de sala y tracking de coordenadas en tiempo real de forma asíncrona.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Preset quick selectors for prompt
                        Text(
                            text = "Pre-cargar Escena de Ejemplo",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        val presetPrompts = listOf(
                            "Catedral Gótica" to "Catedral gótica inmensa con techos gigantes, materiales de piedra reverberante y pasos de alguien caminando al fondo",
                            "Túnel Metálico" to "Pasillo estrecho dentro de un submarino con tuberías metálicas resonantes, se escucha un drone zumbando arriba",
                            "Estudio Cerrado" to "Estudio de grabación pequeño cubierto de madera absorbente, con una voz muy íntima hablando en frente",
                            "Bosque Abierto" to "Parque abierto gigante rodeado de césped sin rebote acústico, se escucha el claxon de un auto a la distancia"
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(presetPrompts) { (label, prompt) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E293B))
                                        .clickable { customScenePrompt = prompt }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(text = label, fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Prompt input
                        OutlinedTextField(
                            value = customScenePrompt,
                            onValueChange = { customScenePrompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .testTag("scene_prompt_input"),
                            placeholder = { Text("Describe el entorno acústico...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFA78BFA),
                                unfocusedBorderColor = Color(0xFF1E293B),
                                focusedContainerColor = Color(0xFF020617),
                                unfocusedContainerColor = Color(0xFF020617)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            textStyle = TextStyle(fontSize = 12.sp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Trigger analysis button
                        Button(
                            onClick = { viewModel.analyzeAcousticScene(customScenePrompt) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("analyze_scene_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                            shape = RoundedCornerShape(12.dp),
                            enabled = geminiState !is GeminiUiState.Loading
                        ) {
                            if (geminiState is GeminiUiState.Loading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Analizando con Gemini...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            } else {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "CONSULTAR CEREBRO DE IA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        // Display Gemini Analysis Status / Output
                        Spacer(modifier = Modifier.height(8.dp))
                        when (val state = geminiState) {
                            is GeminiUiState.Success -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF064E3B).copy(alpha = 0.3f))
                                        .border(1.dp, Color(0xFF059669).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "METADATOS ACOPLADOS CON ÉXITO:",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Entorno: ${state.analysis.sceneType} | Reverb RT60: ${state.analysis.rt60}s | Material: ${state.analysis.materials}",
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (state.analysis.objects.isNotEmpty()) {
                                            Text(
                                                text = "Objeto trackeado: ${state.analysis.objects.first().label} en [X: ${state.analysis.objects.first().x}, Y: ${state.analysis.objects.first().y}, Z: ${state.analysis.objects.first().z}]",
                                                fontSize = 10.sp,
                                                color = Color(0xFF38BDF8)
                                            )
                                        }
                                    }
                                }
                            }
                            is GeminiUiState.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF7F1D1D).copy(alpha = 0.3f))
                                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "Aviso: ${state.message}",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFCA5A5)
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // --- NODE 4: INTERPOLATION ENGINE CONTROLS ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "MOTOR DE INTERPOLACIÓN PROCEDURAL (EL OPTIMIZADOR)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Permite alimentar el sintetizador a 120Hz de refresco fluido recalculando trayectorias matemáticas intermedias, evitando saltos bruscos y ahorrando batería al no requerir llamadas constantes de IA.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val modes = listOf("Física (Muelle/Spring)", "Cúbica (Esferal)", "Lineal")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            modes.forEach { mode ->
                                val selected = interpolationMode == mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B))
                                        .clickable { viewModel.setInterpolationMode(mode) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode,
                                        fontSize = 10.sp,
                                        color = if (selected) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- WORKMANAGER SYSTEM OPTIMIZATION (NODE 6) ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.OfflineBolt,
                                contentDescription = "WorkManager",
                                tint = Color(0xFF10B981)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "WORKMANAGER: CALIBRACIÓN EN SEGUNDO PLANO",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Planifica simulaciones matemáticas pesadas en segundo plano con WorkManager, optimizando el rendimiento de la batería al ejecutarse de forma inteligente según políticas del sistema operativo.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Trigger WorkManager Button
                        Button(
                            onClick = { viewModel.scheduleBackgroundCalibration(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("workmanager_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.OfflineBolt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "ENCANDILAR CALIBRACIÓN WORKMANAGER", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Black)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Display Current Live Work Status
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Estado",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Estado: $workStatus",
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Database historic execution logs list
                        if (logs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Historial de Logs (Persistidos en Room DB):",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                logs.forEach { log ->
                                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF020617))
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Log #${log.id} - ${log.presetId}",
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Trackeados: ${log.totalCoordinatesTracked} pts | CPU DSP: ${log.avgCpuUsage}%",
                                                fontSize = 10.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Text(
                                            text = "$timeStr | OK",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF10B981),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- FOOTER & ABOUT ---
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Binaural Spatializer Engine - Real-time HRTF Stereo Simulation. optimized for Android OS battery policies.",
                    fontSize = 10.sp,
                    color = Color(0xFF4B5563),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun DSpToggleSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B).copy(alpha = 0.3f))
            .border(1.dp, Color(0xFF334155).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF38BDF8),
                    uncheckedThumbColor = Color(0xFF64748B),
                    uncheckedTrackColor = Color(0xFF0F172A)
                ),
                modifier = Modifier
                    .size(34.dp)
                    .scaleSwitch(0.7f)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            fontSize = 9.sp,
            color = Color(0xFF64748B)
        )
    }
}

// Extension function to help scale switches nicely
fun Modifier.scaleSwitch(scaleValue: Float) = this.then(
    scale(scaleValue)
)

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
