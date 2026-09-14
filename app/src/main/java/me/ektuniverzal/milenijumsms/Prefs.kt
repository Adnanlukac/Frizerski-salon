package me.ektuniverzal.milenijumsms

import android.content.Context

/**
 * Prefs.kt — čuva adresu sajta i pristupni token na telefonu (SharedPreferences).
 * Token se dobija JEDNOM pri uparivanju i pamti se dok se aplikacija ne "Odspoji".
 */
object Prefs {
    private const val FILE = "milenijum_sms_prefs"
    private const val KEY_SITE_URL = "site_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BROJ_POSLATIH = "broj_poslatih_lokalno"
    private const val KEY_BROJ_NEUSPJELIH = "broj_neuspjelih_lokalno"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getSiteUrl(context: Context): String? = prefs(context).getString(KEY_SITE_URL, null)

    /** Uvijek se pobrini da adresa sajta završava sa "/" — API pozivi to očekuju. */
    fun setSiteUrl(context: Context, url: String) {
        val normalizovano = if (url.endsWith("/")) url else "$url/"
        prefs(context).edit().putString(KEY_SITE_URL, normalizovano).apply()
    }

    fun getDeviceToken(context: Context): String? = prefs(context).getString(KEY_DEVICE_TOKEN, null)

    fun setDeviceToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun getDeviceName(context: Context): String = prefs(context).getString(KEY_DEVICE_NAME, "") ?: ""

    fun setDeviceName(context: Context, naziv: String) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, naziv).apply()
    }

    fun isConnected(context: Context): Boolean =
        !getSiteUrl(context).isNullOrBlank() && !getDeviceToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun incrementPoslato(context: Context) {
        val trenutno = prefs(context).getInt(KEY_BROJ_POSLATIH, 0)
        prefs(context).edit().putInt(KEY_BROJ_POSLATIH, trenutno + 1).apply()
    }

    fun incrementNeuspjelo(context: Context) {
        val trenutno = prefs(context).getInt(KEY_BROJ_NEUSPJELIH, 0)
        prefs(context).edit().putInt(KEY_BROJ_NEUSPJELIH, trenutno + 1).apply()
    }

    fun getBrojPoslatih(context: Context): Int = prefs(context).getInt(KEY_BROJ_POSLATIH, 0)
    fun getBrojNeuspjelih(context: Context): Int = prefs(context).getInt(KEY_BROJ_NEUSPJELIH, 0)
}
