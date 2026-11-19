package com.stasisstate.slatetv.plugin.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SampleSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A0D12"))
            setPadding(80, 60, 80, 60)
        }
        val title = TextView(this).apply {
            text = "Nordic Palettes (External Plugin)"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = "This external APK implements Slate TV's ISlateBackgroundPlugin AIDL interface.\nNo additional settings required."
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 32)
        }
        val closeBtn = Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
            requestFocus()
        }
        root.addView(title)
        root.addView(desc)
        root.addView(closeBtn)
        setContentView(root)
    }
}
