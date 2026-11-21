package com.taha.audiohaptics

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import android.view.View
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
        private var lastViewHapticTime = 0L
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookVibratorCapabilities(lpparam.classLoader)
        hookViewHapticFeedback(lpparam.classLoader)

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
        } catch (_: Throwable) {
        }
    }

    // اعتراض جميع تفاعلات الواجهة والإيماءات وأزرار التبديل (Switches) مباشرة
    private fun hookViewHapticFeedback(classLoader: ClassLoader) {
        try {
            XposedBridge.hookAllMethods(View::class.java, "performHapticFeedback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val feedbackConstant = (param.args.getOrNull(0) as? Int) ?: return

                    // إجبار النظام على قبول الاهتزاز حتى لو كان معطلاً في إعدادات العرض
                    if (param.args.size >= 2 && param.args[1] is Int) {
                        val flags = param.args[1] as Int
                        param.args[1] = flags or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    }

                    lastViewHapticTime = System.currentTimeMillis()
                    val profile = mapConstantToProfile(feedbackConstant)
                    ToneGeneratorEngine.triggerTaptic(view.context, profile)
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun mapConstantToProfile(constant: Int): Int {
        return when (constant) {
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.REJECT -> ToneGeneratorEngine.PROFILE_HEAVY

            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.TEXT_HANDLE_MOVE,
            HapticFeedbackConstants.SEGMENT_TICK,
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK -> ToneGeneratorEngine.PROFILE_TICK

            // أزرار التبديل (Toggle ON / OFF) في الإعدادات والتطبيقات
            21 /* TOGGLE_ON */ -> ToneGeneratorEngine.PROFILE_TOGGLE_ON
            22 /* TOGGLE_OFF */ -> ToneGeneratorEngine.PROFILE_TOGGLE_OFF

            // إيماءات السحب والرجوع (Gestures)
            12 /* GESTURE_START */,
            13 /* GESTURE_END */,
            23 /* GESTURE_THRESHOLD_ACTIVATE */,
            24 /* GESTURE_THRESHOLD_DEACTIVATE */ -> ToneGeneratorEngine.PROFILE_GESTURE

            HapticFeedbackConstants.CONFIRM -> ToneGeneratorEngine.PROFILE_TOGGLE_ON

            else -> ToneGeneratorEngine.PROFILE_TAP
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

                // تجنب التكرار المزدوج إذا تم التقاط النقرة للتو عبر View.performHapticFeedback
                val now = System.currentTimeMillis()
                val (duration, amplitude) = extractVibrationParams(param.args)
                if (duration <= 40L && (now - lastViewHapticTime) < 18L) {
                    return
                }

                val profile = when {
                    duration <= 12L -> ToneGeneratorEngine.PROFILE_TICK
                    duration <= 28L -> ToneGeneratorEngine.PROFILE_TAP
                    duration <= 50L -> ToneGeneratorEngine.PROFILE_HEAVY
                    else -> ToneGeneratorEngine.PROFILE_CONTINUOUS
                }

                ToneGeneratorEngine.triggerTaptic(context, profile, duration, amplitude)
            }
        })

        XposedBridge.hookAllMethods(serviceClass, "cancelVibrate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                ToneGeneratorEngine.stopVibration()
            }
        })
    }

    private fun extractVibrationParams(args: Array<Any?>): Pair<Long, Int> {
        var duration = 22L
        var amplitude = 220

        for (arg in args) {
            if (arg == null) continue
            if (arg is Long && arg > 0) {
                duration = arg
                break
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && arg is CombinedVibration) {
                try {
                    val effect = XposedHelpers.callMethod(arg, "getEffect") as? VibrationEffect
                    if (effect != null) return parseVibrationEffect(effect)
                } catch (_: Throwable) {
                }
            }
            if (arg is VibrationEffect) {
                return parseVibrationEffect(arg)
            }
        }
        return Pair(duration, amplitude)
    }

    private fun parseVibrationEffect(effect: VibrationEffect): Pair<Long, Int> {
        try {
            val dur = XposedHelpers.callMethod(effect, "getDuration") as? Long
            if (dur != null && dur > 0) {
                val amp = (XposedHelpers.callMethod(effect, "getAmplitude") as? Int) ?: 220
                return Pair(dur, if (amp == -1) 220 else amp)
            }
            val timings = XposedHelpers.callMethod(effect, "getTimings") as? LongArray
            if (timings != null && timings.isNotEmpty()) {
                val total = timings.sum()
                if (total > 0) return Pair(total, 240)
            }
        } catch (_: Throwable) {
        }
        return Pair(18L, 210)
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
                    ToneGeneratorEngine.baseFrequencyHz = intent.getDoubleExtra("freq", 85.0)
                    ToneGeneratorEngine.volumeGain = intent.getFloatExtra("gain", 0.95f)
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
