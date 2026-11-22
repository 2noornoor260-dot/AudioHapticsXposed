package com.taha.audiohaptics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("haptics_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "إعدادات الاهتزاز الصوتي الاحترافي (v5.0)"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        val enableSwitch = SwitchMaterial(this).apply {
            text = "تفعيل الاهتزاز الصوتي"
            isChecked = prefs.getBoolean("enabled", true)
        }

        val modeLabel = TextView(this).apply {
            text = "\nمخرج صوت الاهتزاز:"
            textSize = 16f
        }

        val radioGroup = RadioGroup(this).apply {
            addView(RadioButton(context).apply { id = 0; text = "التابلت (السماعات الـ 4 + Dolby) + الإيربودز معاً" })
            addView(RadioButton(context).apply { id = 1; text = "سماعات التابلت الـ 4 + Dolby Atmos فقط" })
            addView(RadioButton(context).apply { id = 2; text = "الإيربودز (البلوتوث) فقط" })
            check(prefs.getInt("outputMode", 0))
        }

        val initFreq = prefs.getInt("freq", 118)
        val freqLabel = TextView(this).apply {
            text = "\nتردد الرنين (90Hz - 145Hz): $initFreq Hz"
        }

        val freqSeek = SeekBar(this).apply {
            max = 55
            progress = initFreq - 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val currentFreq = p + 90
                    freqLabel.text = "\nتردد الرنين (90Hz - 145Hz): $currentFreq Hz"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val initGain = prefs.getInt("gain", 95)
        val gainLabel = TextView(this).apply {
            text = "\nقوة الاهتزاز: $initGain%"
        }

        val gainSeek = SeekBar(this).apply {
            max = 100
            progress = initGain
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    gainLabel.text = "\nقوة الاهتزاز: $p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val testTapBtn = Button(this).apply {
            text = "حفظ وتجربة نقرة Taptic سريعة"
            setOnClickListener {
                saveAndSync(prefs, enableSwitch.isChecked, radioGroup.checkedRadioButtonId, freqSeek.progress + 90, gainSeek.progress)
                ToneGeneratorEngine.trigger(this@MainActivity, ToneGeneratorEngine.TYPE_TAP)
            }
        }

        val testDoubleBtn = Button(this).apply {
            text = "تجربة نقرة مزدوجة (أزرار التبديل / Recent Apps)"
            setOnClickListener {
                saveAndSync(prefs, enableSwitch.isChecked, radioGroup.checkedRadioButtonId, freqSeek.progress + 90, gainSeek.progress)
                ToneGeneratorEngine.trigger(this@MainActivity, ToneGeneratorEngine.TYPE_DOUBLE_TAP)
            }
        }

        val testLongBtn = Button(this).apply {
            text = "تجربة اهتزاز موتور متواصل (1.5 ثانية)"
            setOnClickListener {
                saveAndSync(prefs, enableSwitch.isChecked, radioGroup.checkedRadioButtonId, freqSeek.progress + 90, gainSeek.progress)
                ToneGeneratorEngine.trigger(this@MainActivity, ToneGeneratorEngine.TYPE_CONTINUOUS, 1500L, 255)
            }
        }

        layout.addView(title)
        layout.addView(enableSwitch)
        layout.addView(modeLabel)
        layout.addView(radioGroup)
        layout.addView(freqLabel)
        layout.addView(freqSeek)
        layout.addView(gainLabel)
        layout.addView(gainSeek)
        layout.addView(testTapBtn)
        layout.addView(testDoubleBtn)
        layout.addView(testLongBtn)

        setContentView(layout)
    }

    private fun saveAndSync(prefs: android.content.SharedPreferences, enabled: Boolean, mode: Int, freq: Int, gain: Int) {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putInt("outputMode", mode)
            .putInt("freq", freq)
            .putInt("gain", gain)
            .apply()

        ToneGeneratorEngine.enabled = enabled
        ToneGeneratorEngine.outputMode = mode
        ToneGeneratorEngine.baseFrequencyHz = freq.toDouble()
        ToneGeneratorEngine.volumeGain = gain / 100f
        ToneGeneratorEngine.invalidatePools()

        val intent = Intent(MainHook.ACTION_UPDATE_PREFS).apply {
            putExtra("enabled", enabled)
            putExtra("outputMode", mode)
            putExtra("freq", freq.toDouble())
            putExtra("gain", gain / 100f)
        }
        sendBroadcast(intent)
    }
}
