package de.dtm.videoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Start-Bildschirm: Disc „einlegen" (Datei wählen), Stream-URL, Kopfhörer, Streaming-Apps. */
class MainActivity : AppCompatActivity() {

    private lateinit var disc: IntroView
    private lateinit var stage: FrameLayout
    private var introKind = 0
    private val introNames = arrayOf("DVD im Laufwerk", "Kassette", "USB-Stick", "DVD-Hülle (klappt auf)", "Weltraum-Lauftext")
    private val introTexts = arrayOf(
        "Lege deine Disc ein", "Lege die Kassette ein", "Stecke den Stick an", "Öffne die Hülle", "Wähle unten eine Datei"
    )
    private lateinit var status: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        status.text = "Wird geladen …"
        disc.insert {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(uris))
            )
        }
    }

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        startActivity(Intent(this, FolderActivity::class.java).setData(uri))
    }

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Pal.BG, android.graphics.Color.parseColor("#16102B"))
            )
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(label("DTM Video Player", 28f, Pal.TEXT, true).apply { gravity = Gravity.CENTER })
        status = label("Lege deine Disc ein", 15f, Pal.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(status)

        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        introKind = getSharedPreferences("settings", MODE_PRIVATE).getInt("intro", 0).coerceIn(0, introNames.size - 1)
        setIntro(introKind)

        val btnCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnCol.addView(pill("Disc einlegen (Datei wählen)", true) { openPicker() }, matchWrap())

        btnCol.addView(
            pill("Start-Bild wählen", false) { chooseIntro() },
            matchWrap(10, this)
        )
        btnCol.addView(
            pill("Ordner öffnen (nur Abspielbares)", false) { treePicker.launch(null) },
            matchWrap(10, this)
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            pill("Stream-URL", false) { askUrl() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(10), dp(5), 0) }
        )
        row.addView(
            pill("Kopfhörer", false) { AudioOutputs.show(this, null) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), dp(10), 0, 0) }
        )
        btnCol.addView(row, matchWrap())

        btnCol.addView(
            label("Streaming – öffnet die Original-App", 12f, Pal.MUTED).apply { gravity = Gravity.CENTER },
            matchWrap(16, this)
        )
        val apps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Netflix" to "com.netflix.mediaclient",
            "Prime" to "com.amazon.avod.thirdpartyclient",
            "Disney+" to "com.disney.disneyplus",
            "YouTube" to "com.google.android.youtube"
        ).forEach { (name, pkg) ->
            apps.addView(
                pill(name, false) { openApp(pkg, name) }.apply { textSize = 13f; setPadding(dp(6), dp(11), dp(6), dp(11)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) }
            )
        }
        btnCol.addView(apps, matchWrap())
        root.addView(btnCol, matchWrap())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Pal.BG)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })

        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onResume() {
        super.onResume()
        disc.reset()
        status.text = introTexts[introKind]
    }

    private fun setIntro(kind: Int) {
        introKind = kind
        val v: IntroView = when (kind) {
            1 -> CassetteView(this)
            2 -> UsbView(this)
            3 -> CaseView(this)
            4 -> CrawlView(this)
            else -> DiscView(this)
        }
        stage.removeAllViews()
        disc = v
        stage.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status.text = introTexts[kind]
    }

    private fun chooseIntro() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Start-Bild")
            .setSingleChoiceItems(introNames, introKind) { d, which ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("intro", which).apply()
                setIntro(which)
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openPicker() {
        picker.launch(arrayOf("*/*"))
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://… (mp4, m3u8, mpd)"
            setSingleLine()
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stream-Adresse")
            .setView(input)
            .setPositiveButton("Abspielen") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) {
                    startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(url)))
                } else {
                    Toast.makeText(this, "Bitte eine http(s)-Adresse eingeben", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openApp(pkg: String, name: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            startActivity(launch)
            return
        }
        Toast.makeText(this, "$name ist nicht installiert", Toast.LENGTH_SHORT).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }
}
