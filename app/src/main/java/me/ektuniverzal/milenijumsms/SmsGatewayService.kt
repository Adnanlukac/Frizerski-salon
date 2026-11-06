package me.ektuniverzal.milenijumsms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * SmsGatewayService.kt
 *
 * Foreground servis (ima stalno vidljivu obavijest — Android ga zato
 * ne gasi agresivno kao obične pozadinske zadatke). Svakih ~15 sekundi, za SVAKI uključeni sajt redom:
 *   1. pita sajt ima li SMS poruka na čekanju (api/device/pending.php)
 *   2. za svaku, šalje SMS preko SIM kartice telefona (sa razmakom između poruka)
 *   3. javlja sajtu rezultat (api/device/potvrdi.php)
 *   4. javlja "još sam živ" (api/device/heartbeat.php) — za ONLINE status u adminu
 * Svaki sajt koristi vlastiti token; sajtovi ne znaju jedni za druge.
 */
class SmsGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var posaoUToku: Job? = null

    companion object {
        const val KANAL_ID = "milenijum_sms_gateway"
        const val NOTIFIKACIJA_ID = 1
        const val INTERVAL_MS = 15_000L
        const val RAZMAK_SMS_MS = 1_500L
        const val ACTION_SMS_SENT = "me.ektuniverzal.milenijumsms.SMS_SENT"
    }

    override fun onCreate() {
        super.onCreate()
        napraviNotifikacioniKanal()
        startForeground(NOTIFIKACIJA_ID, izgradiNotifikaciju("Pokretanje..."))
        posaoUToku = scope.launch { petljaZaProvjeru() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Android pokušava ponovo pokrenuti servis ako ga sistem ugasi
    }

    override fun onDestroy() {
        posaoUToku?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun petljaZaProvjeru() {
        while (true) {
            try {
                provjeriIObradiPoruke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                azurirajNotifikaciju("Greška: ${e.message}")
            }
            delay(INTERVAL_MS)
        }
    }

    /** Obilazi sve UKLJUČENE sajtove redom; greška na jednom sajtu ne zaustavlja ostale. */
    private suspend fun provjeriIObradiPoruke() {
        val sajtovi = Prefs.getSites(this).filter { it.enabled }
        if (sajtovi.isEmpty()) {
            azurirajNotifikaciju("Nema uključenih sajtova")
            return
        }
        var naCekanju = 0
        var saGreskom = 0
        for (sajt in sajtovi) {
            try {
                val rez = obradiSajt(sajt)
                if (rez < 0) saGreskom++ else naCekanju += rez
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saGreskom++
                Prefs.setStatus(this, sajt.id, "Greška: ${e.message}")
            }
        }
        val dio = if (saGreskom > 0) " · greška: $saGreskom" else ""
        azurirajNotifikaciju("Sajtova: ${sajtovi.size}$dio · poslato ukupno: ${Prefs.getBrojPoslatih(this)}")
    }

    /** Vraća broj poruka na čekanju na sajtu, ili -1 ako sajt nije dostupan / odbija token. */
    private suspend fun obradiSajt(sajt: Site): Int {
        val heartbeatRes = ApiClient.heartbeat(sajt.url, sajt.token, verzijaApp())
        val naCekanju = heartbeatRes.data ?: 0

        val rezultat = ApiClient.preuzmiPending(sajt.url, sajt.token)
        if (!rezultat.success) {
            val err = rezultat.error ?: ""
            val tekst = if (err.startsWith("HTTP 401") || err.startsWith("HTTP 403"))
                "Sajt odbija token — upari ponovo" else "Greška veze — pokušavam ponovo"
            Prefs.setStatus(this, sajt.id, tekst)
            return -1
        }

        val zadaci = rezultat.data ?: emptyList()
        if (zadaci.isEmpty()) {
            Prefs.setStatus(this, sajt.id, "Povezano · na čekanju: $naCekanju")
            return naCekanju
        }

        for ((i, zadatak) in zadaci.withIndex()) {
            val uspjeh = posaljiSms(zadatak.telefon, zadatak.poruka)
            ApiClient.potvrdi(sajt.url, sajt.token, zadatak.id, uspjeh, if (uspjeh) null else "Slanje nije uspjelo na telefonu")
            Prefs.recordResult(this, sajt.id, uspjeh)
            // razmak između poruka (zajednički za sve sajtove): operater ne voli "rafal" sa jedne SIM kartice
            if (i < zadaci.size - 1) delay(RAZMAK_SMS_MS)
        }
        Prefs.setStatus(this, sajt.id, "Povezano · zadnje slanje: ${zadaci.size} poruka")
        return 0
    }

    /**
     * Šalje SMS (dijeli na više dijelova ako je duži od granice — poruke sa
     * š/đ/č/ć/ž koriste UTF-16 kodiranje, pa je granica ~70 karaktera po
     * dijelu umjesto 160).
     *
     * NAPOMENA: ne čeka se sistemska potvrda operatera ("sent broadcast") —
     * na nekim uređajima/proizvođačima (npr. neki Xiaomi/HyperOS modeli) taj
     * broadcast ne stiže pouzdano čak i kad je SMS stvarno uspješno poslat,
     * što je dovodilo do pogrešnog prikaza "Greška" u adminu iako je poruka
     * stigla klijentu. Status "Poslato" se sad javlja čim telefon preda
     * poruku SIM kartici (standardan pristup za jednostavne SMS gateway-e).
     */
    private fun posaljiSms(telefon: String, poruka: String): Boolean {
        return try {
            val smsManager = getSystemService(SmsManager::class.java)
            val dijelovi = smsManager.divideMessage(poruka)
            if (dijelovi.size == 1) {
                smsManager.sendTextMessage(telefon, null, dijelovi[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(telefon, null, dijelovi, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun verzijaApp(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun napraviNotifikacioniKanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kanal = NotificationChannel(
                KANAL_ID, "SMS gateway", NotificationManager.IMPORTANCE_LOW
            )
            kanal.description = "Prikazuje status veze sa sajtovima i slanja SMS poruka"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(kanal)
        }
    }

    private fun izgradiNotifikaciju(tekst: String): Notification {
        return NotificationCompat.Builder(this, KANAL_ID)
            .setContentTitle("SMS gateway")
            .setContentText(tekst)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun azurirajNotifikaciju(tekst: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFIKACIJA_ID, izgradiNotifikaciju(tekst))
    }
}
