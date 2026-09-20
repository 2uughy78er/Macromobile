package com.macromobile.imagemacro.ui

import android.content.Context
import android.content.ContextWrapper

/** Compose 의 LocalContext 에서 [MainActivity] 를 찾아낸다. */
fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}
