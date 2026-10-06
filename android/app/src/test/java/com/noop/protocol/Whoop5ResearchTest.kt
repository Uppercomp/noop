package com.noop.protocol

import com.noop.data.V18AuxCodec
import com.noop.data.V18AuxRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Expected JSON is verbatim stdout from standalone optimized Swift helpers (see WHOOP5_OPENSTRAP_PORT.md).
class Whoop5ResearchTest {
    private fun oracle() = JSONObject(javaClass.classLoader!!.getResourceAsStream("whoop5_research_oracle.json")!!
        .bufferedReader().use { it.readText() })
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun canonical(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { canonical(v.get(it)) }.toSortedMap()
        is JSONArray -> (0 until v.length()).map { canonical(v.get(it)) }
        is Map<*, *> -> v.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
        is List<*> -> v.map(::canonical)
        is Number -> v.toLong()
        else -> v
    }

    @Test fun researchCasesMatchSwiftAndRemainArchived() {
        val cases = oracle().getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val frame = bytes(case.getString("hex"))
            val decoded = decodeHistorical(frame, DeviceFamily.WHOOP5)!!
            assertEquals("tag ${case.getInt("tag")}", canonical(case.getJSONObject("expect")), canonical(decoded))
            assertEquals(1, rejectedHistoricalRecords(listOf(frame), DeviceFamily.WHOOP5).size)
            val streams = extractHistoricalStreams(listOf(frame), 1780000000, 1780000000, DeviceFamily.WHOOP5)
            assertTrue("research telemetry must not become scored physiology", streams.isEmpty)
            assertNull(decoded["heart_rate"])
            assertNull(decoded["spo2_candidate_82"])
        }
    }

    @Test fun researchRejectsCorruptAndWrongSizedFrames() {
        val frame = bytes(oracle().getJSONArray("cases").getJSONObject(0).getString("hex"))
        val bad = frame.copyOf().also { it[21] = (it[21].toInt() xor 1).toByte() }
        assertNull(decodeWhoop5Research(bad))
        assertNull(decodeWhoop5Research(frame.copyOf(187)))
        assertNull(decodeWhoop5Research(frame + byteArrayOf(0)))
    }

    @Test fun appendedAuxSlotsMatchSwiftForEveryFlagByte() {
        val expected = oracle().getJSONArray("v18_aux_blobs")
        for (flag in 0..255) {
            val row = V18AuxRow(0, recordFlags = flag.toLong(), timestampSubseconds = 65535L - flag,
                auxWord61 = flag * 257L, auxByte64 = 255L - flag, activityClassRaw = flag.toLong())
            val packed = V18AuxCodec.pack(row)
            assertEquals(expected.getString(flag), packed.joinToString("") { "%02x".format(it.toInt() and 255) })
            assertEquals(row, V18AuxCodec.unpack(packed, 0))
        }
        val legacy = V18AuxCodec.unpack(bytes("020100000078563412"), 0)
        assertEquals(0x12345678L, legacy.recordIndex)
        assertNull(legacy.recordFlags)
        assertNull(legacy.timestampSubseconds)
    }
    @Test fun waveformMetadataMatchesSwiftAndKeepsSignedViewSeparate() {
        val json = oracle()
        val frame = bytes(json.getString("waveform_hex"))
        val row = com.noop.data.PpgWaveformRow(0, emptyList(), baseCode = 0xFFFF_FFFFL,
            rawFrame = frame.map { it.toInt() and 255 })
        val m = row.metadata!!
        val values = mapOf("recordFlags" to m.recordFlags, "recordIndex" to m.recordIndex,
            "unix" to m.unix, "timestampSubseconds" to m.timestampSubseconds, "motionBits" to m.motionBits,
            "statusWord" to m.statusWord, "primaryFlags" to m.primaryFlags,
            "resultByte" to m.resultByte, "paddingByte" to m.paddingByte)
        assertEquals(canonical(json.getJSONObject("waveform_metadata")), canonical(values))
        assertEquals(-1L, row.signedBaseCode)
        assertEquals(0xFFFF_FFFFL, row.baseCode)
        assertNull(com.noop.data.PpgWaveformRow(0, emptyList()).metadata)
        val corrupt = frame.copyOf().also { it[82] = (it[82].toInt() xor 1).toByte() }
        assertNull(Whoop5WaveformMetadata.decode(corrupt))
        assertNull(Whoop5WaveformMetadata.decode(frame.copyOf(87)))
    }

}
