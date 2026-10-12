package com.michi.arana

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min

/**
 * Servicio en primer plano que mantiene a la figura flotando sobre cualquier app.
 * - Toca la figura: abre/cierra el panel de chat.
 * - Mantén pulsada: te escucha por el micrófono.
 * - Arrástrala: se pega al borde más cercano. Suéltala sobre la ✕ de abajo para cerrarla.
 */
class OverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "com.michi.arana.STOP"
        const val ACTION_LISTEN = "com.michi.arana.LISTEN"
        private const val CHANNEL_RUN = "michi_run"
        private const val CHANNEL_AVISO = "michi_aviso"
        private const val NOTIF_ID = 7
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var avisos: Avisos
    private lateinit var brain: Brain

    // BYNS flotante
    private lateinit var avatar: AvatarView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleSize = 0

    // Globo de diálogo junto a la figura
    private lateinit var talk: TextView
    private lateinit var talkParams: WindowManager.LayoutParams
    private var talkShown = false
    private val hideTalk = Runnable { if (talkShown) { wm.removeView(talk); talkShown = false } }

    // Zona para cerrar
    private lateinit var closeZone: TextView
    private lateinit var closeParams: WindowManager.LayoutParams
    private var closeShown = false

    // Panel de chat
    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micBtn: Button

    // Voz
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    // «Oye BYNS»: escucha continua en segundo plano
    private enum class Listen { NONE, WAKE, COMMAND }
    private var listenMode = Listen.NONE
    private var speaking = false
    private var listenAfterSpeech = false
    private val wakeRunnable = Runnable { startWake() }
    private val wakeRegex = Regex("(?:^|\\b)(?:oye|hey|ey|oie|hola|ok|okey)?\\s*(?:byns|bins|bines|binz|bains|baines|bayns|baynes|beins|veins|vins|vines|vyns|bings|bain|vains|byn)\\b[\\s,.]*(.*)$")

    // Claude
    private val executor = Executors.newSingleThreadExecutor()
    private var claude: ClaudeClient? = null
    private var claudeKey = ""
    private val prefs get() = getSharedPreferences("michi", MODE_PRIVATE)

    private val ticker = object : Runnable {
        override fun run() {
            val due = avisos.popDue(System.currentTimeMillis())
            if (due.isNotEmpty()) fire(due)
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        avisos = Avisos(this)
        brain = Brain(this, avisos) { avatar.skin }
        startInForeground()
        tts = TextToSpeech(this, this)
        buildBubble()
        buildTalk()
        buildCloseZone()
        main.post(ticker)
        main.postDelayed({ say(brain.hola(), if (avatar.skin == AvatarView.Skin.SOLDADO) AvatarView.Move.SALUTE else AvatarView.Move.WAVE) }, 600)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_LISTEN -> startListening()
            else -> {
                startInForeground()  // vuelve a declarar los tipos por si ahora hay permiso de micrófono
                if (::avatar.isInitialized) {
                    val sk = savedSkin()
                    if (sk != avatar.skin) { avatar.transformTo(sk); applyVoice(sk) }
                }
                if (prefs.getBoolean("wake", false)) scheduleWake(1500) else stopWake()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        executor.shutdownNow()
        tts?.shutdown(); tts = null
        listOfNotNull(avatar, panel, if (talkShown) talk else null, if (closeShown) closeZone else null).forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Al girar la tablet, mantener la figura dentro de la pantalla
        main.postDelayed({
            snapToEdge(animate = false)
            panel?.let { layoutPanel(); wm.updateViewLayout(it, panelParams) }
        }, 300)
    }

    // ---------------------------------------------------------------- primer plano

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "BYNS activo", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_AVISO, "Avisos de BYNS", NotificationManager.IMPORTANCE_HIGH))

        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("BYNS está activo")
            .setContentText("Di «Oye BYNS» o toca el muñequito")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ocultar", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= 30 && hasMic()) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- utilidades de ventana

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    // ---------------------------------------------------------------- figura

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        bubbleSize = dp(if (isTablet) 130 else 96)
        avatar = AvatarView(this).apply { contentDescription = "BYNS, asistente"; setSkinNow(savedSkin()) }
        bubbleParams = overlayParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            val prefs = getSharedPreferences("michi", MODE_PRIVATE)
            x = prefs.getInt("x", screenW - bubbleSize)
            y = prefs.getInt("y", screenH / 3)
        }
        wm.addView(avatar, bubbleParams)
        snapToEdge(animate = false)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable { longPressed = true; vibrate(30); startListening() }

        avatar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true; main.removeCallbacks(longPress)
                        showClose(true); hideTalk.run()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(avatar, bubbleParams)
                        highlightClose(overClose())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        val close = overClose()
                        showClose(false)
                        if (close) { stopSelf(); return@setOnTouchListener true }
                        snapToEdge(animate = true)
                    } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        main.post { togglePanel() }
                    }
                }
            }
            true
        }
    }

    private fun snapToEdge(animate: Boolean) {
        val maxX = screenW - bubbleSize
        val targetX = if (bubbleParams.x + bubbleSize / 2 < screenW / 2) 0 else maxX
        bubbleParams.y = bubbleParams.y.coerceIn(dp(24), (screenH - bubbleSize - dp(24)).coerceAtLeast(dp(24)))
        val save = {
            getSharedPreferences("michi", MODE_PRIVATE).edit().putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply()
        }
        if (!animate) {
            bubbleParams.x = targetX
            try { wm.updateViewLayout(avatar, bubbleParams) } catch (_: Exception) {}
            save(); return
        }
        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 220
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                try { wm.updateViewLayout(avatar, bubbleParams) } catch (_: Exception) {}
            }
            start()
        }
        main.postDelayed(save, 240)
    }

    private fun onLeft() = bubbleParams.x + bubbleSize / 2 < screenW / 2

    // ---------------------------------------------------------------- zona de cerrar

    private fun buildCloseZone() {
        val s = dp(64)
        closeZone = TextView(this).apply {
            text = "✕"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = round(Color.argb(200, 30, 30, 46), s / 2f, dp(2), Color.WHITE)
        }
        closeParams = overlayParams(s, s,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        ).apply { alpha = 0.8f }
    }

    private fun showClose(show: Boolean) {
        if (show == closeShown) return
        if (show) {
            closeParams.x = (screenW - closeParams.width) / 2
            closeParams.y = screenH - closeParams.height - dp(48)
            wm.addView(closeZone, closeParams)
        } else wm.removeView(closeZone)
        closeShown = show
    }

    private fun overClose(): Boolean {
        val cx = bubbleParams.x + bubbleSize / 2f; val cy = bubbleParams.y + bubbleSize / 2f
        val zx = closeParams.x + closeParams.width / 2f; val zy = closeParams.y + closeParams.height / 2f
        return hypot(cx - zx, cy - zy) < dp(80)
    }

    private fun highlightClose(on: Boolean) {
        closeZone.scaleX = if (on) 1.25f else 1f; closeZone.scaleY = closeZone.scaleX
    }

    // ---------------------------------------------------------------- globo de diálogo

    private fun buildTalk() {
        talk = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1A2E")); textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = round(Color.WHITE, dp(18).toFloat(), dp(3), Color.parseColor("#1A1A2E"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 6; ellipsize = TextUtils.TruncateAt.END
        }
        // No tocable: los toques pasan a la app de abajo (alpha ≤ 0.8 lo exige Android 12+)
        talkParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        ).apply { alpha = 0.8f }
    }

    private fun showTalk(text: String, ms: Long) {
        if (panel != null) return
        talk.text = text
        talk.maxWidth = min(dp(340), screenW - bubbleSize - dp(24))
        talk.measure(
            View.MeasureSpec.makeMeasureSpec(talk.maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        talkParams.x = if (onLeft()) bubbleParams.x + bubbleSize + dp(4) else bubbleParams.x - talk.measuredWidth - dp(4)
        talkParams.y = (bubbleParams.y + dp(8)).coerceAtMost(screenH - talk.measuredHeight - dp(8))
        if (talkShown) wm.updateViewLayout(talk, talkParams) else { wm.addView(talk, talkParams); talkShown = true }
        main.removeCallbacks(hideTalk)
        main.postDelayed(hideTalk, ms)
    }

    // ---------------------------------------------------------------- panel de chat

    private fun togglePanel() { if (panel == null) openPanel() else closePanel() }

    @SuppressLint("SetTextI18n")
    private fun openPanel() {
        hideTalk.run()
        val ink = Color.parseColor("#1A1A2E")
        val red = Color.parseColor("#E23636")
        val blue = Color.parseColor("#2B4EB8")

        val root = object : LinearLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closePanel(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.parseColor("#F4F6FC"), dp(22).toFloat(), dp(3), ink)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            elevation = dp(8).toFloat()
            // El panel toma el foco primero para que el teclado solo salga al tocar el cuadro de texto
            isFocusableInTouchMode = true
        }

        // Cabecera
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "BYNS"; textSize = 24f; setTextColor(red)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for ((label, sk) in listOf("🪖" to AvatarView.Skin.SOLDADO, "🔴" to AvatarView.Skin.ARANA_ROJA, "⚫" to AvatarView.Skin.ARANA_NEGRA)) {
            header.addView(Button(this).apply {
                text = label; textSize = 16f; contentDescription = sk.title
                background = round(Color.parseColor("#DDE3F2"), dp(20).toFloat())
                setOnClickListener { changeSkin(sk) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
        }
        header.addView(Button(this).apply {
            text = "✕"; textSize = 18f; setTextColor(ink); contentDescription = "Cerrar panel"
            background = round(Color.parseColor("#DDE3F2"), dp(20).toFloat())
            setOnClickListener { closePanel() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        // Conversación
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        logScroll = ScrollView(this).apply {
            addView(log)
            background = round(Color.WHITE, dp(16).toFloat(), dp(1), Color.parseColor("#D5DCEC"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        history.forEach { addMsg(it.first, it.second, remember = false) }
        if (history.isEmpty()) addMsg(false, "¿Qué hacemos? Escríbeme o toca 🎤 para hablar.")

        // Atajos
        val chips = LinearLayout(this)
        listOf("Transfórmate", "Saca el fusil", "Columpiate", "Baila", "Voltereta", "¿Qué hora es?", "Temporizador de 5 minutos", "Abre YouTube", "Cuéntame un chiste")
            .forEach { q ->
                chips.addView(Button(this).apply {
                    text = q; isAllCaps = false; textSize = 14f; setTextColor(ink)
                    background = round(Color.WHITE, dp(18).toFloat(), dp(1), Color.parseColor("#C5CEE6"))
                    setPadding(dp(14), 0, dp(14), 0); minHeight = 0; minimumHeight = 0
                    setOnClickListener { send(q) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) })
            }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Entrada
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Escribe aquí…"; textSize = 16f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(ink); setHintTextColor(Color.parseColor("#7A84A6"))
            background = round(Color.WHITE, dp(16).toFloat(), dp(2), Color.parseColor("#C5CEE6"))
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
            setOnFocusChangeListener { _, has ->
                layoutPanel(keyboard = has)
                panel?.let { try { wm.updateViewLayout(it, panelParams) } catch (_: Exception) {} }
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        micBtn = Button(this).apply {
            text = "🎤"; textSize = 20f; contentDescription = "Hablar"
            background = round(blue, dp(24).toFloat())
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        row.addView(micBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        row.addView(Button(this).apply {
            text = "➤"; textSize = 20f; setTextColor(Color.WHITE); contentDescription = "Enviar"
            background = round(red, dp(24).toFloat())
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        panelParams = overlayParams(0, 0, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            windowAnimations = android.R.style.Animation_Dialog
        }
        panel = root
        layoutPanel()
        root.requestFocus()
        wm.addView(root, panelParams)
        // Volver a poner la figura encima del panel
        wm.removeView(avatar); wm.addView(avatar, bubbleParams)
        paintMic()
        avatar.play(AvatarView.Move.WAVE, 1200)
    }

    private fun layoutPanel(keyboard: Boolean = false) {
        val w = min(dp(440), screenW - dp(24))
        val maxH = if (keyboard) (screenH * 0.45f).toInt() else (screenH * 0.7f).toInt()
        val h = min(dp(560), maxH)
        panelParams.width = w; panelParams.height = h
        val gap = dp(6)
        panelParams.x = if (onLeft()) (bubbleSize + gap).coerceAtMost(screenW - w) else (screenW - bubbleSize - gap - w).coerceAtLeast(0)
        panelParams.y = if (keyboard) dp(24) else (bubbleParams.y - dp(20)).coerceIn(dp(24), (screenH - h - dp(24)).coerceAtLeast(dp(24)))
    }

    private fun closePanel() {
        val p = panel ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(p.windowToken, 0)
        wm.removeView(p)
        panel = null
        if (listening) stopListening()
    }

    private val history = mutableListOf<Pair<Boolean, String>>()

    private fun addMsg(me: Boolean, text: String, remember: Boolean = true) {
        if (remember) { history += me to text; if (history.size > 40) history.removeAt(0) }
        if (panel == null) return
        val tv = TextView(this).apply {
            this.text = text; textSize = 16f
            setTextColor(if (me) Color.WHITE else Color.parseColor("#1A1A2E"))
            background = round(if (me) Color.parseColor("#2B4EB8") else Color.parseColor("#EEF1F9"), dp(14).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        log.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (me) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (me) marginStart = dp(40) else marginEnd = dp(40)
        })
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit() {
        val t = input.text.toString()
        input.setText("")
        send(t)
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        addMsg(true, text.trim())
        avatar.play(AvatarView.Move.THINK, 500)
        main.postDelayed({
            val r = brain.handle(text)
            if (r != null) apply(r) else askClaude(text)
        }, 350)
    }

    private fun apply(r: Reply) {
        avatar.facing = if (onLeft()) 1 else -1   // apunta/dispara hacia el centro de la pantalla
        if (r.skin != null) {
            changeSkin(r.skin, announce = false)
            main.postDelayed({ say(r.text) }, 700)
        } else say(r.text, r.move, r.moveMs)
    }

    private fun savedSkin(): AvatarView.Skin =
        try { AvatarView.Skin.valueOf(prefs.getString("skin", "SOLDADO")!!) } catch (_: Exception) { AvatarView.Skin.SOLDADO }

    private fun changeSkin(sk: AvatarView.Skin, announce: Boolean = true) {
        prefs.edit().putString("skin", sk.name).apply()
        if (sk == avatar.skin) return
        avatar.transformTo(sk)
        applyVoice(sk)
        if (announce) main.postDelayed({ say(brain.hola()) }, 900)
    }

    private fun askClaude(text: String) {
        val key = prefs.getString("claude_key", "")!!.trim()
        if (key.isEmpty()) { apply(brain.offline(text)); return }
        if (claude == null || key != claudeKey) { claude = ClaudeClient(key); claudeKey = key }
        val c = claude!!
        val skin = avatar.skin
        avatar.play(AvatarView.Move.THINK)
        showTalk("Buscando con Claude…", 20000)
        executor.execute {
            val answer = c.ask(text, skin)
            main.post {
                avatar.play(AvatarView.Move.IDLE)
                say(answer, AvatarView.Move.WAVE, 1500)
            }
        }
    }

    // ---------------------------------------------------------------- hablar

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val sys = Locale.getDefault()
        val loc = if (sys.language == "es") sys else Locale("es", "ES")
        if (t.setLanguage(loc) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale("es"))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { avatar.talking = true; speaking = true } }
            override fun onDone(id: String?) { main.post { afterSpeech() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeech() } }
        })
        ttsReady = true
        applyVoice(avatar.skin)
    }

    private fun afterSpeech() {
        avatar.talking = false; speaking = false
        if (listenAfterSpeech) { listenAfterSpeech = false; startListening() } else scheduleWake(400)
    }

    /**
     * Una voz distinta para cada traje, elegida entre las voces en español instaladas en la tablet,
     * con su propio tono y velocidad.
     */
    private fun applyVoice(sk: AvatarView.Skin) {
        val t = tts ?: return
        if (!ttsReady) return
        val v = when (sk) {
            AvatarView.Skin.SOLDADO -> VoiceStyle(0.8f, 0.95f, 0, listOf("es-es", "es-mx", "es-us"))
            AvatarView.Skin.ARANA_ROJA -> VoiceStyle(1.2f, 1.1f, 1, listOf("es-us", "es-mx", "es-es"))
            AvatarView.Skin.ARANA_NEGRA -> VoiceStyle(1.0f, 1.15f, 2, listOf("es-us", "es-mx", "es-es"))
        }
        try {
            val voices = t.voices.orEmpty().filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
                .sortedWith(compareBy({ x -> v.prefer.indexOf(x.locale.toLanguageTag().lowercase()).let { if (it < 0) 9 else it } }, { it.name }))
            if (voices.isNotEmpty()) t.voice = voices[v.idx % voices.size]
        } catch (_: Exception) {}
        t.setPitch(v.pitch); t.setSpeechRate(v.rate)
    }

    private data class VoiceStyle(val pitch: Float, val rate: Float, val idx: Int, val prefer: List<String>)

    private fun say(text: String, move: AvatarView.Move = AvatarView.Move.IDLE, ms: Long = 2500, showBubble: Boolean = true) {
        addMsg(false, text)
        if (move != AvatarView.Move.IDLE) avatar.play(move, ms)
        if (listenMode == Listen.WAKE) { try { recognizer?.cancel() } catch (_: Exception) {}; listenMode = Listen.NONE }
        main.removeCallbacks(wakeRunnable)
        if (showBubble) showTalk(text, (2500L + text.length * 70L).coerceAtMost(12000L))
        val muted = getSharedPreferences("michi", MODE_PRIVATE).getBoolean("mute", false)
        if (ttsReady && !muted) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "m" + System.nanoTime())
        } else {
            avatar.talking = true; speaking = true
            main.postDelayed({ afterSpeech() }, (800L + text.length * 50L).coerceAtMost(6000L))
        }
    }

    // ---------------------------------------------------------------- escuchar

    private fun startListening() {
        if (!hasMic()) {
            say("Necesito permiso para usar el micrófono. Te abro la app para darlo.")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Esta tablet no tiene reconocimiento de voz. Instala o activa la app de Google para dictar.")
            return
        }
        tts?.stop(); avatar.talking = false; speaking = false
        main.removeCallbacks(wakeRunnable)
        if (listenMode == Listen.WAKE) { try { recognizer?.cancel() } catch (_: Exception) {} }
        listenMode = Listen.COMMAND
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        val sys = Locale.getDefault()
        val lang = if (sys.language == "es") sys.toLanguageTag() else "es-ES"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true; paintMic()
        avatar.play(AvatarView.Move.LISTEN)
        showTalk("Te escucho…", 6000)
        try { recognizer?.startListening(i) } catch (_: Exception) { listenMode = Listen.NONE; endListening() }
    }

    private fun recognizerIntent(): Intent {
        val sys = Locale.getDefault()
        val lang = if (sys.language == "es") sys.toLanguageTag() else "es-ES"
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    private fun scheduleWake(ms: Long) {
        main.removeCallbacks(wakeRunnable)
        if (prefs.getBoolean("wake", false)) main.postDelayed(wakeRunnable, ms)
    }

    private fun stopWake() {
        main.removeCallbacks(wakeRunnable)
        if (listenMode == Listen.WAKE) { try { recognizer?.cancel() } catch (_: Exception) {}; listenMode = Listen.NONE }
    }

    /** Escucha en silencio esperando «Oye BYNS». Se reinicia sola tras cada frase. */
    private fun startWake() {
        if (!prefs.getBoolean("wake", false) || listenMode != Listen.NONE || speaking || !hasMic()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (panel != null && ::input.isInitialized && input.hasFocus()) { scheduleWake(3000); return }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        listenMode = Listen.WAKE
        try { recognizer?.startListening(recognizerIntent()) } catch (_: Exception) { listenMode = Listen.NONE; scheduleWake(3000) }
    }

    private fun onWakeResults(all: List<String>) {
        listenMode = Listen.NONE
        for (raw in all) {
            val m = wakeRegex.find(Brain.norm(raw)) ?: continue
            val cmd = m.groupValues[1].trim()
            vibrate(25)
            if (cmd.isNotEmpty()) {
                // «Oye BYNS, abre YouTube»: usa las últimas palabras originales (con tildes)
                val words = raw.trim().split(Regex("\\s+"))
                send(words.takeLast(cmd.split(" ").size).joinToString(" "))
            } else {
                listenAfterSpeech = true
                say(if (avatar.skin == AvatarView.Skin.SOLDADO) "¿Sí? A la orden." else "¿Sí? Te escucho.", AvatarView.Move.LISTEN, 1500)
            }
            return
        }
        scheduleWake(150)
    }

    private fun stopListening() { try { recognizer?.stopListening() } catch (_: Exception) {}; endListening() }

    private fun endListening() {
        listening = false; paintMic()
        if (avatar.move == AvatarView.Move.LISTEN) avatar.play(AvatarView.Move.IDLE)
    }

    private fun paintMic() {
        if (panel == null) return
        micBtn.background = round(if (listening) Color.parseColor("#E23636") else Color.parseColor("#2B4EB8"), dp(24).toFloat())
        micBtn.text = if (listening) "■" else "🎤"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partial: Bundle?) {
            if (listenMode == Listen.WAKE) return
            val s = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!s.isNullOrBlank()) showTalk("«$s…»", 4000)
        }
        override fun onResults(results: Bundle?) {
            if (listenMode == Listen.WAKE) {
                onWakeResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
                return
            }
            listenMode = Listen.NONE
            endListening()
            val s = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (s.isNullOrBlank()) say("No te entendí bien. ¿Lo repites?", AvatarView.Move.THINK) else send(s)
        }
        override fun onError(error: Int) {
            if (listenMode == Listen.WAKE) {
                listenMode = Listen.NONE
                scheduleWake(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 150L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1500L
                    else -> 5000L
                })
                return
            }
            listenMode = Listen.NONE
            endListening()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché. Mantenme pulsado y vuelve a hablar."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Necesito internet para entender la voz."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Me falta el permiso del micrófono."
                else -> "Uy, el micrófono falló. Prueba otra vez."
            }
            say(msg, AvatarView.Move.THINK)
        }
    }

    // ---------------------------------------------------------------- avisos

    private fun fire(due: List<Aviso>) {
        val text = "¡Atención! Ya es hora: " + due.joinToString(" y ") { it.label } + "."
        try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() } catch (_: Exception) {}
        vibrate(600)
        main.postDelayed({ say(text, AvatarView.Move.PARTY) }, 900)
        avatar.play(AvatarView.Move.PARTY, 5000)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100,
            Notification.Builder(this, CHANNEL_AVISO)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("BYNS")
                .setContentText(due.joinToString(" · ") { it.label })
                .setAutoCancel(true)
                .build())
    }

    private fun vibrate(ms: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}
