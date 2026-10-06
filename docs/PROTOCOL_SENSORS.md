# WHOOP sensor records

<a id="whoop-5mg-sensor-records"></a>

Applicability: [central scope and compatibility](PROTOCOL.md#scope-and-compatibility).

This companion to [the main protocol reference](PROTOCOL.md) specifies measurement
records and their validity boundaries. It complements the historical
[WHOOP 5 data experiment](WHOOP5_DEEP_DATA.md) and
[optical collection guide](WHOOP5_OPTICAL_EXPERIMENT.md).
For ECG R16/R17, use [the ECG contract](PROTOCOL_ECG.md).

Frame shapes, count capacities and processing qualifications follow the central
scope. Earlier physical scales and timing conventions remain explicitly
qualified; they do not establish a new hardware calibration or every device’s
sensor configuration.

## Contents

- [WHOOP 4](#whoop-4)
  - [WHOOP 4 realtime heart-rate record (type 40)](#whoop-4-realtime-heart-rate-record-type-40)
  - [WHOOP 4 realtime raw layouts (type 43)](#whoop-4-realtime-raw-layouts-type-43)
  - [WHOOP 4 historical v24](#whoop-4-historical-v24)
  - [WHOOP 4 historical v25 and unknown versions](#whoop-4-historical-v25-and-unknown-versions)
  - [WHOOP 4 sensor and record controls](#whoop-4-sensor-and-record-controls)
- [WHOOP 5/MG](#whoop-5mg)
  - [Packet types, record layouts and integrity](#packet-types-record-layouts-and-integrity)
  - [Packet 40: live HR and R-R](#packet-40-live-hr-and-r-r)
  - [R18: biometric summary](#r18-biometric-summary)
    - [Step source, cadence and activity](#step-source-cadence-and-activity)
    - [Motion/rest state and override](#motionrest-state-and-override)
  - [R18 quality-adjacent source-selection bits](#r18-quality-adjacent-source-selection-bits)
  - [R20: optical blocks](#r20-optical-blocks)
    - [Configuration and conditional routing](#configuration-and-conditional-routing)
  - [R21: six-axis IMU](#r21-six-axis-imu)
  - [Inertial record timestamps](#inertial-record-timestamps)
  - [R22 inner version](#r22-inner-version)
  - [R22 version 9 queued channels and sample encoding](#r22-version-9-queued-channels-and-sample-encoding)
  - [R22 version 9 metadata refinement](#r22-version-9-metadata-refinement)
  - [R26: compact optical window](#r26-compact-optical-window)
  - [Dedicated IMU stream types 51 and 52](#dedicated-imu-stream-types-51-and-52)
  - [Implementation boundaries](#implementation-boundaries)
  - [Constructed arithmetic checks](#constructed-arithmetic-checks)

<a id="whoop-4-sensor-records"></a>

## WHOOP 4

WHOOP 4 uses its own envelope and versioned record layouts. These boundaries are
capture-backed or retained interoperability conventions; they are not derived from
the WHOOP 5/MG tables below.

| Record | Established WHOOP 4 boundary |
|---|---|
| Type 40 realtime | The WHOOP 4 type-40 layout and the standard Heart Rate Service are separate sources. Do not apply the WHOOP 5/MG packet-40 absolute offsets below. |
| Type 43 realtime raw | Several legacy layouts exist. Command 63 controls the observed R10/R11 stream; command 82 does not stop it. Preserve an unknown layout instead of guessing from type alone. |
| Type 47 v24 | Schema-backed legacy biometric/optical/IMU record. On one 41.17.6.0 offload all 1,704 records were CRC-valid v24; HR agreed with `60000 / mean(R-R)` to about 1 bpm and gravity magnitude was about 1 g. This is one capture, not a generation default. |
| Type 47 v25 | Version 41.17.6.0 also produces this 84-byte frame depending on device configuration: layout byte 25 at frame 5, Unix seconds `u32le` at 11, sensor block from 23 and three signed movement values at 73/75/77. Interpreting those values as gravity scaled by `1/16384` is observation-based, not a confirmed device parameter. No per-second HR field is mapped. |
| Legacy v5/7/9/12 | Retained layout variants. Their documentation is not proof that every field was independently captured on current firmware. |
| Legacy IMU block | Payload length 1,917 means declared length 1,924. The layout has 100 signed `i16le` samples per axis at the offsets listed in the [WHOOP 4 profile](PROTOCOL_WHOOP4.md#legacy-imu-layout). Scales `1/4096` and `2000/32768` are commonly applied interpretations, not confirmed device parameters. |

For historical records, select the layout from the emitted version and validate
the complete family-specific frame length and CRCs first. Version 24 and 25 are
different layouts, not revisions to reconcile by shifting fields. Capture-backed
physiological cross-checks support the listed interpretations but do not establish
medical accuracy, wavelength identity, calibration, or universal sample timing.

### WHOOP 4 realtime heart-rate record (type 40)

The table below is **documented for this version** and corroborated by observed
WHOOP 4 notifications. Offsets are absolute in the reassembled WHOOP 4 frame.

| Offset | Width | Field | Boundary |
|---:|---:|---|---|
| 4 | 1 | Packet type 40 | Family-specific envelope already verified |
| 5 | 1 | Frame sequence | Not a timestamp or request correlation |
| 6 | 4 | Unix/device seconds | **Capture interpretation**; correct wall time depends on clock state |
| 10 | 2 | Subseconds | **Retained field**; exact unit is not independently calibrated here |
| 12 | 1 | Heart rate | bpm |
| 13 | 1 | R-R count | Bounds-check against frame end before iterating |
| 14 + 2n | 2 each | R-R intervals | milliseconds; zero is a placeholder rather than a measured interval |

The standard Heart Rate Service is a separate BLE source. It can serve as a
low-bandwidth alternative, but its values do not prove that proprietary type-40
output or historical storage is healthy.

### WHOOP 4 realtime raw layouts (type 43)

The common prefix is **documented for this version**: command/subtype at 6,
record header at 7–14, Unix seconds at 15 and subseconds at 19–20.
After that prefix, select by validated payload length, which equals declared length minus 7; packet type alone is
not enough.

| Payload length | Validation | Established layout | Unmapped region |
|---:|---|---|---|
| 1,917 | **Documented for this version; observed in device captures.** Declared length is 1,924. | HR at 21, R-R count at 22, four R-R values at 23–30; accelerometer X/Y/Z spans 89–688 as 100 × 3 × `i16le` from 89/289/489; gyroscope spans 692–1291 from 692/892/1092; tail 1292–1923 | 31–88 and 689–691 remain unresolved; tail semantics are not yet named |
| 1,921 | **Documented for this version; observed in device captures.** Declared length is 1,928. | Common time fields remain at 15–20; an additional optical header occupies 21–41; one signed 24-bit little-endian AC-coupled optical waveform starts at 42 with stride 4, up to 419 samples | fourth stride byte, auxiliary bytes and tail semantics |

**Observed in device captures:** the 1,917 layout produced about 100 samples per axis per
packet at roughly one packet per second. Accelerometer scaling `1/4096 g` matched
a gravity sphere fit; gyroscope scaling `2000/32768 degrees/s` matched bounded
720-degree rotations within the observation's turn-count precision. These are
commonly applied interpretations, not confirmed device parameters or a guarantee
for every sensor-board configuration.

**Capture interpretation:** the 1,921 waveform showed a clean pulse-like signal
on-finger and flattened in air, supporting an optical/PPG role. It did not expose
four interleaved wavelength channels. Red, IR and ambient identities are therefore
not assigned to this live stream; byte 3 of each four-byte stride must be retained.

Command 63 is the **capture-backed** output switch for the observed R10/R11
stream. Commands 81/82 concern collection state and did not substitute for command
63 in the observed WHOOP 4 path. Command 105 is outside the documented 41.17.6.0
command set and has no recorded observation;
commands 106/107 are the supported IMU stream SET/GET pair. An accepted write
does not prove packet production or persistence.

### WHOOP 4 historical v24

This mapping combines a retained layout with device captures. Offsets are absolute
in the complete WHOOP 4 frame; version is byte 5.

| Offset | Width/type | Field | Validation boundary |
|---:|---|---|---|
| 11 | 4/u32 | Unix seconds | capture-backed |
| 21 | 1/u8 | Heart rate | capture-backed bpm |
| 22 | 1/u8 | R-R count | frame bounds apply |
| 23 | 2 × count | R-R intervals | milliseconds; zero omitted |
| 33, 35 | 2/u16 each | green and red/IR-labelled raw optical scalars | labels come from the legacy layout; not calibrated wavelength proof |
| 40/44/48 | 4/f32 | gravity vector | capture cross-check near 1 g |
| 55 | 1/u8 | skin-contact state | zero is the retained off-wrist interpretation |
| 56/60/64 | 4/f32 | second gravity vector | physical distinction from first vector unresolved |
| 68/70 | 2/u16 | raw red/IR SpO2-adjacent scalars | not an SpO2 percentage |
| 72 | 2/u16 | skin-temperature raw ADC | not centidegrees; family-specific provisional conversion only |
| 74 | 2/u16 | ambient-light raw | uncalibrated |
| 76/78 | 2/u16 | LED-drive words | units and channel assignment unresolved |
| 80 | 2/u16 | respiration-adjacent raw | not breaths/minute |
| 82 | 2/u16 | signal-quality word | scale/polarity unresolved |

One version-identified 41.17.6.0 offload contained 1,704
CRC-valid v24 records; HR and R-R arithmetic agreed to roughly one bpm and gravity
magnitude was physiologically plausible. This validates decoding for that capture,
not cloud equivalence, medical accuracy or universal v24 output behavior.
Versions 12 and 24 share a retained layout map; that association is not a fresh
v12 device validation.

### WHOOP 4 historical v25 and unknown versions

**Documented for this version; observed in device captures:** depending on device
configuration, 41.17.6.0 produces v25 as an 84-byte complete frame with layout
byte 25 at 5, seconds at 11, a sensor block from 23 and signed movement values at
73/75/77. Interpreting those values as gravity divided by 16384 is observation-based,
not a confirmed device parameter. Forty-five mapped records had gravity magnitude about 0.94–0.99 g. No
per-second HR field is mapped; do not infer HR from the waveform.

Versions 5/7/9, 12/24 and 25 have distinct documented maps. An otherwise unknown
WHOOP 4 version remains an unknown layout even if some values resemble v24.

### WHOOP 4 sensor and record controls

The documented 41.17.6.0 controls cover AFE, LED/TIA/bias, accelerometer,
gyroscope/IMU, fuel gauge, charger/cap-sense and board-dependent sensor-enable
revisions 7, 9, 12 and 19. Separate record controls exist for R7, R9, R10+R11,
R12/R24 and R19, plus AFE and IMU streaming. Saved and realtime output exists
for R7, R9, R10+R11, R12 and R24. These facts do not establish the complete
wire layouts, units, calibration, cadence or availability in every device state.

The strap reports a step count and can recover from a count that remains zero or
unchanged; it also reports sleep/wake motion classifications and rolling motion
statistics. False-step suppression affects the reported count. Exact filters,
threshold units, orientation compensation, persistence and ground-truth accuracy
remain unknown. This counter
must not be equated with app or cloud steps without a version-labelled wire field
and a validation set.

<a id="whoop-5mg-version-baseline"></a>

## WHOOP 5/MG

The remaining R18/R20/R21/R22/R26 contracts apply to 50.42.1.0 unless a subsection
explicitly names an older device observation or interpretation convention.

### Packet types, record layouts and integrity

All offsets are absolute in the **reassembled WHOOP 5/MG format-1 frame**.
Integers and IEEE-754 floats are little-endian unless stated otherwise. Verify
framing, declared length, header CRC16 over `[0,6)`, and body CRC32 over
`[8,frame_length-4)` before interpreting fields. Offsets are not notification-local;
a record can span BLE fragments.

| Packet type at byte 8 | Meaning and routing boundary |
|---:|---|
| 40 | Live heart rate and R-R intervals; separate map below |
| 43 | Live sensor data; R16 and R17 ECG use a layout selector at byte 9. Type alone does not identify a waveform. |
| 47 | Historical data; byte 9 selects R16, R17, R18, R20, R21, R26 or another record layout. Preserve unknown layouts. |
| 48 | Event; WHOOP 5 event number at byte 10 and u32 event timestamp at byte 12. Variable payloads require event-specific handling. |
| 51 | Dedicated realtime IMU stream; [documented count/span layout](#dedicated-imu-stream-types-51-and-52), current strap output unconfirmed. |
| 52 | Dedicated historical IMU stream; [documented count/span layout](#dedicated-imu-stream-types-51-and-52). Do **not** assume type 47/R21. |

Packet number, record layout, command number and event number are separate
namespaces. WHOOP 4 type-43 shapes must not be imported by shifting offsets alone.
The 1,917-byte WHOOP 4 IMU and 1,921-byte optical variants do not define WHOOP 5
records. Current START/END/COMPLETE messages use metadata type 49. Preserve type 56 compatibility where older supported-device observations require it; no universal release boundary is established.

For R18/R20/R21/R26, byte 9 is the layout, u32 at 11 is a record index and u32 at
15 is a Unix-seconds timestamp. The index is not a timestamp: do not fill time gaps
by counting records or assume rollover/reset behavior. Unmapped fields must remain
opaque, not silently converted to zero measurements.

### Packet 40: live HR and R-R

| Offset | Width / type | Meaning |
|---:|---|---|
| 10 | 4 / u32 | Unix-seconds timestamp |
| 14 | 2 / u16 | Additional time field; scale and epoch coupling unresolved |
| 16 | 1 / u8 | Heart rate, bpm |
| 17 | 1 / u8 | Declared interval count |
| 18 + 2i | 2 / u16 | R-R ticks, 1/1024 second |

Read at most the declared number of complete words before the CRC trailer. Omit
zero intervals and never consume an incomplete word. For positive ticks, integer
milliseconds are `(ticks * 1000 + 512) // 1024`. Use a wide enough intermediate
for multiplication. No packet-local quality flag or independent absolute timestamp
per interval is established. Do not reuse the unknown additional time field's
scale from an unrelated clock command.

### R18: biometric summary

The established summary shape is **124 bytes**, with CRC at 120. The following
measurement conventions include earlier interpretations; they are not all physical
sensor guarantees for every firmware version.

| Offset | Width / encoding | Field, scale and validity |
|---:|---|---|
| 9 | 1 / u8 | Layout 18 |
| 11 | 4 / u32 | Record index |
| 15 | 4 / u32 | Unix seconds; reject implausible dates according to the application's time-range policy |
| 22 | 1 / u8 | Heart rate, bpm; retain quality context from byte 36 |
| 23 | 1 / u8 | R-R count; the documented layout contains at most four complete positive words |
| 24 + 2i | 2 / u16 | Up to four R-R words; 1/1024 s, same rounded-ms conversion as packet 40 |
| 33 | 1 / u8 | Cardiac-adjacent flags, meanings unresolved |
| 36 | 1 / u8 | HR/R-R quality flags; bit 7 has an earlier validity interpretation not independently established here for the reference baseline |
| 37 | 1 / u8 | Alternate HR in bpm; an earlier convention treats byte-36 bit 7 as its validity signal; validity for the reference baseline remains unresolved |
| 38 | 2 / u16 | R-R-adjacent packed word; unit/meaning unresolved |
| 41 | 4 / f32 | Dynamic, gravity-removed acceleration; g in the retained convention, accept finite values in [0,8] |
| 45, 49, 53 | 4 each / f32 | Gravity x/y/z, g; no established per-axis sentinel |
| 57 | 2 / u16 | Selected cumulative step/motion counter; selection detailed below |
| 59 | 2 / u16 | Cadence-like raw value, supplied from u8; high byte is zero |
| 61 | 2 / u16 | Hardware counter when software override is active, otherwise zero |
| 63 | 1 / u8 | Activity: 0 still, 1 walk, 2 run; other input classes become FF and must not be surfaced as those three classes |
| 64 | 1 / u8 | 10 hex when software counter override is active, otherwise zero |
| 69, 71 | 2 each / i16 | Auxiliary thermal channels, retained raw/10 °C interpretation; accept 0–60 °C |
| 73 | 2 / u16 | Skin-temperature convention, raw/100 °C; accept 5–45 °C |
| 75, 77, 79 | 2 each / u16 | Raw status words; no sleep-stage meaning established |
| 81 | 1 / bitfield | Four two-bit groups; detailed below |
| 82 | 1 / u8 | Sleep-adjacent raw byte; 80/A0 hex are candidate sentinels, not established physiological labels |
| 106, 107 | 1 each / u8 | Optical baseline-like raw values; per-byte optical identity provisional |
| 108, 109 | 1 each / u8 | Optical amplitude-like values; simultaneous 128 has been treated as a signal-quality sentinel |
| 113 | 4 / f32 | Unknown finite float; zero may mean unset, no established quantity |
| 120 | 4 / u32 | CRC32 over `[8,120)` |

The temperature encodings quantize the corresponding inputs with
factors ten and one hundred before integer conversion. This does not establish
which physical sensors supply the auxiliary channels. Keep application plausibility
ranges distinct from manufacturer-defined validity flags.

Byte-36 bits 4 and 5 include [source-selection and hold-state contributions](#r18-quality-adjacent-source-selection-bits). Do not infer alternate HR validity from
its numerical agreement with the main HR. The optical byte pairs are each encoded
from a byte-reversed halfword; that structure does not independently identify two
wavelengths. The baseline pair has an off-wrist zero interpretation, while the
amplitude pair's simultaneous 128 is a quality interpretation, not an SpO₂ value.

The larger tail remains raw. Bytes 83–103, 105, 110–112 and 117–119 have a
zero-filled convention in the available decoder coverage, and byte 104 a marker;
these are not universal sentinels. Do not reject a future record solely because a
previously constant tail changes.

#### Step source, cadence and activity

For this layout, the normal value at 57 is the hardware pedometer count. When software
counter override is enabled, 57 carries the software count, 61 retains the hardware
count and 64 becomes `0x10`. With no override, bytes 61–62 and 64 are zero. This
allows a client to retain both sources and avoid turning a change of source into a
false step delta.

The hardware tuple contains count, cadence-like byte and activity class. A
cadence value is **not a documented steps-per-minute conversion**; even a
consistent relationship to speed is not guaranteed. Preserve it raw. Neither
counter is established as equal to the official app's aggregated step count.
Rollover, reset and day boundaries remain unresolved; a timestamped counter must
not be advertised as a midnight-reset daily total. Byte 63 also has older
quality-oriented naming, so retain the raw byte with the selected activity label.

#### Motion/rest state and override

Byte 81 packs four independent two-bit values:

| Bits | Interpretation |
|---|---|
| 0–1 | On-wrist/validity-related value; raw vocabulary incomplete |
| 2–3 | Wake-quality-related value; raw vocabulary incomplete |
| 4–5 | Band motion/rest state |
| 6–7 | Additional state value; raw vocabulary unresolved |

For the ordinary baseline motion/rest path, `(byte81 >> 4) & 3` has the labels
**0 WAKE, 1 STILL, 2 SLEEP, 3 UP**. An override can retain a selected state instead
of updating it from the motion/rest classifier. The override's operating modes
are unresolved and the packet does not provide an established way to identify
all of them. Preserve the raw state, and present labels as band state rather than
an unconditional physiological measurement.

Nonzero state labels do not have complete runtime validation for this version.
SLEEP is not a mapping to light, deep or REM sleep, and is unrelated to processor
power-saving sleep. Byte 75 is not a deep-sleep indicator. The record supplies
neither a validated hypnogram nor an established production SpO₂ value.

### R18 quality-adjacent source-selection bits

The packed byte at frame 36 combines several independently observable fields.
Bit 4 marks active alternate-source selection. Bit 5 is set with bit 4 and can
remain set without bit 4 for up to nine further qualifying updates. The low four
bits carry a separate raw field, and bit 6 can be set independently; these values
can coexist. Preserve the complete raw byte.

These relationships do not establish a single quality enum, a complete validity
mask, clinical quality, bit-7 validity or a sufficient rule for accepting or
discarding a reading. Bit 5 is not an instantaneous source-selection indicator,
and update counts are not a wall-clock duration. A separate byte in the flags word
is zero for nonpositive finite input; positive values are limited to 30–210 and
rounded to the nearest integer, with positive half values rounded upward. Its units
remain unresolved and the range is not proof of heart-rate or quality semantics.

### R20: optical blocks

R20 is exactly **2,140 bytes**: a 26-byte header, five 422-byte blocks, then CRC32
at 2136 covering `[8,2136)`. It is not a checksum over only the blocks. Layout is
20 at byte 9; byte 10 is a marker, often `0x81`, not an established validity flag.
Record index/time use the common offsets 11/15.

Block bases `B` are **26, 448, 870, 1292, 1714**. Each block has a shared 21-byte
configuration header, two 200-byte sample regions and one reserved byte.

| Relative offset | Width / encoding | Meaning |
|---:|---|---|
| B+0 | 1 / u8 | Valid sample count **per slot**, 0–50 |
| B+1 | 1 / u8 | Source/emitter selector A; enum unresolved |
| B+2 | 2 / u16 | Drive/configuration A; physical unit unresolved |
| B+4 | 1 / u8 | Source/emitter selector B; enum unresolved |
| B+5 | 2 / u16 | Drive/configuration B; physical unit unresolved |
| B+7 | 1 / u8 | Detector routing A; enum unresolved |
| B+8 | 4 / u32 | Range A; physical unit unresolved |
| B+12 | 2 / i16 | Offset A; physical unit unresolved |
| B+14 | 1 / u8 | Detector routing B; enum unresolved |
| B+15 | 4 / u32 | Range B; physical unit unresolved |
| B+19 | 2 / i16 | Offset B; physical unit unresolved |
| B+21+4i | 4 / i32 | Slot A sample i, raw optical count |
| B+221+4i | 4 / i32 | Slot B sample i, raw optical count |
| B+421 | 1 / u8 | Reserved; zero in established historical convention |

All **50 positions in each of ten columns** occupy the frame even if the count
is smaller. Decode only `0 <= i < count`; count zero means no populated readings,
not fifty measured zeros. Reject counts above 50. The signed containers cover an
optical domain convention of −524288 through 524287, including the positive rail;
negative values must not become large unsigned measurements. The encoding alone
does not provide a calibrated optical unit.

For a constructed count-only example, block counts `[12,0,0,12,12]` represent
`3 × 2 × 12 = 72` populated values, despite 500 available positions. This example
contains no measured samples.

#### Configuration and conditional routing

The fourth block (zero-based block 3) has an alternate source pair. When the
primary source has no samples, a fallback source supplies that block, including
its count, and marker bit 0 is set. Do not interpret the fourth block as a permanently
fixed optical channel or its marker as a quality verdict.

In observed configuration metadata, drive/configuration values are quantized as
`(((input + 5) mod 2^32) // 10) mod 2^16`: the addition wraps as u32 before
unsigned division, then the result is stored as u16. This rounding/truncation does not establish milliamps or another
upstream physical unit. Historical conventions include ranges 16/32 and offsets
in multiples of 800. In the first block, accepted offset
settings 0/8000/16000/24000 produce signed metadata values 0/800/1600/2400.
This relationship does not establish every block's complete routing or a physical unit.
A zero-drive fourth block has served as a dark control; that
pattern is not a guarantee under every routing configuration. The two slots share
one header and remain **A/B**, not red/infrared/green. Detector geometry, wavelength,
source enums and calibrated drive/range/offset units remain unresolved.

### R21: six-axis IMU

R21 and the [dedicated IMU streams](#dedicated-imu-stream-types-51-and-52) use the
[ICM-45686 IMU](PROTOCOL_WHOOP5.md#whoop5-icm-45686). R21 has fixed length
**1,244 bytes**, with CRC32 at 1240 covering `[8,1240)`.
Layout is 21 at 9 and the marker at 10 commonly `0x80`; neither marker alone proves
measurement quality. Sequence and Unix-seconds base time are at 11 and 15.

| Offset | Width / encoding | Meaning |
|---:|---|---|
| 21, 22 | u8 each | Configuration values 4 and 100 |
| 24 | u16 | Accelerometer valid count, capacity 100 |
| 26, 27 | u8 each | Configuration byte 3, followed by an uninterpreted configuration byte |
| 28+2i | i16 | Acceleration x |
| 228+2i | i16 | Acceleration y |
| 428+2i | i16 | Acceleration z |
| 628 | u8 | Configuration value 100 |
| 630 | u16 | Gyroscope valid count, capacity 100 |
| 632, 633 | u8 each | Configuration byte 5, followed by an uninterpreted configuration byte |
| 640+2i | i16 | Gyroscope x |
| 840+2i | i16 | Gyroscope y |
| 1040+2i | i16 | Gyroscope z |

The six columns each reserve 200 bytes. Counts are independent u8 values widened
to u16, so their high bytes are zero in this version. All column capacity remains
present when counts are smaller. Bounds-check each count against 100 and consume
only that group's valid values. Do not require accelerometer and gyroscope counts
to be equal; the record capacity permits independent counts up to 100.

An earlier IMU convention scales acceleration as `raw / 4096` g,
gyroscope as `raw * 2000 / 32768` degrees/s, and places sample i at
`base_time + i/100` for a 100 Hz, one-second buffer. The fixed layout does not
independently establish those physical settings or a timing rule for
partly populated buffers. Configuration bytes above do not yet form a proven
range enum. Preserve raw values/counts when active scaling or timing is unknown.

No per-sample quality bit, axis-to-strap/body geometry, counter rollover, timestamp
jitter rule or active range configuration is established here. Structurally valid
six-axis data is not proof of a body orientation.

### Inertial record timestamps

In this version, layout 21 is carried by live packet 43 and historical
packet 47. Its little-endian timestamp has Unix seconds at frame offset 15 and a
u16 fraction at offset 19. Combine them as `seconds + fraction / 32768`.

The fraction represents a clock reading quantized to hundredths of a second.
Its finer binary representation does not imply finer acquisition precision.
For valid clock readings, fractional words range from 0 to 32440. This statement
covers layout 21; it does not establish timing jitter, clock validity or the
fractional scale of other layouts.

No matching strap output is established for packets 51/52. The [documented
count/span layout](#dedicated-imu-stream-types-51-and-52) below provides separate
bounds; do not decode these packets as layout 21.

Command 106 changes requested live motion state, which can take effect after the
acknowledgement. Successful application enables packet 43/layout 21 output. Failure
or rapid opposite requests can leave requested state and observed output different;
see [collection coordination](PROTOCOL_CONFIGURATION.md#collection-and-live-stream-coordination).
No packet 51/52 output is established by this route.

### R22 inner version

On the wire, the R22 inner version is byte 21 of the complete frame, followed
by subversion at byte 22. The frame remains 188 bytes across the selected version
paths. These bytes are distinct from layout 22 at byte 9. In format 1, bytes 6–7
contain the CRC16-Modbus value over bytes 0–5; there is no format tag there. Format 2
instead carries an unnamed marker with value 3 at byte 34. [Version preferences](PROTOCOL_CONFIGURATION.md#r22-version-preferences)
can fall back or use queued data. Preserve unknown versions; neither a preference
name nor the fixed wrapper size supplies the full body schema. No packet 51/52
layout follows from this R22 selection path.

### R22 version 9 queued channels and sample encoding

For this layout, the v9 body contains a channel identifier at complete-frame byte 141.
Current output uses identifier 0; retained records use identifiers 1..5.
These identifiers distinguish numeric input channels; their physical mapping
and units remain unresolved.

When the first 32-bit words of numeric channels 1 and 2 are both zero, the strap
can replay retained output; otherwise it emits current output.
These are value tests, not valid-count or complete-sensor-availability tests.
A replayed body does not prove that all current sensor inputs were absent.

During replay, one retained body is emitted at a time in channel order
**1,2,4,5,3**, exhausting each channel before the next. A retained body is not
necessarily a fresh reading. When no retained record is available, the strap emits
**version 4**, even when version 9 has priority in the configuration. Always decode
the actual inner version at byte 21.

The emitted version and subversion are also interpreted together as a little-endian
16-bit number. Values above 9 become version 1,
subversion 0: 9/1 is normalized, whereas 0/0 is unchanged by this check. This is a
documented output rule, not a client whitelist or a rule for every R22 variant.

The strap keeps up to 60 queued records for each of the five channels. At capacity,
a new record replaces the last position while the first 59 remain; this is not a
rotating window of the most recent 60 records. Replay order is not a promise of
chronological order across channels. New current data does not restart an ongoing
replay. No maximum replay age or guaranteed number of delivered records is established.

For emitted version 9/subversion 0, offsets below are from the complete 188-byte
frame; the body starts at byte 21:

| Offset | Size | Meaning |
|---|---|---|
| 21 | 1 | Inner version 9 |
| 22 | 1 | Subversion 0 |
| 23 | 4 | Raw packed metadata; complete meaning unresolved |
| 27 | 2 | Raw packed metadata; complete meaning unresolved |
| 29 | 2 | Raw numeric field; meaning unresolved |
| 31 | 4 | Initial sample bit pattern |
| 35 | 98 | 49 little-endian signed 16-bit adjacent-sample differences |
| 133 | 4 | Raw metadata; meaning unresolved |
| 137 | 2 | Packed channel-selected metadata; [subfields](#r22-version-9-metadata-refinement) below |
| 139 | 1 | Raw 0/1 field; meaning/freshness unresolved |
| 140 | 1 | Zero in this documented layout |
| 141 | 1 | Numeric channel identifier 0..5 |
| 142 | 42 | Opaque tail; do not assume zero |

Adjacent subtraction first wraps to 32 bits and is interpreted as signed, then
clipped to `[-32768,32767]`. For example, difference bits `0xffffffff` mean -1 at
this stage, not a large positive jump. Cumulative signed deltas reconstruct
50 sample bit patterns modulo 2^32 only when no difference was clipped; clipping
is lossy. Preserve the initial 32-bit pattern. Neither the encoding nor its
channel identifier establishes calibrated units, physical signedness or sample
frequency. The 42-byte tail is outside the sample encoding.

Keep body bytes 121–162 opaque. Other body versions can place values within this
region, and a version 9 record does not guarantee that the tail is zero. Ignore
the tail when decoding version 9; do not use its
contents as a freshness marker or as extra sample values.

The R18 validity convention and v8 remain unresolved. The v9 addition does not
define packets 51/52; their [documented count/span layout](#dedicated-imu-stream-types-51-and-52) is separate.

### R22 version 9 metadata refinement

The little-endian word at complete-frame bytes 137..138 (body 116..117) is
**packed metadata**, not a scalar gain, amplitude or quality score. Preserve its
raw value alongside any extracted subfields.

In current observations, bits 0..1 identify metadata group 0, 1 or 2. Channel IDs
0/1/2 use group 0; IDs 3/4 use group 1; ID 5 uses group 2. Bit 2 is an additional state
contribution for group 2; bit 3 is zero in this output. The channel ID remains
at frame 141 (body 120), and these group numbers do not establish physical
wavelengths or electrode assignments.

When this metadata is available, bits 4..5 and 6..7 contain two separate
2-bit values and bits 8..11 contain a 4-bit value. Their individual meanings
remain unspecified. The upper nibble combines shifted source bytes; do not
assign four independent boolean meanings until those source values are defined.
When it is unavailable, the strap emits `group_tag | 0x0c00`.
**That pattern is not a unique availability indicator:** available metadata can
produce the same word. Do not reject a record solely because its metadata equals
that pattern.

Frame 139 (body 118) carries one raw flag bit as 0 or 1; its meaning remains
unspecified. This does not guarantee freshness on every record, and replayed v9
records carry their retained metadata. The 42-byte tail at frame 142..183 remains
opaque, with no universal zero guarantee.

### R26: compact optical window

R26 is exactly **88 bytes**, CRC at 84 covering `[8,84)`. It carries an optical
base value plus **24 adjacent deltas**, not 24 independent absolute readings.
A one-second interpretation has been used for the 25-sample window; physical
wavelength and calibrated sample units remain unresolved.

| Offset | Width / encoding | Meaning |
|---:|---|---|
| 9 | u8 | Layout 26 |
| 11 | u32 | Retained record index |
| 15 | u32 | Retained Unix-seconds timestamp |
| 19 | u16 | Additional timestamp word; tick scale unresolved |
| 21 | u16 | Burst counter; wraps modulo 65536 |
| 23 | u32 | Absolute first optical code, retained raw |
| 27+2i | i16, i=0…23 | Adjacent delta for sample i+1 |
| 75 | f32 | Summary motion value; physical conversion not established here |
| 79 | u16 | Summary status, raw |
| 81 | u8 | On-wrist-related summary value, raw |
| 82 | u8 | Signal-acceptance result; classification vocabulary unresolved |
| 83 | u8 | Zero padding in this version |
| 84 | u32 | CRC32 |

Decode using a sufficiently wide accumulator:

```text
sample[0] = base
sample[i+1] = sample[i] + delta[i]
```

Each transmitted delta is clipped to **[-32768,32767]**. Therefore this reconstructs
the transmitted approximation; it cannot recover a larger original adjacent jump.
For a constructed numerical example, an original +50000 difference can only be
represented as +32767, a loss of 17233 at that step. Later deltas do not inherently
repair that lost offset because each represents another adjacent difference.
This example is arithmetic, not a waveform capture.

The burst identifier is an actual two-byte field and increments when acquisition
enters a new burst; it is not the record index, a channel selector or a ring-slot
index. Zero remains possible after wrapping and is not established as an invalid
wire value. Byte 12 belongs to the record index and
must not be interpreted as a wavelength either.

A buffered window retains its own index/time while awaiting delivery, so receive
time or the current transport context must not replace its payload timestamp.
Neither footer acceptance nor the on-wrist-related byte provides an established
clinical quality vocabulary. This record does not identify simultaneous red and
infrared channels, and carries no established R-R interval field. Do not derive
SpO₂ from unassigned channels.

**Measured relationship between the two summary bytes.** On one real capture (firmware
50.41.1.0, 680 v26 records) bytes 81 and 82 were near-exact complements of each other:
byte 81 read 0 while byte 82 read 1 in 581 records, and byte 81 read 1 while byte 82 read 0
in 83, with only 14 records at (0,0) and 2 at (1,1) — 664/680, or 97.6%. So byte 82 is
**not** supported as an independent signal-quality reading by that corpus: it mirrors the
on-wrist-related byte inverted. Both also latched — the mixed values appear only in the
first four bursts of that capture, after which byte 81 held 0 and byte 82 held 1
throughout. One strap, one capture; recorded here so a future mapper does not treat byte
82 as quality data without re-checking.

### Dedicated IMU stream types 51 and 52

Packet types 51 and 52 share the following documented count/span layout. Valid
captured frames from a matching strap output remain unconfirmed. Do not substitute
the R10, R21 or R22 layout for these types.

In this layout, complete-frame offsets 24 and 26 contain little-endian unsigned
16-bit accelerometer and gyroscope counts, A and G. The planar arrays begin at:

| Array | Complete-frame byte offset |
| --- | --- |
| Accelerometer X | `28` |
| Accelerometer Y | `28 + 2*A` |
| Accelerometer Z | `28 + 4*A` |
| Gyroscope X | `28 + 6*A` |
| Gyroscope Y | `28 + 6*A + 2*G` |
| Gyroscope Z | `28 + 6*A + 4*G` |

The calculated span ends at `28 + 6*A + 6*G`. Validate the count header and all
calculated spans against the independently validated sample-data boundary,
excluding checksum/trailer bytes, before allocating or reading sample arrays. These offsets are relative to the complete
frame on the identified path, not to a stripped payload or every framing variant.
Sample signedness, physical scale, cadence, timestamp fields and normal count
limits remain unspecified. Preserve these packets as opaque when that frame
contract cannot be established.

### Implementation boundaries

Keep versioned shape checks separate from physical interpretation. Counts determine
valid values; fixed lengths determine buffer capacity. Do not treat padding,
inactive optical slots, placeholder fields or zero-filled tails as measurements.
Preserve unknown layouts and raw status alongside supported decoded streams so a
new firmware version cannot silently inherit an incompatible decoder.

These specifications do not make packet 51/52 equivalent to R21, turn ECG into
an optical record, or make band-state SLEEP an external sleep stage. They provide
decoding boundaries; remaining calibration, hardware and timing
uncertainties require version-specific validation before stronger user-facing claims.

### Constructed arithmetic checks

Run `python3 docs/protocol-examples/validate_examples.py` from the repository root.
The [standalone example](protocol-examples/validate_examples.py) checks clipped
R26 reconstruction and R-R conversion with invented values, alongside ECG field
checks. It does not validate captured records, CRC implementation, firmware
execution, physical calibration or application integration.
