package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.SessionLog
import com.example.data.SpatialAudioRepository
import kotlinx.coroutines.delay
import java.util.Random

class AudioAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Retrieve database and repository
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = SpatialAudioRepository(db)

        // Simulate some computational background DSP analysis
        // Like calibrating RT60 convolution coefficients for acoustic scenes
        var mathAccumulator = 0.0
        val random = Random()
        val totalIterations = 150000

        // Perform some synthetic math calculations to represent CPU work
        for (i in 0 until totalIterations) {
            mathAccumulator += Math.sin(random.nextDouble()) * Math.cos(random.nextDouble())
            if (i % 50000 == 0) {
                // Yield execution to support cancellation
                delay(200)
            }
        }

        // Insert log in local Room DB to confirm execution
        val trackedCoordinatesCount = random.nextInt(150) + 50
        val cpuMockUsage = 1.25f // extremely efficient background load (1.25%)
        
        val completionLog = SessionLog(
            presetId = "background_recalc",
            totalCoordinatesTracked = trackedCoordinatesCount,
            avgCpuUsage = cpuMockUsage,
            energySavingMode = true,
            timestamp = System.currentTimeMillis()
        )

        repository.insertLog(completionLog)

        return Result.success()
    }
}
