@file:OptIn(UnstableApi::class)

package de.dtm.videoplayer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Links/Rechts-Balance direkt im Audiosignal (nur Stereo, 16 Bit oder Float).
 * balance: -1 = nur links, 0 = Mitte, +1 = nur rechts.
 */
class BalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var balance: Float = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ok = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        // NOT_SET = Prozessor bleibt inaktiv (z. B. bei Mono / 5.1) und stört nichts
        return if (ok) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val b = balance
        val leftGain = if (b > 0f) 1f - b else 1f
        val rightGain = if (b < 0f) 1f + b else 1f

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 8) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                out.putFloat(l * leftGain)
                out.putFloat(r * rightGain)
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                val l = inputBuffer.short.toInt()
                val r = inputBuffer.short.toInt()
                out.putShort((l * leftGain).toInt().toShort())
                out.putShort((r * rightGain).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/** Baut den Balance-Prozessor in die Audio-Pipeline von ExoPlayer ein. */
class CinemaRenderersFactory(
    context: Context,
    private val balance: BalanceAudioProcessor
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(balance))
            .build()
    }
}
