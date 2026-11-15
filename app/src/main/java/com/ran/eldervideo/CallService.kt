package com.ran.eldervideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioManager
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
+import android.telecom.CallAudioState
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * 默认电话应用（ROLE_DIALER）之后，系统把来电交给这里，而不是它自己的来电界面。
 * 这里做三件事：记住当前那通电话、把自绘的 CallActivity 拉起来、
 * 在通话结束的瞬间把"这通电话怎么结束的"写进 CallLedger —— 结束页只按账本显示，
 * 不再问 Telecom 现在的状态（HyperOS 上通话结束后 state 经常停在 DIALING、方向报 UNKNOWN）。
 */
class CallService : InCallService() {

    companion object {
        private const val TAG = "ElderCall"

        @Volatile
        var current: Call? = null
            private set

        @Volatile
        var instance: CallService? = null
            private set

        @Volatile
        private var appCtx: Context? = null

        /**
         * 免提 + 最大声（1.0.44 重做）。
         * 之前 CallActivity 里那个 forceSpeakerMaxVolume 只设了 mode 和音量、
         * 把自己变量记成 true 就打日志「speaker on」——Android 10 后音频路由由
         * Telecom 管，真正开关扬声器必须 call.setAudioRoute，所以一直是听筒。
         * 打进打出都经过这里（InCallService 拿得到 Call），延迟补两次防路由被抢回。
         */
        /**
         * 路由 API 走反射：Call.Details.getAvailableAudioRoutes / Call.setAudioRoute
         * 在 compileSdk 34 上直接引用编译不过（#145 实测 4 个 Unresolved reference），
         * 反射按类名取方法在设备端最稳——AudioRoute.TYPE_BUILTIN_SPEAKER 常量值为 2。
         */
        /** 首次失败时把设备端真实的方法/字段名打出来，不再盲猜（#147 教训） */
        private var routeProbed = false

        private fun probeRoutes(c: Call) {
            if (routeProbed) return
            routeProbed = true
            try {
                Log.i(TAG, "probe Call route methods: " +
                    c.javaClass.methods.map { it.name }.filter { it.contains("Route", true) }.distinct())
                val det = c.javaClass.getMethod("getDetails").invoke(c)
                if (det == null) { Log.w(TAG, "probe details null"); return }
                Log.i(TAG, "probe Details class=${det.javaClass.name} route methods: " +
                    det.javaClass.methods.map { it.name }.filter { it.contains("Route", true) }.distinct())
                val getter = det.javaClass.methods.firstOrNull {
                    it.name.contains("Route", true) && it.parameterCount == 0 &&
                        List::class.java.isAssignableFrom(it.returnType)
                } ?: return
                @Suppress("UNCHECKED_CAST")
                val pairs = getter.invoke(det) as? List<Any> ?: return
                pairs.firstOrNull()?.let { p ->
                    Log.i(TAG, "probe pair class=${p.javaClass.name} fields: " +
                        p.javaClass.fields.map { "${it.name}:${it.type.simpleName}" })
                }
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }

        /** telecom 路由（dialer 角色的正门）；Android 15 上 getter 名字可能变了，探测着用 */
        private fun routeViaTelecom(c: Call): Boolean {
            return try {
                val det = c.javaClass.getMethod("getDetails").invoke(c) ?: return false
                val getter = det.javaClass.methods.firstOrNull {
                    it.name.contains("Route", true) && it.parameterCount == 0 &&
                        List::class.java.isAssignableFrom(it.returnType)
                } ?: return false
                @Suppress("UNCHECKED_CAST")
                val pairs = getter.invoke(det) as? List<Any> ?: return false
                val routeClass = Class.forName("android.telecom.CallAudioState\$Route")
                for (pair in pairs) {
                    // AudioRoutePair.audioRoute 是 int；新版分组结构则找 getType()
                    var type: Int? = null
                    try { type = pair.javaClass.getField("audioRoute").getInt(pair) } catch (_: Throwable) {}
                    if (type == null) {
                        try {
                            val r = pair.javaClass.getMethod("getRoute").invoke(pair)
                            type = r?.javaClass?.getMethod("getType")?.invoke(r) as? Int
                        } catch (_: Throwable) {}
                    }
                    if (type == 2) { // ROUTE_BUILTIN_SPEAKER
                        val ctor = routeClass.constructors.firstOrNull {
                            it.parameterCount == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType
                        } ?: return false
                        val route = ctor.newInstance(2, null, "Speaker")
                        c.javaClass.getMethod("setAudioRoute", routeClass).invoke(c, route)
                        return true
                    }
                }
                false
            } catch (t: Throwable) {
                Log.w(TAG, "telecom route failed: ${t.javaClass.simpleName} ${t.message}")
                probeRoutes(c)
                false
            }
        }

        /** Android 12+ 官方新路：setCommunicationDevice（通话由本 App 发起时允许） */
        private fun routeViaCommDevice(am: AudioManager?): Boolean {
            return try {
                if (am == null || android.os.Build.VERSION.SDK_INT < 31) return false
                val getter = am.javaClass.getMethod("getAvailableCommunicationDevices")
                @Suppress("UNCHECKED_CAST")
                val devs = getter.invoke(am) as? List<Any> ?: return false
                val spk = devs.firstOrNull {
                    (it.javaClass.getMethod("getType").invoke(it) as? Int) == 2 // TYPE_BUILTIN_SPEAKER
                } ?: return false
                val amClass = AudioManager::class.java
                amClass.getMethod("setCommunicationDevice",
                    Class.forName("android.media.AudioDeviceInfo")).invoke(am, spk)
                Log.i(TAG, "comm device set ok")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "comm device failed: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }

        /**
         * 1.0.47：免提的唯一正门。
         * 之前一直在反射 Call.getDetails() 找 Route getter——方向是错的：setAudioRoute 从来不在
         * Call 上，它是 InCallService 的实例方法，由 Telecom 的 CallAudioModeStateMachine 执行。
         * 这就是「日志写着 speaker on、耳朵听到的是听筒」的真正原因。
         */
        private fun setRouteSpeaker(): Boolean {
            val svc = instance ?: return false
            return try {
                @Suppress("DEPRECATION")
                svc.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "setAudioRoute failed: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }

        fun forceSpeakerNow(from: String) {
            val svc = instance ?: return
            try {
                val am = svc.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                var routed = "none"
                if (setRouteSpeaker()) routed = "incallService"
                try { am?.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Throwable) {}
                if (routed == "none" && routeViaCommDevice(am)) routed = "commDevice"
                if (routed == "none") {
                    @Suppress("DEPRECATION")
                    try { am?.isSpeakerphoneOn = true; routed = "legacy" } catch (_: Throwable) {}
                }
                var vol = -1
                try {
                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
                    am?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
                    vol = max
                } catch (_: Throwable) {}
                @Suppress("DEPRECATION")
                val spk = try { am?.isSpeakerphoneOn == true } catch (_: Throwable) { false }
                Log.i(TAG, "speaker[$from] routed=$routed speakerOn=$spk vol=$vol")
            } catch (t: Throwable) {
                Log.w(TAG, "speaker[$from] failed: $t")
            }
        }

        fun scheduleSpeaker(from: String) {
            forceSpeakerNow(from)
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.postDelayed({ forceSpeakerNow("$from+0.8s") }, 800L)
            h.postDelayed({ forceSpeakerNow("$from+2s") }, 2000L)
        }

        /** 接听：只接语音，不接视频 */
        fun acceptCall() {
            val c = current ?: return
            try {
                c.answer(VideoProfile.STATE_AUDIO_ONLY)
                Log.i(TAG, "accept ok")
                scheduleSpeaker("accept")
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }

        /** 挂断：响铃中走 reject，已接通走 disconnect */
        fun endCall() {
            val c = current ?: return
            try {
                if (c.details.state == Call.STATE_RINGING) {
                    c.reject(false, null as String?)
                } else {
                    c.disconnect()
                }
                Log.i(TAG, "end ok")
            } catch (t: Throwable) {
                Log.w(TAG, "end failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }

        /** 结束页：CallActivity 还活着它自己会刷，不在了在这里补一枪 */
        fun showEndScreen() {
            val ctx = appCtx ?: return
            cancelNote(ctx)
            if (CallActivity.alive) return
            instance?.ensureAnchor()
            try {
                ctx.startActivity(
                    Intent(ctx, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                Log.i(TAG, "end screen started from service")
            } catch (t: Throwable) {
                Log.w(TAG, "end screen failed: $t")
            }
        }

        /**
         * 全屏通知兜底：HyperOS 有「后台弹出界面」这层限制，InCallService 里 startActivity
         * 有可能被静默吞掉，界面不出现但系统照样在响铃。这条带 fullScreenIntent 的通话通知
         * 是系统允许的唤起方式，作为第二条路。
         */
        private fun postCallNote(ctx: Context, name: String, number: String) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        "call_screen", "来电界面", NotificationManager.IMPORTANCE_HIGH
                    )
                    ch.setShowBadge(false)
                    nm.createNotificationChannel(ch)
                }
                val pi = PendingIntent.getActivity(
                    ctx, 7301,
                    Intent(ctx, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val b = Notification.Builder(ctx, "call_screen")
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle(if (name.isEmpty()) "有人打来了电话" else name)
                    .setContentText(if (number.isEmpty()) "点这里看这通电话" else number)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                nm.notify(7301, b.build())
                Log.i(TAG, "call notification posted")
            } catch (t: Throwable) {
                Log.w(TAG, "call notification failed: $t")
            }
        }

        private fun cancelNote(ctx: Context) {
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(7301)
            } catch (t: Throwable) {
            }
        }

        private fun watch(call: Call) {
            try {
                call.registerCallback(object : Call.Callback() {
                    override fun onStateChanged(c: Call, state: Int) {
                        try {
                            if (state == Call.STATE_ACTIVE) {
                                CallLedger.everActive = true
                                Log.i(TAG, "state ACTIVE")
                                scheduleSpeaker("active")
                            }
                            if (state == Call.STATE_DISCONNECTED) {
                                Log.i(TAG, "state DISCONNECTED")
                                decide(c)
                            }
                        } catch (t: Throwable) {
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "register callback failed: $t")
            }
        }

        /** 定论并收尾。endReason 已经写过就不会被覆盖 */
        private fun decide(call: Call) {
            CallLedger.settle(CallLedger.everActive, CallLedger.selfHung, CallLedger.outgoing)
            if (current === call) current = null
            showEndScreen()
        }
    }

    /**
     * 系统承认的实际路由。免提到底生没生效，只看这一行日志——
     * 不再像以前那样拿本地变量的自述当结论。
     */
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        try {
            @Suppress("DEPRECATION")
            val r = audioState?.route ?: -1
            @Suppress("DEPRECATION")
            val isSpk = r == CallAudioState.ROUTE_SPEAKER
            Log.i(TAG, "audioState route=$r speaker=$isSpk")
        } catch (t: Throwable) {
            Log.w(TAG, "audioState read failed: $t")
        }
    }

    /**
     * 后台弹窗豁免锚点。
     * HyperOS/Android 10+ 会静默拦掉从后台发起的 startActivity——表现就是电话在响、
     * 我们那页却没出现。系统对「自己已经有可见窗口」的应用放行（BAL 豁免条件之一），
     * 所以这里常驻一个 1×1、全透明、坐标在屏幕外、不吃任何触摸的悬浮窗当那扇"可见窗口"。
     * 看不见、不影响任何操作，服务销毁时一起收掉。
     */
    private var anchor: View? = null

    private fun ensureAnchor() {
        if (anchor != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission, anchor skipped")
            return
        }
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val v = View(this)
            wm.addView(
                v,
                WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { x = -2000; y = -2000; alpha = 0f }
            )
            anchor = v
            Log.i(TAG, "anchor overlay added")
        } catch (t: Throwable) {
            Log.w(TAG, "anchor failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun dropAnchor() {
        val v = anchor ?: return
        anchor = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appCtx = applicationContext
        ensureAnchor()
        Log.i(TAG, "InCallService created")
    }

    override fun onDestroy() {
        dropAnchor()
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        current = call

        val num = try {
            call.details.handle?.schemeSpecificPart.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val dirOut = try {
            call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        } catch (t: Throwable) {
            false
        }
        val st = try {
            call.details.state
        } catch (t: Throwable) {
            Call.STATE_NEW
        }
        Log.i(TAG, "call added state=$st dirOut=$dirOut num=$num ledgerOut=${CallLedger.outgoing} reason=${CallLedger.endReason}")

        if (CallLedger.outgoing && CallLedger.endReason.isEmpty() && CallLedger.startAt > 0) {
            // 刚由我们自己拨出去的那一通：账本在 DialActivity 里已经开好，别覆盖
            if (CallLedger.number.isEmpty()) CallLedger.number = num
            CallLedger.name = CallBook.nameFor(CallLedger.number)
        } else if (dirOut) {
            CallLedger.beginOutgoing(num)
        } else {
            CallLedger.beginIncoming(num)
        }
        if (st == Call.STATE_ACTIVE) CallLedger.everActive = true

        watch(call)

        ensureAnchor()
        try {
            startActivity(
                Intent(this, CallActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "start CallActivity failed: $t")
        }
        postCallNote(this, CallLedger.name, CallLedger.number)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "call removed ledger=${CallLedger.endReason} active=${CallLedger.everActive} out=${CallLedger.outgoing}")
        // onCallRemoved 常常比 DISCONNECTED 先到，这里也定论一次（已有的不会覆盖）
        decide(call)
    }
}

/**
 * 一通电话的账本。
 *
 * 为什么需要：Telecom 在通话结束时不一定及时把 Call 摘掉，也不一定给 DISCONNECTED 回调
 * （这台 HyperOS 上实测 state 停在 DIALING），callDirection 还可能报 UNKNOWN。
 * 界面如果每次去问"现在的状态是什么"，就会漏掉挂断那一刻——表现就是：电话挂了，
 * 屏幕直接回桌面，老人不知道该干什么。所以由能在第一时间拿到状态的 InCallService 记账，
 * 界面只按账本显示结束原因。
 */
object CallLedger {
    private const val TAG = "ElderCall"

    @Volatile
    var outgoing = false          // 是不是我们打出去的

    @Volatile
    var everActive = false        // 对方接起来过

    @Volatile
    var selfHung = false          // 是自己按的挂断 / 拒接

    @Volatile
    var number = ""

    @Volatile
    var name = ""

    @Volatile
    var startAt = 0L

    /** "" 还在通话中；missed 没接通；ended 接通过又挂了；callerHangup 别人打来又挂了；selfHung 自己挂的 */
    @Volatile
    var endReason = ""

    @Volatile
    var endedAt = 0L

    @Synchronized
    fun beginOutgoing(num: String) {
        reset()
        outgoing = true
        number = num
        name = CallBook.nameFor(num)
        startAt = System.currentTimeMillis()
        Log.i(TAG, "ledger begin outgoing num=$num name=$name")
    }

    @Synchronized
    fun beginIncoming(num: String) {
        // 同一通电话的回调会重复到达，两秒内不清账
        if (endReason.isEmpty() && startAt > 0 &&
            System.currentTimeMillis() - startAt < 2000
        ) {
            if (number.isEmpty()) number = num
            if (name.isEmpty()) name = CallBook.nameFor(num)
            return
        }
        reset()
        number = num
        name = CallBook.nameFor(num)
        startAt = System.currentTimeMillis()
        Log.i(TAG, "ledger begin incoming num=$num name=$name")
    }

    fun reset() {
        outgoing = false
        everActive = false
        selfHung = false
        number = ""
        name = ""
        startAt = System.currentTimeMillis()
        endReason = ""
        endedAt = 0L
    }

    @Synchronized
    fun settle(active: Boolean, self: Boolean, out: Boolean): String {
        if (endReason.isNotEmpty()) return endReason
        val r = when {
            self -> "selfHung"
            out && !active -> "missed"
            !out && !active -> "callerHangup"
            else -> "ended"
        }
        endReason = r
        endedAt = System.currentTimeMillis()
        Log.i(TAG, "ledger settle reason=$r out=$out active=$active self=$self num=$number")
        return r
    }

    @Synchronized
    fun markSelfHung() {
        selfHung = true
        if (endReason.isEmpty()) {
            endReason = "selfHung"
            endedAt = System.currentTimeMillis()
            Log.i(TAG, "ledger self hung")
        }
    }

    @Synchronized
    fun forceMissed() {
        if (endReason.isEmpty()) {
            endReason = "missed"
            endedAt = System.currentTimeMillis()
            Log.i(TAG, "ledger force missed")
        }
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
