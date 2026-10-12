package com.michi.arana

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

data class Aviso(val id: Long, val label: String, val at: Long)

/** Avisos (temporizadores y recordatorios) guardados en el teléfono para sobrevivir reinicios. */
class Avisos(context: Context) {
    private val prefs = context.getSharedPreferences("michi", Context.MODE_PRIVATE)
    val list = mutableListOf<Aviso>()

    init {
        try {
            val arr = JSONArray(prefs.getString("avisos", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Aviso(o.getLong("id"), o.getString("label"), o.getLong("at"))
            }
        } catch (_: Exception) {}
    }

    fun add(label: String, at: Long) { list += Aviso(System.nanoTime(), label, at); save() }
    fun clear() { list.clear(); save() }

    /** Saca y devuelve los avisos que ya vencieron. */
    fun popDue(now: Long): List<Aviso> {
        val due = list.filter { it.at <= now }
        if (due.isNotEmpty()) { list.removeAll(due); save() }
        return due
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("at", it.at)) }
        prefs.edit().putString("avisos", arr.toString()).apply()
    }
}

/** Respuesta del asistente: lo que dice y el gesto que hace. */
data class Reply(val text: String, val mood: SpideyView.Mood = SpideyView.Mood.IDLE)

/**
 * Entiende frases sencillas en español y responde. Todo funciona sin internet
 * (salvo abrir búsquedas, que se las pasa al navegador).
 */
class Brain(private val ctx: Context, private val avisos: Avisos) {

    private val es = Locale("es")

    private val chistes = listOf(
        "¿Por qué las arañas son buenas con las computadoras? Porque siempre están en la red.",
        "¿Qué le dijo una araña a otra? ¡Qué bien te queda esa tela!",
        "¿Cuál es el colmo de un héroe araña? Tener miedo a las alturas.",
        "Mi sentido arácnido dice que hoy vas a sonreír. ¡Y nunca falla!",
        "¿Por qué la araña no juega a las escondidas? Porque siempre la pillan en su red."
    )

    fun handle(raw: String): Reply {
        val text = raw.trim()
        val t = norm(text)
        val now = Date()

        if (Regex("\\b(hola|buenas|buenos dias|que tal|hey)\\b").containsMatchIn(t))
            return Reply("¡Hola, hola! Tu amigo arácnido está listo. ¿En qué te ayudo?", SpideyView.Mood.WAVE)
        if ("gracias" in t) return Reply("¡De nada! Un gran poder conlleva una gran amabilidad.", SpideyView.Mood.WAVE)
        if (Regex("quien eres|como te llamas|tu nombre").containsMatchIn(t))
            return Reply("Soy Michi Araña, tu asistente flotante. Me cuelgo encima de cualquier app para ayudarte.", SpideyView.Mood.WAVE)
        if ("chiste" in t) return Reply(chistes.random(), SpideyView.Mood.PARTY)

        if (Regex("\\b(que hora|hora es|dime la hora)\\b").containsMatchIn(t))
            return Reply("Son las ${hhmm(now.time)}.")
        if (Regex("\\b(que dia|que fecha|fecha de hoy|dia es hoy)\\b").containsMatchIn(t))
            return Reply("Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es).format(now)}.")
        if (Regex("\\bbateria\\b").containsMatchIn(t)) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val extra = if (pct in 0..20) " ¡Hay que cargarla pronto!" else ""
            return Reply("La batería está al $pct por ciento.$extra")
        }

        // Avisos
        if (Regex("\\b(cancela|borra|quita|elimina)\\b.*\\b(avisos?|temporizador(es)?|recordatorios?|alarmas?)\\b").containsMatchIn(t)) {
            avisos.clear(); return Reply("Listo, borré todos los avisos.")
        }
        if (Regex("\\b(que|cuales|mis)\\b.*\\b(avisos|recordatorios|temporizadores)\\b").containsMatchIn(t)) {
            if (avisos.list.isEmpty()) return Reply("No tienes avisos activos.")
            val s = avisos.list.sortedBy { it.at }.joinToString("; ") { "${it.label}, a las ${hhmm(it.at)}" }
            return Reply("Tienes: $s.")
        }
        val isTimer = Regex("\\b(temporizador|cronometro|cuenta regresiva|timer)\\b").containsMatchIn(t)
        val isRem = Regex("\\b(recuerdame|recordatorio|avisame|recordar|alarma)\\b").containsMatchIn(t)
        if (isTimer || isRem) {
            val dur = parseDur(t)
            val at = if (dur > 0) System.currentTimeMillis() + dur else parseClock(t)
            if (at == 0L) return Reply(
                if (isTimer) "¿De cuánto tiempo? Por ejemplo: temporizador de 10 minutos."
                else "¿Cuándo te aviso? Por ejemplo: recuérdame tomar agua en 20 minutos, o a las 5 de la tarde."
            )
            val label = if (isTimer && !isRem) "Temporizador de ${fmtDur(if (dur > 0) dur else at - System.currentTimeMillis())}"
            else {
                val l = text
                    .replace(Regex("^\\s*(por favor,?\\s*)?(recu[eé]rdame|av[ií]same|recordatorio|recordar|alarma)( de| para| que)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\b(en|dentro de)\\s+(un|una|media|\\d).*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\ba la?s? \\d.*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (l.isEmpty()) "Recordatorio" else l.replaceFirstChar { it.uppercase() }
            }
            avisos.add(label, at)
            val cuando = if (dur > 0) "te aviso en ${fmtDur(dur)}" else "te aviso a las ${hhmm(at)}"
            return Reply("¡Hecho! $label: $cuando.", SpideyView.Mood.WAVE)
        }

        // Abrir apps: «abre YouTube», «abrir la cámara»
        Regex("^(?:por favor\\s+)?(?:abre|abrir|abreme|lanza|inicia|pon)\\s+(?:la |el |los |las |app (?:de )?)?(.+)$").find(t)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', '!', '?')
            return openApp(name)
        }

        // Búsquedas: «busca recetas de pan»
        Regex("^(?:busca|buscar|buscame|googlea|que es|quien es|quien fue)\\s+(.+)$").find(t)?.let { m ->
            val q = if (t.startsWith("busca") || t.startsWith("googlea")) m.groupValues[1] else text
            return search(q)
        }

        return Reply(
            "Eso aún no lo sé. Prueba: «¿qué hora es?», «temporizador de 5 minutos», «recuérdame tomar agua en 30 minutos», «abre YouTube», «busca dinosaurios» o «cuéntame un chiste».",
            SpideyView.Mood.THINK
        )
    }

    private fun openApp(name: String): Reply {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val wanted = norm(name)
        val best = apps.map { it to norm(it.loadLabel(pm).toString()) }
            .filter { (_, label) -> label == wanted || label.contains(wanted) || wanted.contains(label) }
            .minByOrNull { (_, label) -> if (label == wanted) 0 else 1 + kotlin.math.abs(label.length - wanted.length) }
            ?: return Reply("No encontré ninguna app llamada «$name».", SpideyView.Mood.THINK)
        val info = best.first.activityInfo
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.packageName, info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            Reply("¡Allá voy! Abriendo ${best.first.loadLabel(pm)}.", SpideyView.Mood.WAVE)
        } catch (_: Exception) {
            Reply("No pude abrir ${best.first.loadLabel(pm)}.", SpideyView.Mood.THINK)
        }
    }

    private fun search(q: String): Reply {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(web); Reply("Buscando «$q» en la red… ¡la otra red!", SpideyView.Mood.WAVE)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Reply("Buscando «$q».", SpideyView.Mood.WAVE)
            } catch (_: Exception) { Reply("No encontré un navegador para buscar.") }
        }
    }

    companion object {
        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(Locale("es")), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[¿?¡!,;]"), " ").replace(Regex("\\s+"), " ").trim()

        fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale("es")).format(Date(ms))

        fun fmtDur(ms: Long): String {
            val s = (ms / 1000.0).roundToLong()
            if (s < 60) return "$s ${if (s == 1L) "segundo" else "segundos"}"
            val m = (s / 60.0).roundToLong()
            if (m < 60) return "$m ${if (m == 1L) "minuto" else "minutos"}"
            val h = m / 60; val r = m % 60
            return "$h ${if (h == 1L) "hora" else "horas"}" + if (r > 0) " y $r minutos" else ""
        }

        fun parseDur(input: String): Long {
            val t = input.replace(Regex("\\bmedia hora\\b"), "30 minutos").replace(Regex("\\b(un|una|uno)\\b"), "1")
            val re = Regex("(\\d+(?:[.,]\\d+)?)\\s*(segundos?|seg|minutos?|min|horas?|hrs?|h|m|s)\\b")
            var total = 0.0
            for (m in re.findAll(t)) {
                val n = m.groupValues[1].replace(",", ".").toDouble()
                total += n * when (m.groupValues[2][0]) { 'h' -> 3_600_000.0; 'm' -> 60_000.0; else -> 1000.0 }
            }
            return total.toLong()
        }

        fun parseClock(t: String): Long {
            val m = Regex("\\ba la?s? (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|de la manana|de la tarde|de la noche)?").find(t)
                ?: return 0
            var h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
            val suf = m.groupValues[3]
            if (h > 23 || mi > 59) return 0
            if (Regex("pm|p\\.m|tarde|noche").containsMatchIn(suf) && h < 12) h += 12
            if (Regex("am|a\\.m|manana").containsMatchIn(suf) && h == 12) h = 0
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val now = System.currentTimeMillis()
            if (c.timeInMillis <= now && suf.isEmpty() && h < 12) c.add(Calendar.HOUR_OF_DAY, 12)
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
            return max(c.timeInMillis, now + 1000)
        }
    }
}
