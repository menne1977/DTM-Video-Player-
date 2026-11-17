@file:OptIn(UnstableApi::class)

package de.dtm.videoplayer

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Bluetooth-Kopfhörer erkennen und als Ausgabegerät wählen. */
object AudioOutputs {

    /** -1 = automatisch (System entscheidet) */
    var preferredId: Int = -1

    private fun btTypes(): Set<Int> {
        val s = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
        if (Build.VERSION.SDK_INT >= 31) {
            s.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            s.add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (Build.VERSION.SDK_INT >= 33) s.add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        return s
    }

    fun isBt(d: AudioDeviceInfo): Boolean = d.isSink && d.type in btTypes()

    fun btDevices(ctx: Context): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isBt(it) }
    }

    private fun typeName(d: AudioDeviceInfo): String = when {
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (Headset)"
        d.type == AudioDeviceInfo.TYPE_HEARING_AID -> "Hörgerät"
        Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
        Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Lautsprecher"
        else -> "Bluetooth"
    }

    fun applyTo(ctx: Context, player: ExoPlayer) {
        val dev = if (preferredId == -1) null else
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == preferredId }
        if (dev == null) preferredId = -1
        player.setPreferredAudioDevice(dev)
    }

    fun show(ctx: Context, player: ExoPlayer?, onChanged: () -> Unit = {}) {
        val devices = btDevices(ctx)
        val names = ArrayList<String>()
        names.add("Automatisch (System-Standard)")
        devices.forEach { names.add("${it.productName} · ${typeName(it)}") }
        var checked = devices.indexOfFirst { it.id == preferredId } + 1

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (devices.isEmpty()) "Keine Bluetooth-Kopfhörer verbunden" else "Kopfhörer (${devices.size} verbunden)")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                checked = which
                preferredId = if (which == 0) -1 else devices[which - 1].id
                if (player != null) applyTo(ctx, player)
                onChanged()
                d.dismiss()
            }
            .setNeutralButton("Bluetooth-Einstellungen") { _, _ ->
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .setPositiveButton("2–3 Kopfhörer?") { _, _ -> showMultiHint(ctx) }
            .show()
    }

    private fun showMultiHint(ctx: Context): AlertDialog =
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mehrere Kopfhörer gleichzeitig")
            .setMessage(
                "Normales Android spielt Ton immer nur an einem Bluetooth-Gerät – das kann keine App ändern.\n\n" +
                    "So geht es trotzdem:\n" +
                    "• Samsung: Einstellungen → Verbindungen → Bluetooth → „Dual Audio\" (2 Geräte).\n" +
                    "• Android 13+/LE-Audio-Kopfhörer: „Audio teilen\" / Auracast in den Bluetooth-Einstellungen (mehrere Zuhörer).\n" +
                    "• Sonst: ein Sender-Adapter oder Kopfhörer mit eigener Mehrfach-Kopplung.\n\n" +
                    "Hier in der App wählst du, an welches verbundene Gerät der Film geht. Die Lautstärke regelst du in der App oder direkt am Kopfhörer."
            )
            .setPositiveButton("OK", null)
            .show()
}
