package com.taha.audiohaptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

object ToneGeneratorEngine {
    private val executor = Executors.newSingleThreadExecutor()
    private const val SAMPLE_RATE = 24000

    @Volatile var outputMode: Int = 0
    @Volatile var frequencyHz: Double = 60.0
    @Volatile var volumeGain: Float = 0.85f
    @Volatile var enabled: Boolean = true

    fun triggerPulse(context: Context?, durationMs: Long) {
        if (!enabled || context == null || durationMs <= 0) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }

        val clampedDuration = durationMs.coerceIn(12L, 120L)

        executor.execute {
            try {
                playSubBass(audioManager, clampedDuration)
            } catch (_: Throwable) {
            }
        }
    }

    private fun playSubBass(audioManager: AudioManager, durationMs: Long) {
        val numSamples = ((durationMs / 1000.0) * SAMPLE_RATE).toInt()
        if (numSamples <= 0) return

        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i / (SAMPLE_RATE / frequencyHz)
            val envelope = when {
                i < numSamples * 0.15 -> i / (numSamples * 0.15)
                i > numSamples * 0.70 -> (numSamples - i) / (numSamples * 0.30)
                else -> 1.0
            }
            val sample = (sin(angle) * envelope * Short.MAX_VALUE * volumeGain).toInt()
            buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val usage = when (outputMode) {
            0 -> AudioAttributes.USAGE_ALARM
            1 -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            else -> AudioAttributes.USAGE_MEDIA
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (outputMode == 1) {
            val speakers = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val builtInSpeaker = speakers.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (builtInSpeaker != null) {
                audioTrack.preferredDevice = builtInSpeaker
            }
        }

        audioTrack.write(buffer, 0, buffer.size)
        audioTrack.play()
        Thread.sleep(durationMs + 10)
        audioTrack.release()
    }
}
