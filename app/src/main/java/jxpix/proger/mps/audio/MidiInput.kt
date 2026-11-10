package jxpix.proger.mps.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Listens to every connected MIDI keyboard or controller (USB or Bluetooth) and forwards
 * the notes. Plug a keyboard in and it simply plays the selected channel.
 */
class MidiInput(context: Context, private val onNote: (on: Boolean, pitch: Int, velocity: Float) -> Unit) {

    private val app = context.applicationContext
    private val manager: MidiManager? =
        if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            app.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else null
    private val handler = Handler(Looper.getMainLooper())
    private val devices = HashMap<Int, MidiDevice>()
    private val ports = ArrayList<MidiOutputPort>()
    private var started = false

    /** Names of the connected controllers, for the settings screen. */
    var deviceNames by mutableStateOf<List<String>>(emptyList())
        private set

    private val receiver = object : MidiReceiver() {
        private var status = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { // real-time messages may appear anywhere
                    i++
                    continue
                }
                if (b >= 0x80) {
                    status = b
                    i++
                    if (b >= 0xF0) status = 0 // system messages: ignored
                    continue
                }
                when (status and 0xF0) {
                    0x90, 0x80 -> {
                        if (i + 1 >= end) return
                        val pitch = b
                        val velocity = msg[i + 1].toInt() and 0x7F
                        val on = (status and 0xF0) == 0x90 && velocity > 0
                        onNote(on, pitch, if (on) (velocity / 127f).coerceIn(0.05f, 1f) else 0f)
                        i += 2
                    }
                    0xA0, 0xB0, 0xE0 -> i += 2
                    0xC0, 0xD0 -> i += 1
                    else -> i++
                }
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            devices.remove(device.id)?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            refreshNames()
        }
    }

    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        try {
            @Suppress("DEPRECATION")
            m.registerDeviceCallback(callback, handler)
            @Suppress("DEPRECATION")
            for (info in m.devices) open(info)
        } catch (e: Exception) {
            Log.w(TAG, "MIDI is not available", e)
        }
    }

    fun stop() {
        val m = manager ?: return
        if (!started) return
        started = false
        try {
            m.unregisterDeviceCallback(callback)
        } catch (_: Exception) {
        }
        for (p in ports) {
            try {
                p.close()
            } catch (_: Exception) {
            }
        }
        ports.clear()
        for (d in devices.values) {
            try {
                d.close()
            } catch (_: Exception) {
            }
        }
        devices.clear()
        refreshNames()
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        // an "output port" of a device is where its notes come out
        if (info.outputPortCount == 0 || devices.containsKey(info.id)) return
        try {
            m.openDevice(info, { device ->
                if (device == null || !started) {
                    device?.close()
                    return@openDevice
                }
                devices[info.id] = device
                for (i in 0 until info.outputPortCount) {
                    val port = device.openOutputPort(i) ?: continue
                    port.connect(receiver)
                    ports += port
                }
                refreshNames()
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open MIDI device", e)
        }
    }

    private fun refreshNames() {
        deviceNames = devices.values.map { d ->
            val p = d.info.properties
            p.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                ?: "MIDI"
        }
    }

    private companion object {
        const val TAG = "MPS-Midi"
    }
}
