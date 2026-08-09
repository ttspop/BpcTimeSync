package com.bpctimesync.ntp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 简易 SNTP 客户端
 *
 * NTP 协议 (RFC 5905 / 4330) 原理:
 *   - 客户端发送 48 字节请求报文到 NTP 服务器 UDP 123 端口
 *   - 服务器返回 48 字节响应，包含精确时间戳
 *   - 客户端根据往返延迟计算本地时钟偏差 (offset)
 */
class NtpClient {

    data class NtpResult(
        val serverTime: Long,       // 服务器时间 (Unix 毫秒)
        val roundTripDelay: Long,   // 往返延迟 (毫秒)
        val localOffset: Long       // 本地时钟偏差 (毫秒), 正=本地快于服务器
    )

    companion object {
        private const val NTP_PORT = 123
        private const val NTP_PACKET_SIZE = 48
        // 1900-01-01 到 1970-01-01 的时间差 (秒)
        private const val NTP_EPOCH_OFFSET = 2208988800L
        private const val TIMEOUT_MS = 5000
    }

    /**
     * 向指定服务器发起 NTP 请求并返回同步结果
     */
    suspend fun sync(host: String, timeoutMs: Int = TIMEOUT_MS): Result<NtpResult> =
        withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(host)
                val socket = DatagramSocket()
                socket.soTimeout = timeoutMs

                // 构造 NTP 请求报文 (48 字节)
                val request = ByteArray(NTP_PACKET_SIZE)
                request[0] = 0x1B.toByte() // LI=0, VN=3, Mode=3 (client)

                val sendPacket = DatagramPacket(request, request.size, address, NTP_PORT)
                val t0 = System.currentTimeMillis()
                socket.send(sendPacket)

                // 接收响应
                val response = ByteArray(NTP_PACKET_SIZE)
                val receivePacket = DatagramPacket(response, response.size)
                socket.receive(receivePacket)
                val t3 = System.currentTimeMillis()
                socket.close()

                // 解析 NTP 响应
                val transmitTimestamp = parseTimestamp(response, 40)

                // 计算偏差和延迟
                val roundTripDelay = t3 - t0
                val serverTime = transmitTimestamp - (roundTripDelay / 2)
                val localOffset = t3 - transmitTimestamp

                Result.success(NtpResult(serverTime, roundTripDelay, localOffset))
            } catch (e: Exception) {
                Result.failure(Exception("NTP 同步失败: ${e.message}"))
            }
        }

    /**
     * 尝试依次从多个服务器同步，返回第一个成功的结果
     */
    suspend fun syncFromPool(servers: List<String>): Result<NtpResult> {
        for (server in servers) {
            val result = sync(server)
            if (result.isSuccess) return result
        }
        return Result.failure(Exception("所有 NTP 服务器均同步失败"))
    }

    /**
     * 解析 NTP 报文中的 64-bit 时间戳 (从指定偏移开始)
     */
    private fun parseTimestamp(buffer: ByteArray, offset: Int): Long {
        // 读取高 32 位 (整数秒)
        var seconds = 0L
        for (i in 0 until 4) {
            seconds = (seconds shl 8) or (buffer[offset + i].toInt() and 0xFF).toLong()
        }

        // 读取低 32 位 (小数秒, 精度 = 1/2^32 秒)
        var fraction = 0L
        for (i in 4 until 8) {
            fraction = (fraction shl 8) or (buffer[offset + i].toInt() and 0xFF).toLong()
        }

        // 转换: NTP epoch (1900) → Unix epoch (1970), 小数转毫秒
        val unixSeconds = seconds - NTP_EPOCH_OFFSET
        val millis = (fraction * 1000L) / 0x100000000L

        return unixSeconds * 1000 + millis
    }
}
