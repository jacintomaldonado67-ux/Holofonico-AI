package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SpatialAudioService : Service() {

    companion object {
        const val TAG = "SpatialAudioService"
        const val CHANNEL_ID = "SpatialAudioServiceChannel"
        const val NOTIFICATION_ID = 8888

        const val ACTION_OPEN_AUDIO_EFFECT_CONTROL_PANEL = "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_PANEL"
        const val ACTION_CLOSE_AUDIO_EFFECT_CONTROL_PANEL = "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_PANEL"

        // Static active reference for high-performance direct binding
        @Volatile
        var instance: SpatialAudioService? = null
            private set

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        // Active audio parameters
        @Volatile var x: Float = 0.0f
        @Volatile var y: Float = 1.0f
        @Volatile var z: Float = 1.0f

        @Volatile var rt60: Float = 0.45f
        @Volatile var material: String = "Madera"

        @Volatile var itdEnabled: Boolean = true
        @Volatile var ildEnabled: Boolean = true
        @Volatile var reverbEnabled: Boolean = true
        @Volatile var filterEnabled: Boolean = true

        @Volatile var soundSystemMode: String = "Auriculares HRTF (Binaural)"

        @Volatile var isServiceActive: Boolean = false

        // Direct updates called by the ViewModel/Interpolation Loop
        fun updateCoordinates(nx: Float, ny: Float, nz: Float) {
            x = nx
            y = ny
            z = nz
            instance?.applyEffectsParameters()
        }

        fun updateSoundSystem(mode: String) {
            soundSystemMode = mode
            instance?.applyEffectsParameters()
        }

        fun updateAcoustics(nRt60: Float, nMaterial: String) {
            rt60 = nRt60
            material = nMaterial
            instance?.applyEffectsParameters()
        }

        fun updateBypasses(itd: Boolean, ild: Boolean, reverb: Boolean, filter: Boolean) {
            itdEnabled = itd
            ildEnabled = ild
            reverbEnabled = reverb
            filterEnabled = filter
            instance?.applyEffectsParameters()
        }
    }

    // Map of active audio session IDs to their instantiated Android system audio effects
    private val activeSessions = ConcurrentHashMap<Int, SessionEffects>()

    // BroadcastReceiver to intercept OPEN/CLOSE audio effect control panel actions from players
    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
            val pkgName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Desconocido"

            Log.d(TAG, "Receiver action: $action, sessionId: $sessionId, pkg: $pkgName")

            if (sessionId != AudioEffect.ERROR && sessionId != 0) {
                if (action == ACTION_OPEN_AUDIO_EFFECT_CONTROL_PANEL) {
                    registerSession(sessionId, pkgName)
                } else if (action == ACTION_CLOSE_AUDIO_EFFECT_CONTROL_PANEL) {
                    unregisterSession(sessionId)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceActive = true
        _isServiceRunning.value = true
        Log.d(TAG, "SpatialAudioService created")

        // Register BroadcastReceiver for player audio sessions
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN_AUDIO_EFFECT_CONTROL_PANEL)
            addAction(ACTION_CLOSE_AUDIO_EFFECT_CONTROL_PANEL)
        }
        ContextCompat.registerReceiver(
            this,
            audioSessionReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        // Try to hook global audio session 0 as a default fallback
        registerSession(0, "Sistema Global")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Holofónico IA: Espacializador")
            .setContentText("Procesamiento de audio activo en segundo plano para YouTube, Disney+ y más.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(audioSessionReceiver)
        
        // Release all audio sessions effects
        for (sessionId in activeSessions.keys) {
            unregisterSession(sessionId)
        }
        activeSessions.clear()

        instance = null
        isServiceActive = false
        _isServiceRunning.value = false
        Log.d(TAG, "SpatialAudioService destroyed")
    }

    /**
     * Registers a new player audio session and instantiates the system audio effects.
     */
    private fun registerSession(sessionId: Int, packageName: String) {
        if (activeSessions.containsKey(sessionId)) return

        Log.d(TAG, "Registering and applying 3D effects on session $sessionId for player: $packageName")
        try {
            val effects = SessionEffects(sessionId, packageName)
            effects.initialize()
            activeSessions[sessionId] = effects
            applyEffectsParameters()
        } catch (e: Exception) {
            Log.e(TAG, "Error instantiating effects for session $sessionId: ${e.message}")
        }
    }

    /**
     * Releases system audio effects for a closed session.
     */
    private fun unregisterSession(sessionId: Int) {
        val effects = activeSessions.remove(sessionId)
        if (effects != null) {
            Log.d(TAG, "Releasing 3D effects on session $sessionId for player: ${effects.packageName}")
            effects.release()
        }
    }

    /**
     * Exposes active playing packages for the UI to display.
     */
    fun getActivePlayers(): List<String> {
        return activeSessions.values.map { "${it.packageName} (Sess: ${it.sessionId})" }
    }

    /**
     * Applies the active spatial coordinates, reverb, and filter parameters to all active sessions.
     */
    @Synchronized
    fun applyEffectsParameters() {
        val sessionsToCleanup = mutableListOf<Int>()
        for ((id, effects) in activeSessions) {
            try {
                effects.update()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating session $id effects, will cleanup: ${e.message}")
                sessionsToCleanup.add(id)
            }
        }
        for (id in sessionsToCleanup) {
            unregisterSession(id)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal del Servicio de Audio Espacial",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal del servicio holofónico de audio de fondo"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Container for Android AudioEffects attached to a specific playback session.
     */
    private inner class SessionEffects(val sessionId: Int, val packageName: String) {
        private var virtualizer: Virtualizer? = null
        private var reverb: EnvironmentalReverb? = null
        private var equalizer: Equalizer? = null

        fun initialize() {
            // 1. Initialize Virtualizer (for HRTF 3D spatial width)
            try {
                virtualizer = Virtualizer(0, sessionId).apply {
                    enabled = itdEnabled || ildEnabled
                    if (strengthSupported) {
                        // Max strength 3D effect
                        setStrength(1000.toShort())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Virtualizer init error on session $sessionId: ${e.message}")
            }

            // 2. Initialize EnvironmentalReverb (for Schroeder/RT60 simulation)
            try {
                reverb = EnvironmentalReverb(0, sessionId).apply {
                    enabled = reverbEnabled
                    decayTime = (rt60 * 1000).toInt().coerceIn(100, 5000)
                    reflectionsLevel = (-1000).toShort()
                    reverbLevel = (-1000).toShort()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverb init error on session $sessionId: ${e.message}")
            }

            // 3. Initialize Equalizer (for material absorption filter simulation)
            try {
                equalizer = Equalizer(0, sessionId).apply {
                    enabled = filterEnabled
                }
            } catch (e: Exception) {
                Log.e(TAG, "Equalizer init error on session $sessionId: ${e.message}")
            }
        }

        fun update() {
            // Update Virtualizer (HRTF)
            try {
                virtualizer?.let { v ->
                    val desiredState = itdEnabled || ildEnabled
                    if (v.enabled != desiredState) {
                        v.enabled = desiredState
                    }
                    if (desiredState) {
                        // Adjust virtualizer strength based on sound mode
                        val targetStrength = when (soundSystemMode) {
                            "Ultimea Aura 7.1.2 (Atmos)" -> 1000.toShort()
                            "Ultimea Poseidon 5.1 (Surround)" -> 900.toShort()
                            "Ultimea Solo 2.1 (Extendido)" -> 800.toShort()
                            else -> ((abs(x) * 1000).toInt().coerceIn(200, 1000).toShort()) // Binaural: dynamic
                        }
                        v.setStrength(targetStrength)
                    }
                }
            } catch (e: Exception) {
                // Ignore silent update errors
            }

            // Update Reverb (Room / RT60)
            try {
                reverb?.let { r ->
                    if (r.enabled != reverbEnabled) {
                        r.enabled = reverbEnabled
                    }
                    if (reverbEnabled) {
                        // Map RT60 directly to room decay time in ms
                        val targetDecayMs = (rt60 * 1000).toInt().coerceIn(150, 6000)
                        r.decayTime = targetDecayMs
                        
                        // Adapt reverb volume level based on Z (proximity)
                        // Near objects have lower reverb relative to dry; far objects have higher reverb
                        val proximityFactor = (z / 5.0f).coerceIn(0f, 1f)
                        val targetReverbLevel = (-2000 + (proximityFactor * 1500).toInt()).toShort()
                        r.reverbLevel = targetReverbLevel
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Update Equalizer (Material frequency response / Absorption)
            try {
                equalizer?.let { eq ->
                    if (eq.enabled != filterEnabled) {
                        eq.enabled = filterEnabled
                    }
                    if (filterEnabled) {
                        val numBands = eq.numberOfBands
                        if (numBands > 0) {
                            val lastBand = (numBands - 1).toShort()
                            
                            // Base absorption filter: cut high frequencies depending on material
                            var gainDbMillibels = when (material) {
                                "Piedra" -> 150.toShort()        // Crisp, highly reflective (+1.5 dB)
                                "Metal" -> 300.toShort()         // Resonant metal (+3.0 dB)
                                "Madera" -> (-1000).toShort()     // Warm, wood absorbs high frequencies (-10 dB)
                                "Aire Libre" -> (-2000).toShort() // Heavy high-frequency attenuation (-20 dB)
                                else -> (-500).toShort()
                            }
                            
                            // Adjust EQ curve depending on the selected Soundbar system mode
                            when (soundSystemMode) {
                                "Ultimea Aura 7.1.2 (Atmos)" -> {
                                    // U-curve signature (boost bass & ultra highs)
                                    eq.setBandLevel(0, 400.toShort()) // Bass boost
                                    if (numBands >= 3) {
                                        eq.setBandLevel(1, (-100).toShort()) // Dip mid-bass
                                        val midHighBand = (numBands - 2).toShort()
                                        eq.setBandLevel(midHighBand, (gainDbMillibels / 2).toShort())
                                    }
                                    eq.setBandLevel(lastBand, (gainDbMillibels + 300).toShort()) // Crisp treble boost
                                }
                                "Ultimea Poseidon 5.1 (Surround)" -> {
                                    // Cinematic heavy bass rumble
                                    eq.setBandLevel(0, 600.toShort()) // Heavy bass
                                    if (numBands >= 3) {
                                        eq.setBandLevel(1, 200.toShort())
                                        val midHighBand = (numBands - 2).toShort()
                                        eq.setBandLevel(midHighBand, (gainDbMillibels / 2).toShort())
                                    }
                                    eq.setBandLevel(lastBand, gainDbMillibels)
                                }
                                "Ultimea Solo 2.1 (Extendido)" -> {
                                    // Vocal-optimized: boost mids/high-mids for dialogue clarity
                                    eq.setBandLevel(0, (-100).toShort()) // Tame boominess
                                    if (numBands >= 4) {
                                        eq.setBandLevel(2, 500.toShort()) // Vocal presence
                                        eq.setBandLevel(3, 300.toShort())
                                    } else if (numBands >= 3) {
                                        eq.setBandLevel(1, 400.toShort())
                                    }
                                    eq.setBandLevel(lastBand, (gainDbMillibels - 100).toShort())
                                }
                                else -> {
                                    // Default Binaural: Flat balanced EQ with only material absorption
                                    eq.setBandLevel(0, 0.toShort())
                                    if (numBands >= 3) {
                                        val midHighBand = (numBands - 2).toShort()
                                        eq.setBandLevel(midHighBand, (gainDbMillibels / 2).toShort())
                                    }
                                    eq.setBandLevel(lastBand, gainDbMillibels)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        fun release() {
            try { virtualizer?.release() } catch (e: Exception) {}
            try { reverb?.release() } catch (e: Exception) {}
            try { equalizer?.release() } catch (e: Exception) {}
            
            virtualizer = null
            reverb = null
            equalizer = null
        }
    }
}
