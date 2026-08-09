package com.bpctimesync.bpc

import java.util.Calendar

/**
 * BPC 中国电波授时协议编码器 (标准 20 秒帧格式)
 *
 * 参考: Wikipedia BPC time signal, 专利 CN1667528A
 *
 * 每分钟包含 3 个 20 秒帧，起始于 0s、20s、40s。
 * 每帧内每秒编码 2 位二进制数据 (四进制脉冲宽度调制)。
 *
 * 脉冲宽度编码 (每帧内秒序号 0-19):
 *   第 0 秒 (P0): 全秒高电平，无低电平段 → 帧起始标记
 *   数据秒: 每秒开头先低电平 100/200/300/400ms，然后高电平至秒末
 *     100ms = 00 (四进制0)
 *     200ms = 01 (四进制1)
 *     300ms = 10 (四进制2)
 *     400ms = 11 (四进制3)
 *
 * 帧格式:
 *   S0:  P0 — 帧起始 (全秒高电平)
 *   S1:  帧号 (00/01/10 = 第1/2/3帧)
 *   S2:  保留位 (00)
 *   S3:  时(高2位)
 *   S4:  时(低2位)         → 12小时制, 0~11
 *   S5:  分(高2位)
 *   S6:  分(中2位)
 *   S7:  分(低2位)         → 0~59
 *   S8:  星期(高2位)
 *   S9:  星期(低2位)       → 1=周一, 7=周日
 *   S10: [上/下午, 偶校验(S1-S9)]
 *   S11: 日(高2位)
 *   S12: 日(中2位)
 *   S13: 日(低2位)         → 1~31
 *   S14: 月(高2位)
 *   S15: 月(低2位)         → 1~12
 *   S16: 年(高2位)
 *   S17: 年(中2位)
 *   S18: 年(低2位)         → 0~99
 *   S19: [年MSB, 偶校验(S11-S18)]
 */
class BpcEncoder {

    /** 每秒的低电平持续时间 (即信号减弱时间)，同时也是四进制值 */
    enum class PulseType(val lowMs: Int, val value: Int) {
        FRAME_START(0, -1),  // P0: 无低电平, 全秒高电平
        BIT_00(100, 0),      // 00 → 100ms 低电平
        BIT_01(200, 1),      // 01 → 200ms 低电平
        BIT_10(300, 2),      // 10 → 300ms 低电平
        BIT_11(400, 3)       // 11 → 400ms 低电平
    }

    companion object {
        const val FRAME_SECONDS = 20
        const val FRAMES_PER_MINUTE = 3

        /** 根据四进制值获取 PulseType */
        fun fromValue(v: Int): PulseType = when (v) {
            0 -> PulseType.BIT_00
            1 -> PulseType.BIT_01
            2 -> PulseType.BIT_10
            3 -> PulseType.BIT_11
            else -> PulseType.BIT_00
        }
    }

    /** 一帧的编码结果 (包含帧号信息) */
    data class BpcFrame(
        val pulses: List<PulseType>,    // 20 个元素
        val frameStartSecond: Int,       // 该帧在整分钟内的起始秒 (0/20/40)
        val timestamp: Long
    )

    /**
     * 编码一整分钟的 3 帧 BPC 信号
     */
    fun encodeMinute(calendar: Calendar): List<BpcFrame> {
        return (0 until FRAMES_PER_MINUTE).map { frameIndex ->
            encodeFrame(calendar, frameIndex)
        }
    }

    /**
     * 编码单个 20 秒帧
     * @param frameIndex 帧序号: 0, 1, 2 (对应起始秒 0, 20, 40)
     */
    fun encodeFrame(calendar: Calendar, frameIndex: Int): BpcFrame {
        val pulses = MutableList(FRAME_SECONDS) { PulseType.BIT_00 }

        // 提取时间字段
        val hour = calendar.get(Calendar.HOUR_OF_DAY)       // 0-23
        val minute = calendar.get(Calendar.MINUTE)           // 0-59
        val day = calendar.get(Calendar.DAY_OF_MONTH)        // 1-31
        val month = calendar.get(Calendar.MONTH) + 1         // 1-12
        val year = calendar.get(Calendar.YEAR) % 100         // 0-99

        // 星期: Calendar.SUNDAY=1 → 转换成 1=周一, 7=周日
        val dowRaw = calendar.get(Calendar.DAY_OF_WEEK)
        val dow = if (dowRaw == 1) 7 else dowRaw - 1

        // 12小时制: 0-11 (Wikipedia: "Hour (00-11)")
        val isPM = hour >= 12
        val hour12 = hour % 12  // 0=12AM/12PM, 1-11=对应小时

        // ---- 填充帧数据 ----

        // S0: P0 帧起始 (全秒高电平)
        pulses[0] = PulseType.FRAME_START

        // S1: 帧号 (0/1/2 → 00/01/10)
        pulses[1] = fromValue(frameIndex)

        // S2: 保留位 (00)
        pulses[2] = PulseType.BIT_00

        // S3-S4: 时 (12小时制, 0-11, 4位)
        encodeField(pulses, 3, hour12, 4)

        // S5-S7: 分 (0-59, 6位)
        encodeField(pulses, 5, minute, 6)

        // S8-S9: 星期 (1-7, 4位) — 注: 协议中年份高位已占用第4位，星期只用2+2=4位
        encodeField(pulses, 8, dow, 4)

        // S10: [上/下午(msb), 偶校验(S1-S9 共9秒)(lsb)]
        //       Wikipedia: "Even parity over 01-09"
        val parity1 = evenParityFromBits(pulses, 1, 10)  // 秒 1-9 (1 until 10)
        pulses[10] = fromValue((if (isPM) 2 else 0) or parity1)

        // S11-S13: 日 (1-31, 6位)
        encodeField(pulses, 11, day, 6)

        // S14-S15: 月 (1-12, 4位)
        encodeField(pulses, 14, month, 4)

        // S16-S18: 年 (0-99, 6位)
        encodeField(pulses, 16, year, 6)

        // S19: [年最高位(msb), 偶校验(S11-S18 共8秒)(lsb)]
        //       Wikipedia: "64 P2 Even parity over 11-18"
        val yearMsb = (year / 64) and 1   // 年份 00-63 时 MSB=0
        val parity2 = evenParityFromBits(pulses, 11, 19)  // 秒 11-18 (11 until 19)
        pulses[19] = fromValue((yearMsb shl 1) or parity2)

        return BpcFrame(
            pulses = pulses,
            frameStartSecond = frameIndex * FRAME_SECONDS,
            timestamp = calendar.timeInMillis
        )
    }

    // ---- 内部方法 ----

    /**
     * 将整数值按高位在前编码到 pulses 数组中
     * 每两个二进制位占用一个秒位 (四进制编码)
     *
     * @param pulses 目标数组
     * @param startSec 起始秒序号
     * @param value 要编码的值
     * @param bits 二进制位数 (必须为偶数)
     */
    private fun encodeField(pulses: MutableList<PulseType>, startSec: Int, value: Int, bits: Int) {
        val binary = value.toString(2).padStart(bits, '0')
        for (i in 0 until bits / 2) {
            val highBit = if (binary[i * 2] == '1') 2 else 0
            val lowBit = if (binary[i * 2 + 1] == '1') 1 else 0
            pulses[startSec + i] = fromValue(highBit or lowBit)
        }
    }

    /**
     * 计算偶校验: 统计 pulses[from..<to] 中所有二进制位的1的个数，
     * 返回 0 (已是偶数) 或 1 (需补1变成偶数)
     */
    private fun evenParityFromBits(pulses: List<PulseType>, from: Int, to: Int): Int {
        var ones = 0
        for (i in from until to) {
            ones += pulses[i].value.let { v ->
                when (v) {
                    0 -> 0    // 00
                    1 -> 1    // 01
                    2 -> 1    // 10
                    3 -> 2    // 11
                    else -> 0
                }
            }
        }
        return ones and 1  // 0=even, 1=odd
    }

    /**
     * 将一帧的 PulseType 列表转换为音频输出用的低电平持续时间列表 (毫秒)
     * 返回: List<Int> 长度为 20, 每个值为该秒低电平持续时间 (ms)
     *       0 表示全秒高电平 (P0 帧起始)
     */
    fun toLowDurations(frame: BpcFrame): List<Int> {
        return frame.pulses.map { it.lowMs }
    }
}
