package com.xalies.meshvault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

// Defined ONCE here to be used by LibraryScreen, DashboardScreen, OnboardingScreen, etc.
fun Context.findActivity(): Activity? {
    var currentContext: Context = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}