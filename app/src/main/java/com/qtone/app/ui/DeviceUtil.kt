package com.qtone.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns true if the app is running on a TV device (Fire TV, Nvidia Shield,
 * Google TV, etc.). Returns false for phones and tablets.
 *
 * The check uses Android's UiModeManager which is the official recommended
 * approach. Result is cached per composition so the system service lookup
 * happens only once.
 */
@Composable
fun rememberIsTV(): Boolean {
    val context = LocalContext.current
    return remember {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
