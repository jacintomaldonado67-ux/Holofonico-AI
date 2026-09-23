package com.example.data

import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini REST API Models ---

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    val mimeType: String,
    val data: String // Base64 representation
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiConfig(
    val responseMimeType: String? = "application/json",
    val temperature: Float? = 0.2f
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

// --- Parsed Target Output Models ---

@JsonClass(generateAdapter = true)
data class AudioSceneAnalysis(
    val sceneType: String, // Cerrado, Abierto, Catedral, Pasillo
    val rt60: Float, // Coeficiente de reverberación
    val materials: String, // Madera, Piedra, Metal, Aire Libre
    val objects: List<TrackedObject>
)

@JsonClass(generateAdapter = true)
data class TrackedObject(
    val label: String, // Voz humana, Auto, Pasos, Drone
    val x: Float, // [-1.0, 1.0] (Izquierda a Derecha)
    val y: Float, // [-1.0, 1.0] (Atrás a Adelante)
    val z: Float  // [0.1, 5.0] (Proximidad/Profundidad)
)

// --- Retrofit API Service ---

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

// --- Singleton Client ---

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    /**
     * Sends a text prompt (and optionally a base64 scene frame) to Gemini to extract spatial metadata.
     */
    suspend fun analyzeScene(
        sceneDescription: String,
        imageBase64: String? = null
    ): AudioSceneAnalysis {
        val apiKey = BuildConfig.GEMINI_API_KEY
        
        // Safety Fallback: if API Key is empty or placeholder, return simulated results gracefully.
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return simulateAnalysis(sceneDescription)
        }

        val prompt = """
            Analiza el entorno acústico y los emisores de sonido en base a: "$sceneDescription".
            Debes retornar UN SOLO OBJETO JSON con la siguiente estructura exacta:
            {
              "sceneType": "Cerrado" | "Abierto" | "Catedral" | "Pasillo",
              "rt60": (Float entre 0.1 y 5.0 que representa el tiempo de reverberación en segundos),
              "materials": "Madera" | "Piedra" | "Metal" | "Aire Libre",
              "objects": [
                {
                  "label": "Voz humana" | "Auto" | "Pasos" | "Drone",
                  "x": (Float entre -1.0 y 1.0),
                  "y": (Float entre -1.0 y 1.0),
                  "z": (Float entre 0.1 y 5.0)
                }
              ]
            }
            Asegúrate de estimar el RT60 correspondiente al espacio (ej. Catedral tiene RT60 alto 3-5s, Aire Libre casi 0, Estudio 0.3s).
            Coloca de 1 a 3 objetos en coordenadas lógicas.
        """.trimIndent()

        val parts = mutableListOf<GeminiPart>()
        if (imageBase64 != null) {
            parts.add(GeminiPart(inlineData = GeminiInlineData("image/jpeg", imageBase64)))
        }
        parts.add(GeminiPart(text = prompt))

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts)),
            generationConfig = GeminiConfig(responseMimeType = "application/json"),
            systemInstruction = GeminiContent(listOf(GeminiPart(
                text = "Eres un algoritmo extractor de metadatos acústicos espaciales de precisión extrema. Retorna únicamente JSON válido."
            )))
        )

        return try {
            val response = apiService.generateContent("gemini-3.5-flash", apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw Exception("No candidates received")
            
            // Parse JSON using Moshi
            val adapter = moshi.adapter(AudioSceneAnalysis::class.java)
            adapter.fromJson(jsonText) ?: throw Exception("JSON conversion returned null")
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to simulated mapping on network failure
            simulateAnalysis(sceneDescription)
        }
    }

    private fun simulateAnalysis(sceneDescription: String): AudioSceneAnalysis {
        val desc = sceneDescription.lowercase()
        return when {
            desc.contains("catedral") || desc.contains("iglesia") || desc.contains("gothic") -> {
                AudioSceneAnalysis(
                    sceneType = "Catedral",
                    rt60 = 4.2f,
                    materials = "Piedra",
                    objects = listOf(
                        TrackedObject("Voz humana", -0.4f, 0.5f, 3.2f),
                        TrackedObject("Pasos", 0.6f, -0.2f, 1.5f)
                    )
                )
            }
            desc.contains("pasillo") || desc.contains("túnel") || desc.contains("corridor") -> {
                AudioSceneAnalysis(
                    sceneType = "Pasillo",
                    rt60 = 2.0f,
                    materials = "Metal",
                    objects = listOf(
                        TrackedObject("Drone", 0.0f, 0.8f, 2.1f),
                        TrackedObject("Pasos", -0.7f, -0.4f, 1.1f)
                    )
                )
            }
            desc.contains("parque") || desc.contains("bosque") || desc.contains("campo") || desc.contains("open") -> {
                AudioSceneAnalysis(
                    sceneType = "Abierto",
                    rt60 = 0.05f,
                    materials = "Aire Libre",
                    objects = listOf(
                        TrackedObject("Auto", 0.9f, 0.8f, 4.5f),
                        TrackedObject("Voz humana", -0.5f, 0.1f, 1.2f)
                    )
                )
            }
            else -> { // Default studio/closed
                AudioSceneAnalysis(
                    sceneType = "Cerrado",
                    rt60 = 0.45f,
                    materials = "Madera",
                    objects = listOf(
                        TrackedObject("Voz humana", 0.3f, 0.2f, 1.0f)
                    )
                )
            }
        }
    }
}
