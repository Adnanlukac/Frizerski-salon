package me.ektuniverzal.milenijumsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ektuniverzal.milenijumsms.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var zadnjiPotpis = ""   // da se lista sajtova ne iscrtava iznova ako se ništa nije promijenilo

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

        b.inputDeviceName.setText(Prefs.getDeviceName(this))

        // dok je ekran vidljiv, status sajtova se osvježava svakih nekoliko sekundi (servis radi u pozadini)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    osvjeziPrikaz()
                    delay(4000)
                }
            }
        }

        b.btnScanQr.setOnClickListener {
            val opcije = ScanOptions()
            opcije.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            opcije.setPrompt("Skeniraj QR kod sa sajta")
            opcije.setBeepEnabled(true)
            qrLauncher.launch(opcije)
        }

        b.btnConnect.setOnClickListener { povezi() }
        b.btnTestConnection.setOnClickListener { testirajVezu() }
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

        if (siteUrl.isBlank() || !siteUrl.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(this, "Unesi ispravnu adresu sajta (mora počinjati sa https://)", Toast.LENGTH_LONG).show()
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
                val sajt = Prefs.addOrUpdateSite(this@MainActivity, siteUrl, rezultat.data)
                Prefs.setDeviceName(this@MainActivity, naziv)
                b.logText.text = "Sajt dodat: ${sajt.naziv}"
                b.inputSiteUrl.setText("")
                b.inputPairingCode.setText("")
                pokreniServis()
                osvjeziPrikaz()
            } else {
                b.logText.text = "Greška: ${rezultat.error}"
            }
        }
    }

    private fun testirajVezu() {
        val sajtovi = Prefs.getSites(this)
        if (sajtovi.isEmpty()) return

        b.logText.text = "Provjera veze..."
        lifecycleScope.launch {
            val redovi = withContext(Dispatchers.IO) {
                sajtovi.map { sajt ->
                    val r = ApiClient.heartbeat(sajt.url, sajt.token, verzijaApp())
                    if (r.success) "${sajt.naziv}: veza radi, na čekanju: ${r.data}"
                    else "${sajt.naziv}: veza ne radi (${r.error})"
                }
            }
            b.logText.text = redovi.joinToString("\n")
        }
    }

    private fun ukloniSajt(sajt: Site) {
        AlertDialog.Builder(this)
            .setTitle("Ukloniti sajt?")
            .setMessage("${sajt.naziv}\n\nTelefon više neće slati poruke za ovaj sajt. Za ponovno povezivanje treba novi kod za uparivanje.")
            .setPositiveButton("Ukloni") { _, _ ->
                Prefs.removeSite(this, sajt.id)
                b.logText.text = "Sajt uklonjen: ${sajt.naziv}"
                zadnjiPotpis = ""
                osvjeziPrikaz()
            }
            .setNegativeButton("Odustani", null)
            .show()
    }

    /** Iscrtava jedan red liste: naziv, status, prekidač uključeno/isključeno i dugme Ukloni. */
    private fun napraviRedSajta(sajt: Site): View {
        val gustina = resources.displayMetrics.density
        val dp = { v: Int -> (v * gustina).toInt() }
        val kartica = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        kartica.addView(TextView(this).apply {
            text = sajt.naziv
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
        })
        kartica.addView(TextView(this).apply {
            text = (if (sajt.status.isBlank()) "Čeka prvu provjeru" else sajt.status) +
                "\nPoslato: ${sajt.poslato} · Neuspjelo: ${sajt.neuspjelo}"
            textSize = 12.5f
            setTextColor(getColor(R.color.ink_soft))
        })
        val dno = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val prekidac = SwitchCompat(this).apply {
            text = "Uključen"
            isChecked = sajt.enabled
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, ukljuceno ->
                Prefs.setEnabled(this@MainActivity, sajt.id, ukljuceno)
                zadnjiPotpis = ""
                osvjeziPrikaz()
            }
        }
        val ukloni = Button(this).apply {
            text = "Ukloni"
            setOnClickListener { ukloniSajt(sajt) }
        }
        dno.addView(prekidac)
        dno.addView(ukloni)
        kartica.addView(dno)
        return kartica
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
        val sajtovi = Prefs.getSites(this)
        val aktivnih = sajtovi.count { it.enabled }

        b.connectedActions.visibility = if (sajtovi.isNotEmpty()) View.VISIBLE else View.GONE

        when {
            sajtovi.isEmpty() -> {
                b.statusText.text = "Nije povezano"
                b.statusText.setTextColor(getColor(R.color.danger))
                b.statusDetail.text = "Dodaj prvi sajt: unesi adresu sajta i kod za uparivanje ispod."
            }
            aktivnih == 0 -> {
                b.statusText.text = "Svi sajtovi su isključeni"
                b.statusText.setTextColor(getColor(R.color.danger))
                b.statusDetail.text = "Telefon ne šalje poruke dok ne uključiš bar jedan sajt."
            }
            else -> {
                b.statusText.text = "Povezano · sajtova: $aktivnih od ${sajtovi.size}"
                b.statusText.setTextColor(getColor(R.color.green))
                b.statusDetail.text = "${Prefs.getDeviceName(this).ifBlank { "Telefon" }} · " +
                    "Poslato: ${Prefs.getBrojPoslatih(this)} · Neuspjelo: ${Prefs.getBrojNeuspjelih(this)}"
            }
        }

        val potpis = sajtovi.joinToString("|") { "${it.id}${it.enabled}${it.poslato}${it.neuspjelo}${it.status}" }
        if (potpis != zadnjiPotpis) {
            zadnjiPotpis = potpis
            b.sitesList.removeAllViews()
            sajtovi.forEach { b.sitesList.addView(napraviRedSajta(it)) }
        }

        if (aktivnih > 0) pokreniServis() else stopService(Intent(this, SmsGatewayService::class.java))
    }

    private fun verzijaApp(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
