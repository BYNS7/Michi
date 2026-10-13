package com.michi.arana

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import com.michi.arana.AvatarView.Camo as C
import com.michi.arana.AvatarView.Eyewear as E
import com.michi.arana.AvatarView.Headgear as H
import com.michi.arana.AvatarView.Move as M
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

/** Respuesta del asistente: lo que dice, el movimiento que hace y, si toca, el traje al que cambia. */
data class Reply(
    val text: String,
    val move: AvatarView.Move = AvatarView.Move.IDLE,
    val moveMs: Long = 2500,
    val skin: AvatarView.Skin? = null,
    /** Cambio de equipo del soldado (casco, camuflaje, arma…). */
    val gear: ((AvatarView.Outfit) -> AvatarView.Outfit)? = null
)

/**
 * Entiende órdenes sencillas en español sin internet. Si no reconoce la frase devuelve null
 * y el servicio se la pregunta a Claude.
 */
class Brain(private val ctx: Context, private val avisos: Avisos, private val skin: () -> AvatarView.Skin) {

    private val es = Locale("es")

    private val chistes = listOf(
        "¿Por qué las arañas son buenas con las computadoras? Porque siempre están en la red.",
        "¿Qué le dijo una araña a otra? ¡Qué bien te queda esa tela!",
        "¿Cuál es el colmo de un héroe araña? Tener miedo a las alturas.",
        "Mi sentido arácnido dice que hoy vas a sonreír. ¡Y nunca falla!",
        "¿Por qué la araña no juega a las escondidas? Porque siempre la pillan en su red."
    )

    fun handle(raw: String): Reply? {
        val text = raw.trim()
        val t = norm(text)
        val now = Date()

        if (Regex("\\b(hola|buenas|buenos dias|que tal|hey)\\b").containsMatchIn(t))
            return Reply(hola(), if (skin() == AvatarView.Skin.SOLDADO) AvatarView.Move.SALUTE else AvatarView.Move.WAVE)
        if ("gracias" in t) return Reply(when (skin()) {
            AvatarView.Skin.SOLDADO -> "¡A la orden! Para eso estoy."
            AvatarView.Skin.ARANA_ROJA -> "¡De nada! Un gran poder conlleva una gran amabilidad."
            AvatarView.Skin.ARANA_NEGRA -> "Tranqui, para eso estamos los vecinos arácnidos."
        }, AvatarView.Move.WAVE)
        if (Regex("quien eres|como te llamas|tu nombre").containsMatchIn(t))
            return Reply("Soy BYNS, tu asistente. Ahora llevo el traje de ${skin().title.removePrefix("BYNS ").lowercase()}. Di «Oye BYNS» y te ayudo en cualquier app.", AvatarView.Move.WAVE)

        if ("chiste" in t) return Reply(chistes.random(), AvatarView.Move.PARTY)

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
            return Reply("¡Hecho! $label: $cuando.", AvatarView.Move.WAVE)
        }

        traje(t)?.let { return it }
        movimiento(t)?.let { return it }

        // Abrir apps: «abre YouTube», «abrir la cámara»
        Regex("^(?:por favor\\s+)?(?:abre|abrir|abreme|lanza|inicia|pon)\\s+(?:la |el |los |las |app (?:de )?)?(.+)$").find(t)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', '!', '?')
            return openApp(name)
        }

        return null
    }

    /** Sin Claude configurado: búsquedas al navegador o ayuda. */
    fun offline(text: String): Reply {
        val t = norm(text)
        Regex("^(?:busca|buscar|buscame|googlea|que es|quien es|quien fue)\\s+(.+)$").find(t)?.let { m ->
            val q = if (t.startsWith("busca") || t.startsWith("googlea")) m.groupValues[1] else text
            return search(q)
        }
        return Reply(
            "Para responder cualquier pregunta necesito la clave de Claude: ábreme y pégala en Ajustes. Mientras tanto puedo decirte la hora, poner avisos, abrir apps o cambiar de traje.",
            AvatarView.Move.THINK
        )
    }

    fun hola(): String = when (skin()) {
        AvatarView.Skin.SOLDADO -> "¡BYNS reportándose! ¿Cuál es la misión?"
        AvatarView.Skin.ARANA_ROJA -> "¡Hola! Tu amigable vecino BYNS está listo. ¿Qué necesitas?"
        AvatarView.Skin.ARANA_NEGRA -> "¡Qué onda! BYNS en modo traje negro. ¿Qué hacemos?"
    }

    private fun traje(t: String): Reply? {
        val wants = Regex("\\b(cambia|cambiate|transforma|transformate|convierte|conviertete|ponte|pon|traje|modo|vuelve|usa)\\b").containsMatchIn(t)
        if (!wants || Regex("camuflaje|uniforme|boina|gorra|casco|mochila|lentes|gafas").containsMatchIn(t)) return null
        val target = when {
            Regex("\\b(soldado|militar|ejercito|camuflaje|comando)\\b").containsMatchIn(t) -> AvatarView.Skin.SOLDADO
            Regex("\\b(negro|negra|miles|morales|oscuro)\\b").containsMatchIn(t) -> AvatarView.Skin.ARANA_NEGRA
            Regex("\\b(rojo|roja|spider ?man|spiderman|hombre arana|arana|clasico|aracnido)\\b").containsMatchIn(t) -> AvatarView.Skin.ARANA_ROJA
            Regex("\\btransforma(te)?\\b|\\bcambia(te)? de traje\\b|\\botro traje\\b").containsMatchIn(t) ->
                AvatarView.Skin.values()[(skin().ordinal + 1) % AvatarView.Skin.values().size]
            else -> return null
        }
        if (target == skin()) return Reply("Ya llevo ese traje.", AvatarView.Move.WAVE)
        val text = when (target) {
            AvatarView.Skin.SOLDADO -> "¡Modo soldado activado! Listo para la misión."
            AvatarView.Skin.ARANA_ROJA -> "¡Traje rojo y azul puesto! Tu amigable vecino al rescate."
            AvatarView.Skin.ARANA_NEGRA -> "¡Traje negro activado! Ahora con chispa eléctrica."
        }
        return Reply(text, AvatarView.Move.TRANSFORM, 1100, target)
    }

    private fun movimiento(t: String): Reply? {
        val soldier = skin() == AvatarView.Skin.SOLDADO
        armaDe(t)?.let { w ->
            if (Regex("saca|usa|apunta|dispara|cambia|ponte|toma|agarra|arma|dame|muestra").containsMatchIn(t) || t.split(" ").size <= 3) {
                return Reply("¡${w.label.replaceFirstChar { it.uppercase() }} listo! En posición de tiro.", M.AIM, 6000,
                    skin = if (soldier) null else AvatarView.Skin.SOLDADO, gear = { it.copy(weapon = w) })
            }
        }
        return when {
            Regex("rodilla en tierra|arrodillate|de rodillas|rodilla").containsMatchIn(t) ->
                if (soldier) Reply("¡Rodilla en tierra!", M.KNEEL_AIM, 6000) else Reply("Agachado y alerta.", M.CROUCH, 4000)
            Regex("fusil|rifle|apunta|posicion de (disparo|tiro)|dispara|saca (el|tu|la) arma|en guardia").containsMatchIn(t) ->
                if (soldier) Reply("¡Arma lista! En posición de tiro.", M.AIM, 6000)
                else Reply("Mis únicas armas son las telarañas. ¡Fiu!", M.WEB, 2500)
            Regex("cuerpo a tierra|pecho a tierra|al suelo|echate|tumbate|acuestate").containsMatchIn(t) -> Reply("¡Cuerpo a tierra!", M.PRONE, 6000)
            Regex("lagartijas|flexiones|planchas|ejercicio").containsMatchIn(t) -> Reply("¡Uno, dos, tres! Lagartijas.", M.PUSHUPS, 6000)
            Regex("\\bfirmes\\b|atencion").containsMatchIn(t) -> Reply("¡Firmes!", M.ATTENTION, 3000)
            Regex("saludo militar|\\bsaluda").containsMatchIn(t) ->
                if (soldier) Reply("¡A la orden, mi comandante!", M.SALUTE, 3000) else Reply("¡Hola, hola!", M.WAVE)
            Regex("binocular|prismatico|vigila|explora|reconocimiento").containsMatchIn(t) -> Reply("Vigilando la zona…", M.BINOCULARS, 6000)
            Regex("\\bradio\\b|reporta|comunicate").containsMatchIn(t) -> Reply("Base, aquí BYNS. Todo en orden, cambio.", M.RADIO, 5000)
            Regex("musculo|fuerza|presume|fuerte").containsMatchIn(t) -> Reply("¡Mira estos músculos de plástico!", M.FLEX, 4000)
            Regex("mira alrededor|mira a los lados|otea|busca alrededor").containsMatchIn(t) -> Reply("Revisando el perímetro.", M.LOOK, 5000)
            Regex("agachate|cuclillas|agachado|escondete").containsMatchIn(t) -> Reply("Agachado y en silencio.", M.CROUCH, 5000)
            Regex("sientate|sentado|descansa").containsMatchIn(t) -> Reply("Un descansito.", M.SIT, 6000)
            Regex("\\bbaila|bailar|bailecito").containsMatchIn(t) -> Reply("¡Mira estos pasos!", M.DANCE, 5000)
            Regex("voltereta|mortal|pirueta|flip").containsMatchIn(t) -> Reply("¡Hop!", M.FLIP, 900)
            Regex("\\bsalta|saltar|brinca").containsMatchIn(t) -> Reply("¡Boing, boing!", M.PARTY, 2500)
            Regex("\\bcorre|correr|trota").containsMatchIn(t) -> Reply("¡A toda velocidad!", M.RUN, 4000)
            Regex("\\bmarcha|marchar").containsMatchIn(t) -> Reply("¡Un, dos, un, dos!", M.MARCH, 5000)
            Regex("\\bcamina|\\bpasea").containsMatchIn(t) -> Reply("Dando un paseo.", M.WALK, 4000)
            Regex("de cabeza|boca abajo|cabeza abajo|cuelgate").containsMatchIn(t) ->
                Reply(if (soldier) "¡Colgado de la cuerda, cabeza abajo!" else "¡El mundo se ve mejor al revés!", M.HANG, 7000)
            Regex("columpia|balancea|cruza la pantalla|vuela").containsMatchIn(t) ->
                Reply(if (soldier) "¡Allá voy con la cuerda!" else "¡Wiii! Allá voy.", M.SWING_TRAVEL, 2600)
            Regex("telarana|lanza (una )?red").containsMatchIn(t) ->
                if (soldier) Reply("Sin traje arácnido no tengo telarañas.", M.THINK)
                else Reply("¡Fiu! Telaraña lanzada.", M.WEB, 2500)
            else -> equipo(t)
        }
    }

    private fun armaDe(t: String): AvatarView.Weapon? = when {
        Regex("\\bak\\b|ak ?47|\\baka\\b|kalashnikov|cuerno de chivo").containsMatchIn(t) -> AvatarView.Weapon.AK
        Regex("francotirador|sniper|rifle de precision").containsMatchIn(t) -> AvatarView.Weapon.FRANCOTIRADOR
        Regex("pistola").containsMatchIn(t) -> AvatarView.Weapon.PISTOLA
        Regex("\\bm ?4\\b|carabina").containsMatchIn(t) -> AvatarView.Weapon.FUSIL
        else -> null
    }

    /** Casco, boina, gorra, camuflaje, lentes y mochila del soldado. */
    private fun equipo(t: String): Reply? {
        val quita = Regex("quita|saca|sin\\b").containsMatchIn(t)
        val change: Pair<String, (AvatarView.Outfit) -> AvatarView.Outfit> = when {
            Regex("vision nocturna|visor nocturno|nocturna").containsMatchIn(t) -> "Visión nocturna activada." to { o -> o.copy(head = H.CASCO_NVG) }
            Regex("boina").containsMatchIn(t) -> (if (quita) "Boina guardada." else "¡Boina puesta!") to { o -> o.copy(head = if (quita) H.NINGUNO else H.BOINA) }
            Regex("gorra").containsMatchIn(t) -> (if (quita) "Gorra fuera." else "Gorra puesta.") to { o -> o.copy(head = if (quita) H.NINGUNO else H.GORRA) }
            Regex("casco").containsMatchIn(t) -> (if (quita) "Casco fuera." else "Casco puesto.") to { o -> o.copy(head = if (quita) H.NINGUNO else H.CASCO) }
            Regex("mochila").containsMatchIn(t) -> (if (quita) "Mochila fuera." else "Mochila lista.") to { o -> o.copy(mochila = !quita) }
            Regex("gafas|lentes de sol|lentes tacticos").containsMatchIn(t) && !quita -> "Gafas tácticas puestas." to { o -> o.copy(eyes = E.GAFAS) }
            Regex("lentes|anteojos|gafas").containsMatchIn(t) -> (if (quita) "Sin lentes." else "Lentes puestos.") to { o -> o.copy(eyes = if (quita) E.NINGUNO else E.LENTES) }
            Regex("camuflaje|uniforme").containsMatchIn(t) -> {
                val c = when {
                    Regex("desierto|arena|arido").containsMatchIn(t) -> C.DESIERTO
                    Regex("bosque|selva|verde|woodland").containsMatchIn(t) -> C.BOSQUE
                    Regex("digital|pixel").containsMatchIn(t) -> C.DIGITAL
                    Regex("negro|nocturno|tactico").containsMatchIn(t) -> C.NEGRO
                    Regex("multicam|normal|original").containsMatchIn(t) -> C.MULTICAM
                    else -> null
                }
                if (c == null) "Tengo camuflaje multicam, desierto, bosque, digital y negro. ¿Cuál quieres?" to { o -> o }
                else "Camuflaje ${c.label} puesto." to { o -> o.copy(camo = c) }
            }
            else -> return null
        }
        return Reply(change.first, AvatarView.Move.FLEX, 1500,
            skin = if (skin() == AvatarView.Skin.SOLDADO) null else AvatarView.Skin.SOLDADO, gear = change.second)
    }

    /** Acciones que pide Claude con su herramienta. */
    fun action(tipo: String, valor: String): Reply? {
        val v = norm(valor).replace("_", " ")
        return when (tipo) {
            "movimiento" -> when (valor.uppercase()) {
                "SALUDO_MILITAR" -> Reply("", M.SALUTE, 3000)
                "FIRMES" -> Reply("", M.ATTENTION, 3000)
                "APUNTAR" -> Reply("", M.AIM, 6000)
                "RODILLA_EN_TIERRA" -> Reply("", M.KNEEL_AIM, 6000)
                "MARCHAR" -> Reply("", M.MARCH, 5000)
                "CAMINAR" -> Reply("", M.WALK, 4000)
                "CORRER" -> Reply("", M.RUN, 4000)
                "AGACHARSE" -> Reply("", M.CROUCH, 5000)
                "SENTARSE" -> Reply("", M.SIT, 6000)
                "CUERPO_A_TIERRA" -> Reply("", M.PRONE, 6000)
                "LAGARTIJAS" -> Reply("", M.PUSHUPS, 6000)
                "BINOCULARES" -> Reply("", M.BINOCULARS, 6000)
                "RADIO" -> Reply("", M.RADIO, 5000)
                "MUSCULOS" -> Reply("", M.FLEX, 4000)
                "MIRAR_ALREDEDOR" -> Reply("", M.LOOK, 5000)
                "SALUDAR" -> Reply("", M.WAVE, 2500)
                "SALTAR" -> Reply("", M.PARTY, 2500)
                "BAILAR" -> Reply("", M.DANCE, 5000)
                "VOLTERETA" -> Reply("", M.FLIP, 900)
                "COLUMPIARSE" -> Reply("", M.SWING_TRAVEL, 2600)
                "COLGARSE_DE_CABEZA" -> Reply("", M.HANG, 7000)
                "LANZAR_TELARANA" -> Reply("", M.WEB, 2500)
                "PENSAR" -> Reply("", M.THINK, 2500)
                else -> movimiento(v)
            }
            "traje" -> traje("ponte traje $v")?.copy(text = "")
            "equipo" -> when (valor.uppercase()) {
                "SIN_CASCO" -> equipo("quita casco")
                "CASCO_NVG" -> equipo("vision nocturna")
                "SIN_LENTES" -> equipo("quita lentes")
                "SIN_MOCHILA" -> equipo("quita mochila")
                "GAFAS" -> equipo("gafas tacticas")
                "MULTICAM", "DESIERTO", "BOSQUE", "DIGITAL", "NEGRO" -> equipo("camuflaje $v")
                else -> equipo(v)
            }?.copy(text = "")
            "arma" -> armaDe(if (valor.uppercase() == "FUSIL") "m4" else v)?.let { w ->
                Reply("", M.AIM, 6000, skin = if (skin() == AvatarView.Skin.SOLDADO) null else AvatarView.Skin.SOLDADO, gear = { it.copy(weapon = w) })
            }
            "abrir_app" -> openApp(v)
            "temporizador" -> handle("temporizador de ${valor.filter { it.isDigit() || it == '.' }.ifEmpty { "5" }} minutos")
            else -> null
        }
    }

    private fun openApp(name: String): Reply {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val wanted = norm(name)
        val best = apps.map { it to norm(it.loadLabel(pm).toString()) }
            .filter { (_, label) -> label == wanted || label.contains(wanted) || wanted.contains(label) }
            .minByOrNull { (_, label) -> if (label == wanted) 0 else 1 + kotlin.math.abs(label.length - wanted.length) }
            ?: return Reply("No encontré ninguna app llamada «$name».", AvatarView.Move.THINK)
        val info = best.first.activityInfo
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.packageName, info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            Reply("¡Allá voy! Abriendo ${best.first.loadLabel(pm)}.", AvatarView.Move.WAVE)
        } catch (_: Exception) {
            Reply("No pude abrir ${best.first.loadLabel(pm)}.", AvatarView.Move.THINK)
        }
    }

    private fun search(q: String): Reply {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(web); Reply("Buscando «$q» en la red… ¡la otra red!", AvatarView.Move.WAVE)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Reply("Buscando «$q».", AvatarView.Move.WAVE)
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
