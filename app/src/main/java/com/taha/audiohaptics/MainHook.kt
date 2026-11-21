package com.taha.audiohaptics

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
        hookHasVibrator(lpparam.classLoader)

        if (lpparam.packageName == "android") {
            hookSystemVibratorService(lpparam.classLoader)
        }
    }

    private fun hookHasVibrator(classLoader: ClassLoader) {
        try {
            val systemVibratorClass = XposedHelpers.findClassIfExists("android.os.SystemVibrator", classLoader)
            if (systemVibratorClass != null) {
                XposedBridge.hookAllMethods(systemVibratorClass, "hasVibrator", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(systemVibratorClass, "hasAmplitudeControl", XC_MethodReplacement.returnConstant(true))
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

                var duration = 25L
                for (arg in param.args) {
                    if (arg is Long && arg > 0) {
                        duration = arg
                        break
                    }
                }

                ToneGeneratorEngine.triggerPulse(context, duration)
            }
        })
    }

    private fun registerSettingsReceiver(context: Context) {
        try {
            val filter = IntentFilter(ACTION_UPDATE_PREFS)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    ToneGeneratorEngine.enabled = intent.getBooleanExtra("enabled", true)
                    ToneGeneratorEngine.outputMode = intent.getIntExtra("outputMode", 0)
                    ToneGeneratorEngine.frequencyHz = intent.getDoubleExtra("freq", 60.0)
                    ToneGeneratorEngine.volumeGain = intent.getFloatExtra("gain", 0.85f)
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
