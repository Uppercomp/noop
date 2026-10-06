import XCTest
@testable import WhoopProtocol

final class Whoop5ResearchTests: XCTestCase {
    private struct Oracle: Decodable {
        struct Case: Decodable { let tag: Int; let hex: String; let expect: [String: ParsedValue] }
        let cases: [Case]
    }
    private func bytes(_ hex: String) -> [UInt8] {
        let s = Array(hex.utf8)
        return stride(from: 0, to: s.count, by: 2).map {
            UInt8(String(decoding: s[$0..<($0 + 2)], as: UTF8.self), radix: 16)!
        }
    }
    func testSharedOracleAndArchiveRetention() throws {
        let url = Bundle.module.url(forResource: "whoop5_research_oracle", withExtension: "json")!
        let oracle = try JSONDecoder().decode(Oracle.self, from: Data(contentsOf: url))
        for c in oracle.cases {
            let frame = bytes(c.hex)
            XCTAssertEqual(decodeWhoop5Research(frame), c.expect, "tag \(c.tag)")
            XCTAssertEqual(parseFrame(frame, family: .whoop5).parsed, c.expect)
            XCTAssertEqual(rejectedHistoricalRecords([frame], family: .whoop5), [frame])
            let streams = extractHistoricalStreams([parseFrame(frame, family: .whoop5)],
                                                     deviceClockRef: 1_780_000_000, wallClockRef: 1_780_000_000)
            XCTAssertTrue(streams.isEmpty, "research telemetry must not become scored physiology")
            var corrupt = frame; corrupt[21] ^= 1
            XCTAssertNil(decodeWhoop5Research(corrupt))
            XCTAssertNil(decodeWhoop5Research(Array(frame.dropLast())))
            XCTAssertNil(decodeWhoop5Research(frame + [0]))
        }
    }
    func testWaveformMetadataAndSignedBaseView() throws {
        let url = Bundle.module.url(forResource: "whoop5_research_oracle", withExtension: "json")!
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let frame = bytes(json["waveform_hex"] as! String)
        let expected = try JSONDecoder().decode(Whoop5WaveformMetadata.self,
            from: JSONSerialization.data(withJSONObject: json["waveform_metadata"]!))
        let row = PpgWaveformSample(ts: 0, samples: [], baseCode: 0xFFFF_FFFF, rawFrame: frame)
        XCTAssertEqual(row.metadata, expected)
        XCTAssertEqual(row.signedBaseCode, -1)
        XCTAssertEqual(row.baseCode, 0xFFFF_FFFF)
        XCTAssertNil(PpgWaveformSample(ts: 0, samples: []).metadata)
        var corrupt = frame; corrupt[82] ^= 1
        XCTAssertNil(Whoop5WaveformMetadata.decode(corrupt))
        XCTAssertNil(Whoop5WaveformMetadata.decode(Array(frame.dropLast())))
    }

}
