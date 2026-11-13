package com.ran.eldervideo

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

/**
 * 默认电话应用（ROLE_DIALER）之后，系统把来电交给这里，而不是它自己的来电界面。
 * 这里只做两件事：记住当前那通电话、把自绘的 CallActivity 拉起来。
 * 界面上的按钮通过 answer()/endCall() 直接操作这通电话。
 */
class CallService : InCallService() {

    companion object {
        @Volatile
        var current: Call? = null
            private set

        @Volatile
        var instance: CallService? = null
            private set

        /** 接听：只接语音，不接视频 */
        fun acceptCall() {
            val c = current ?: return
            try {
                c.answer(VideoProfile.STATE_AUDIO_ONLY)
                Log.i("ElderCall", "accept ok")
            } catch (t: Throwable) {
                Log.w("ElderCall", "accept failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }

        /** 挂断：响铃中走 reject，已接通走 disconnect */
        fun endCall() {
            val c = current ?: return
            try {
                if (c.details.state == Call.STATE_RINGING) c.reject(false, null as String?) else c.disconnect()
                Log.i("ElderCall", "end ok")
            } catch (t: Throwable) {
                Log.w("ElderCall", "end failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("ElderCall", "InCallService created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        current = call
        Log.i("ElderCall", "call added state=${call.details.state} num=${call.details.handle?.schemeSpecificPart}")
        try {
            val i = Intent(this, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        } catch (t: Throwable) {
            Log.w("ElderCall", "start CallActivity failed: $t")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (current === call) current = null
        Log.i("ElderCall", "call removed")
    }
}

/** 预存号码表。老人只认名字，所以来电时优先显示名字。 */
object CallBook {

    /** 顺序就是界面上按钮的顺序（一排两个） */
    val contacts: List<Pair<String, String>> = listOf(
        "李存有" to "13703188618",
        "李存浩" to "13131867705",
        "李存岩" to "13131814115",
        "李存满" to "15030195319",
        "王敬然" to "18712961913",
        "李达" to "18603394730"
    )

    private val byNumber: Map<String, String> =
        contacts.associate { (name, number) -> number to name }

    private fun digits(raw: String?): String =
        (raw ?: "").filter { it.isDigit() }

    fun nameFor(raw: String?): String {
        val d = digits(raw)
        if (d.isEmpty()) return ""
        val tail = if (d.length > 11) d.substring(d.length - 11) else d
        return byNumber[tail] ?: byNumber[d] ?: ""
    }

    /** 138 0013 8000 这种分组，老人念得出来 */
    fun pretty(raw: String?): String {
        val d = digits(raw)
        val tail = if (d.length > 11) d.substring(d.length - 11) else d
        if (tail.length != 11) return d
        return tail.substring(0, 3) + " " + tail.substring(3, 7) + " " + tail.substring(7)
    }
}
