package com.michi.arana

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Pantalla de inicio: pide los permisos y enciende/apaga la arañita flotante. */
class MainActivity : Activity() {

    private lateinit var overlayBtn: Button
    private lateinit var micBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var status: TextView

    private val red = Color.parseColor("#E23636")
    private val blue = Color.parseColor("#2B4EB8")
    private val ink = Color.parseColor("#1A1A2E")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = blue

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }

        val prefs = getSharedPreferences("michi", MODE_PRIVATE)
        val savedSkin = try { AvatarView.Skin.valueOf(prefs.getString("skin", "SOLDADO")!!) } catch (_: Exception) { AvatarView.Skin.SOLDADO }
        preview = AvatarView(this).apply { setSkinNow(savedSkin); play(AvatarView.Move.WAVE, 3000) }
        col.addView(preview, LinearLayout.LayoutParams(dp(240), dp(240)))
        col.addView(TextView(this).apply {
            text = "BYNS"; textSize = 38f; setTextColor(red); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        })
        col.addView(TextView(this).apply {
            text = "Tu asistente que flota encima de cualquier app.\n" +
                "Di «Oye BYNS», tócalo para chatear, mantenlo pulsado para hablarle y arrástralo a la ✕ para esconderlo."
            textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })

        overlayBtn = button("1 · Permitir mostrarse sobre otras apps", blue) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        micBtn = button("2 · Permitir micrófono (para hablarle)", blue) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 1)
        }
        startBtn = button("¡Activar a BYNS!", red) { startMichi() }
        stopBtn = button("Esconder a BYNS", Color.parseColor("#5D6788")) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            main.postDelayed({ refresh() }, 300)
        }
        listOf(overlayBtn, micBtn, startBtn, stopBtn).forEach {
            col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        }

        // Traje
        col.addView(section("Traje"))
        val skins = LinearLayout(this)
        for ((label, sk) in listOf("🪖 Soldado" to AvatarView.Skin.SOLDADO, "🔴 Araña roja" to AvatarView.Skin.ARANA_ROJA, "⚫ Araña negra" to AvatarView.Skin.ARANA_NEGRA)) {
            skins.addView(button(label, ink) {
                prefs.edit().putString("skin", sk.name).apply()
                if (sk != preview.skin) preview.transformTo(sk)
                if (OverlayService.running) startMichi()
            }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        }
        col.addView(skins, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Voz y «Oye BYNS»
        col.addView(section("Voz"))
        col.addView(Switch(this).apply {
            text = "Escuchar siempre «Oye BYNS» (gasta más batería)"; textSize = 16f; setTextColor(ink)
            isChecked = prefs.getBoolean("wake", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("wake", on).apply()
                if (OverlayService.running) startMichi()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        col.addView(Switch(this).apply {
            text = "Hablar en voz alta"; textSize = 16f; setTextColor(ink)
            isChecked = !prefs.getBoolean("mute", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("mute", !on).apply() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        // Claude
        col.addView(section("Preguntas con Claude"))
        col.addView(TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#5D6788"))
            text = "Para que BYNS responda cualquier pregunta (y busque en internet) pega tu clave de la API de Claude. " +
                "Se crea en console.anthropic.com → API Keys. Se guarda solo en esta tablet; cada pregunta tiene un pequeño costo en tu cuenta."
        })
        val keyField = EditText(this).apply {
            hint = "sk-ant-…"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("claude_key", ""))
        }
        col.addView(keyField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(6) })
        col.addView(button("Guardar clave", blue) {
            prefs.edit().putString("claude_key", keyField.text.toString().trim()).apply()
            status.text = if (keyField.text.isBlank()) "Clave borrada." else "Clave guardada. ¡Pregúntale lo que quieras a BYNS!"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })

        status = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#5D6788")); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        col.addView(status)

        col.addView(TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#5D6788"))
            setPadding(0, dp(20), 0, 0)
            text = "Puedes decirle o escribirle:\n" +
                "• Oye BYNS, transfórmate / ponte el traje negro / modo soldado\n" +
                "• Saca el fusil / Saludo militar / Baila / Voltereta / Columpiate / Lanza una telaraña\n" +
                "• ¿Quién ganó el último partido de Bolivia? (lo busca con Claude)\n" +
                "• ¿Qué hora es? / ¿Qué día es hoy?\n" +
                "• Temporizador de 10 minutos\n" +
                "• Recuérdame sacar la ropa en 20 minutos\n" +
                "• Recuérdame llamar a mamá a las 6 de la tarde\n" +
                "• ¿Qué avisos tengo? / Cancela los avisos\n" +
                "• Abre YouTube / Abre la cámara\n" +
                "• Busca dibujos de dinosaurios\n" +
                "• ¿Cuánta batería tengo?\n" +
                "• Cuéntame un chiste"
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F3F6FB"))
            addView(col)
        })
    }

    private lateinit var preview: AvatarView

    private fun section(title: String) = TextView(this).apply {
        text = title; textSize = 18f; setTextColor(ink)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // Si ya estaba flotando, que vuelva a declarar el micrófono
        if (OverlayService.running && Settings.canDrawOverlays(this)) startMichi()
    }

    private fun startMichi() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Primero toca el paso 1 y activa «Mostrar sobre otras apps» para BYNS."
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        status.text = "¡BYNS ya está flotando! Puedes ir a cualquier app."
        main.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayBtn.text = if (overlay) "✓ Puede mostrarse sobre otras apps" else "1 · Permitir mostrarse sobre otras apps"
        overlayBtn.isEnabled = !overlay
        micBtn.text = if (mic) "✓ Micrófono permitido" else "2 · Permitir micrófono (para hablarle)"
        micBtn.isEnabled = !mic
        startBtn.isEnabled = overlay
        stopBtn.isEnabled = OverlayService.running
        listOf(overlayBtn, micBtn, startBtn, stopBtn).forEach { it.alpha = if (it.isEnabled) 1f else 0.5f }
        if (!overlay) status.text = "Falta el permiso para flotar sobre otras apps."
        else if (OverlayService.running) status.text = "BYNS está flotando. Si no lo ves, toca «¡Activar a BYNS!»."
        else status.text = "Todo listo: toca «¡Activar a BYNS!»."
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick() }
    }
}
