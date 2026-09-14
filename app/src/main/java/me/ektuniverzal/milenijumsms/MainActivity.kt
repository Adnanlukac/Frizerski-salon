package me.ektuniverzal.milenijumsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ektuniverzal.milenijumsms.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val qrLauncher = registerForActivityResult(ScanContract()) { rezultat ->
        if (rezultat.contents != null) {
            b.inputPairingCode.setText(rezultat.contents)
        }
    }

    private val dozvoleLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* rezultat se ne mora posebno obraditi ovdje */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        trazidozvole()
        osvjeziPrikaz()

        b.inputSiteUrl.setText(Prefs.getSiteUrl(this) ?: "")
        b.inputDeviceName.setText(Prefs.getDeviceName(this))

        b.btnScanQr.setOnClickListener {
            val opcije = ScanOptions()
            opcije.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            opcije.setPrompt("Skeniraj QR kod sa sajta")
            opcije.setBeepEnabled(true)
            qrLauncher.launch(opcije)
        }

        b.btnConnect.setOnClickListener { povezi() }
        b.btnTestConnection.setOnClickListener { testirajVezu() }
        b.btnDisconnect.setOnClickListener { odspoji() }
    }

    override fun onResume() {
        super.onResume()
        osvjeziPrikaz()
    }

    private fun trazidozvole() {
        val potrebne = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            potrebne.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val nedostaju = potrebne.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (nedostaju.isNotEmpty()) {
            dozvoleLauncher.launch(nedostaju.toTypedArray())
        }
    }

    private fun povezi() {
        val siteUrl = b.inputSiteUrl.text.toString().trim()
        val kod = b.inputPairingCode.text.toString().trim()
        val naziv = b.inputDeviceName.text.toString().trim()

        if (siteUrl.isBlank() || !siteUrl.startsWith("http")) {
            Toast.makeText(this, "Unesi ispravnu adresu sajta (počinje sa https://)", Toast.LENGTH_LONG).show()
            return
        }
        if (kod.isBlank()) {
            Toast.makeText(this, "Unesi kod za uparivanje", Toast.LENGTH_LONG).show()
            return
        }

        b.btnConnect.isEnabled = false
        b.logText.text = "Povezivanje..."

        lifecycleScope.launch {
            val rezultat = withContext(Dispatchers.IO) {
                ApiClient.upari(siteUrl, kod, naziv, verzijaApp())
            }
            b.btnConnect.isEnabled = true

            if (rezultat.success && rezultat.data != null) {
                Prefs.setSiteUrl(this@MainActivity, siteUrl)
                Prefs.setDeviceToken(this@MainActivity, rezultat.data)
                Prefs.setDeviceName(this@MainActivity, naziv)
                b.logText.text = "Uspješno povezano."
                pokreniServis()
                osvjeziPrikaz()
            } else {
                b.logText.text = "Greška: ${rezultat.error}"
            }
        }
    }

    private fun testirajVezu() {
        val siteUrl = Prefs.getSiteUrl(this) ?: return
        val token = Prefs.getDeviceToken(this) ?: return

        b.logText.text = "Provjera veze..."
        lifecycleScope.launch {
            val rezultat = withContext(Dispatchers.IO) {
                ApiClient.heartbeat(siteUrl, token, verzijaApp())
            }
            b.logText.text = if (rezultat.success) {
                "Veza radi. Poruka na čekanju (svi uređaji): ${rezultat.data}"
            } else {
                "Veza ne radi: ${rezultat.error}"
            }
        }
    }

    private fun odspoji() {
        stopService(Intent(this, SmsGatewayService::class.java))
        Prefs.clear(this)
        b.logText.text = "Telefon je odspojen."
        osvjeziPrikaz()
    }

    private fun pokreniServis() {
        val intent = Intent(this, SmsGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun osvjeziPrikaz() {
        val povezano = Prefs.isConnected(this)
        b.connectForm.visibility = if (povezano) android.view.View.GONE else android.view.View.VISIBLE
        b.connectedActions.visibility = if (povezano) android.view.View.VISIBLE else android.view.View.GONE

        if (povezano) {
            b.statusText.text = "Povezano"
            b.statusText.setTextColor(getColor(R.color.green))
            b.statusDetail.text = "${Prefs.getDeviceName(this).ifBlank { "Telefon" }} · " +
                "Poslato: ${Prefs.getBrojPoslatih(this)} · Neuspjelo: ${Prefs.getBrojNeuspjelih(this)}"
            pokreniServis()
        } else {
            b.statusText.text = "Nije povezano"
            b.statusText.setTextColor(getColor(R.color.danger))
            b.statusDetail.text = "Unesi adresu sajta i kod za uparivanje ispod."
        }
    }

    private fun verzijaApp(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
