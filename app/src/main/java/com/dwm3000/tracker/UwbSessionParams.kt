package com.dwm3000.tracker

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Parameters exchanged over BLE OOB to initialize a FiRa UWB ranging session.
 * Both Controller and Controlee must agree on these values.
 */
data class UwbSessionParams(
    val sessionId: Int,
    val channel: Int,
    val preambleIndex: Int,
    val localAddress: ByteArray,   // 2-byte UWB short address
    val remoteAddress: ByteArray   // 2-byte UWB short address of peer
) {
    companion object {
        const val SESSION_KEY_LENGTH = 8  // Static STS requires 8-byte key

        /**
         * Serializes session params into a byte array for BLE transfer.
         * Format: [sessionId(4)] [channel(1)] [preambleIndex(1)] [sessionKey(8)] [address(2)] = 16 bytes
         */
        fun serialize(
            sessionId: Int,
            channel: Int,
            preambleIndex: Int,
            sessionKey: ByteArray,
            address: ByteArray
        ): ByteArray {
            val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(sessionId)
            buffer.put(channel.toByte())
            buffer.put(preambleIndex.toByte())
            buffer.put(sessionKey)
            buffer.put(address)
            return buffer.array()
        }

        /**
         * Deserializes BLE payload.
         * Returns: ParsedParams(sessionId, channel, preambleIndex, sessionKey, address)
         */
        fun deserialize(data: ByteArray): ParsedParams {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val sessionId = buffer.getInt()
            val channel = buffer.get().toInt() and 0xFF
            val preambleIndex = buffer.get().toInt() and 0xFF
            val sessionKey = ByteArray(SESSION_KEY_LENGTH)
            buffer.get(sessionKey)
            val address = ByteArray(2)
            buffer.get(address)
            return ParsedParams(sessionId, channel, preambleIndex, sessionKey, address)
        }

        fun generateSessionId(): Int = Random.nextInt(1, Int.MAX_VALUE)

        fun generateSessionKey(): ByteArray {
            val key = ByteArray(SESSION_KEY_LENGTH)
            Random.nextBytes(key)
            return key
        }

        fun generateLocalAddress(): ByteArray {
            val addr = ByteArray(2)
            Random.nextBytes(addr)
            return addr
        }
    }

    data class ParsedParams(
        val sessionId: Int,
        val channel: Int,
        val preambleIndex: Int,
        val sessionKey: ByteArray,
        val address: ByteArray
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbSessionParams) return false
        return sessionId == other.sessionId && channel == other.channel
    }

    override fun hashCode(): Int = sessionId * 31 + channel
}
