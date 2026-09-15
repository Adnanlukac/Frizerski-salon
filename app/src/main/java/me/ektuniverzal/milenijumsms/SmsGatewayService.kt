package me.ektuniverzal.milenijumsms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * SmsGatewayService.kt
 *
 * Foreground servis (ima stalno vidljivu obavijest — Android ga zato
 * ne gasi agresivno kao obične pozadinske zadatke). Svakih ~15 sekundi:
 *   1. pita sajt ima li SMS poruka na čekanju (api/device/pending.php)
 *   2. za svaku, šalje SMS preko SIM kartice telefona
 *   3. javlja sajtu rezultat (api/device/potvrdi.php)
 *   4. javlja "još sam živ" (api/device/heartbeat.php) — za ONLINE status u adminu
 */
class SmsGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var posaoUToku: Job? = null

    companion object {
        const val KANAL_ID = "milenijum_sms_gateway"
        const val NOTIFIKACIJA_ID = 1
        const val INTERVAL_MS = 15_000L
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
            } catch (e: Exception) {
                azurirajNotifikaciju("Greška: ${e.message}")
            }
            delay(INTERVAL_MS)
        }
    }

    private suspend fun provjeriIObradiPoruke() {
        val siteUrl = Prefs.getSiteUrl(this) ?: return
        val token = Prefs.getDeviceToken(this) ?: return

        val heartbeatRes = ApiClient.heartbeat(siteUrl, token, verzijaApp())
        val naCekanju = heartbeatRes.data ?: 0

        val rezultat = ApiClient.preuzmiPending(siteUrl, token)
        if (!rezultat.success) {
            azurirajNotifikaciju("Greška veze — pokušavam ponovo za 15s")
            return
        }

        val zadaci = rezultat.data ?: emptyList()
        if (zadaci.isEmpty()) {
            azurirajNotifikaciju("Povezano · na čekanju: $naCekanju")
            return
        }

        azurirajNotifikaciju("Šaljem ${zadaci.size} poruka...")

        for (zadatak in zadaci) {
            val uspjeh = posaljiSmsIsacekajRezultat(zadatak.telefon, zadatak.poruka)
            ApiClient.potvrdi(siteUrl, token, zadatak.id, uspjeh, if (uspjeh) null else "Slanje nije uspjelo na telefonu")
            if (uspjeh) Prefs.incrementPoslato(this) else Prefs.incrementNeuspjelo(this)
        }

        azurirajNotifikaciju("Povezano · poslato ukupno: ${Prefs.getBrojPoslatih(this)}")
    }

    /**
     * Šalje SMS (dijeli na više dijelova ako je duži od granice — poruke sa
     * š/đ/č/ć/ž koriste UTF-16 kodiranje, pa je granica ~70 karaktera po
     * dijelu umjesto 160) i čeka potvrdu operatera da su SVI dijelovi
     * PRIHVAĆENI za slanje.
     *
     * Svaki dio dobija SVOJU jedinstvenu akciju (umjesto oslanjanja na
     * "index" u intent extras, što neki Android uređaji/proizvođači ne
     * prenesu pouzdano kroz sistemski broadcast) — tako brojanje potvrda
     * radi tačno bez obzira na uređaj.
     */
    private suspend fun posaljiSmsIsacekajRezultat(telefon: String, poruka: String): Boolean =
        suspendCancellableCoroutine { nastavak ->
            try {
                val smsManager = getSystemService(SmsManager::class.java)
                val dijelovi = smsManager.divideMessage(poruka)
                val actionId = System.currentTimeMillis().toInt()

                var brojPotvrdjenih = 0
                var sveUspjesno = true

                val filter = IntentFilter()
                for (i in dijelovi.indices) {
                    filter.addAction("$ACTION_SMS_SENT.$actionId.$i")
                }

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val uspjeh = resultCode == android.app.Activity.RESULT_OK
                        if (!uspjeh) sveUspjesno = false
                        brojPotvrdjenih++
                        if (brojPotvrdjenih >= dijelovi.size) {
                            try { unregisterReceiver(this) } catch (e: Exception) { }
                            if (nastavak.isActive) {
                                nastavak.resume(sveUspjesno)
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }

                val sentIntents = ArrayList<PendingIntent>()
                for (i in dijelovi.indices) {
                    val intent = Intent("$ACTION_SMS_SENT.$actionId.$i")
                    val pi = PendingIntent.getBroadcast(
                        this, actionId + i, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    sentIntents.add(pi)
                }

                if (dijelovi.size == 1) {
                    smsManager.sendTextMessage(telefon, null, dijelovi[0], sentIntents[0], null)
                } else {
                    smsManager.sendMultipartTextMessage(telefon, null, dijelovi, sentIntents, null)
                }

                // Sigurnosna kočnica — ako operater nikad ne odgovori, ne čekaj zauvijek
                nastavak.invokeOnCancellation {
                    try { unregisterReceiver(receiver) } catch (e: Exception) { }
                }
                scope.launch {
                    delay(30_000)
                    if (nastavak.isActive) {
                        try { unregisterReceiver(receiver) } catch (e: Exception) { }
                        nastavak.resume(false)
                    }
                }
            } catch (e: Exception) {
                if (nastavak.isActive) nastavak.resume(false)
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
                KANAL_ID, "Milenijum SMS gateway", NotificationManager.IMPORTANCE_LOW
            )
            kanal.description = "Prikazuje status veze sa sajtom i slanja SMS poruka"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(kanal)
        }
    }

    private fun izgradiNotifikaciju(tekst: String): Notification {
        return NotificationCompat.Builder(this, KANAL_ID)
            .setContentTitle("Milenijum SMS gateway")
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
