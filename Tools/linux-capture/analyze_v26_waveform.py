#!/usr/bin/env python3
"""analyze_v26_waveform.py — characterise the WHOOP 5 type-47 *version-26* record.

The 5.0 historical store interleaves two type-47 record versions: v18 (124-byte per-second biometric
summary, decoded by `decodeWhoop5Historical`) and **v26** (88-byte high-rate buffer). This script tests
what the v26 buffer *is*, using the heart rate as internal ground truth — no external reference or app
export needed.

Method:
  - v26 records are 1/second (unix u32 LE @15, the same slot v18 uses), each carrying 24 LE-i16 samples
    at bytes [27:75] (NOT big-endian — the high byte is 0xFA..0xFF / 0x00..0x01, i.e. small signed
    values in LE order).
  - Concatenate consecutive-second runs → a continuous trace; detrend; autocorrelate.
  - If the dominant period equals the heart rate (from the same-timestamp v18 record), the buffer is a
    pulsatile **optical PPG** trace, not an IMU/motion stream (motion does not phase-lock to HR).

TWO STATISTICS, AND WHY BOTH ARE HERE. This script used to report ONE pooled autocorrelation summed
over every run, and to pick its "fundamental" from a hardcoded lag band of 12..16 (90..120 bpm). Both
are unsound when the heart rate MOVES, and the flaw is invisible on a capture whose HR sits still:

  - pooling ACFs from runs whose periods differ smears the peak, so the pooled answer describes an
    average heart rate that no single run had;
  - a 90..120 bpm band silently excludes most of a wide-ranging capture.

On a real 5.0 capture whose HR spanned 57..95 bpm, the pooled pass reported a peak at lag 22
(65.5 bpm) against a mean HR of 72.3 bpm and a hardcoded-band fundamental of 90.0 bpm, and printed
"inconclusive" — for a buffer whose PER-RUN period in fact tracks the per-run HR at r=+0.979 with a
median error of 1.3 bpm. So the pooled table is kept (it is informative about a stable capture) but the
verdict now comes from the per-run tracking test, which is the test AGENTS.md actually requires: a
derived physiological signal must be shown to track a VARYING input, not to match once.

SAMPLE RATE. The record alone does not establish 24 Hz (see docs/BLE_REVERSE_ENGINEERING.md). This
script therefore never assumes it silently: it DERIVES the rate from the runs themselves as
`HR * lag / 60` and reports the spread, so the 24 Hz constant is corroborated by data or contradicted
by it. On the capture above the derivation gives 23.91 Hz (median 23.90, sd 0.81) — 24 Hz fits about
three times better than 25.

Usage:  python3 analyze_v26_waveform.py [capture.json]   (default: capture_hist_ack.json)
"""

import json
import statistics
import struct
import sys
from collections import Counter

try:                                              # importable from elsewhere (unit tests) too
    from capture_io import configure_utf8_stdio
except ImportError:                               # pragma: no cover
    def configure_utf8_stdio():
        pass

WAVE_START, WAVE_END = 27, 75          # 24 LE-i16 samples per record
SAMPLE_RATE_HZ = 24                     # the working assumption, now DERIVED and reported below
HR_MIN, HR_MAX = 40.0, 180.0           # physiological band for the per-run peak search


def u32le(r, o):
    return r[o] | (r[o + 1] << 8) | (r[o + 2] << 16) | (r[o + 3] << 24)


def le_i16(r, a, b):
    return [struct.unpack("<h", r[i:i + 2])[0] for i in range(a, b - 1, 2)]


def detrend(x, w=12):
    """Subtract a centred moving average (≈1.7 beats wide) to remove DC/baseline wander."""
    out = []
    for i in range(len(x)):
        lo, hi = max(0, i - w), min(len(x), i + w + 1)
        out.append(x[i] - statistics.mean(x[lo:hi]))
    return out


def acf(x, lag):
    n = len(x) - lag
    if n <= 2:
        return 0.0
    m = statistics.mean(x)
    den = sum((xi - m) ** 2 for xi in x)
    return (sum((x[i] - m) * (x[i + lag] - m) for i in range(n)) / den) if den else 0.0


def consecutive_runs(ts, min_len=4):
    """Index runs of consecutive unix seconds -- PPG phase is only continuous within a run."""
    if not ts:
        return []
    runs, cur = [], [0]
    for i in range(1, len(ts)):
        if ts[i] - ts[i - 1] == 1:
            cur.append(i)
        else:
            runs.append(cur)
            cur = [i]
    runs.append(cur)
    return [r for r in runs if len(r) >= min_len]


def band_lags(sample_rate=SAMPLE_RATE_HZ):
    """The lag range a physiological heart rate can occupy at this sample rate."""
    return max(2, int(sample_rate * 60.0 / HR_MAX)), int(sample_rate * 60.0 / HR_MIN)


def peak_lag(sig, lo, hi):
    """Lag of maximum autocorrelation within [lo, hi], with a parabolic sub-lag refinement."""
    band = [(l, acf(sig, l)) for l in range(lo, hi + 1) if l < len(sig) // 2]
    if not band:
        return None, 0.0
    lag, val = max(band, key=lambda t: t[1])
    if val <= 0:
        return lag, val
    below = dict((l, v) for l, v in band)
    if lag - 1 in below and lag + 1 in below:
        y0, y1, y2 = below[lag - 1], val, below[lag + 1]
        den = y0 - 2 * y1 + y2
        if den != 0:
            shift = 0.5 * (y0 - y2) / den
            if abs(shift) <= 1:
                lag = lag + shift
    return lag, val


def pearson(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx <= 0 or syy <= 0:
        return None
    return sxy / (sxx * syy) ** 0.5


def run_signal(v26, run):
    sig = []
    for i in run:
        sig += le_i16(v26[i], WAVE_START, WAVE_END)
    return detrend(sig)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "capture_hist_ack.json"
    with open(path, encoding="utf-8") as f:
        frames = [bytes.fromhex(c["hex"]) for c in json.load(f)]
    v26 = [r for r in frames if len(r) == 88 and r[8] == 47 and r[9] == 26]
    v18 = [r for r in frames if len(r) == 124 and r[8] == 47 and r[9] == 18]
    if not v26:
        print("no v26 records in", path)
        return
    hr_at = {u32le(r, 15): r[22] for r in v18 if r[22]}
    v26.sort(key=lambda r: u32le(r, 15))
    ts = [u32le(r, 15) for r in v26]
    runs = consecutive_runs(ts, min_len=4)

    lags = range(8, 30)
    agg = {l: [] for l in lags}
    hrs = []
    for run in runs:
        sig = run_signal(v26, run)
        for l in lags:
            agg[l].append(acf(sig, l))
        hrs += [hr_at[ts[i]] for i in run if ts[i] in hr_at]

    print(f"{path}: {len(v26)} v26 records in {len(runs)} consecutive runs "
          f"({sum(len(r) for r in runs)} samples-of-records)")
    if not hrs:
        print("no co-timestamped v18 heart rate -- cannot test the buffer")
        return
    print(f"measured HR (v18, same timestamps): mean {statistics.mean(hrs):.1f} bpm "
          f"({min(hrs)}..{max(hrs)})\n")

    print("POOLED autocorrelation (informative when HR is stable; smeared when it is not)")
    print("lag  bpm@24Hz  mean-autocorr")
    for l in lags:
        a = statistics.mean(agg[l])
        print(f"{l:3d}  {SAMPLE_RATE_HZ * 60 / l:6.1f}   {a:+.3f} {'#' * int(max(0, a) * 40)}")

    lo, hi = band_lags()
    print(f"\nPER-RUN tracking test -- the verdict below rests on this, not on the pooled table")
    print(f"{'run@unix':>11} {'n':>3} {'meanHR':>7} {'peakLag':>8} {'bpm':>7} {'acf':>6} {'err':>6}")
    pairs, implied = [], []
    for run in runs:
        sig = run_signal(v26, run)
        lag, val = peak_lag(sig, lo, min(hi, len(sig) // 2 - 1))
        rhs = [hr_at[ts[i]] for i in run if ts[i] in hr_at]
        if lag is None or len(rhs) < 3:
            continue
        mean_hr = statistics.mean(rhs)
        bpm = SAMPLE_RATE_HZ * 60 / lag
        pairs.append((mean_hr, bpm))
        implied.append(mean_hr * lag / 60.0)
        print(f"{ts[run[0]]:>11} {len(run):>3} {mean_hr:>7.1f} {lag:>8.1f} {bpm:>7.1f} "
              f"{val:>+6.2f} {abs(bpm - mean_hr):>6.1f}")
    if not pairs:
        print("  no run carried both a period and a heart rate")
        return

    rate = statistics.median(implied)
    print(f"\nDERIVED sample rate = HR * lag / 60 over {len(implied)} runs: "
          f"median {rate:.2f} Hz, mean {statistics.mean(implied):.2f} Hz, "
          f"sd {statistics.pstdev(implied):.2f}")
    print(f"  (the {SAMPLE_RATE_HZ} Hz working assumption is corroborated by the data, not assumed)")
    if abs(rate - SAMPLE_RATE_HZ) > 1.0:
        print(f"  !! the derived rate differs from the {SAMPLE_RATE_HZ} Hz constant -- the bpm column "
              "above is scaled by an assumption this capture does not support")

    r = pearson([p[0] for p in pairs], [p[1] for p in pairs])
    errs = [abs(b - a) for a, b in pairs]
    within5 = sum(1 for e in errs if e <= 5)
    print(f"\ntracking across {len(pairs)} runs: HR {min(p[0] for p in pairs):.1f}"
          f"..{max(p[0] for p in pairs):.1f} bpm, derived {min(p[1] for p in pairs):.1f}"
          f"..{max(p[1] for p in pairs):.1f} bpm")
    print(f"  pearson r = " + ("n/a" if r is None else f"{r:+.3f}")
          + f"   median |error| {sorted(errs)[len(errs) // 2]:.1f} bpm   within 5 bpm {within5}/{len(errs)}")
    if r is not None and r >= 0.5 and within5 >= len(errs) * 0.6:
        verdict = "PPG (tracks a VARYING heart rate)"
    elif r is not None and r >= 0.5:
        verdict = "PPG-like (period follows HR, large absolute scatter)"
    else:
        verdict = "inconclusive"
    print(f"verdict: v26 buffer is {verdict}")
    print("\nThis is evidence about the SIGNAL. It is not a calibration, not a wavelength label, and "
          "not an SpO2 value.")


if __name__ == "__main__":
    configure_utf8_stdio()
    main()
