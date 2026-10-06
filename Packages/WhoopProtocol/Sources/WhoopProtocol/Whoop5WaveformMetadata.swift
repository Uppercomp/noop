import Foundation

// Candidate field names from OpenStrap/protocol ecb512b710fb. Bytes remain authoritative.
public struct Whoop5WaveformMetadata: Equatable, Codable, Sendable {
    public let recordFlags: Int
    public let recordIndex: Int
    public let unix: Int
    public let timestampSubseconds: Int
    public let motionBits: Int
    public let statusWord: Int
    public let primaryFlags: Int
    public let resultByte: Int
    public let paddingByte: Int

    public static func decode(_ frame: [UInt8]) -> Whoop5WaveformMetadata? {
        guard frame.count == 88, frame[8] == 47, frame[9] == 26,
              verifyFrame(frame, family: .whoop5).ok else { return nil }
        func u8(_ off: Int) -> Int { Int(frame[off]) }
        func u16(_ off: Int) -> Int { u8(off) | (u8(off + 1) << 8) }
        func u32(_ off: Int) -> Int { (0..<4).reduce(0) { $0 | (u8(off + $1) << (8 * $1)) } }
        return Whoop5WaveformMetadata(recordFlags: u8(10), recordIndex: u32(11), unix: u32(15),
                                     timestampSubseconds: u16(19), motionBits: u32(75), statusWord: u16(79),
                                     primaryFlags: u8(81), resultByte: u8(82), paddingByte: u8(83))
    }
}
