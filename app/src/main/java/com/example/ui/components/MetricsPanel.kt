package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
fun MetricsPanel(
    x: Float,
    y: Float,
    z: Float,
    cpuUsage: Float,
    batterySaved: Int,
    itdEnabled: Boolean,
    ildEnabled: Boolean,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    // Math computations for live polar coordinates
    val distanceMeters = sqrt(x * x + y * y + z * z) * 1.5f // Scaled to meter bounds
    val angleRad = atan2(x, y)
    val angleDegrees = Math.toDegrees(angleRad.toDouble()).toInt()

    // ITD Delay Calculations
    val delaySamples = if (itdEnabled && !isMuted) (abs(x) * 24).toInt() else 0
    val delayMicroseconds = (delaySamples * 1000000f / 44100f).toInt() // delay = samples / Fs

    // ILD Calculations (dB)
    val attenuationL = if (ildEnabled && !isMuted) {
        if (x > 0) -20f * Math.log10(1.0 - x * 0.6) else 0.0
    } else 0.0
    val attenuationR = if (ildEnabled && !isMuted) {
        if (x < 0) -20f * Math.log10(1.0 - abs(x) * 0.6) else 0.0
    } else 0.0

    // JNI/NDK native simulation benchmark values
    val ndkLatency = "8 ms (Oboe Native)"
    val javaLatency = "42 ms (Java Track)"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
            .padding(16.dp)
            .testTag("metrics_panel")
    ) {
        Text(
            text = "TELEMETRÍA DE SEÑAL Y OPTIMIZACIÓN CPU",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                letterSpacing = 1.2.sp
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // --- Grid of live coordinate values ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCard(
                label = "COORDENADAS",
                value = "X: %.2f | Y: %.2f | Z: %.2f".format(x, y, z),
                color = Color(0xFF38BDF8),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard(
                label = "GEOMETRÍA 3D",
                value = "Dist: %.1fm | Áng: %d°".format(distanceMeters, angleDegrees),
                color = Color(0xFFE2E8F0),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCard(
                label = "RETARDO ITD",
                value = if (isMuted) "Silenciado" else "$delaySamples smp | $delayMicroseconds µs",
                color = if (itdEnabled) Color(0xFF00E5FF) else Color(0xFF64748B),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MetricCard(
                label = "ATENUACIÓN ILD",
                value = if (isMuted) "Silenciado" else "L: %.1fdB | R: %.1fdB".format(attenuationL, attenuationR),
                color = if (ildEnabled) Color(0xFFA78BFA) else Color(0xFF64748B),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Benchmark and Hardware NDK Simulator ---
        Text(
            text = "EMULADOR NATIVO NDK (OBOE / C++)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B)
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF020617))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Latency comparison
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Latencia",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.width(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Latencia del Buffer de Audio",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ndkLatency,
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = " vs $javaLatency",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // CPU load indicator
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "CPU",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Carga de CPU (AudioTrack DSP Thread)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "%.2f%%".format(cpuUsage),
                        color = Color(0xFFF59E0B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (cpuUsage / 5.0f).coerceIn(0.01f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFFF59E0B),
                    trackColor = Color(0xFF1E293B)
                )
            }

            // Battery performance saving score
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = "Batería",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.width(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Ahorro de Batería Acumulado (Por Interpolación Procedural)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Est. +${batterySaved} J (Evita %95 de llamadas asíncronas de IA)",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B).copy(alpha = 0.4f))
            .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
