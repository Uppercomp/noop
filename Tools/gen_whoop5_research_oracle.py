#!/usr/bin/env python3
"""Print the WHOOP 5 research/auxiliary oracle using independently compiled Swift helpers.

The input frames are public OpenStrap fixtures (MIT, licenses/OpenStrap-MIT.txt) plus
NOOP's existing public v26 fixture. Expected results are read from neither input file
nor Kotlin. See docs/WHOOP5_OPENSTRAP_PORT.md before updating the shared fixtures.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "Packages/WhoopProtocol/Sources/WhoopProtocol"
MAIN = r"""import Foundation
func bytes(_ hex: String) -> [UInt8] {
    let s = Array(hex.utf8)
    return stride(from: 0, to: s.count, by: 2).map { UInt8(String(decoding: s[$0..<($0 + 2)], as: UTF8.self), radix: 16)! }
}
let inputJSON = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
let inputs = (inputJSON["cases"] as! [[String: Any]]).map { ["tag": $0["tag"]!, "hex": $0["hex"]!] }
var cases: [[String: Any]] = []
for input in inputs {
    let frame = bytes(input["hex"] as! String)
    let decoded = decodeWhoop5Research(frame)!
    let data = try JSONEncoder().encode(decoded)
    var row = input
    row["expect"] = try JSONSerialization.jsonObject(with: data)
    cases.append(row)
}
let blobs = (0...255).map { flag -> String in
    let sample = V18AuxSample(ts: 0, recordFlags: flag, timestampSubseconds: 65535 - flag,
                              auxWord61: flag * 257, auxByte64: 255 - flag, activityClassRaw: flag)
    return V18AuxCodec.pack(sample).map { String(format: "%02x", $0) }.joined()
}
let waveformHex = inputJSON["waveform_hex"] as! String
let metadata = Whoop5WaveformMetadata.decode(bytes(waveformHex))!
let metadataData = try JSONEncoder().encode(metadata)
let result: [String: Any] = ["cases": cases, "v18_aux_blobs": blobs, "waveform_hex": waveformHex,
                           "waveform_metadata": try JSONSerialization.jsonObject(with: metadataData)]
let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
print(String(decoding: data, as: UTF8.self))
"""


def generate(swiftc: str) -> str:
    with tempfile.TemporaryDirectory(prefix="noop-whoop5-oracle-") as directory:
        work = Path(directory)
        family = (PROTOCOL / "DeviceFamily.swift").read_text(encoding="utf-8")
        (work / "Family.swift").write_text(family[:family.index("/// Which checksum")], encoding="utf-8")
        streams = (PROTOCOL / "Streams.swift").read_text(encoding="utf-8")
        start = streams.index("public enum V18AuxSlot")
        end = streams.index("public struct Streams:", start)
        (work / "Aux.swift").write_text("import Foundation\n" + streams[start:end], encoding="utf-8")
        codec = (ROOT / "Packages/WhoopStore/Sources/WhoopStore/V18Aux.swift").read_text(encoding="utf-8")
        (work / "Codec.swift").write_text(codec.replace("import WhoopProtocol", ""), encoding="utf-8")
        (work / "main.swift").write_text(MAIN, encoding="utf-8")
        executable = work / "oracle"
        sources = [work / "Family.swift", PROTOCOL / "Framing.swift", PROTOCOL / "Values.swift",
                   PROTOCOL / "Whoop5Research.swift", PROTOCOL / "Whoop5WaveformMetadata.swift",
                   work / "Aux.swift", work / "Codec.swift", work / "main.swift"]
        subprocess.run([swiftc, "-O", *map(str, sources), "-o", str(executable)], check=True)
        fixture = ROOT / "android/app/src/test/resources/whoop5_research_oracle.json"
        return subprocess.check_output([str(executable), str(fixture)], text=True, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--swiftc", default="swiftc")
    args = parser.parse_args()
    print(generate(args.swiftc), end="")
