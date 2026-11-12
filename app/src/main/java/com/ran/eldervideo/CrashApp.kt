package com.ran.eldervideo

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 兜底：把未捕获异常写到本地，下次启动时显示在界面上。
 * 测试阶段不接 adb 也能定位问题。
 */
class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                getSharedPreferences("crash", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last", sw.toString().take(4000))
                    .apply()
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
