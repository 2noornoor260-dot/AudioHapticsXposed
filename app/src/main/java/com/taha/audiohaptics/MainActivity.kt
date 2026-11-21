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
            text = "إعدادات الاهتزاز الصوتي (Audio Haptics)"
            textSize = 20f
            setPadding(0, 0, 0, 32)
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
            val rbBoth = RadioButton(context).apply {
                id = 0
                text = "التابلت + الإيربودز معاً (تجربة مزدوجة)"
            }
            val rbTablet = RadioButton(context).apply {
                id = 1
                text = "سماعات التابلت فقط"
            }
            val rbAirpods = RadioButton(context).apply {
                id = 2
                text = "الإيربودز (البلوتوث) فقط"
            }
            addView(rbBoth)
            addView(rbTablet)
            addView(rbAirpods)
            check(prefs.getInt("outputMode", 0))
        }

        val freqLabel = TextView(this).apply {
            text = "\nتردد الاهتزاز (50Hz - 100Hz): \${prefs.getInt("freq", 60)} Hz"
        }

        val freqSeek = SeekBar(this).apply {
            max = 50
            progress = prefs.getInt("freq", 60) - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    freqLabel.text = "\nتردد الاهتزاز (50Hz - 100Hz): \${p + 50} Hz"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val gainLabel = TextView(this).apply {
            text = "\nقوة النبضة: \${prefs.getInt("gain", 85)}%"
        }

        val gainSeek = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("gain", 85)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    gainLabel.text = "\nقوة النبضة: \$p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val testBtn = Button(this).apply {
            text = "حفظ وتجربة الاهتزاز الآن"
            setOnClickListener {
                val enabled = enableSwitch.isChecked
                val mode = radioGroup.checkedRadioButtonId
                val freq = freqSeek.progress + 50
                val gain = gainSeek.progress

                prefs.edit()
                    .putBoolean("enabled", enabled)
                    .putInt("outputMode", mode)
                    .putInt("freq", freq)
                    .putInt("gain", gain)
                    .apply()

                ToneGeneratorEngine.enabled = enabled
                ToneGeneratorEngine.outputMode = mode
                ToneGeneratorEngine.frequencyHz = freq.toDouble()
                ToneGeneratorEngine.volumeGain = gain / 100f
                ToneGeneratorEngine.triggerPulse(this@MainActivity, 45L)

                val intent = Intent(MainHook.ACTION_UPDATE_PREFS).apply {
                    putExtra("enabled", enabled)
                    putExtra("outputMode", mode)
                    putExtra("freq", freq.toDouble())
                    putExtra("gain", gain / 100f)
                }
                sendBroadcast(intent)
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
        layout.addView(testBtn)

        setContentView(layout)
    }
}
