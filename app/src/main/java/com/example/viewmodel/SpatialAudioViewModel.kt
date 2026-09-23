package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.audio.SpatialAudioSynth
import com.example.audio.SpatialAudioService
import com.example.data.AcousticPreset
import com.example.data.AppDatabase
import com.example.data.AudioSceneAnalysis
import com.example.data.GeminiClient
import com.example.data.SessionLog
import com.example.data.SpatialAudioRepository
import com.example.worker.AudioAnalysisWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

sealed class GeminiUiState {
    object Idle : GeminiUiState()
    object Loading : GeminiUiState()
    data class Success(val analysis: AudioSceneAnalysis) : GeminiUiState()
    data class Error(val message: String) : GeminiUiState()
}

class SpatialAudioViewModel(
    private val repository: SpatialAudioRepository
) : ViewModel() {

    // --- Singleton DSP Synthesis Engine ---
    val synth = SpatialAudioSynth()

    // --- Room Database Observers ---
    val presets: StateFlow<List<AcousticPreset>> = repository.allPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<SessionLog>> = repository.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- User UI Configurable States ---
    private val _selectedPreset = MutableStateFlow<AcousticPreset?>(null)
    val selectedPreset = _selectedPreset.asStateFlow()

    private val _isMuted = MutableStateFlow(true)
    val isMuted = _isMuted.asStateFlow()

    private val _soundType = MutableStateFlow("Drone Cósmico")
    val soundType = _soundType.asStateFlow()

    private val _soundSystemMode = MutableStateFlow("Auriculares HRTF (Binaural)")
    val soundSystemMode = _soundSystemMode.asStateFlow()

    // DSP Bypass toggles
    private val _itdEnabled = MutableStateFlow(true)
    val itdEnabled = _itdEnabled.asStateFlow()

    private val _ildEnabled = MutableStateFlow(true)
    val ildEnabled = _ildEnabled.asStateFlow()

    private val _reverbEnabled = MutableStateFlow(true)
    val reverbEnabled = _reverbEnabled.asStateFlow()

    private val _filterEnabled = MutableStateFlow(true)
    val filterEnabled = _filterEnabled.asStateFlow()

    // --- Node 4: Procedural Interpolation Engine State ---
    // Target Coordinates (where the user wants the sound to go)
    val targetX = MutableStateFlow(0.0f)
    val targetY = MutableStateFlow(1.0f)
    val targetZ = MutableStateFlow(1.0f)

    // Smooth Interpolated Coordinates (actual audio DSP coordinates)
    private val _interpolatedX = MutableStateFlow(0.0f)
    val interpolatedX = _interpolatedX.asStateFlow()

    private val _interpolatedY = MutableStateFlow(1.0f)
    val interpolatedY = _interpolatedY.asStateFlow()

    private val _interpolatedZ = MutableStateFlow(1.0f)
    val interpolatedZ = _interpolatedZ.asStateFlow()

    // Interpolation Strategies: "Linear", "Esferica", "Física (Muelle/Spring)"
    private val _interpolationMode = MutableStateFlow("Física (Muelle/Spring)")
    val interpolationMode = _interpolationMode.asStateFlow()

    // --- Gemini AI Brain State ---
    private val _geminiState = MutableStateFlow<GeminiUiState>(GeminiUiState.Idle)
    val geminiState = _geminiState.asStateFlow()

    private val _isAutoAdaptEnabled = MutableStateFlow(false)
    val isAutoAdaptEnabled = _isAutoAdaptEnabled.asStateFlow()

    fun setAutoAdaptEnabled(enabled: Boolean) {
        _isAutoAdaptEnabled.value = enabled
    }

    // --- WorkManager State ---
    private val _workStatus = MutableStateFlow("No iniciado")
    val workStatus = _workStatus.asStateFlow()

    // --- Performance Monitors ---
    private val _simulatedCpu = MutableStateFlow(1.5f) // Percent CPU
    val simulatedCpu = _simulatedCpu.asStateFlow()

    private val _simulatedBatterySaved = MutableStateFlow(0) // Wh or simulated percent saved
    val simulatedBatterySaved = _simulatedBatterySaved.asStateFlow()

    // Physics spring states
    private var velocityX = 0.0f
    private var velocityY = 0.0f
    private var velocityZ = 0.0f
    private val springStiffness = 120.0f // Hooke's law stiffness
    private val springDamping = 12.0f     // Damping friction

    private var interpolationJob: Job? = null

    init {
        // Pre-populate Database defaults asynchronously
        viewModelScope.launch {
            repository.populateDefaults()
            // Select default preset
            presets.collect { list ->
                if (list.isNotEmpty() && _selectedPreset.value == null) {
                    val default = list.firstOrNull { it.id == "estudio" } ?: list.first()
                    selectPreset(default)
                }
            }
        }

        // Start 120Hz Procedural Interpolation loop (extremely fast native feel)
        startInterpolationLoop()
    }

    /**
     * Toggles play/mute. Audio synthesis starts/stops based on this.
     */
    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        synth.isMuted = muted
        if (muted) {
            synth.stop()
        } else {
            // Apply current parameters upon starting
            applySynthParameters()
            synth.start()
        }
    }

    fun setSoundType(type: String) {
        _soundType.value = type
        synth.soundType = type
    }

    fun setSoundSystemMode(mode: String) {
        _soundSystemMode.value = mode
        synth.soundSystemMode = mode
        SpatialAudioService.updateSoundSystem(mode)
    }

    fun setItdEnabled(enabled: Boolean) {
        _itdEnabled.value = enabled
        synth.itdEnabled = enabled
        SpatialAudioService.updateBypasses(enabled, _ildEnabled.value, _reverbEnabled.value, _filterEnabled.value)
    }

    fun setIldEnabled(enabled: Boolean) {
        _ildEnabled.value = enabled
        synth.ildEnabled = enabled
        SpatialAudioService.updateBypasses(_itdEnabled.value, enabled, _reverbEnabled.value, _filterEnabled.value)
    }

    fun setReverbEnabled(enabled: Boolean) {
        _reverbEnabled.value = enabled
        synth.reverbEnabled = enabled
        SpatialAudioService.updateBypasses(_itdEnabled.value, _ildEnabled.value, enabled, _filterEnabled.value)
    }

    fun setFilterEnabled(enabled: Boolean) {
        _filterEnabled.value = enabled
        synth.filterEnabled = enabled
        SpatialAudioService.updateBypasses(_itdEnabled.value, _ildEnabled.value, _reverbEnabled.value, enabled)
    }

    fun setInterpolationMode(mode: String) {
        _interpolationMode.value = mode
    }

    /**
     * Selects and loads an acoustic preset, updating DSP coefficients.
     */
    fun selectPreset(preset: AcousticPreset) {
        _selectedPreset.value = preset
        synth.rt60 = preset.rt60
        synth.material = preset.material
        applySynthParameters()
        SpatialAudioService.updateAcoustics(preset.rt60, preset.material)
    }

    private fun applySynthParameters() {
        synth.x = _interpolatedX.value
        synth.y = _interpolatedY.value
        synth.z = _interpolatedZ.value
    }

    /**
     * Direct coordinate update (Node 2: skip interpolation if camera cuts or manual override)
     */
    fun warpToCoordinates(x: Float, y: Float, z: Float) {
        targetX.value = x
        targetY.value = y
        targetZ.value = z
        _interpolatedX.value = x
        _interpolatedY.value = y
        _interpolatedZ.value = z
        applySynthParameters()
    }

    /**
     * Node 4: Continuous Procedural Interpolation Engine.
     * Computes vectors at 120Hz to ensure absolute smoothness.
     */
    private fun startInterpolationLoop() {
        interpolationJob?.cancel()
        interpolationJob = viewModelScope.launch(Dispatchers.Default) {
            val dt = 1.0f / 120.0f // 120Hz time step
            while (true) {
                val tx = targetX.value
                val ty = targetY.value
                val tz = targetZ.value

                var cx = _interpolatedX.value
                var cy = _interpolatedY.value
                var cz = _interpolatedZ.value

                when (_interpolationMode.value) {
                    "Lineal" -> {
                        // Standard Linear interpolation
                        val lerpFactor = 0.08f
                        cx += (tx - cx) * lerpFactor
                        cy += (ty - cy) * lerpFactor
                        cz += (tz - cz) * lerpFactor
                    }
                    "Cúbica (Esferal)" -> {
                        // Ease in/out easing
                        val lerpFactor = 0.05f
                        val dx = tx - cx
                        val dy = ty - cy
                        val dz = tz - cz
                        cx += dx * lerpFactor * (1.0f + abs(dx) * 0.3f)
                        cy += dy * lerpFactor * (1.0f + abs(dy) * 0.3f)
                        cz += dz * lerpFactor * (1.0f + abs(dz) * 0.3f)
                    }
                    else -> { // "Física (Muelle/Spring)"
                        // Hooke's spring equation with damping
                        val forceX = (tx - cx) * springStiffness - velocityX * springDamping
                        velocityX += forceX * dt
                        cx += velocityX * dt

                        val forceY = (ty - cy) * springStiffness - velocityY * springDamping
                        velocityY += forceY * dt
                        cy += velocityY * dt

                        val forceZ = (tz - cz) * springStiffness - velocityZ * springDamping
                        velocityZ += forceZ * dt
                        cz += velocityZ * dt
                    }
                }

                // Push to StateFlows for UI representation
                _interpolatedX.value = cx
                _interpolatedY.value = cy
                _interpolatedZ.value = cz

                // Update real-time DSP thread memory directly
                synth.x = cx
                synth.y = cy
                synth.z = cz

                // Update background system spatializer service
                SpatialAudioService.updateCoordinates(cx, cy, cz)

                // Calculate energy footprint simulation
                updateEnergyMetrics()

                delay(8) // ~120Hz (8.33 milliseconds)
            }
        }
    }

    /**
     * Visual tracking of energy efficiency comparing different rendering paths.
     */
    private var energyAccumulator = 0.0f
    private fun updateEnergyMetrics() {
        // High fidelity is active if synth is unmuted
        val active = !_isMuted.value
        val hasReverb = _reverbEnabled.value
        
        // Calculate simulated CPU percentage:
        // Base overhead of UI thread + DSP Thread synthesis complex waves
        var cpu = 0.45f // background baseline
        if (active) {
            cpu += 0.85f // AudioTrack write
            cpu += when (_soundType.value) {
                "Drone Cósmico" -> 0.40f // FM waves
                "Voz de Robot" -> 0.55f // Metallic wave modulation
                "Ruido de Lluvia" -> 0.25f // Random noise
                "Pasos Rítmicos" -> 0.35f // Rhythmic envelope
                else -> 0.3f
            }
            if (hasReverb) cpu += 0.65f // Feedback Delay Networks
            if (_itdEnabled.value) cpu += 0.15f // circular buffers delay
            if (_filterEnabled.value) cpu += 0.10f // recursive filters
        }

        _simulatedCpu.value = cpu

        // Accumulate saved energy (in Joules or relative units) when bypassing heavy AI
        // Procedural interpolation saves battery by reducing heavy AI network API calls (N FPS -> 0.1 FPS)
        if (active) {
            energyAccumulator += 0.025f // accumulated saved battery score
            _simulatedBatterySaved.value = energyAccumulator.toInt()
        }
    }

    /**
     * Node 3: AI Brain Processing.
     * Uses Gemini to analyze an acoustic description or frame.
     */
    fun analyzeAcousticScene(sceneDescription: String) {
        _geminiState.value = GeminiUiState.Loading
        viewModelScope.launch {
            try {
                val analysisResult = withContext(Dispatchers.IO) {
                    GeminiClient.analyzeScene(sceneDescription)
                }
                
                // On Success: Add custom AI preset to Room DB
                val presetId = "ai_" + analysisResult.sceneType.lowercase()
                val customPreset = AcousticPreset(
                    id = presetId,
                    name = "IA: " + analysisResult.sceneType,
                    type = analysisResult.sceneType,
                    rt60 = analysisResult.rt60,
                    material = analysisResult.materials,
                    isCustom = true
                )
                repository.insertPreset(customPreset)

                // Select this preset in DB
                selectPreset(customPreset)

                // Warp coordinates of audio emitter to the object tracked by the IA (Node 3B)
                val primaryObject = analysisResult.objects.firstOrNull()
                if (primaryObject != null) {
                    // Warp to detected coordinates
                    warpToCoordinates(primaryObject.x, primaryObject.y, primaryObject.z)
                    if (primaryObject.label.isNotBlank()) {
                        // Match sound source type if matched
                        when {
                            primaryObject.label.contains("voz", true) -> setSoundType("Voz de Robot")
                            primaryObject.label.contains("pasos", true) -> setSoundType("Pasos Rítmicos")
                            primaryObject.label.contains("auto", true) -> setSoundType("Drone Cósmico")
                            primaryObject.label.contains("drone", true) -> setSoundType("Drone Cósmico")
                        }
                    }
                }

                _geminiState.value = GeminiUiState.Success(analysisResult)

                // Insert Log in database
                val trackingLog = SessionLog(
                    presetId = presetId,
                    totalCoordinatesTracked = analysisResult.objects.size,
                    avgCpuUsage = _simulatedCpu.value,
                    energySavingMode = false, // IA consumed temporary burst
                    timestamp = System.currentTimeMillis()
                )
                repository.insertLog(trackingLog)

            } catch (e: Exception) {
                _geminiState.value = GeminiUiState.Error(e.message ?: "Error desconocido de IA")
            }
        }
    }

    /**
     * Background battery / scheduling optimizer using WorkManager.
     */
    fun scheduleBackgroundCalibration(context: Context) {
        _workStatus.value = "Programando..."
        
        val workRequest = OneTimeWorkRequestBuilder<AudioAnalysisWorker>().build()
        val workManager = WorkManager.getInstance(context)
        
        workManager.enqueue(workRequest)

        // Observe progress of background worker live in UI
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    val statusText = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> "Encolado (WorkManager)"
                        WorkInfo.State.RUNNING -> "Analizando acústica de sala en segundo plano..."
                        WorkInfo.State.SUCCEEDED -> "¡Calibrado con éxito! (Batería optimizada)"
                        WorkInfo.State.FAILED -> "Error de calibración en segundo plano"
                        WorkInfo.State.CANCELLED -> "Calibración cancelada"
                        else -> "Estado: ${workInfo.state}"
                    }
                    _workStatus.value = statusText
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        interpolationJob?.cancel()
        synth.stop()
    }
}

// --- ViewModel Factory ---
class SpatialAudioViewModelFactory(private val repository: SpatialAudioRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SpatialAudioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SpatialAudioViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
