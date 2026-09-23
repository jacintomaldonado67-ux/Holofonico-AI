package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialAudioSynth {

    // --- State & Coordinates (Volatile for cross-thread memory visibility) ---
    @Volatile var x: Float = 0.0f          // Left to Right [-1.0f, 1.0f]
    @Volatile var y: Float = 1.0f          // Rear to Front [-1.0f, 1.0f]
    @Volatile var z: Float = 1.0f          // Distance [0.1f, 5.0f]

    @Volatile var rt60: Float = 0.45f      // Reverberation time in seconds
    @Volatile var material: String = "Madera" // Wood, Stone, Metal, Outdoor

    @Volatile var itdEnabled: Boolean = true
    @Volatile var ildEnabled: Boolean = true
    @Volatile var reverbEnabled: Boolean = true
    @Volatile var filterEnabled: Boolean = true

    @Volatile var soundType: String = "Drone Cósmico" // "Drone Cósmico", "Voz de Robot", "Ruido de Lluvia", "Pasos Rítmicos"
    @Volatile var isMuted: Boolean = true

    // --- Audio Thread Control ---
    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null
    @Volatile private var isRunning: Boolean = false

    // --- Sample Generation State ---
    private val sampleRate = 44100
    private var phase = 0.0
    private val random = Random()
    private var footstepsTimer = 0
    private var footstepsActive = false
    private var footstepsEnvelope = 0.0f

    // --- DSP Buffers & Filters ---
    // ITD Delay lines (Stereo)
    private val maxItdDelay = 32
    private val itdBufferL = FloatArray(128)
    private val itdBufferR = FloatArray(128)
    private var itdWriteIndex = 0

    // Reverb Delay lines (Comb filters)
    private val reverbDelayL = 1943 // samples (~44ms)
    private val reverbDelayR = 2311 // samples (~52ms)
    private val reverbBufferL = FloatArray(4000)
    private val reverbBufferR = FloatArray(4000)
    private var reverbIndexL = 0
    private var reverbIndexR = 0

    // Low-Pass filter state
    private var lastOutL = 0.0f
    private var lastOutR = 0.0f

    // --- Soundbar Sound System Modes & Virtual Channel Delay Lines ---
    @Volatile var soundSystemMode: String = "Auriculares HRTF (Binaural)"

    private val surroundDelaySamples = 660 // ~15ms delay for side-wall bounce crosstalk cancellation
    private val heightDelaySamples = 308    // ~7ms delay for height-ceiling bounce reflection

    private val slBuffer = FloatArray(1024)
    private val srBuffer = FloatArray(1024)
    private val hlBuffer = FloatArray(1024)
    private val hrBuffer = FloatArray(1024)

    private var slIndex = 0
    private var srIndex = 0
    private var hlIndex = 0
    private var hrIndex = 0

    private var lastHeightOutL = 0.0f
    private var lastHeightOutR = 0.0f

    /**
     * Starts the spatial audio synthesis thread.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        isMuted = false

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 1024 shorts is 512 stereo frames (~11.6ms), low latency
        val bufferSize = max(minBufSize, 2048)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        workerThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            dspLoop()
        }.apply {
            priority = Thread.MAX_PRIORITY
            name = "SpatialAudioSynthThread"
            start()
        }
    }

    /**
     * Stops and releases the synthesis resources.
     */
    fun stop() {
        isRunning = false
        isMuted = true
        workerThread?.join(500)
        workerThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }

    /**
     * Core DSP processing loop executing at 44.1kHz.
     */
    private fun dspLoop() {
        // Output block size: 512 stereo samples (1024 shorts total)
        val blockSize = 512
        val shortBuffer = ShortArray(blockSize * 2)

        while (isRunning) {
            if (isMuted) {
                // If muted, fill the buffer with silence and sleep shortly to conserve CPU/Battery
                shortBuffer.fill(0)
                audioTrack?.write(shortBuffer, 0, shortBuffer.size)
                Thread.sleep(10)
                continue
            }

            // Local cache of volatile variables for sample consistency during block processing
            val currentX = x
            val currentY = y
            val currentZ = z
            val currentRt60 = rt60
            val currentMaterial = material
            val useItd = itdEnabled
            val useIld = ildEnabled
            val useReverb = reverbEnabled
            val useFilter = filterEnabled
            val selectedSound = soundType
            val systemMode = soundSystemMode

            // Calculated physical parameters
            val distance = sqrt(currentX * currentX + currentY * currentY + currentZ * currentZ)
            val clampedDist = max(0.4f, distance)
            
            // ILD (Atenuación por distancia y sombreado de cabeza)
            val baseAttenuation = 1.0f / (clampedDist * 0.8f + 0.2f) // Inverse law
            
            var volumeL = baseAttenuation
            var volumeR = baseAttenuation
            
            if (useIld) {
                // Pan/ILD calculations based on source orientation
                if (currentX > 0) { // On the right, Left ear shadowed
                    volumeL *= (1.0f - currentX * 0.6f)
                } else if (currentX < 0) { // On the left, Right ear shadowed
                    volumeR *= (1.0f - abs(currentX) * 0.6f)
                }
            }

            // ITD (Delay in samples): Max delay 24 samples (~540 µs delay for head diameter)
            val itdDelaySamples = if (useItd) {
                val delay = (abs(currentX) * 24).toInt()
                min(maxItdDelay, max(0, delay))
            } else {
                0
            }

            // Reverberation Coefficient
            val revFeedback = if (useReverb) {
                min(0.88f, currentRt60 / 5.0f * 0.75f)
            } else {
                0.0f
            }

            // One-pole Low-Pass Filter Alpha (Material absorption)
            val filterAlpha = if (useFilter) {
                when (currentMaterial) {
                    "Piedra" -> 0.95f       // Crisp, long reflections
                    "Metal" -> 0.92f        // Metallic high frequency
                    "Madera" -> 0.65f       // Absorb high frequencies, warm
                    "Aire Libre" -> 0.40f   // High absorption, muffled
                    else -> 0.70f
                }
            } else {
                1.0f // Bypass (Alpha = 1 passes everything)
            }

            // Synthesize block of audio
            for (i in 0 until blockSize) {
                // 1. Synthesize base sound source wave
                val rawSource = generateSoundSource(selectedSound)
                var sampleL = 0.0f
                var sampleR = 0.0f

                when (systemMode) {
                    "Ultimea Aura 7.1.2 (Atmos)" -> {
                        // 1. Calculate gain coefficients based on source position relative to virtual speaker coordinates
                        // C (0, 1, 1)
                        val distC = sqrt(currentX * currentX + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainC = max(0.02f, 1.0f - distC * 0.45f)

                        // L (-0.5, 1, 1) / R (0.5, 1, 1)
                        val distL = sqrt((currentX + 0.5f) * (currentX + 0.5f) + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainL = max(0.02f, 1.0f - distL * 0.45f)
                        val distR = sqrt((currentX - 0.5f) * (currentX - 0.5f) + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainR = max(0.02f, 1.0f - distR * 0.45f)

                        // SL (-1, -0.2, 1.2) / SR (1, -0.2, 1.2)
                        val distSL = sqrt((currentX + 1.0f) * (currentX + 1.0f) + (currentY + 0.2f) * (currentY + 0.2f) + (currentZ - 1.2f) * (currentZ - 1.2f))
                        val gainSL = max(0.02f, 1.0f - distSL * 0.45f)
                        val distSR = sqrt((currentX - 1.0f) * (currentX - 1.0f) + (currentY + 0.2f) * (currentY + 0.2f) + (currentZ - 1.2f) * (currentZ - 1.2f))
                        val gainSR = max(0.02f, 1.0f - distSR * 0.45f)

                        // LH (-0.3, 0.5, 2) / RH (0.3, 0.5, 2)
                        val distLH = sqrt((currentX + 0.3f) * (currentX + 0.3f) + (currentY - 0.5f) * (currentY - 0.5f) + (currentZ - 2.0f) * (currentZ - 2.0f))
                        val gainLH = max(0.02f, 1.0f - distLH * 0.40f)
                        val distRH = sqrt((currentX - 0.3f) * (currentX - 0.3f) + (currentY - 0.5f) * (currentY - 0.5f) + (currentZ - 2.0f) * (currentZ - 2.0f))
                        val gainRH = max(0.02f, 1.0f - distRH * 0.40f)

                        // 2. Synthesize virtual speaker channel signals
                        val sigC = rawSource * gainC
                        val sigL = rawSource * gainL
                        val sigR = rawSource * gainR
                        val sigSL = rawSource * gainSL
                        val sigSR = rawSource * gainSR
                        val sigLH = rawSource * gainLH
                        val sigRH = rawSource * gainRH

                        // 3. Write SL/SR to surround buffers and read crosstalk-cancelled delayed signals
                        slBuffer[slIndex] = sigSL
                        val delayedSL = slBuffer[(slIndex - surroundDelaySamples + slBuffer.size) % slBuffer.size]
                        slIndex = (slIndex + 1) % slBuffer.size

                        srBuffer[srIndex] = sigSR
                        val delayedSR = srBuffer[(srIndex - surroundDelaySamples + srBuffer.size) % srBuffer.size]
                        srIndex = (srIndex + 1) % srBuffer.size

                        // 4. Write LH/RH to height buffers, read delayed ceiling-bounce signals, apply HRTF treble-boost/high-pass
                        hlBuffer[hlIndex] = sigLH
                        val delayedLH = hlBuffer[(hlIndex - heightDelaySamples + hlBuffer.size) % hlBuffer.size]
                        hlIndex = (hlIndex + 1) % hlBuffer.size

                        hrBuffer[hrIndex] = sigRH
                        val delayedRH = hrBuffer[(hrIndex - heightDelaySamples + hrBuffer.size) % hrBuffer.size]
                        hrIndex = (hrIndex + 1) % hrBuffer.size

                        // Peak high pass filter for heights to simulate pinna head transfer spectral tilt (treble accentuation)
                        val sigHeightL = delayedLH * 0.35f + (delayedLH - lastHeightOutL) * 0.65f
                        val sigHeightR = delayedRH * 0.35f + (delayedRH - lastHeightOutR) * 0.65f
                        lastHeightOutL = delayedLH
                        lastHeightOutR = delayedRH

                        // 5. Downmix everything to Left and Right physical channels of the Soundbar
                        // Left physical gets Center (panned mid), Left, Surround Left, Height Left, and Crosstalk Cancellation of Surround Right
                        sampleL = 0.5f * sigC + sigL + sigSL + sigHeightL - 0.55f * delayedSR
                        // Right physical gets Center (panned mid), Right, Surround Right, Height Right, and Crosstalk Cancellation of Surround Left
                        sampleR = 0.5f * sigC + sigR + sigSR + sigHeightR - 0.55f * delayedSL
                    }
                    "Ultimea Poseidon 5.1 (Surround)" -> {
                        // 5.1 Mode: L, R, C, SL, SR
                        val distC = sqrt(currentX * currentX + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainC = max(0.02f, 1.0f - distC * 0.45f)

                        val distL = sqrt((currentX + 0.5f) * (currentX + 0.5f) + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainL = max(0.02f, 1.0f - distL * 0.45f)
                        val distR = sqrt((currentX - 0.5f) * (currentX - 0.5f) + (currentY - 1f) * (currentY - 1f) + (currentZ - 1f) * (currentZ - 1f))
                        val gainR = max(0.02f, 1.0f - distR * 0.45f)

                        val distSL = sqrt((currentX + 1.0f) * (currentX + 1.0f) + (currentY + 0.2f) * (currentY + 0.2f) + (currentZ - 1.2f) * (currentZ - 1.2f))
                        val gainSL = max(0.02f, 1.0f - distSL * 0.45f)
                        val distSR = sqrt((currentX - 1.0f) * (currentX - 1.0f) + (currentY + 0.2f) * (currentY + 0.2f) + (currentZ - 1.2f) * (currentZ - 1.2f))
                        val gainSR = max(0.02f, 1.0f - distSR * 0.45f)

                        val sigC = rawSource * gainC
                        val sigL = rawSource * gainL
                        val sigR = rawSource * gainR
                        val sigSL = rawSource * gainSL
                        val sigSR = rawSource * gainSR

                        slBuffer[slIndex] = sigSL
                        val delayedSL = slBuffer[(slIndex - surroundDelaySamples + slBuffer.size) % slBuffer.size]
                        slIndex = (slIndex + 1) % slBuffer.size

                        srBuffer[srIndex] = sigSR
                        val delayedSR = srBuffer[(srIndex - surroundDelaySamples + srBuffer.size) % srBuffer.size]
                        srIndex = (srIndex + 1) % srBuffer.size

                        // Downmix without heights
                        sampleL = 0.5f * sigC + sigL + sigSL - 0.50f * delayedSR
                        sampleR = 0.5f * sigC + sigR + sigSR - 0.50f * delayedSL
                    }
                    "Ultimea Solo 2.1 (Extendido)" -> {
                        // Extended Stereo Mode: Wide spatial matrix with delay-based Haas width expansion
                        val midSig = rawSource * 0.5f
                        val sideSig = rawSource * currentX * 0.6f

                        slBuffer[slIndex] = sideSig
                        // Haas effect delay (approx 2.5ms delay = 110 samples)
                        val delayedSide = slBuffer[(slIndex - 110 + slBuffer.size) % slBuffer.size]
                        slIndex = (slIndex + 1) % slBuffer.size

                        sampleL = midSig + sideSig + 0.4f * delayedSide
                        sampleR = midSig - sideSig - 0.4f * delayedSide
                    }
                    else -> {
                        // "Auriculares HRTF (Binaural)" (Default standard headphones HRTF)
                        itdBufferL[itdWriteIndex] = rawSource
                        itdBufferR[itdWriteIndex] = rawSource

                        if (useItd) {
                            if (currentX > 0) {
                                val readIndexL = (itdWriteIndex - itdDelaySamples + itdBufferL.size) % itdBufferL.size
                                sampleL = itdBufferL[readIndexL]
                                sampleR = itdBufferR[itdWriteIndex]
                            } else {
                                val readIndexR = (itdWriteIndex - itdDelaySamples + itdBufferR.size) % itdBufferR.size
                                sampleL = itdBufferL[itdWriteIndex]
                                sampleR = itdBufferR[readIndexR]
                            }
                        } else {
                            sampleL = rawSource
                            sampleR = rawSource
                        }
                        itdWriteIndex = (itdWriteIndex + 1) % itdBufferL.size

                        sampleL *= volumeL
                        sampleR *= volumeR
                    }
                }

                // 4. Apply Reverberation Comb feedback loop (Schroeder element)
                if (useReverb && revFeedback > 0.05f) {
                    // Left Reverb Comb
                    val revSampleL = reverbBufferL[reverbIndexL]
                    reverbBufferL[reverbIndexL] = sampleL + revSampleL * revFeedback
                    sampleL = sampleL * 0.7f + revSampleL * 0.3f
                    reverbIndexL = (reverbIndexL + 1) % reverbDelayL

                    // Right Reverb Comb (independent delay for wide stereo field)
                    val revSampleR = reverbBufferR[reverbIndexR]
                    reverbBufferR[reverbIndexR] = sampleR + revSampleR * revFeedback
                    sampleR = sampleR * 0.7f + revSampleR * 0.3f
                    reverbIndexR = (reverbIndexR + 1) % reverbDelayR
                }

                // 5. Apply Material absorption Low-Pass Filter (One-pole recursion)
                if (useFilter) {
                    sampleL = filterAlpha * sampleL + (1.0f - filterAlpha) * lastOutL
                    sampleR = filterAlpha * sampleR + (1.0f - filterAlpha) * lastOutR
                    
                    lastOutL = sampleL
                    lastOutR = sampleR
                }

                // 6. Scale and convert to 16-bit Short (-32768 to 32767)
                val masterVolume = 0.45f // Prevents digital clipping
                val scaledL = (sampleL * masterVolume * 32767.0f).toInt()
                val scaledR = (sampleR * masterVolume * 32767.0f).toInt()

                // Clip bounds safety
                shortBuffer[i * 2] = min(32767, max(-32768, scaledL)).toShort()
                shortBuffer[i * 2 + 1] = min(32767, max(-32768, scaledR)).toShort()
            }

            // Write PCM short buffer block synchronously to AudioTrack buffer
            audioTrack?.write(shortBuffer, 0, shortBuffer.size)
        }
    }

    /**
     * Synthesizes audio samples in real-time based on the selected sound type.
     */
    private fun generateSoundSource(type: String): Float {
        phase += 1.0 / sampleRate
        if (phase > 1000.0) phase = 0.0

        return when (type) {
            "Drone Cósmico" -> {
                // Rich ambient drone: Low hum (110Hz) mixed with a slow modulator (0.2Hz LFO) and harmonics
                val lfo1 = sin(2.0 * PI * 0.15 * phase)
                val frequency = 110.0 + lfo1 * 4.0 // FM synthesis
                val wave1 = sin(2.0 * PI * frequency * phase)
                val wave2 = sin(2.0 * PI * (frequency * 1.5) * phase) * 0.3
                val wave3 = sin(2.0 * PI * (frequency * 2.0) * phase) * 0.15
                (wave1 + wave2 + wave3).toFloat()
            }
            "Voz de Robot" -> {
                // Metallic, ring-modulated sci-fi voice: 220Hz pulse wave modulated by high-speed LFO
                val carrier = sin(2.0 * PI * 220.0 * phase)
                val robotRingMod = sin(2.0 * PI * 85.0 * phase) // Ring modulation
                val rawVal = carrier * robotRingMod
                // Fast exponential sweep to sound speech-like
                val vocalFormant = sin(2.0 * PI * 650.0 * phase) * 0.25f
                (rawVal * 0.7f + vocalFormant).toFloat()
            }
            "Ruido de Lluvia" -> {
                // Soft pinkish white noise for spatial rain visualization
                val whiteNoise = random.nextFloat() * 2.0f - 1.0f
                // Low-pass filter noise slightly to give it a rich rain texture
                val filteredNoise = 0.25f * whiteNoise + 0.75f * (random.nextFloat() * 2.0f - 1.0f)
                filteredNoise
            }
            "Pasos Rítmicos" -> {
                // Synthesizes realistic periodic footsteps clicking
                footstepsTimer++
                // footstep occurs every 0.6s (26460 samples)
                val period = (sampleRate * 0.65).toInt()
                val currentStep = footstepsTimer % period

                if (currentStep < 2000) { // Footstep active (attack/decay duration)
                    if (currentStep == 0) {
                        footstepsActive = true
                        footstepsEnvelope = 1.0f
                    }
                    // Exponential decay envelope
                    footstepsEnvelope *= 0.998f
                    // Short white noise burst representing friction/shoe click
                    val clickNoise = random.nextFloat() * 2.0f - 1.0f
                    val clickFreq = sin(2.0 * PI * 180.0 * phase) * 0.4
                    (clickNoise * footstepsEnvelope * 0.5f + clickFreq * footstepsEnvelope * 0.5f).toFloat()
                } else {
                    footstepsActive = false
                    0.0f
                }
            }
            else -> 0.0f
        }
    }
}
