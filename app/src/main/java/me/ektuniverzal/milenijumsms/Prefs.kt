package me.ektuniverzal.milenijumsms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Jedan upareni sajt: adresa, token koji je sajt izdao ovom telefonu i lokalna brojila/status. */
data class Site(
    val id: String,
    val naziv: String,
    val url: String,
    val token: String,
    val enabled: Boolean = true,
    val poslato: Int = 0,
    val neuspjelo: Int = 0,
    val status: String = ""
)

/**
 * Prefs.kt — čuva LISTU sajtova (svaki sa vlastitim tokenom) na telefonu (SharedPreferences).
 * Token svakog sajta se dobija JEDNOM pri uparivanju i važi samo za taj sajt.
 * Stari (jedan sajt) zapis se pri prvom pokretanju nove verzije automatski prebacuje u listu,
 * pa već upareni telefon nastavlja da radi bez ponovnog uparivanja.
 */
object Prefs {
    private const val FILE = "milenijum_sms_prefs"
    private const val KEY_SITES = "sites_json"
    private const val KEY_SITE_URL = "site_url"            // staro (jedan sajt)
    private const val KEY_DEVICE_TOKEN = "device_token"    // staro (jedan sajt)
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BROJ_POSLATIH = "broj_poslatih_lokalno"      // staro
    private const val KEY_BROJ_NEUSPJELIH = "broj_neuspjelih_lokalno"  // staro

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Adresa uvijek završava sa "/" — API pozivi to očekuju. */
    fun normalizujUrl(url: String): String {
        val u = url.trim()
        return if (u.endsWith("/")) u else "$u/"
    }

    private fun uJson(s: Site) = JSONObject().apply {
        put("id", s.id); put("naziv", s.naziv); put("url", s.url); put("token", s.token)
        put("enabled", s.enabled); put("poslato", s.poslato); put("neuspjelo", s.neuspjelo); put("status", s.status)
    }

    private fun izJson(o: JSONObject) = Site(
        id = o.optString("id"), naziv = o.optString("naziv"), url = o.optString("url"),
        token = o.optString("token"), enabled = o.optBoolean("enabled", true),
        poslato = o.optInt("poslato", 0), neuspjelo = o.optInt("neuspjelo", 0), status = o.optString("status", "")
    )

    @Synchronized
    fun getSites(context: Context): List<Site> {
        val p = prefs(context)
        val raw = p.getString(KEY_SITES, null)
        if (raw == null) {
            // migracija sa stare verzije (jedan sajt)
            val stariUrl = p.getString(KEY_SITE_URL, null)
            val stariToken = p.getString(KEY_DEVICE_TOKEN, null)
            if (!stariUrl.isNullOrBlank() && !stariToken.isNullOrBlank()) {
                val sajt = Site(
                    id = UUID.randomUUID().toString(), naziv = nazivIzAdrese(stariUrl),
                    url = normalizujUrl(stariUrl), token = stariToken,
                    poslato = p.getInt(KEY_BROJ_POSLATIH, 0), neuspjelo = p.getInt(KEY_BROJ_NEUSPJELIH, 0)
                )
                sacuvaj(context, listOf(sajt))
                p.edit().remove(KEY_SITE_URL).remove(KEY_DEVICE_TOKEN).remove(KEY_BROJ_POSLATIH).remove(KEY_BROJ_NEUSPJELIH).apply()
                return listOf(sajt)
            }
            return emptyList()
        }
        return try {
            val niz = JSONArray(raw)
            (0 until niz.length()).map { izJson(niz.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sacuvaj(context: Context, lista: List<Site>) {
        val niz = JSONArray()
        lista.forEach { niz.put(uJson(it)) }
        prefs(context).edit().putString(KEY_SITES, niz.toString()).apply()
    }

    fun nazivIzAdrese(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { "Sajt" }

    /** Dodaje sajt; ako sajt sa istom adresom već postoji, samo mu zamjenjuje token (ponovno uparivanje). */
    @Synchronized
    fun addOrUpdateSite(context: Context, url: String, token: String): Site {
        val norm = normalizujUrl(url)
        val lista = getSites(context).toMutableList()
        val idx = lista.indexOfFirst { it.url.equals(norm, ignoreCase = true) }
        val sajt = if (idx >= 0) {
            lista[idx].copy(token = token, enabled = true, status = "Ponovo upareno").also { lista[idx] = it }
        } else {
            Site(id = UUID.randomUUID().toString(), naziv = nazivIzAdrese(norm), url = norm, token = token)
                .also { lista.add(it) }
        }
        sacuvaj(context, lista)
        return sajt
    }

    @Synchronized
    fun removeSite(context: Context, id: String) {
        sacuvaj(context, getSites(context).filter { it.id != id })
    }

    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        sacuvaj(context, getSites(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun setStatus(context: Context, id: String, status: String) {
        sacuvaj(context, getSites(context).map { if (it.id == id) it.copy(status = status) else it })
    }

    @Synchronized
    fun recordResult(context: Context, id: String, uspjesno: Boolean) {
        sacuvaj(context, getSites(context).map {
            if (it.id != id) it
            else if (uspjesno) it.copy(poslato = it.poslato + 1) else it.copy(neuspjelo = it.neuspjelo + 1)
        })
    }

    fun getDeviceName(context: Context): String = prefs(context).getString(KEY_DEVICE_NAME, "") ?: ""

    fun setDeviceName(context: Context, naziv: String) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, naziv).apply()
    }

    /** true ako postoji bar jedan sajt (uključen ili ne). */
    fun hasSites(context: Context): Boolean = getSites(context).isNotEmpty()

    /** true ako postoji bar jedan UKLJUČEN sajt — tada servis treba da radi. */
    fun isConnected(context: Context): Boolean = getSites(context).any { it.enabled }

    fun getBrojPoslatih(context: Context): Int = getSites(context).sumOf { it.poslato }
    fun getBrojNeuspjelih(context: Context): Int = getSites(context).sumOf { it.neuspjelo }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
