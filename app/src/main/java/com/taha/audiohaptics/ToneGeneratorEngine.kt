package com.taha.audiohaptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object ToneGeneratorEngine {
    private const val SAMPLE_RATE = 48000

    const val TYPE_TICK = 0
    const val TYPE_TAP = 1
    const val TYPE_DOUBLE_TAP = 2
    const val TYPE_HEAVY = 3
    const val TYPE_CONTINUOUS = 4

    @Volatile var outputMode: Int = 0 // 0 = التابلت + الإيربودز | 1 = التابلت فقط | 2 = الإيربودز فقط
    @Volatile var baseFrequencyHz: Double = 118.0 // التردد الرنيني المثالي لهيكل Tab A7
    @Volatile var volumeGain: Float = 0.95f
    @Volatile var enabled: Boolean = true

    private val shortExecutor = Executors.newSingleThreadExecutor()
    private val poolLock = Any()

    // مسارات STATIC جاهزة في الذاكرة لعزف فوري بدون تأخير أو خروشة
    private var tapPool: Array<AudioTrack>? = null
    private var tickPool: Array<AudioTrack>? = null
    private var heavyPool: Array<AudioTrack>? = null
    private var doublePool: Array<AudioTrack>? = null

    private val tapIdx = AtomicInteger(0)
    private val tickIdx = AtomicInteger(0)
    private val heavyIdx = AtomicInteger(0)
    private val doubleIdx = AtomicInteger(0)

    private var cachedFreq = -1.0
    private var cachedGain = -1f
    private var cachedMode = -1

    // للتحكم في الاهتزاز المتواصل فقط
    private val isContinuousPlaying = AtomicBoolean(false)
    @Volatile private var continuousTrack: AudioTrack? = null

    fun invalidatePools() {
        cachedMode = -1
    }

    fun stopContinuous() {
        isContinuousPlaying.set(false)
        try {
            continuousTrack?.pause()
            continuousTrack?.flush()
            continuousTrack?.release()
        } catch (_: Throwable) {
        }
        continuousTrack = null
    }

    fun trigger(context: Context?, type: Int, durationMs: Long = 20L, amplitude: Int = 240) {
        if (!enabled || context == null || amplitude <= 0) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        if (type == TYPE_CONTINUOUS && durationMs > 65L) {
            startContinuous(audioManager, durationMs.coerceAtMost(10000L), amplitude)
            return
        }

        shortExecutor.execute {
            try {
                ensurePoolsBuilt(audioManager)
                val track = when (type) {
                    TYPE_TICK -> tickPool?.let { it[tickIdx.getAndIncrement() % it.size] }
                    TYPE_DOUBLE_TAP -> doublePool?.let { it[doubleIdx.getAndIncrement() % it.size] }
                    TYPE_HEAVY -> heavyPool?.let { it[heavyIdx.getAndIncrement() % it.size] }
                    else -> tapPool?.let { it[tapIdx.getAndIncrement() % it.size] }
                } ?: return@execute

                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.reloadStaticData()
                track.play()
            } catch (_: Throwable) {
                invalidatePools()
            }
        }
    }

    private fun ensurePoolsBuilt(audioManager: AudioManager) {
        if (tapPool != null && cachedFreq == baseFrequencyHz && cachedGain == volumeGain && cachedMode == outputMode) {
            return
        }
        synchronized(poolLock) {
            if (tapPool != null && cachedFreq == baseFrequencyHz && cachedGain == volumeGain && cachedMode == outputMode) {
                return
            }
            releasePool(tapPool)
            releasePool(tickPool)
            releasePool(heavyPool)
            releasePool(doublePool)

            val f = baseFrequencyHz
            val g = volumeGain

            // 1. نقرة خفيفة (Tick - 12ms)
            val tickPcm = generateTapticPcm(durMs = 12, fStart = f * 1.25, fEnd = f * 0.95, gain = g * 0.65)
            // 2. نقرة أساسية قوية وواضحة (Tap - 18ms)
            val tapPcm = generateTapticPcm(durMs = 18, fStart = f * 1.32, fEnd = f * 0.85, gain = g * 1.0)
            // 3. نقرة عميقة للضغط المطوّل و Recent Apps (Heavy - 34ms)
            val heavyPcm = generateTapticPcm(durMs = 34, fStart = f * 1.20, fEnd = f * 0.78, gain = g * 1.15)
            // 4. نقرة مزدوجة لأزرار التبديل (Double Tap - 55ms)
            val doublePcm = generateDoubleTapticPcm(f = f, gain = g)

            tickPool = Array(4) { buildStaticTrack(audioManager, tickPcm) }
            tapPool = Array(6) { buildStaticTrack(audioManager, tapPcm) }
            heavyPool = Array(3) { buildStaticTrack(audioManager, heavyPcm) }
            doublePool = Array(3) { buildStaticTrack(audioManager, doublePcm) }

            cachedFreq = f
            cachedGain = g
            cachedMode = outputMode
        }
    }

    private fun releasePool(pool: Array<AudioTrack>?) {
        pool?.forEach {
            try {
                it.stop()
                it.release()
            } catch (_: Throwable) {
            }
        }
    }

    // توليد موجة Taptic فيزيائية مغلقة (Zero-Crossing + Hann Window) بدون أي خروشة
    private fun generateTapticPcm(durMs: Int, fStart: Double, fEnd: Double, gain: Float): ShortArray {
        val frames = ((durMs / 1000.0) * SAMPLE_RATE).toInt()
        val stereo = ShortArray(frames * 2)
        var phase = 0.0

        for (i in 0 until frames) {
            val progress = i.toDouble() / frames
            // نافذة Hann الناعمة تجعل البداية 0 والنهاية 0 تماماً بدون أي طقطقة
            val window = 0.5 * (1.0 - cos(2.0 * PI * progress))
            val instFreq = fStart + (fEnd - fStart) * progress
            phase += 2.0 * PI * instFreq / SAMPLE_RATE

            // مزيج متوازن يعطي إحساس الارتطام الميكانيكي القوي في سماعات التابلت
            val wave = sin(phase) + 0.25 * sin(2.0 * phase)
            val sample = (wave * window * Short.MAX_VALUE * 0.78 * gain)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            stereo[i * 2] = sample
            stereo[i * 2 + 1] = sample
        }
        return stereo
    }

    private fun generateDoubleTapticPcm(f: Double, gain: Float): ShortArray {
        val p1 = generateTapticPcm(14, f * 1.15, f * 0.90, gain * 0.80)
        val gap = ShortArray(((0.022) * SAMPLE_RATE).toInt() * 2) // 22ms صمت بين النبضتين
        val p2 = generateTapticPcm(18, f * 1.35, f * 0.88, gain * 1.05)
        return p1 + gap + p2
    }

    private fun buildStaticTrack(audioManager: AudioManager, pcmData: ShortArray): AudioTrack {
        val usage = if (outputMode == 2) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcmData.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        applyOutputRouting(audioManager, track)
        track.write(pcmData, 0, pcmData.size)
        return track
    }

    private fun applyOutputRouting(audioManager: AudioManager, track: AudioTrack) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        when (outputMode) {
            1 -> {
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) track.preferredDevice = speaker
            }
            2 -> {
                val bt = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bt != null) track.preferredDevice = bt
            }
            else -> track.preferredDevice = null
        }
    }

    // محرك مستقل للاهتزاز المتواصل بتموج موتور 36Hz (يشبه الموتور الفعلي وليس صفارة)
    private fun startContinuous(audioManager: AudioManager, durationMs: Long, amplitude: Int) {
        stopContinuous()
        isContinuousPlaying.set(true)
        val ampGain = (amplitude.coerceIn(1, 255) / 255.0) * volumeGain

        Thread {
            try {
                val usage = if (outputMode == 2) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
                val attributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()

                val chunkFrames = 1440 // 30ms buffer لمنع أي تقطع في الاهتزاز الطويل
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(chunkFrames * 4)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                applyOutputRouting(audioManager, track)
                continuousTrack = track
                track.play()

                val totalFrames = ((durationMs / 1000.0) * SAMPLE_RATE).toInt()
                val buf = ShortArray(chunkFrames * 2)
                var written = 0
                val f = baseFrequencyHz

                while (isContinuousPlaying.get() && written < totalFrames) {
                    val toWrite = minOf(chunkFrames, totalFrames - written)
                    for (i in 0 until toWrite) {
                        val pos = written + i
                        val t = pos.toDouble() / SAMPLE_RATE

                        // تموج دوران موتور ERM حقيقي (36 نبضة في الثانية)
                        val motorPulse = 0.45 + 0.55 * (0.5 * (1.0 + sin(2.0 * PI * 36.0 * t)))
                        val carrier = sin(2.0 * PI * f * t) + 0.25 * sin(2.0 * PI * (f * 2.0) * t)

                        val fadeIn = (pos / (SAMPLE_RATE * 0.015)).coerceIn(0.0, 1.0)
                        val fadeOut = ((totalFrames - pos) / (SAMPLE_RATE * 0.020)).coerceIn(0.0, 1.0)

                        val s = (carrier * motorPulse * fadeIn * fadeOut * Short.MAX_VALUE * 0.72 * ampGain)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        buf[i * 2] = s
                        buf[i * 2 + 1] = s
                    }
                    track.write(buf, 0, toWrite * 2)
                    written += toWrite
                }

                if (isContinuousPlaying.get()) {
                    track.stop()
                }
                track.release()
            } catch (_: Throwable) {
            } finally {
                isContinuousPlaying.set(false)
            }
        }.start()
    }
}
