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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object ToneGeneratorEngine {
    private const val SAMPLE_RATE = 48000

    // أنماط Taptic Engine المختلفة
    const val PROFILE_TAP = 0
    const val PROFILE_TICK = 1
    const val PROFILE_TOGGLE_ON = 2
    const val PROFILE_TOGGLE_OFF = 3
    const val PROFILE_GESTURE = 4
    const val PROFILE_HEAVY = 5
    const val PROFILE_CONTINUOUS = 6

    @Volatile var outputMode: Int = 0 // 0 = التابلت + الإيربودز | 1 = التابلت فقط | 2 = الإيربودز فقط
    @Volatile var baseFrequencyHz: Double = 85.0 // تردد Taptic Engine العميق (بدون صوت مزعج)
    @Volatile var volumeGain: Float = 0.95f
    @Volatile var enabled: Boolean = true

    private val isEngineRunning = AtomicBoolean(false)
    private val needsTrackRebuild = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null

    // قناة صوتية مستقلة لكل نبضة (Polyphonic Voice) لمنع تداخل الموجات والخروشة
    private class TapticVoice {
        @Volatile var active = false
        var pos = 0
        var totalSamples = 0
        var startFreq = 85.0
        var endFreq = 85.0
        var damping = 0.0
        var gain = 1.0
        var phase = 0.0
        var delaySamples = 0 // لدعم النبضات المزدوجة (Double-tap Taptic)
    }

    private val voices = Array(8) { TapticVoice() }
    private val voiceLock = Any()

    fun ensureStarted(context: Context?) {
        if (context != null) {
            appContext = context.applicationContext ?: context
        }
        if (isEngineRunning.compareAndSet(false, true)) {
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioLoop()
            }, "TapticSynthesizer").start()
        }
    }

    fun requestTrackRebuild() {
        needsTrackRebuild.set(true)
    }

    fun stopVibration() {
        synchronized(voiceLock) {
            for (v in voices) {
                if (v.active && v.totalSamples - v.pos > 240) {
                    v.totalSamples = v.pos + 240 // إخماد ناعم خلال 5ms
                }
            }
        }
    }

    fun triggerTaptic(context: Context?, profile: Int, durationMs: Long = 20L, amplitude: Int = 230) {
        if (!enabled) return
        ensureStarted(context)

        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        val ampNorm = (amplitude.coerceIn(1, 255) / 255.0) * volumeGain
        val base = baseFrequencyHz

        when (profile) {
            PROFILE_TICK -> {
                // نبضة دقيقة جداً للتمرير والقوائم (7ms)
                spawnVoice(delayMs = 0, durMs = 8, fStart = base + 12.0, fEnd = base, damp = 180.0, gain = ampNorm * 0.55)
            }
            PROFILE_TAP -> {
                // نقرة Taptic حادة ومكتومة الصوت (13ms)
                spawnVoice(delayMs = 0, durMs = 13, fStart = base + 18.0, fEnd = base - 5.0, damp = 140.0, gain = ampNorm * 0.90)
            }
            PROFILE_TOGGLE_ON -> {
                // نبضة مزدوجة صاعدة لتشغيل المفتاح (Switch ON)
                spawnVoice(delayMs = 0, durMs = 10, fStart = base - 8.0, fEnd = base, damp = 160.0, gain = ampNorm * 0.65)
                spawnVoice(delayMs = 24, durMs = 14, fStart = base + 15.0, fEnd = base + 5.0, damp = 130.0, gain = ampNorm * 1.0)
            }
            PROFILE_TOGGLE_OFF -> {
                // نبضة مزدوجة هابطة لإطفاء المفتاح (Switch OFF)
                spawnVoice(delayMs = 0, durMs = 12, fStart = base + 10.0, fEnd = base, damp = 140.0, gain = ampNorm * 0.85)
                spawnVoice(delayMs = 22, durMs = 10, fStart = base - 5.0, fEnd = base - 15.0, damp = 170.0, gain = ampNorm * 0.55)
            }
            PROFILE_GESTURE -> {
                // إحساس سحبة إيماءة الرجوع المرنة (18ms)
                spawnVoice(delayMs = 0, durMs = 18, fStart = base - 10.0, fEnd = base + 14.0, damp = 95.0, gain = ampNorm * 0.95)
            }
            PROFILE_HEAVY -> {
                // ضغطة عميقة للضغط المطوّل (36ms)
                spawnVoice(delayMs = 0, durMs = 36, fStart = base + 8.0, fEnd = base - 12.0, damp = 55.0, gain = ampNorm * 1.15)
            }
            else -> {
                // اهتزاز مستمر (رنين أو ألعاب)
                val safeDur = durationMs.coerceIn(14L, 10000L)
                spawnVoice(delayMs = 0, durMs = safeDur, fStart = base + 10.0, fEnd = base, damp = 0.0, gain = ampNorm)
            }
        }
    }

    private fun spawnVoice(delayMs: Long, durMs: Long, fStart: Double, fEnd: Double, damp: Double, gain: Double) {
        synchronized(voiceLock) {
            // اختيار قناة خاملة أو أقدم قناة
            val voice = voices.firstOrNull { !it.active } ?: voices.maxByOrNull { it.pos } ?: return
            voice.delaySamples = ((delayMs / 1000.0) * SAMPLE_RATE).toInt()
            voice.totalSamples = ((durMs / 1000.0) * SAMPLE_RATE).toInt().coerceAtLeast(120)
            voice.pos = 0
            voice.startFreq = fStart
            voice.endFreq = fEnd
            voice.damping = damp
            voice.gain = gain
            voice.phase = 0.0
            voice.active = true
        }
    }

    private fun buildAudioTrack(audioManager: AudioManager): AudioTrack {
        val usage = when (outputMode) {
            2 -> AudioAttributes.USAGE_MEDIA
            else -> AudioAttributes.USAGE_ALARM
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE) // تفعيل السماعات الأربعة و Dolby Atmos
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        // استخدام الحد الأدنى الحقيقي للـ Buffer لإزالة التأخير تماماً
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val track = builder.build()

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        when (outputMode) {
            1 -> {
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) track.preferredDevice = speaker
            }
            2 -> {
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btDevice != null) track.preferredDevice = btDevice
            }
            else -> track.preferredDevice = null
        }

        track.play()
        return track
    }

    private fun audioLoop() {
        val chunkFrames = 128 // ~2.6ms فقط لكل دفعة لاستجابة فورية
        val stereoBuffer = ShortArray(chunkFrames * 2)
        var track: AudioTrack? = null

        while (isEngineRunning.get()) {
            try {
                val ctx = appContext
                if (ctx == null) {
                    Thread.sleep(15)
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

                var anyActive = false
                for (v in voices) {
                    if (v.active) {
                        anyActive = true
                        break
                    }
                }

                if (!anyActive || !enabled) {
                    stereoBuffer.fill(0)
                    track.write(stereoBuffer, 0, stereoBuffer.size)
                    continue
                }

                for (i in 0 until chunkFrames) {
                    var mixedSample = 0.0

                    for (v in voices) {
                        if (!v.active) continue

                        if (v.delaySamples > 0) {
                            v.delaySamples--
                            continue
                        }

                        val progress = v.pos.toDouble() / v.totalSamples
                        val timeSec = v.pos.toDouble() / SAMPLE_RATE

                        // تغير التردد السلس لمحاكاة تسارع وكبح الكتلة المغناطيسية
                        val instFreq = v.startFreq + (v.endFreq - v.startFreq) * progress
                        v.phase += 2.0 * PI * instFreq / SAMPLE_RATE
                        if (v.phase > 2.0 * PI) v.phase -= 2.0 * PI

                        // إخماد فيزيائي (Damping) + تنعيم ناعم للبداية (2ms) والنهاية (4ms)
                        val dampingFactor = if (v.damping > 0.0) exp(-v.damping * timeSec) else (0.88 + 0.12 * sin(2.0 * PI * 30.0 * timeSec))
                        val attack = (v.pos / (SAMPLE_RATE * 0.002)).coerceIn(0.0, 1.0)
                        val remaining = v.totalSamples - v.pos
                        val release = (remaining / (SAMPLE_RATE * 0.004)).coerceIn(0.0, 1.0)

                        // موجة نقية بدون توافقيات حادة لإعطاء هزة فيزيائية دون صوت نقرة مزعج
                        mixedSample += sin(v.phase) * dampingFactor * attack * release * v.gain

                        v.pos++
                        if (v.pos >= v.totalSamples) {
                            v.active = false
                        }
                    }

                    // محدد ناعم (Soft Limiter) لمنع التشويش عند تداخل عدة نقرات سريعة
                    val clamped = mixedSample.coerceIn(-1.1, 1.1)
                    val finalPcm = (clamped * Short.MAX_VALUE * 0.85)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()

                    stereoBuffer[i * 2] = finalPcm
                    stereoBuffer[i * 2 + 1] = finalPcm
                }

                track.write(stereoBuffer, 0, stereoBuffer.size)

            } catch (_: Throwable) {
                track = null
                Thread.sleep(30)
            }
        }
    }
}
