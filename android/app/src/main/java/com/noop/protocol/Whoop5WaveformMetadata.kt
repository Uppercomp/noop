package com.noop.protocol

// Candidate field names from OpenStrap/protocol ecb512b710fb. Bytes remain authoritative.
data class Whoop5WaveformMetadata(
    val recordFlags: Int,
    val recordIndex: Long,
    val unix: Long,
    val timestampSubseconds: Int,
    val motionBits: Long,
    val statusWord: Int,
    val primaryFlags: Int,
    val resultByte: Int,
    val paddingByte: Int,
) {
    companion object {
        fun decode(frame: ByteArray): Whoop5WaveformMetadata? {
            if (frame.size != 88 || (frame[8].toInt() and 255) != 47 ||
                (frame[9].toInt() and 255) != 26 || !Framing.verifyFrame(frame, DeviceFamily.WHOOP5).ok) return null
            fun u8(off: Int) = frame[off].toInt() and 255
            fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
            fun u32(off: Int) = (0..3).fold(0L) { n, i -> n or (u8(off + i).toLong() shl (8 * i)) }
            return Whoop5WaveformMetadata(u8(10), u32(11), u32(15), u16(19), u32(75),
                u16(79), u8(81), u8(82), u8(83))
        }
    }
}
