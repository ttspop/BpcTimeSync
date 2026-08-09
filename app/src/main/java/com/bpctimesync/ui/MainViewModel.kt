package com.bpctimesync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bpctimesync.audio.BpcAudioOutput
import com.bpctimesync.bpc.BpcEncoder
import com.bpctimesync.ntp.NtpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ---- NTP 状态 ----
    private val _ntpServer = MutableStateFlow("time1.aliyun.com")
    val ntpServer: StateFlow<String> = _ntpServer.asStateFlow()

    private val _ntpSynced = MutableStateFlow(false)
    val ntpSynced: StateFlow<Boolean> = _ntpSynced.asStateFlow()

    private val _ntpOffset = MutableStateFlow(0L)
    val ntpOffset: StateFlow<Long> = _ntpOffset.asStateFlow()

    private val _ntpSyncing = MutableStateFlow(false)
    val ntpSyncing: StateFlow<Boolean> = _ntpSyncing.asStateFlow()

    // ---- 时间显示 ----
    private val _currentTime = MutableStateFlow("")
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    private val _currentDate = MutableStateFlow("")
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    // ---- 发射状态 ----
    private val _transmitState = MutableStateFlow(BpcAudioOutput.State.IDLE)
    val transmitState: StateFlow<BpcAudioOutput.State> = _transmitState.asStateFlow()

    private val _transmitProgress = MutableStateFlow(0)
    val transmitProgress: StateFlow<Int> = _transmitProgress.asStateFlow()

    private val _transmitTotalSec = MutableStateFlow(60)
    val transmitTotalSec: StateFlow<Int> = _transmitTotalSec.asStateFlow()

    private val _transmitStatus = MutableStateFlow("")
    val transmitStatus: StateFlow<String> = _transmitStatus.asStateFlow()

    private val _transmitMinuteLabel = MutableStateFlow("")
    val transmitMinuteLabel: StateFlow<String> = _transmitMinuteLabel.asStateFlow()

    private val _lastTransmitTime = MutableStateFlow("无记录")
    val lastTransmitTime: StateFlow<String> = _lastTransmitTime.asStateFlow()

    /** 等待整秒边界时的倒计时 (秒), 0 = 没有在等待 */
    private val _countdownSec = MutableStateFlow(0)
    val countdownSec: StateFlow<Int> = _countdownSec.asStateFlow()

    // ---- 设置 ----
    private val _sampleRate = MutableStateFlow(192000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _repeatCount = MutableStateFlow(3)
    val repeatCount: StateFlow<Int> = _repeatCount.asStateFlow()

    private val _roundMinuteTx = MutableStateFlow(true)
    val roundMinuteTx: StateFlow<Boolean> = _roundMinuteTx.asStateFlow()

    // ---- 引擎 ----
    private val ntpClient = NtpClient()
    private val bpcEncoder = BpcEncoder()
    val audioOutput = BpcAudioOutput()

    val ntpServers = listOf("time1.aliyun.com", "ntp.tencent.com", "pool.ntp.org")

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA)

    init {
        viewModelScope.launch {
            while (true) {
                val cal = Calendar.getInstance()
                val adjustedTime = cal.timeInMillis - _ntpOffset.value
                val adjustedCal = Calendar.getInstance().apply { timeInMillis = adjustedTime }
                _currentTime.value = timeFormatter.format(adjustedCal.time)
                _currentDate.value = dateFormatter.format(adjustedCal.time)
                delay(200)
            }
        }
    }

    fun syncNtp() {
        if (_ntpSyncing.value) return
        viewModelScope.launch {
            _ntpSyncing.value = true
            val result = ntpClient.sync(_ntpServer.value)
            result.onSuccess { ntpResult ->
                _ntpOffset.value = ntpResult.localOffset
                _ntpSynced.value = true
            }.onFailure {
                val poolResult = ntpClient.syncFromPool(ntpServers)
                poolResult.onSuccess { ntpResult ->
                    _ntpOffset.value = ntpResult.localOffset
                    _ntpSynced.value = true
                }.onFailure {
                    _ntpSynced.value = false
                }
            }
            _ntpSyncing.value = false
        }
    }

    fun setNtpServer(server: String) { _ntpServer.value = server }

    // ---- 发射操作 ----

    fun startTransmit() {
        if (audioOutput.state == BpcAudioOutput.State.TRANSMITTING) return
        if (_countdownSec.value > 0) return  // 已经在倒计时中

        val count = _repeatCount.value.coerceIn(1, 10)

        viewModelScope.launch {
            // —— 第一步: 等待下一整分 :00 边界 ——
            // 只能在 :00 秒启动: P0 帧标记必须是真实时钟的秒 0
            // 如果在秒 20 启动, P0 落在秒 20, 手表会把秒 20 当作秒 0 → 偏差 20 秒
            if (_roundMinuteTx.value) {
                _transmitState.value = BpcAudioOutput.State.TRANSMITTING
                _transmitTotalSec.value = count * 60

                val offset = _ntpOffset.value
                fun adjustedNow() = System.currentTimeMillis() - offset
                val now = adjustedNow()
                val msIntoSec = now % 1000

                // 距离下一个 :00 的毫秒数
                var waitMs = 60_000L - (now % 60_000L)

                // 太近了 (< 100ms) 则多等 60 秒, 保安全
                if (waitMs < 100) waitMs += 60_000L

                val targetTime = now + waitMs

                _transmitStatus.value = "等待整分 :00 对齐..."
                while (true) {
                    val left = targetTime - adjustedNow()
                    if (left <= 50) break
                    _countdownSec.value = ((left + 500) / 1000).toInt()
                    _transmitStatus.value = "将在 ${_countdownSec.value}s 后于整分 :00 发射"
                    delay(500)
                }
                while (adjustedNow() < targetTime) {
                    // busy-wait, 误差 < 1ms
                }
                _countdownSec.value = 0
            }

            // —— 第二步: 编码所有分钟的数据 ——
            // :00 对齐保证 P0 帧标记落在真实秒 0, 编码此刻的分钟即可
            val allMinutes = mutableListOf<List<Int>>()
            val baseTime = System.currentTimeMillis() - _ntpOffset.value
            for (i in 0 until count) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = baseTime
                    add(Calendar.MINUTE, i)
                }
                val frames = bpcEncoder.encodeMinute(cal)
                val minuteData = mutableListOf<Int>()
                for (frame in frames) {
                    minuteData.addAll(bpcEncoder.toLowDurations(frame))
                }
                allMinutes.add(minuteData)
            }

            _transmitProgress.value = 0
            _transmitMinuteLabel.value = if (count > 1) "分钟 1/$count" else ""
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(baseTime))

            // —— 第三步: 无缝连续发射 ——
            audioOutput.transmitAllMinutesAsync(
                scope = viewModelScope,
                allMinutes = allMinutes,
                onProgress = { elapsed, total ->
                    _transmitProgress.value = elapsed
                    _transmitStatus.value = "$elapsed/$total 秒"
                },
                onMinuteDone = { cur, total ->
                    if (total > 1) {
                        _transmitMinuteLabel.value = "分钟 $cur/$total"
                    }
                },
                onComplete = {
                    _transmitState.value = BpcAudioOutput.State.IDLE
                    _lastTransmitTime.value = timeStr
                },
                onError = { _ ->
                    _transmitState.value = BpcAudioOutput.State.ERROR
                }
            )
        }
    }

    fun stopTransmit() {
        audioOutput.stop()
        _transmitState.value = BpcAudioOutput.State.STOPPED
    }

    fun setSampleRate(rate: Int) { _sampleRate.value = rate }
    fun setRepeatCount(count: Int) { _repeatCount.value = count }
    fun setRoundMinuteTx(enabled: Boolean) { _roundMinuteTx.value = enabled }

    override fun onCleared() {
        super.onCleared()
        audioOutput.release()
    }
}
