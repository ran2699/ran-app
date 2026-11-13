package com.ran.eldervideo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.WeakHashMap

/**
 * 按键手感：按下去就震，用最大振幅（255）+ 80ms。
 * 全 App 统一从 decorView 递归挂上去，不用改每个自定义按钮的源码
 * （桌面上的 MechanicalButton 就是这么覆盖到的）。
 */
object Haptics {

    private val hooked: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun heavy(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            if (v == null || !v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(80L, 255))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(80L)
            }
        } catch (t: Throwable) {
        }
    }

    /** 挂到整个界面上；布局变化后新加进来的按钮也会补挂 */
    fun attach(root: View) {
        walk(root)
        try {
            root.viewTreeObserver.addOnGlobalLayoutListener { walk(root) }
        } catch (t: Throwable) {
        }
    }

    private fun walk(v: View) {
        if (v !is ViewGroup && v !is android.widget.TextView && v !is android.widget.Button) {
            // 其它自定义 View 也可能当按钮用，一样看有没有点击监听
        }
        if (!hooked.contains(v) && isPressable(v)) {
            hooked.add(v)
            v.setOnTouchListener { view, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) heavy(view.context)
                false
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }

    private fun isPressable(v: View): Boolean {
        if (v is BigCallButton) return false // 它自己在 onTouchEvent 里震，免得震两下
        return try {
            v.hasOnClickListeners()
        } catch (t: Throwable) {
            false
        }
    }
}
