package jxpix.proger.mps.cloud

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Plays an MP3 straight from its address (tracks and the previews of projects). Main thread only. */
internal class StreamPlayer {
    private var player: MediaPlayer? = null

    fun play(url: String, onReady: () -> Unit, onEnd: () -> Unit, onError: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener {
                if (player === p) {
                    p.start()
                    onReady()
                }
            }
            p.setOnCompletionListener {
                if (player === p) {
                    stop()
                    onEnd()
                }
            }
            p.setOnErrorListener { _, _, _ ->
                if (player === p) {
                    stop()
                    onError()
                }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            stop()
            onError()
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        try {
            p.reset()
            p.release()
        } catch (_: Exception) {
        }
    }
}
