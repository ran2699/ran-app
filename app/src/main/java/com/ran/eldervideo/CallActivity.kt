package com.ran.eldervideo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自绘的来电、拨出、通话、没接通四种界面。系统那一套不会出现（本应用是默认电话应用）。
 *
 *   来电      王敬然 / 给你打来了电话      + 绿字「点一下绿色的按钮接电话」
 *   拨出      正在给 / 王敬然 / 打电话      + 「你再等一会，他还没有接电话」
 *   接通      王敬然 / 他接了你的电话      + 「现在可以说话了」（震动两下、自动开免提）
 *   没接通    红色白边字「他没有接你的电话，你可以过一会再拨」+ 关闭按钮
 */
class CallActivity : Activity() {

    companion object {
        /** 界面在不在。CallService 用它决定要不要补拉结束页 */
        @Volatile
        var alive = false
    }

    private lateinit var col: LinearLayout
    private lateinit var tagView: TextView
    private lateinit var nameView: TextView
    private lateinit var lineView: TextView
    private lateinit var numView: TextView
    private lateinit var stateView: TextView
    private lateinit var hintView: TextView
    private lateinit var hangBtn: BigCallButton
    private lateinit var answerBtn: BigCallButton
    private lateinit var missedLayer: FrameLayout
    private lateinit var endHeading: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var connectAt = 0L
    private var ringing = false

    private var preview = false
    private var previewName = ""
    private var previewNumber = ""
    private var previewState = ""

    private var shownOnce = false
    private var everActive = false
    private var outgoing = false
    private var endedShown = false
    private var speakerOn = false
    private var hungSelf = false
    private var screenSince = 0L
    private var lastState = -999
    private var lastStateAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try {
                render()
            } catch (t: Throwable) {
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        preview = intent?.getBooleanExtra("preview", false) ?: false
        previewName = intent?.getStringExtra("preview_name").orEmpty()
        previewNumber = intent?.getStringExtra("preview_number").orEmpty()
        previewState = intent?.getStringExtra("preview_state").orEmpty()
        screenSince = System.currentTimeMillis()
        alive = true
        Log.i("ElderCall", "CallActivity onCreate preview=$preview previewState=$previewState")
        buildUi()
        setupImmersive()
        render()
        handler.post(ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersive()
    }

    private fun setupImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
            }
        } catch (t: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            // Activity 是 singleTop，第二屏会被复用：预览参数必须重读一遍，
            // 否则切到「接通」「没接通」还停在上一次的画面。
            if (intent?.getBooleanExtra("preview", false) == true) {
                previewName = intent.getStringExtra("preview_name") ?: previewName
                previewNumber = intent.getStringExtra("preview_number") ?: previewNumber
                previewState = intent.getStringExtra("preview_state") ?: previewState
                missedLayer.visibility = View.GONE
                col.visibility = View.VISIBLE
                endedShown = false
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            render()
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        alive = false
        handler.removeCallbacks(ticker)
        stopRing()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 字号自动收缩：宁可字大一点，也不能因为系统字体被放大而裁掉半个字 */
    private fun autoSize(tv: TextView, minSp: Int, maxSp: Int, lines: Int) {
        tv.maxLines = lines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                minSp, maxSp, 2, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** 音频是不是还在通话模式。挂断后系统会把它切回 NORMAL，
     *  这个信号不挑权限，比 TelecomManager.isInCall 在 MIUI 上可靠
     *  （后者对我们这种第三方拨号器会直接返回 false）。 */
    private fun audioInCall(): Boolean = try {
        val am = getSystemService(AudioManager::class.java)
        am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
    } catch (t: Throwable) {
        true
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0B0B"))

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(80), dp(22), dp(30))
        }

        tagView = TextView(this).apply {
            text = "来电"
            setTextColor(Color.parseColor("#9AA5B1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
        }
        col.addView(tagView, LinearLayout.LayoutParams(-1, -2))

        nameView = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        lineView = TextView(this).apply {
            text = "给你打来了电话"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(lineView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        numView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        }
        col.addView(numView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        stateView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
        }
        col.addView(stateView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        hintView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#7BD88F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(hintView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        hangBtn = BigCallButton(this, "挂电话", Color.parseColor("#C62828"), Color.parseColor("#6E0A0A"), BigCallButton.ICON_HANG)
        hangBtn.setOnPressListener {
            // 自己按挂断：账本记成 selfHung，界面立刻给一张「电话已经挂断了」，不误报没接
            hungSelf = true
            CallLedger.markSelfHung()
            CallService.endCall()
            showEnd()
        }
        answerBtn = BigCallButton(this, "接电话", Color.parseColor("#2E9E4F"), Color.parseColor("#14501F"), BigCallButton.ICON_ANSWER)
        answerBtn.setOnPressListener { CallService.acceptCall() }

        row.addView(hangBtn, LinearLayout.LayoutParams(0, dp(200), 1f).apply { rightMargin = dp(10) })
        row.addView(answerBtn, LinearLayout.LayoutParams(0, dp(200), 1f).apply { leftMargin = dp(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(34) })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- 没接通那一屏：红字白边 + 关闭 ----
        missedLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            visibility = View.GONE
        }
        val missCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(34), dp(60), dp(34), dp(60))
        }
        // 字由 showEnd() 填：红字白边 = 没接上，白字 = 挂了
        endHeading = FrameLayout(this)
        missCol.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        missCol.addView(endHeading, LinearLayout.LayoutParams(-1, -2))
        missCol.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val closeBtn = BigCallButton(this, "关闭", Color.parseColor("#3A3F46"), Color.parseColor("#22262B"), BigCallButton.ICON_CROSS)
        closeBtn.textSizeSp = 34f
        closeBtn.iconSizeDp = 40f
        closeBtn.setOnPressListener { finishSafely() }
        missCol.addView(closeBtn, LinearLayout.LayoutParams(-1, dp(140)).apply { bottomMargin = dp(30) })
        missedLayer.addView(missCol, FrameLayout.LayoutParams(-1, -1))
        root.addView(missedLayer, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        autoSize(nameView, 44, 96, 2)
        autoSize(lineView, 30, 52, 2)
        autoSize(stateView, 28, 40, 1)
        autoSize(hintView, 30, 44, 2)
        Haptics.attach(window.decorView)
    }

    /**
     * 红字白边：两个 TextView 严格叠在一起 —— 下层只描白边，上层填红。
     * 在同一个 TextView 里改 paint.style 连画两遍，实测红字会被白边糊成白的。
     */
    private class StrokeLabel(ctx: Context, label: String, sizeSp: Float) : TextView(ctx) {
        init {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(12f * resources.displayMetrics.density, 1f)
        }

        override fun onDraw(c: Canvas) {
            val p = paint
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f * resources.displayMetrics.density
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.WHITE
            super.onDraw(c)
            p.style = Paint.Style.FILL
        }
    }

    private class FillLabel(ctx: Context, label: String, sizeSp: Float) : TextView(ctx) {
        init {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF3B30"))
            setLineSpacing(12f * resources.displayMetrics.density, 1f)
        }
    }

    private fun setName(name: String) {
        nameView.text = name
    }

    /** 填结束页上的字。断行写死，不让它自己断在「电/话」中间 */
    private fun setHeading(msg: String, red: Boolean) {
        endHeading.removeAllViews()
        if (red) {
            endHeading.addView(StrokeLabel(this, msg, 36f), FrameLayout.LayoutParams(-1, -2))
            endHeading.addView(FillLabel(this, msg, 36f), FrameLayout.LayoutParams(-1, -2))
        } else {
            val tv = TextView(this).apply {
                text = msg
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 3
                setLineSpacing(14f * resources.displayMetrics.density, 1f)
            }
            endHeading.addView(tv, FrameLayout.LayoutParams(-1, -2))
        }
    }

    /** 电话结束那一屏。原因只看账本，不看 Telecom 现在的状态 */
    private fun showEnd() {
        if (endedShown) return
        endedShown = true
        handler.removeCallbacks(ticker)
        stopRing()
        val reason = CallLedger.endReason
        val who = if (CallLedger.name.isNotEmpty()) CallLedger.name else "他"
        val red = reason == "missed"
        val msg = when (reason) {
            "missed" -> "他没有接你的电话\n你可以过一会再拨"
            "callerHangup" -> who + " 挂断了电话\n你可以给他打回去"
            else -> "电话已经挂断了"
        }
        try {
            setHeading(msg, red)
            col.visibility = View.GONE
            missedLayer.visibility = View.VISIBLE
        } catch (t: Throwable) {
        }
        Log.i("ElderCall", "end screen reason=$reason")
    }

    private fun render() {
        if (endedShown) return

        // 调试预览：不需要真打通电话也能看每一屏
        if (preview) {
            renderPreview()
            return
        }

        // 账本已经定论：不管 Call 对象还在不在，都显示结束页。
        // 「挂断后有没有页面」只在这一处判断，不再问 Telecom 现在的状态。
        if (CallLedger.endReason.isNotEmpty()) {
            showEnd()
            return
        }

        val call = CallService.current
        if (call == null) {
            // 起来那一瞬间 current 可能还没设好，等一秒再说
            if (System.currentTimeMillis() - screenSince < 1200) return
            outgoing = CallLedger.outgoing
            everActive = CallLedger.everActive
            CallLedger.settle(everActive, CallLedger.selfHung, outgoing)
            showEnd()
            return
        }
        shownOnce = true


        val st = try {
            call.details.state
        } catch (t: Throwable) {
            Call.STATE_DISCONNECTED
        }
        val number = try {
            call.details.handle?.schemeSpecificPart.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        // 方向以账本为准（DialActivity 拨号那一刻就记下了），Telecom 报 UNKNOWN 也不会错
        outgoing = CallLedger.outgoing || try {
            call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        } catch (t: Throwable) {
            false
        }
        if (outgoing) CallLedger.outgoing = true
        // 对方先挂了，Telecom 有时只是把 Call 对象晾在那儿（state 停在 DIALING / ACTIVE），
        // 界面就卡住。只看「状态多久没变」+「音频还在不在通话模式」这两个信号：
        //   已接通：8 秒没变且音频已退出通话 → 收尾
        //   拨号中：60 秒还没接通 → 当作没接上，弹提示页
        if (st != lastState) {
            lastState = st
            lastStateAt = System.currentTimeMillis()
            Log.i("ElderCall", "state=$st outgoing=$outgoing active=$everActive")
        }
        val idleMs = System.currentTimeMillis() - lastStateAt
        if (st == Call.STATE_ACTIVE && idleMs > 8000 && !audioInCall()) {
            Log.i("ElderCall", "已接通但音频已退出通话，收尾")
            finishSafely()
            return
        }
        if ((st == Call.STATE_DIALING || st == Call.STATE_CONNECTING) && idleMs > 20000) {
            Log.i("ElderCall", "拨号 20 秒仍未接通，当作没接上")
            CallLedger.forceMissed()
            showEnd()
            return
        }

        val name = CallBook.nameFor(number)
        val shown = if (name.isNotEmpty()) name else "陌生号码"
        numView.text = CallBook.pretty(number)
        numView.visibility = View.VISIBLE

        when (st) {
            Call.STATE_RINGING -> {
                setName(shown)
                tagView.text = "来电"
                tagView.visibility = View.VISIBLE
                lineView.text = "给你打来了电话"
                lineView.visibility = View.VISIBLE
                stateView.text = ""
                hintView.text = "点一下绿色的按钮接电话"
                hintView.setTextColor(Color.parseColor("#7BD88F"))
                hintView.visibility = View.VISIBLE
                answerBtn.visibility = View.VISIBLE
                hangBtn.visibility = View.VISIBLE
                startRing()
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                // 正在给 / 王敬然 / 打电话
                setName(shown)
                tagView.text = "正在给"
                tagView.visibility = View.VISIBLE
                lineView.text = "打电话"
                lineView.visibility = View.VISIBLE
                stateView.text = ""
                hintView.text = "你再等一会\n他还没有接电话"
                hintView.setTextColor(Color.parseColor("#CFD8DC"))
                hintView.visibility = View.VISIBLE
                answerBtn.visibility = View.GONE
                hangBtn.visibility = View.VISIBLE
                stopRing()
            }
            Call.STATE_ACTIVE -> {
                if (!everActive) {
                    everActive = true
                    CallLedger.everActive = true
                    connectAt = System.currentTimeMillis()
                    notifyConnected()
                }
                setName(shown)
                tagView.visibility = View.GONE
                lineView.text = "他接了你的电话"
                lineView.visibility = View.VISIBLE
                hintView.text = "现在可以说话了"
                hintView.setTextColor(Color.parseColor("#7BD88F"))
                hintView.visibility = View.VISIBLE
                answerBtn.visibility = View.GONE
                hangBtn.visibility = View.VISIBLE
                stopRing()
                val sec = (System.currentTimeMillis() - connectAt) / 1000L
                stateView.text = String.format("%02d:%02d", sec / 60, sec % 60)
            }
            Call.STATE_DISCONNECTED -> {
                stopRing()
                CallLedger.settle(everActive, hungSelf, outgoing)
                showEnd()
            }
            else -> {
            }
        }
    }

    private fun renderPreview() {
        val pn = if (previewName.isNotEmpty()) previewName else "王敬然"
        val pnum = if (previewNumber.isNotEmpty()) previewNumber else "18712961913"
        when (previewState) {
            "missed" -> {
                if (!endedShown) {
                    endedShown = true
                    handler.removeCallbacks(ticker)
                    setHeading("他没有接你的电话\n你可以过一会再拨", true)
                    col.visibility = View.GONE
                    missedLayer.visibility = View.VISIBLE
                }
            }
            "active" -> {
                setName(pn)
                tagView.visibility = View.GONE
                lineView.text = "他接了你的电话"
                numView.text = CallBook.pretty(pnum)
                hintView.text = "现在可以说话了"
                hintView.setTextColor(Color.parseColor("#7BD88F"))
                stateView.text = "00:07"
                answerBtn.visibility = View.GONE
            }
            "dialing" -> {
                setName(pn)
                tagView.text = "正在给"
                tagView.visibility = View.VISIBLE
                lineView.text = "打电话"
                numView.text = CallBook.pretty(pnum)
                hintView.text = "你再等一会\n他还没有接电话"
                hintView.setTextColor(Color.parseColor("#CFD8DC"))
                stateView.text = ""
                answerBtn.visibility = View.GONE
            }
            else -> {
                setName(pn)
                tagView.text = "来电"
                tagView.visibility = View.VISIBLE
                lineView.text = "给你打来了电话"
                numView.text = CallBook.pretty(pnum)
                hintView.text = "点一下绿色的按钮接电话"
                hintView.setTextColor(Color.parseColor("#7BD88F"))
                stateView.text = ""
                answerBtn.visibility = View.VISIBLE
            }
        }
    }

    /** 接通那一下：震两下、自动开免提、音量拉到最大 */
    private fun notifyConnected() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 180, 220), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 220, 180, 220), -1)
                }
            }
        } catch (t: Throwable) {
            Log.w("ElderCall", "接通震动失败：" + t.message)
        }
        try {
            val am = getSystemService(AudioManager::class.java)
            am.mode = AudioManager.MODE_IN_CALL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (dev != null) am.setCommunicationDevice(dev)
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            speakerOn = true
            Log.i("ElderCall", "免提已开，音量 $max/${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} 模式=${am.mode}")
        } catch (t: Throwable) {
            Log.w("ElderCall", "开免提失败：" + t.message)
        }
    }

    private fun finishSafely() {
        Log.i("ElderCall", "关闭通话界面 outgoing=$outgoing active=$everActive hungSelf=$hungSelf state=$lastState")
        handler.removeCallbacks(ticker)
        stopRing()
        try {
            finish()
        } catch (t: Throwable) {
        }
    }

    private fun startRing() {
        if (ringing) return
        ringing = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
            r.play()
            ringtone = r
        } catch (t: Throwable) {
        }
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 800, 700, 800, 700)
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
                vibrator = v
            }
        } catch (t: Throwable) {
        }
    }

    private fun stopRing() {
        if (!ringing) return
        ringing = false
        try {
            ringtone?.stop()
        } catch (t: Throwable) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
        }
        vibrator = null
    }
}
