package cn.enaium.webrtc.ns.examples.basic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.enaium.webrtc.ns.NoiseSuppressor
import cn.enaium.webrtc.ns.NsLevel
import cn.enaium.webrtc.ns.createNoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Microphone -> noise suppressor -> speaker, on a thread of its own.
 *
 * The capture is 48 kHz mono PCM16, read in 10 ms frames (480 samples), which
 * is exactly the frame the suppressor works on. Its samples are carried in the
 * int16 range, so the `Short` values are converted 1:1 and no scaling is
 * applied in either direction.
 */
class NsLoopbackController {

    var isRunning by mutableStateOf(false)
        private set

    var nsEnabled by mutableStateOf(true)

    /** Monitors the processed capture through the speaker. */
    var playbackEnabled by mutableStateOf(true)

    /** Suppression target in dB: 6, 12, 18 or 21. */
    var levelDb by mutableIntStateOf(12)
        private set

    var status by mutableStateOf("Idle")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var running = false

    private var worker: Thread? = null

    private var record: AudioRecord? = null

    private var track: AudioTrack? = null

    fun setLevel(db: Int) {
        levelDb = db
    }

    fun updateStatus(message: String) {
        status = message
    }

    fun start() {
        if (running) return
        error = null

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING)
        if (minRecord <= 0) {
            error = "AudioRecord cannot capture $SAMPLE_RATE Hz mono PCM16"
            status = "Unavailable"
            return
        }
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT_MONO, ENCODING)
        if (minTrack <= 0) {
            error = "AudioTrack cannot play $SAMPLE_RATE Hz mono PCM16"
            status = "Unavailable"
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN_MONO,
                ENCODING,
                maxOf(minRecord * 2, FRAME_SAMPLES * 2 * 10),
            )
        } catch (e: Exception) {
            error = "Cannot open the microphone: ${e.message}"
            status = "Unavailable"
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error = "The microphone did not initialize"
            status = "Unavailable"
            return
        }

        val player = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT_MONO)
                .build(),
            maxOf(minTrack * 2, FRAME_SAMPLES * 2 * 10),
            AudioTrack.MODE_STREAM,
            0,
        )

        record = recorder
        track = player
        running = true
        isRunning = true
        status = "Running at $SAMPLE_RATE Hz, ${levelDb} dB"
        worker = thread(name = "ns-loopback", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        record?.stop()
        track?.stop()
        worker?.join(1000)
        worker = null
        record?.release()
        record = null
        track?.release()
        track = null
        isRunning = false
        status = "Idle"
    }

    private fun loop() {
        val recorder = record ?: return
        val player = track ?: return
        val frame = FloatArray(FRAME_SAMPLES)
        val pcm = ShortArray(FRAME_SAMPLES)

        var suppressor: NoiseSuppressor? = null
        var suppressorLevel = Int.MIN_VALUE
        var reported = false

        try {
            recorder.startRecording()
            player.play()
            loop@ while (running) {
                // AudioRecord may return a short read; fill a whole 10 ms frame
                // before the suppressor sees it.
                var filled = 0
                while (filled < FRAME_SAMPLES && running) {
                    val read = recorder.read(pcm, filled, FRAME_SAMPLES - filled)
                    if (read < 0) {
                        if (running) error = "Microphone read failed: $read"
                        break@loop
                    }
                    filled += read
                }
                if (!running) break
                if (!reported) {
                    reported = true
                    status = "Running at $SAMPLE_RATE Hz, ${levelDb} dB"
                }

                if (nsEnabled) {
                    // Rebuilding is the only way to change the target level; it
                    // costs one frame and happens on this thread.
                    if (suppressor == null || suppressorLevel != levelDb) {
                        suppressor?.close()
                        suppressor = createNoiseSuppressor(SAMPLE_RATE, 1, levelOf(levelDb))
                        suppressorLevel = levelDb
                    }
                    for (i in 0 until FRAME_SAMPLES) frame[i] = pcm[i].toFloat()
                    suppressor.analyze(frame)
                    suppressor.process(frame)
                    for (i in 0 until FRAME_SAMPLES) {
                        pcm[i] = frame[i].coerceIn(-32768f, 32767f).roundToInt().toShort()
                    }
                }

                if (playbackEnabled) {
                    var written = 0
                    while (written < FRAME_SAMPLES && running) {
                        val count = player.write(pcm, written, FRAME_SAMPLES - written)
                        if (count < 0) {
                            if (running) error = "Playback write failed: $count"
                            break@loop
                        }
                        written += count
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "loopback failed", e)
                error = e.message ?: e.toString()
            }
        } finally {
            suppressor?.close()
            runCatching { recorder.stop() }
            runCatching { player.stop() }
            if (running) {
                running = false
                isRunning = false
            }
        }
    }

    private fun levelOf(db: Int): NsLevel = when (db) {
        6 -> NsLevel.DB6
        18 -> NsLevel.DB18
        21 -> NsLevel.DB21
        else -> NsLevel.DB12
    }

    private companion object {
        const val TAG = "NsLoopback"
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100
        const val CHANNEL_IN_MONO = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT_MONO = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
