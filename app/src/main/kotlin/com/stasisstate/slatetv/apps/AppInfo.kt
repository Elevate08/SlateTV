package com.stasisstate.slatetv.apps

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val launchIntent: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
    val isLeanback: Boolean,
)
