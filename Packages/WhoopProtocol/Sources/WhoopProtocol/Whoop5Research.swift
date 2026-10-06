import Foundation

// Independently implemented from OpenStrap/protocol ecb512b710fb (MIT), gen5_records.dart.
// Candidate R22 geometry only. No health metric, unit, or sample rate is inferred from these bytes.
func decodeWhoop5Research(_ frame: [UInt8]) -> [String: ParsedValue]? {
    guard frame.count == 188, frame[8] == 47, frame[9] == 22,
          verifyFrame(frame, family: .whoop5).ok else { return nil }
    func u8(_ off: Int) -> Int { Int(frame[off]) }
    func u16(_ off: Int) -> Int { u8(off) | (u8(off + 1) << 8) }
    func u32(_ off: Int) -> Int { (0..<4).reduce(0) { $0 | (u8(off + $1) << (8 * $1)) } }
    func i16(_ off: Int) -> Int { Int(Int16(bitPattern: UInt16(u16(off)))) }
    let tag = u8(21)
    var out: [String: ParsedValue] = [
        "hist_version": .int(22), "record_flags": .int(u8(10)), "record_index": .int(u32(11)),
        "unix": .int(u32(15)), "timestamp_subseconds": .int(u16(19)), "research_tag": .int(tag),
        "research_body": .intArray((21..<184).map(u8)),
    ]
    func window(_ number: Int, _ innerOffset: Int, _ slots: Int) {
        let off = innerOffset + 8
        out["optical_base_\(number)"] = .int(Int(Int32(bitPattern: UInt32(u32(off)))))
        out["optical_deltas_\(number)"] = .intArray((0..<slots).map { i16(off + 4 + $0 * 2) })
    }
    func metadata(_ innerBase: Int) {
        let off = innerBase + 8
        out["metadata_flags_snapshot"] = .int(u8(off + 1))
        out["metadata_motion_bits"] = .int(u32(off + 4))
        out["metadata_float_bits"] = .intArray([u32(off + 8), u32(off + 12), u32(off + 16)])
        out["metadata_status_word"] = .int(u16(off + 20))
        out["metadata_flags"] = .int(u8(off + 26))
    }
    switch tag {
    case 1, 2, 4:
        window(0, 15, 49)
        metadata(117)
        if tag != 1 { out["research_extension"] = .intArray((152..<163).map(u8)) }
    case 3:
        window(0, 15, 24); window(1, 67, 24); metadata(119)
    case 5:
        out["pip_unix"] = .int(u32(23))
        window(0, 23, 24)
        out["metadata_motion_bits"] = .int(u32(83))
        out["metadata_status_word"] = .int(u16(87))
        out["metadata_flags"] = .int(u8(89))
        // Tail after the embedded PIP window may contain the PREVIOUS record. Never read it as metadata.
    case 6:
        for (axis, off) in [("x", 26), ("y", 76), ("z", 126)] {
            out["accel_\(axis)_raw"] = .intArray((0..<25).map { i16(off + $0 * 2) })
        }
        out["research_tail"] = .intArray((176..<184).map(u8))
    default: break // Future tags retain header and raw body, without guessed fields.
    }
    return out
}
