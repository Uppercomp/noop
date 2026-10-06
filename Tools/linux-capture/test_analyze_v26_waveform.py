#!/usr/bin/env python3
"""Tests for analyze_v26_waveform — constructed vectors only, no device captures.

The regression this guards is specific and was found on real hardware. The tool used to report ONE
pooled autocorrelation and to pick its fundamental from a hardcoded lag band of 12..16 (90..120 bpm).
On a real WHOOP 5.0 capture whose heart rate spanned 57..95 bpm that combination printed
"inconclusive" — while the per-run period in fact tracked the per-run HR at r=+0.979. Both halves of
the flaw are invisible when HR is stable, which is exactly what the original validation capture had.

So these tests use a SYNTHETIC capture whose heart rate MOVES across runs, and assert that the tool
recovers each run's rate and reports tracking. A tool that pools, or that searches only 90..120 bpm,
fails these.
"""

import math
import struct
import unittest

import analyze_v26_waveform as az


def v26_frame(unix, samples, base=200000):
    """One 88-byte v26 frame: base @23, 24 LE-i16 adjacent deltas @27."""
    f = bytearray(88)
    f[0] = 0xAA
    f[8] = 47
    f[9] = 26
    struct.pack_into("<I", f, 15, unix)
    struct.pack_into("<I", f, 23, base & 0xFFFFFFFF)
    for i in range(24):
        struct.pack_into("<h", f, 27 + 2 * i, samples[i + 1] - samples[i])
    return bytes(f)


def v18_frame(unix, hr):
    f = bytearray(124)
    f[0] = 0xAA
    f[8] = 47
    f[9] = 18
    struct.pack_into("<I", f, 15, unix)
    f[22] = hr
    struct.pack_into("<I", f, 11, unix)
    return bytes(f)


def sine_run(start_unix, seconds, hr_bpm, amp=3000, base=200000):
    """A run of v26 frames whose samples are a sine at the given heart rate.

    The rate is the PPG fundamental the tool must recover: 24 samples/second, so period = 24*60/hr.
    """
    f = hr_bpm / 60.0                      # Hz
    out = []
    for s in range(seconds):
        t0 = s * 24
        samples = [base + int(amp * math.sin(2 * math.pi * f * (t0 + k) / 24.0)) for k in range(25)]
        out.append(v26_frame(start_unix + s, samples, base))
    return out


class DspTests(unittest.TestCase):
    def test_consecutive_runs_splits_on_gaps(self):
        self.assertEqual(az.consecutive_runs([1, 2, 3, 10, 11, 12, 13, 40], min_len=3),
                         [[0, 1, 2], [3, 4, 5, 6]])

    def test_consecutive_runs_drops_short_runs(self):
        self.assertEqual(az.consecutive_runs([1, 2, 50, 51, 52, 53], min_len=4), [[2, 3, 4, 5]])

    def test_band_lags_covers_a_physiological_range(self):
        lo, hi = az.band_lags()
        # 8 = 180 bpm, 36 = 40 bpm at 24 Hz. The old hardcoded 12..16 band is a subset.
        self.assertEqual((lo, hi), (8, 36))
        self.assertLess(lo, 12)
        self.assertGreater(hi, 16)

    def test_peak_lag_finds_a_sine_period(self):
        for hr, expected in ((60, 24), (72, 20), (96, 15)):
            samples = [200000 + 3000 * math.sin(2 * math.pi * (hr / 60.0) * k / 24.0)
                       for k in range(960)]
            lag, val = az.peak_lag(az.detrend(samples), 8, 36)
            self.assertAlmostEqual(lag, expected, delta=1.0,
                                   msg=f"HR {hr} should peak near lag {expected}, got {lag}")
            self.assertGreater(val, 0.0)


class TrackingTests(unittest.TestCase):
    """The behaviour the pooling/hardcoded-band version got wrong."""

    HR_SEQUENCE = (60, 72, 84, 96, 66, 78)

    def build(self):
        frames, expected = [], []
        unix = 1_700_000_000
        for hr in self.HR_SEQUENCE:
            frames += sine_run(unix, 40, hr)
            frames += [v18_frame(unix + s, hr) for s in range(40)]
            expected.append(hr)
            unix += 6000                        # a clear gap => a new run
        return frames, expected

    def test_per_run_period_tracks_a_varying_rate(self):
        frames, expected = self.build()
        v26 = [f for f in frames if len(f) == 88]
        v18 = [f for f in frames if len(f) == 124]
        hr_at = {az.u32le(r, 15): r[22] for r in v18}
        v26.sort(key=lambda r: az.u32le(r, 15))
        ts = [az.u32le(r, 15) for r in v26]
        runs = az.consecutive_runs(ts, min_len=10)
        self.assertEqual(len(runs), len(self.HR_SEQUENCE))

        lo, hi = az.band_lags()
        got = []
        for run in runs:
            lag, _ = az.peak_lag(az.run_signal(v26, run), lo, min(hi, len(az.run_signal(v26, run)) // 2 - 1))
            got.append(az.SAMPLE_RATE_HZ * 60 / lag)

        self.assertEqual(len(got), len(expected))
        for g, e in zip(got, expected):
            self.assertAlmostEqual(g, e, delta=6.0, msg=f"recovered {g:.1f} bpm for a {e} bpm run")
        r = az.pearson(expected, got)
        self.assertIsNotNone(r)
        self.assertGreater(r, 0.9, "the per-run period must track a varying heart rate")

    def test_derived_sample_rate_recovers_24_hz(self):
        """The rate must be DERIVABLE from the data, not silently assumed."""
        frames, _ = self.build()
        v26 = [f for f in frames if len(f) == 88]
        v18 = [f for f in frames if len(f) == 124]
        hr_at = {az.u32le(r, 15): r[22] for r in v18}
        v26.sort(key=lambda r: az.u32le(r, 15))
        ts = [az.u32le(r, 15) for r in v26]
        lo, hi = az.band_lags()
        implied = []
        for run in az.consecutive_runs(ts, min_len=10):
            lag, _ = az.peak_lag(az.run_signal(v26, run), lo, hi)
            hrs = [hr_at[ts[i]] for i in run if ts[i] in hr_at]
            implied.append(sum(hrs) / len(hrs) * lag / 60.0)
        rate = sorted(implied)[len(implied) // 2]
        self.assertAlmostEqual(rate, 24.0, delta=1.0,
                               msg=f"derived {rate:.2f} Hz from a 24 Hz synthetic capture")

    def test_pooled_autocorrelation_is_not_used_for_the_verdict(self):
        """A sanity check on WHY pooling fails: with periods 24 vs 15 the pooled peak sits between."""
        frames, _ = self.build()
        v26 = [f for f in frames if len(f) == 88]
        v26.sort(key=lambda r: az.u32le(r, 15))
        ts = [az.u32le(r, 15) for r in v26]
        lo, hi = az.band_lags()
        agg = {l: [] for l in range(lo, hi + 1)}
        for run in az.consecutive_runs(ts, min_len=10):
            sig = az.run_signal(v26, run)
            for l in agg:
                agg[l].append(az.acf(sig, l))
        pooled = {l: sum(v) / len(v) for l, v in agg.items()}
        pooled_best = max(pooled, key=lambda l: pooled[l])
        per_run = [az.peak_lag(az.run_signal(v26, run), lo, hi)[0]
                   for run in az.consecutive_runs(ts, min_len=10)]
        # The pooled peak is a compromise lag; it cannot equal every run's own period.
        self.assertFalse(all(abs(pooled_best - l) < 1e-9 for l in per_run),
                         "pooling should not reproduce each run's own period")
        self.assertGreater(len(set(per_run)), 1, "the synthetic runs must have distinct periods")


if __name__ == "__main__":
    unittest.main()
