package com.noop.protocol

// Independently implemented from OpenStrap/protocol ecb512b710fb (MIT), gen5_records.dart.
// Candidate R22 geometry only. No health metric, unit, or sample rate is inferred from these bytes.
internal fun decodeWhoop5Research(frame: ByteArray): Map<String, Any?>? {
    if (frame.size != 188 || (frame[8].toInt() and 255) != 47 ||
        (frame[9].toInt() and 255) != 22 || !Framing.verifyFrame(frame, DeviceFamily.WHOOP5).ok) return null
    fun u8(off: Int) = frame[off].toInt() and 255
    fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
    fun u32(off: Int): Long = (0..3).fold(0L) { n, i -> n or (u8(off + i).toLong() shl (8 * i)) }
    fun i16(off: Int) = u16(off).toShort().toInt()
    val tag = u8(21)
    val out = linkedMapOf<String, Any?>(
        "hist_version" to 22, "record_flags" to u8(10), "record_index" to u32(11),
        "unix" to u32(15), "timestamp_subseconds" to u16(19), "research_tag" to tag,
        "research_body" to (21 until 184).map(::u8),
    )
    fun window(number: Int, innerOffset: Int, slots: Int) {
        val off = innerOffset + 8
        out["optical_base_$number"] = u32(off).toInt().toLong()
        out["optical_deltas_$number"] = (0 until slots).map { i16(off + 4 + it * 2) }
    }
    fun metadata(innerBase: Int) {
        val off = innerBase + 8
        out["metadata_flags_snapshot"] = u8(off + 1)
        out["metadata_motion_bits"] = u32(off + 4)
        out["metadata_float_bits"] = listOf(u32(off + 8), u32(off + 12), u32(off + 16))
        out["metadata_status_word"] = u16(off + 20)
        out["metadata_flags"] = u8(off + 26)
    }
    when (tag) {
        1, 2, 4 -> {
            window(0, 15, 49)
            metadata(117)
            if (tag != 1) out["research_extension"] = (152 until 163).map(::u8)
        }
        3 -> { window(0, 15, 24); window(1, 67, 24); metadata(119) }
        5 -> {
            out["pip_unix"] = u32(23)
            window(0, 23, 24)
            out["metadata_motion_bits"] = u32(83)
            out["metadata_status_word"] = u16(87)
            out["metadata_flags"] = u8(89)
            // Tail after the embedded PIP window may contain the PREVIOUS record. Never read it as metadata.
        }
        6 -> {
            for ((axis, off) in listOf("x" to 26, "y" to 76, "z" to 126)) {
                out["accel_${axis}_raw"] = (0 until 25).map { i16(off + it * 2) }
            }
            out["research_tail"] = (176 until 184).map(::u8)
        }
        // Future tags retain header and raw body, without guessed fields.
    }
    return out
}
