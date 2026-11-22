package com.taha.audiohaptics

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val ACTION_UPDATE_PREFS = "com.taha.audiohaptics.UPDATE_PREFS"
        private var receiverRegistered = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // تزييف وجود الهزاز فقط (بدون عمل Hook لـ View لتجنب التكرار العشوائي)
        hookVibratorCapabilities(lpparam.classLoader)

        // حصر الصوت في خدمة النظام المركزية فقط لمنع تداخل الأصوات
        if (lpparam.packageName == "android") {
            hookSystemVibratorService(lpparam.classLoader)
        }
    }

    private fun hookVibratorCapabilities(classLoader: ClassLoader) {
        try {
            val sysVib = XposedHelpers.findClassIfExists("android.os.SystemVibrator", classLoader)
            if (sysVib != null) {
                XposedBridge.hookAllMethods(sysVib, "hasVibrator", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(sysVib, "hasAmplitudeControl", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(sysVib, "areAllEffectsSupported", XC_MethodReplacement.returnConstant(1))
                XposedBridge.hookAllMethods(sysVib, "areAllPrimitivesSupported", XC_MethodReplacement.returnConstant(true))
            }

            val vibClass = XposedHelpers.findClassIfExists("android.os.Vibrator", classLoader)
            if (vibClass != null) {
                XposedBridge.hookAllMethods(vibClass, "hasVibrator", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(vibClass, "hasAmplitudeControl", XC_MethodReplacement.returnConstant(true))
            }

            val vibMgr = XposedHelpers.findClassIfExists("android.os.VibratorManager", classLoader)
            if (vibMgr != null) {
                XposedBridge.hookAllMethods(vibMgr, "getVibratorIds", XC_MethodReplacement.returnConstant(intArrayOf(0)))
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookSystemVibratorService(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            "com.android.server.vibrator.VibratorManagerService",
            classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.server.VibratorService",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(serviceClass, "vibrate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = AndroidAppHelper.currentApplication()?.applicationContext
                    ?: XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context

                if (context != null && !receiverRegistered) {
                    registerSettingsReceiver(context)
                }

                val (type, duration, amplitude) = classifyVibration(param.args)
                if (amplitude > 0) {
                    ToneGeneratorEngine.trigger(context, type, duration, amplitude)
                }
            }
        })

        XposedBridge.hookAllMethods(serviceClass, "cancelVibrate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                ToneGeneratorEngine.stopContinuous()
            }
        })
    }

    // تصنيف ذكي لكل أمر اهتزاز قادم للنظام (أزرار، إيماءات، Recent Apps، كيبورد، ألعاب)
    private fun classifyVibration(args: Array<Any?>): Triple<Int, Long, Int> {
        for (arg in args) {
            if (arg == null) continue

            if (arg is Long && arg > 0) {
                val type = when {
                    arg <= 15L -> ToneGeneratorEngine.TYPE_TICK
                    arg <= 35L -> ToneGeneratorEngine.TYPE_TAP
                    arg <= 65L -> ToneGeneratorEngine.TYPE_HEAVY
                    else -> ToneGeneratorEngine.TYPE_CONTINUOUS
                }
                return Triple(type, arg, 240)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && arg is CombinedVibration) {
                try {
                    val effect = XposedHelpers.callMethod(arg, "getEffect") as? VibrationEffect
                    if (effect != null) return parseEffect(effect)
                } catch (_: Throwable) {
                }
            }

            if (arg is VibrationEffect) {
                return parseEffect(arg)
            }
        }
        return Triple(ToneGeneratorEngine.TYPE_TAP, 18L, 230)
    }

    private fun parseEffect(effect: VibrationEffect): Triple<Int, Long, Int> {
        val className = effect.javaClass.name

        // 1. Prebaked Effects (أزرار التبديل، نقرات النظام، الإيماءات)
        if (className.contains("Prebaked")) {
            val effectId = (XposedHelpers.callMethod(effect, "getId") as? Int) ?: 0
            return when (effectId) {
                2, 21 -> Triple(ToneGeneratorEngine.TYPE_TICK, 12L, 200) // EFFECT_TICK / TEXTURE_TICK
                1 -> Triple(ToneGeneratorEngine.TYPE_DOUBLE_TAP, 55L, 245) // EFFECT_DOUBLE_CLICK (Switches)
                5 -> Triple(ToneGeneratorEngine.TYPE_HEAVY, 34L, 255) // EFFECT_HEAVY_CLICK (Recent Apps / Long Press)
                else -> Triple(ToneGeneratorEngine.TYPE_TAP, 18L, 235) // EFFECT_CLICK
            }
        }

        // 2. Composed Primitives (إيماءة Recent Apps والرجوع في أندرويد الحديث)
        if (className.contains("Composed")) {
            try {
                val segments = XposedHelpers.callMethod(effect, "getSegments") as? List<*>
                if (!segments.isNullOrEmpty()) {
                    val firstSeg = segments[0]
                    val segName = firstSeg?.javaClass?.name ?: ""
                    if (segName.contains("PrimitiveSegment")) {
                        val primId = (XposedHelpers.callMethod(firstSeg, "getPrimitiveId") as? Int) ?: 1
                        if (segments.size >= 2) {
                            return Triple(ToneGeneratorEngine.TYPE_DOUBLE_TAP, 55L, 245)
                        }
                        return when (primId) {
                            7, 8 -> Triple(ToneGeneratorEngine.TYPE_TICK, 12L, 210) // PRIMITIVE_TICK / LOW_TICK
                            2, 3 -> Triple(ToneGeneratorEngine.TYPE_HEAVY, 34L, 255) // PRIMITIVE_THUD / SPIN
                            else -> Triple(ToneGeneratorEngine.TYPE_TAP, 18L, 240) // PRIMITIVE_CLICK
                        }
                    }
                    // إذا كان StepSegment (OneShot أو Waveform)
                    val dur = (XposedHelpers.callMethod(effect, "getDuration") as? Long) ?: 20L
                    if (dur > 65L) {
                        return Triple(ToneGeneratorEngine.TYPE_CONTINUOUS, dur, 245)
                    } else if (dur > 0L) {
                        val type = if (dur <= 15L) ToneGeneratorEngine.TYPE_TICK else if (dur <= 35L) ToneGeneratorEngine.TYPE_TAP else ToneGeneratorEngine.TYPE_HEAVY
                        return Triple(type, dur, 240)
                    }
                }
            } catch (_: Throwable) {
            }
            return Triple(ToneGeneratorEngine.TYPE_HEAVY, 30L, 245)
        }

        // 3. OneShot / Waveform التقليدي
        try {
            val dur = (XposedHelpers.callMethod(effect, "getDuration") as? Long) ?: -1L
            if (dur > 65L) {
                return Triple(ToneGeneratorEngine.TYPE_CONTINUOUS, dur, 245)
            } else if (dur > 0L) {
                val amp = (XposedHelpers.callMethod(effect, "getAmplitude") as? Int) ?: 235
                if (amp == 0) return Triple(ToneGeneratorEngine.TYPE_TICK, 0L, 0)
                val type = when {
                    dur <= 15L -> ToneGeneratorEngine.TYPE_TICK
                    dur <= 35L -> ToneGeneratorEngine.TYPE_TAP
                    else -> ToneGeneratorEngine.TYPE_HEAVY
                }
                return Triple(type, dur, if (amp == -1) 235 else amp)
            }
        } catch (_: Throwable) {
        }

        return Triple(ToneGeneratorEngine.TYPE_TAP, 18L, 235)
    }

    private fun registerSettingsReceiver(context: Context) {
        try {
            val filter = IntentFilter(ACTION_UPDATE_PREFS)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    ToneGeneratorEngine.enabled = intent.getBooleanExtra("enabled", true)
                    ToneGeneratorEngine.outputMode = intent.getIntExtra("outputMode", 0)
                    ToneGeneratorEngine.baseFrequencyHz = intent.getDoubleExtra("freq", 118.0)
                    ToneGeneratorEngine.volumeGain = intent.getFloatExtra("gain", 0.95f)
                    ToneGeneratorEngine.invalidatePools()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {
        }
    }
}
