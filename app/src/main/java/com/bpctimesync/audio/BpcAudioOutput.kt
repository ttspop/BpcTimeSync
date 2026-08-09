package com.bpctimesync.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*

/**
 * BPC 音频信号输出引擎
 *
 * 载波生成:
 *   - 目标载波: 68.5 kHz (BPC 商丘授时台)
 *   - 实际输出基频: 17.125 = 68.5 / 4 kHz
 *   - 非对称脉冲波 (占空比约 40%), 使包含偶次谐波
 *   - 第 4 次谐波 = 17.125 x 4 = 68.5 kHz
 *
 * BPC 调制方式 (ASK 幅度键控):
 *   - 真实 BPC: 每秒开始先降功率 10dB 持续 100/200/300/400ms, 然后满功率
 *   - 本模拟: 每秒开始先静默 (低电平) 持续对应时长, 然后输出载波 (高电平)
 *   - P0 帧起始: 全秒载波 (无静默段)
 *
 * 多分钟连续发射:
 *   - AudioTrack 只创建一次, 分钟之间无缝衔接
 *   - 避免手表在帧边界丢失同步
 *
 * 建议操作:
 *   1. 手机音量调至最大
 *   2. 连接外接线圈或耳机到 Type-C/3.5mm 接口
 *   3. 将手表置于线圈侧面 2-5cm 距离
 */
class BpcAudioOutput {

    companion object {
        private const val TAG = "BpcAudioOutput"

        const val TARGET_FREQ_HZ = 68_500
        const val HARMONIC_ORDER = 4
        const val BASE_FREQ_HZ = 17_125
        const val SAMPLE_RATE = 192_000

        private const val PULSE_DUTY = 0.40f
        private val PHASE_INCREMENT = BASE_FREQ_HZ.toFloat() / SAMPLE_RATE.toFloat()
        private const val BUFFER_SIZE = SAMPLE_RATE / 4
    }

    enum class State { IDLE, TRANSMITTING, STOPPED, ERROR }

    private var audioTrack: AudioTrack? = null
    private var transmitJob: Job? = null
    private var _state: State = State.IDLE
    val state: State get() = _state

    /**
     * 连续发射多分钟 BPC 信号。
     * AudioTrack 只创建一次, 分钟间零间隙。
     *
     * @param allMinutes 所有分钟的数据, 每个 inner list 长度 60。
     *        allMinutes[0] = 第1分钟, allMinutes[1] = 第2分钟, ...
     *        每个元素: 该秒低电平持续 ms (0/100/200/300/400)
     * @param onProgress 进度回调 (总已播秒数, 总秒数)
     * @param onMinuteDone 每分钟完成回调 (当前是第几分钟, 总共几分钟)
     */
    suspend fun transmitAllMinutes(
        allMinutes: List<List<Int>>,
        onProgress: suspend (elapsedSec: Int, totalSec: Int) -> Unit = { _, _ -> },
        onMinuteDone: suspend (currentMin: Int, totalMin: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val totalMinutes = allMinutes.size
        val totalSeconds = totalMinutes * 60

        try {
            _state = State.TRANSMITTING
            initializeAudioTrack()

            var globalPhase = 0.0f
            var elapsedSec = 0

            for (minuteIdx in 0 until totalMinutes) {
                if (!isActive) break

                val lowDurations = allMinutes[minuteIdx]
                if (lowDurations.size != 60) {
                    Log.e(TAG, "Minute $minuteIdx has ${lowDurations.size} entries, expected 60")
                    break
                }

                for (sec in 0 until 60) {
                    if (!isActive) break

                    val lowMs = lowDurations[sec]
                    val highMs = 1000 - lowMs

                    if (lowMs > 0) {
                        globalPhase = outputSilence(lowMs, globalPhase)
                    }
                    if (highMs > 0) {
                        globalPhase = outputCarrier(highMs, globalPhase)
                    }

                    elapsedSec++
                    onProgress(elapsedSec, totalSeconds)
                }

                if (isActive) {
                    onMinuteDone(minuteIdx + 1, totalMinutes)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transmit error: ${e.message}", e)
            _state = State.ERROR
        } finally {
            releaseAudioTrack()
            if (_state != State.ERROR) {
                _state = State.IDLE
            }
        }
    }

    fun transmitAllMinutesAsync(
        scope: CoroutineScope,
        allMinutes: List<List<Int>>,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
        onMinuteDone: suspend (Int, Int) -> Unit = { _, _ -> },
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                transmitAllMinutes(allMinutes, onProgress, onMinuteDone)
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e(TAG, "Async transmit error", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "未知错误") }
            }
        }
    }

    fun stop() {
        transmitJob?.cancel()
        transmitJob = null
        releaseAudioTrack()
        _state = State.STOPPED
    }

    fun release() {
        stop()
        _state = State.IDLE
    }

    // ---- 内部实现 ----

    private fun initializeAudioTrack() {
        releaseAudioTrack()

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, BUFFER_SIZE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        Log.i(TAG, "AudioTrack initialized: rate=$SAMPLE_RATE")
    }

    private fun outputCarrier(durationMs: Int, startPhase: Float): Float {
        val track = audioTrack ?: return startPhase
        val totalSamples = (SAMPLE_RATE.toLong() * durationMs / 1000).toInt()
        if (totalSamples <= 0) return startPhase

        val amplitude = Short.MAX_VALUE.toShort()
        val buffer = ShortArray(minOf(totalSamples, BUFFER_SIZE))
        var samplesWritten = 0
        var phase = startPhase

        while (samplesWritten < totalSamples) {
            val batch = minOf(buffer.size, totalSamples - samplesWritten)
            for (i in 0 until batch) {
                buffer[i] = if (phase < PULSE_DUTY) amplitude else 0
                phase += PHASE_INCREMENT
                if (phase >= 1.0f) phase -= 1.0f
            }
            track.write(buffer, 0, batch)
            samplesWritten += batch
        }
        return phase
    }

    private fun outputSilence(durationMs: Int, startPhase: Float): Float {
        val track = audioTrack ?: return startPhase
        val totalSamples = (SAMPLE_RATE.toLong() * durationMs / 1000).toInt()
        if (totalSamples <= 0) return startPhase

        val buffer = ShortArray(minOf(totalSamples, BUFFER_SIZE))
        var samplesWritten = 0

        while (samplesWritten < totalSamples) {
            val batch = minOf(buffer.size, totalSamples - samplesWritten)
            track.write(buffer, 0, batch)
            samplesWritten += batch
        }
        return (startPhase + totalSamples * PHASE_INCREMENT) % 1.0f
    }

    private fun releaseAudioTrack() {
        audioTrack?.apply {
            try { stop(); release() } catch (e: Exception) { Log.e(TAG, "Release: ${e.message}") }
        }
        audioTrack = null
    }
}
