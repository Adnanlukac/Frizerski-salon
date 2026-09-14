package me.ektuniverzal.milenijumsms

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ApiClient.kt — svi pozivi ka api/device/*.php rutama na sajtu.
 * Sve funkcije su BLOKIRAJUĆE (rade sinhrono) — pozivati ih iz pozadinske
 * niti/korutine, nikad direktno sa glavne niti.
 */

data class SmsTask(val id: Int, val telefon: String, val poruka: String, val tip: String)
data class ApiResult<T>(val success: Boolean, val data: T? = null, val error: String? = null)

object ApiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun punaAdresa(siteUrl: String, putanja: String): String {
        val osnova = if (siteUrl.endsWith("/")) siteUrl else "$siteUrl/"
        return osnova + putanja
    }

    /** Uparivanje pomoću koda sa sajta — vraća device_token ako uspije. */
    fun upari(siteUrl: String, kod: String, naziv: String, verzijaApp: String): ApiResult<String> {
        return try {
            val telo = JSONObject().apply {
                put("kod", kod)
                put("naziv", naziv)
                put("verzija_app", verzijaApp)
            }
            val req = Request.Builder()
                .url(punaAdresa(siteUrl, "api/device/upari.php"))
                .post(telo.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { odgovor ->
                val tijelo = odgovor.body?.string() ?: ""
                val json = JSONObject(tijelo)
                if (json.optBoolean("success", false)) {
                    ApiResult(true, data = json.optString("device_token"))
                } else {
                    ApiResult(false, error = json.optString("error", "Nepoznata greška."))
                }
            }
        } catch (e: Exception) {
            ApiResult(false, error = "Greška veze: ${e.message}")
        }
    }

    /** Preuzimanje SMS zadataka na čekanju. */
    fun preuzmiPending(siteUrl: String, token: String): ApiResult<List<SmsTask>> {
        return try {
            val req = Request.Builder()
                .url(punaAdresa(siteUrl, "api/device/pending.php"))
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(req).execute().use { odgovor ->
                val tijelo = odgovor.body?.string() ?: ""
                if (!odgovor.isSuccessful) {
                    return ApiResult(false, error = "HTTP ${odgovor.code}: $tijelo")
                }
                val json = JSONObject(tijelo)
                if (!json.optBoolean("success", false)) {
                    return ApiResult(false, error = json.optString("error", "Nepoznata greška."))
                }
                val niz: JSONArray = json.optJSONArray("poruke") ?: JSONArray()
                val lista = mutableListOf<SmsTask>()
                for (i in 0 until niz.length()) {
                    val stavka = niz.getJSONObject(i)
                    lista.add(
                        SmsTask(
                            id = stavka.optInt("id"),
                            telefon = stavka.optString("telefon"),
                            poruka = stavka.optString("poruka"),
                            tip = stavka.optString("tip")
                        )
                    )
                }
                ApiResult(true, data = lista)
            }
        } catch (e: Exception) {
            ApiResult(false, error = "Greška veze: ${e.message}")
        }
    }

    /** Javljanje ishoda slanja (SENT ili FAILED). */
    fun potvrdi(siteUrl: String, token: String, id: Int, uspjesno: Boolean, greska: String? = null): Boolean {
        return try {
            val telo = JSONObject().apply {
                put("id", id)
                put("status", if (uspjesno) "SENT" else "FAILED")
                if (greska != null) put("greska", greska)
            }
            val req = Request.Builder()
                .url(punaAdresa(siteUrl, "api/device/potvrdi.php"))
                .header("Authorization", "Bearer $token")
                .post(telo.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** "Još sam živ" signal — koristi se za ONLINE status u admin panelu. */
    fun heartbeat(siteUrl: String, token: String, verzijaApp: String): ApiResult<Int> {
        return try {
            val telo = JSONObject().apply { put("verzija_app", verzijaApp) }
            val req = Request.Builder()
                .url(punaAdresa(siteUrl, "api/device/heartbeat.php"))
                .header("Authorization", "Bearer $token")
                .post(telo.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { odgovor ->
                val tijelo = odgovor.body?.string() ?: ""
                if (!odgovor.isSuccessful) {
                    return ApiResult(false, error = "HTTP ${odgovor.code}")
                }
                val json = JSONObject(tijelo)
                if (json.optBoolean("success", false)) {
                    ApiResult(true, data = json.optInt("poruka_na_cekanju", 0))
                } else {
                    ApiResult(false, error = json.optString("error"))
                }
            }
        } catch (e: Exception) {
            ApiResult(false, error = "Greška veze: ${e.message}")
        }
    }
}
