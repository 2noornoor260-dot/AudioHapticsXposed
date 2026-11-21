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
        hookVibratorCapabilities(lpparam.classLoader)

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
                XposedBridge.hookAllMethods(sysVib, "areAllEffectsSupported", XC_MethodReplacement.returnConstant(1)) // VIBRATION_EFFECT_SUPPORT_YES
                XposedBridge.hookAllMethods(sysVib, "areAllPrimitivesSupported", XC_MethodReplacement.returnConstant(true))
            }

            val vibClass = XposedHelpers.findClassIfExists("android.os.Vibrator", classLoader)
            if (vibClass != null) {
                XposedBridge.hookAllMethods(vibClass, "hasVibrator", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(vibClass, "hasAmplitudeControl", XC_MethodReplacement.returnConstant(true))
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

        // اعتراض جميع أوامر التشغيل (vibrate)
        XposedBridge.hookAllMethods(serviceClass, "vibrate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = AndroidAppHelper.currentApplication()?.applicationContext
                    ?: XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context

                if (context != null && !receiverRegistered) {
                    registerSettingsReceiver(context)
                }

                val (duration, amplitude) = extractVibrationParams(param.args)
                ToneGeneratorEngine.triggerVibration(context, duration, amplitude)
            }
        })

        // اعتراض أوامر الإيقاف الفوري (cancelVibrate)
        XposedBridge.hookAllMethods(serviceClass, "cancelVibrate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                ToneGeneratorEngine.stopVibration()
            }
        })
    }

    private fun extractVibrationParams(args: Array<Any?>): Pair<Long, Int> {
        var duration = 30L
        var amplitude = 220

        for (arg in args) {
            if (arg == null) continue

            // 1. مدة مباشرة بالملي ثانية
            if (arg is Long && arg > 0) {
                duration = arg
                break
            }

            // 2. استخراج المدة والشدة من CombinedVibration (أندرويد 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && arg is CombinedVibration) {
                try {
                    val effect = XposedHelpers.callMethod(arg, "getEffect") as? VibrationEffect
                    if (effect != null) {
                        return parseVibrationEffect(effect)
                    }
                } catch (_: Throwable) {
                }
            }

            // 3. استخراج المدة والشدة من VibrationEffect مباشرة
            if (arg is VibrationEffect) {
                return parseVibrationEffect(arg)
            }
        }
        return Pair(duration, amplitude)
    }

    private fun parseVibrationEffect(effect: VibrationEffect): Pair<Long, Int> {
        try {
            // إذا كان OneShot
            val dur = XposedHelpers.callMethod(effect, "getDuration") as? Long
            if (dur != null && dur > 0) {
                val amp = (XposedHelpers.callMethod(effect, "getAmplitude") as? Int) ?: 220
                return Pair(dur, if (amp == -1) 220 else amp)
            }

            // إذا كان Waveform (اهتزاز متقطع أو طويل)
            val timings = XposedHelpers.callMethod(effect, "getTimings") as? LongArray
            if (timings != null && timings.isNotEmpty()) {
                val total = timings.sum()
                if (total > 0) return Pair(total, 240)
            }
        } catch (_: Throwable) {
        }
        // النقرات الجاهزة (Click / Tick / Heavy Click)
        return Pair(24L, 210)
    }

    private fun registerSettingsReceiver(context: Context) {
        try {
            val filter = IntentFilter(ACTION_UPDATE_PREFS)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val newMode = intent.getIntExtra("outputMode", 0)
                    val modeChanged = ToneGeneratorEngine.outputMode != newMode
                    ToneGeneratorEngine.enabled = intent.getBooleanExtra("enabled", true)
                    ToneGeneratorEngine.outputMode = newMode
                    ToneGeneratorEngine.baseFrequencyHz = intent.getDoubleExtra("freq", 105.0)
                    ToneGeneratorEngine.volumeGain = intent.getFloatExtra("gain", 0.90f)
                    if (modeChanged) {
                        ToneGeneratorEngine.requestTrackRebuild()
                    }
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
