package com.taha.audiohaptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object ToneGeneratorEngine {
    private const val SAMPLE_RATE = 48000 // معدل 48kHz لتفعيل معالج الصوت والدولبي أتموس بالكامل

    @Volatile var outputMode: Int = 0 // 0 = التابلت + الإيربودز | 1 = التابلت فقط | 2 = الإيربودز فقط
    @Volatile var baseFrequencyHz: Double = 105.0 // تردد الرنين المثالي لهيكل Tab A7
    @Volatile var volumeGain: Float = 0.90f
    @Volatile var enabled: Boolean = true

    private val isPlaying = AtomicBoolean(false)
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var playThread: Thread? = null

    fun stopVibration() {
        isPlaying.set(false)
        try {
            currentTrack?.pause()
            currentTrack?.flush()
            currentTrack?.release()
        } catch (_: Throwable) {
        }
        currentTrack = null
    }

    fun triggerVibration(context: Context?, durationMs: Long, amplitude: Int = 255) {
        if (!enabled || context == null || durationMs <= 0) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        // إيقاف أي اهتزاز سابق فوراً لمنع تداخل الأصوات
        stopVibration()

        val ampFactor = (amplitude.coerceIn(1, 255) / 255f) * volumeGain
        val safeDuration = durationMs.coerceIn(15L, 10000L) // دعم من 15ms وحتى 10 ثوانٍ متواصلة

        isPlaying.set(true)
        playThread = Thread {
            playMotorSimulation(audioManager, safeDuration, ampFactor)
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun playMotorSimulation(audioManager: AudioManager, durationMs: Long, gain: Float) {
        try {
            val usage = when (outputMode) {
                0 -> AudioAttributes.USAGE_ALARM // يخرج من السماعات الأربعة + الإيربودز معاً
                1 -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION // السماعات الداخلية فقط
                else -> AudioAttributes.USAGE_MEDIA // الإيربودز (البلوتوث) فقط عبر Dolby Atmos
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE) // تفعيل معالجة Dolby Atmos والمحيطية
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // ستيريو لتشغيل السماعات الأربعة معاً
                .build()

            val totalFrames = ((durationMs / 1000.0) * SAMPLE_RATE).toInt()
            // تقسيم البث لأجزاء صغيرة (Chunks) لإمكانية الإيقاف الفوري في أي لحظة
            val chunkFrames = (SAMPLE_RATE * 0.02).toInt() // 20ms لكل دفعة
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkFrames * 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (outputMode == 1) {
                val speakers = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val builtInSpeaker = speakers.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (builtInSpeaker != null) {
                    track.preferredDevice = builtInSpeaker
                }
            }

            currentTrack = track
            track.play()

            val stereoBuffer = ShortArray(chunkFrames * 2)
            var framesWritten = 0

            while (isPlaying.get() && framesWritten < totalFrames) {
                val framesToWrite = minOf(chunkFrames, totalFrames - framesWritten)

                for (i in 0 until framesToWrite) {
                    val globalSample = framesWritten + i
                    val timeSec = globalSample.toDouble() / SAMPLE_RATE

                    // 1. ضربة انطلاق الموتور (Transient Kick) في أول 12 مللي ثانية
                    val kickFreq = if (timeSec < 0.012) {
                        baseFrequencyHz + 65.0 * exp(-timeSec * 250.0)
                    } else {
                        baseFrequencyHz
                    }

                    // 2. الموجة الأساسية + توافقية ميكانيكية (Harmonic) + رعشة دوران (AM Modulation 32Hz)
                    val primary = sin(2.0 * PI * kickFreq * timeSec)
                    val harmonic = 0.35 * sin(2.0 * PI * (baseFrequencyHz * 2.0) * timeSec)
                    val motorRumble = 0.82 + 0.18 * sin(2.0 * PI * 32.0 * timeSec)

                    // 3. تنعيم البداية والنهاية لمنع الطقطقة (Anti-pop Envelope)
                    val attack = (globalSample / (SAMPLE_RATE * 0.004)).coerceIn(0.0, 1.0)
                    val remaining = totalFrames - globalSample
                    val release = (remaining / (SAMPLE_RATE * 0.008)).coerceIn(0.0, 1.0)

                    val rawSignal = (primary + harmonic) * motorRumble * attack * release
                    val finalSample = (rawSignal * Short.MAX_VALUE * 0.72 * gain)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()

                    // كتابة نفس الإشارة للقناتين اليمنى واليسرى لتشغيل كل السماعات الأربعة بقوة متساوية
                    stereoBuffer[i * 2] = finalSample
                    stereoBuffer[i * 2 + 1] = finalSample
                }

                track.write(stereoBuffer, 0, framesToWrite * 2)
                framesWritten += framesToWrite
            }

            if (isPlaying.get()) {
                track.stop()
            }
            track.release()
        } catch (_: Throwable) {
        } finally {
            isPlaying.set(false)
        }
    }
}
