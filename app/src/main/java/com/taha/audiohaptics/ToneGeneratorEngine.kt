package com.taha.audiohaptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object ToneGeneratorEngine {
    private const val SAMPLE_RATE = 48000

    @Volatile var outputMode: Int = 0 // 0 = التابلت + الإيربودز | 1 = التابلت فقط | 2 = الإيربودز فقط
    @Volatile var baseFrequencyHz: Double = 105.0
    @Volatile var volumeGain: Float = 0.90f
    @Volatile var enabled: Boolean = true

    private val isEngineRunning = AtomicBoolean(false)
    private val needsTrackRebuild = AtomicBoolean(false)

    // حالة النبضة الحالية (تُحدّث لحظياً بدون Locks أو إنشاء Threads)
    private val remainingSamples = AtomicLong(0L)
    private val totalPulseSamples = AtomicLong(1L)
    private val currentPulseSample = AtomicLong(0L)
    private val currentAmplitude = AtomicInteger(0)

    @Volatile private var appContext: Context? = null

    fun ensureStarted(context: Context?) {
        if (context != null) {
            appContext = context.applicationContext ?: context
        }
        if (isEngineRunning.compareAndSet(false, true)) {
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioLoop()
            }, "AudioHapticsEngine").start()
        }
    }

    fun requestTrackRebuild() {
        needsTrackRebuild.set(true)
    }

    fun stopVibration() {
        // خفض متدرج سريع خلال 5 مللي ثانية لمنع أي صوت خربشة عند الإيقاف
        val fadeSamples = (SAMPLE_RATE * 0.005).toLong()
        if (remainingSamples.get() > fadeSamples) {
            remainingSamples.set(fadeSamples)
        }
    }

    fun triggerVibration(context: Context?, durationMs: Long, amplitude: Int = 255) {
        if (!enabled || durationMs <= 0) return
        ensureStarted(context)

        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        val safeDuration = durationMs.coerceIn(14L, 10000L)
        val samples = ((safeDuration / 1000.0) * SAMPLE_RATE).toLong()

        currentAmplitude.set(amplitude.coerceIn(1, 255))
        totalPulseSamples.set(samples)
        currentPulseSample.set(0L)
        remainingSamples.set(samples)
    }

    private fun buildAudioTrack(audioManager: AudioManager): AudioTrack {
        val usage = when (outputMode) {
            0 -> AudioAttributes.USAGE_ALARM
            1 -> AudioAttributes.USAGE_ALARM // نستخدم ALARM لتفعيل السماعات الأربعة و Dolby Atmos
            else -> AudioAttributes.USAGE_MEDIA
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE) // تفعيل Dolby Atmos
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val chunkFrames = 240 // 5ms لكل دفعة لاستجابة لحظية بدون تأخير
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(chunkFrames * 8)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()

        // توجيه مخرج الصوت بدقة حسب اختيار المستخدم
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        when (outputMode) {
            1 -> {
                // إجبار الخروج من سماعات التابلت الداخلية فقط وعزل الإيربودز
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    track.preferredDevice = speaker
                }
            }
            2 -> {
                // توجيه للإيربودز / البلوتوث فقط
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btDevice != null) {
                    track.preferredDevice = btDevice
                }
            }
            else -> {
                track.preferredDevice = null
            }
        }

        track.play()
        return track
    }

    private fun audioLoop() {
        val chunkFrames = 240 // 5ms per buffer
        val stereoBuffer = ShortArray(chunkFrames * 2)
        var track: AudioTrack? = null
        var phasePrimary = 0.0
        var phaseHarmonic = 0.0
        var phaseMod = 0.0

        while (isEngineRunning.get()) {
            try {
                val ctx = appContext
                if (ctx == null) {
                    Thread.sleep(20)
                    continue
                }

                val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                if (track == null || needsTrackRebuild.getAndSet(false)) {
                    try {
                        track?.pause()
                        track?.flush()
                        track?.release()
                    } catch (_: Throwable) {
                    }
                    track = buildAudioTrack(audioManager)
                }

                var rem = remainingSamples.get()

                if (rem <= 0L || !enabled) {
                    // ضخ صمت تام (أصفار) للحفاظ على المسار مفتوحاً ومنع أي صوت خربشة
                    stereoBuffer.fill(0)
                    phasePrimary = 0.0
                    phaseHarmonic = 0.0
                    phaseMod = 0.0
                    track.write(stereoBuffer, 0, stereoBuffer.size)
                    continue
                }

                val baseFreq = baseFrequencyHz
                val gain = (currentAmplitude.get() / 255f) * volumeGain
                val total = totalPulseSamples.get().coerceAtLeast(1L)
                var pos = currentPulseSample.get()

                for (i in 0 until chunkFrames) {
                    if (rem > 0L) {
                        val timeSec = pos.toDouble() / SAMPLE_RATE

                        // ضربة انطلاق الموتور في أول 10ms
                        val instFreq = if (timeSec < 0.010) {
                            baseFreq + 55.0 * exp(-timeSec * 300.0)
                        } else {
                            baseFreq
                        }

                        // تحديث الطور (Phase Accumulation) لمنع أي انقطاع أو خربشة في الموجة
                        phasePrimary += 2.0 * PI * instFreq / SAMPLE_RATE
                        phaseHarmonic += 2.0 * PI * (baseFreq * 2.0) / SAMPLE_RATE
                        phaseMod += 2.0 * PI * 32.0 / SAMPLE_RATE

                        if (phasePrimary > 2.0 * PI) phasePrimary -= 2.0 * PI
                        if (phaseHarmonic > 2.0 * PI) phaseHarmonic -= 2.0 * PI
                        if (phaseMod > 2.0 * PI) phaseMod -= 2.0 * PI

                        val primary = sin(phasePrimary)
                        val harmonic = 0.30 * sin(phaseHarmonic)
                        val rumble = 0.84 + 0.16 * sin(phaseMod)

                        // تنعيم ناعم للبداية (4ms) والنهاية (8ms)
                        val attack = (pos / (SAMPLE_RATE * 0.004)).coerceIn(0.0, 1.0)
                        val release = (rem / (SAMPLE_RATE * 0.008)).coerceIn(0.0, 1.0)

                        val signal = (primary + harmonic) * rumble * attack * release
                        val sample = (signal * Short.MAX_VALUE * 0.75 * gain)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        stereoBuffer[i * 2] = sample
                        stereoBuffer[i * 2 + 1] = sample

                        pos++
                        rem--
                    } else {
                        stereoBuffer[i * 2] = 0
                        stereoBuffer[i * 2 + 1] = 0
                    }
                }

                currentPulseSample.set(pos)
                remainingSamples.set(rem)
                track.write(stereoBuffer, 0, stereoBuffer.size)

            } catch (_: Throwable) {
                track = null
                Thread.sleep(50)
            }
        }
    }
}
