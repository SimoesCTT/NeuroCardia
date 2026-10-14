"""
Stream NeuroCardia corpora directly from PhysioNet and compute the
Kuramoto CSI + residual + P-wave features for each record.

Outputs CSV files under validation/reports/streamed/.

Usage:
    python3 scripts/stream_corpus.py [db1 db2 ...]

Databases:
    nsrdb   MIT-BIH Normal Sinus Rhythm (18 records, 24 h, 128 Hz)
    afdb    MIT-BIH Atrial Fibrillation (25 records, 10 h, 250 Hz)
    mitdb   MIT-BIH Arrhythmia (48 records, 30 min, 360 Hz)
    ltafdb  Long-Term AF (84 records, 24-25 h, 128 Hz)

Default: nsrdb afdb.
"""
import sys
import os
import time
import csv
import numpy as np
import wfdb
from scipy.signal import find_peaks, butter, filtfilt

# ---------------------------------------------------------------------
# Kuramoto engine (numpy port of the Kotlin KuramotoNetwork)
# ---------------------------------------------------------------------

RIEMANN_16 = np.array([
    14.134725, 21.022040, 25.010858, 30.424876,
    32.935062, 37.586178, 40.918719, 48.005151,
    49.773832, 52.970321, 56.446248, 59.347044,
    60.831779, 65.112544, 67.079811, 69.546402,
])
OMEGA = (RIEMANN_16 - RIEMANN_16.mean()) / RIEMANN_16.std(ddof=0)


def kuramoto_csi(rr_seconds, K=2.0, dt=0.05):
    """Return the CSI mean over the trajectory for a given RR series."""
    rr = np.asarray(rr_seconds, dtype=float)
    if rr.size < 5:
        return None
    R_bar = float(rr.mean())
    if R_bar <= 0:
        return None

    N = 16
    theta = np.zeros(N)
    phi = np.cumsum(2.0 * np.pi * R_bar / rr)

    r_trace = []
    for k in range(rr.size):
        theta[0] = phi[k] % (2.0 * np.pi)
        n_steps = max(1, min(200, int(rr[k] / dt)))
        for _ in range(n_steps):
            diff = theta[None, :] - theta[:, None]
            coupling = np.sin(diff).sum(axis=1) / N
            theta = theta + dt * (OMEGA + K * coupling)
            r_trace.append(np.abs(np.mean(np.exp(1j * theta))))

    return float(np.mean(r_trace))


# ---------------------------------------------------------------------
# R-peak detection (Pan-Tompkins-like, sufficient for streaming)
# ---------------------------------------------------------------------

def detect_rpeaks(signal, fs):
    """Bandpass 5-15 Hz, square, 150 ms moving average, adaptive peak.
    Returns sample indices of R-peaks."""
    nyq = fs / 2.0
    b, a = butter(2, [5.0 / nyq, 15.0 / nyq], btype="band")
    y = filtfilt(b, a, signal)
    y = y * y
    w = max(1, int(0.150 * fs))
    kernel = np.ones(w) / w
    y = np.convolve(y, kernel, mode="same")

    # Threshold at a fraction of the 99th percentile, refractory 250 ms.
    thr = 0.35 * np.percentile(y, 99)
    min_dist = max(1, int(0.25 * fs))
    peaks, _ = find_peaks(y, height=thr, distance=min_dist)
    return peaks


def rr_series(signal, fs):
    peaks = detect_rpeaks(signal, fs)
    if peaks.size < 3:
        return np.array([]), peaks
    return np.diff(peaks) / fs, peaks


# ---------------------------------------------------------------------
# P-wave features (matching PWaveFeatures.kt)
# ---------------------------------------------------------------------

def bandpass(x, fs, low, high):
    nyq = fs / 2.0
    b, a = butter(2, [low / nyq, high / nyq], btype="band")
    return filtfilt(b, a, x)


def pwave_energy(signal, fs, rpeaks):
    if rpeaks.size < 1:
        return np.nan
    f = bandpass(signal, fs, 0.5, 15.0)
    pre = int(0.200 * fs); post = int(0.060 * fs)
    acc = []; cnt = 0
    for r in rpeaks:
        s = max(0, r - pre); e = max(s + 1, r - post)
        if e > f.size: continue
        acc.append(np.abs(f[s:e]).mean()); cnt += 1
    return float(np.mean(acc)) if cnt else np.nan


def pwave_pr_var(signal, fs, rpeaks):
    if rpeaks.size < 5:
        return np.nan
    f = bandpass(signal, fs, 0.5, 15.0)
    pre = int(0.200 * fs); post = int(0.060 * fs)
    ivs = []
    for r in rpeaks:
        s = max(0, r - pre); e = max(s + 1, r - post)
        if e > f.size: continue
        seg = np.abs(f[s:e])
        if seg.size == 0: continue
        pk = int(np.argmax(seg))
        ivs.append((r - (s + pk)) / fs * 1000.0)
    if len(ivs) < 5:
        return np.nan
    return float(np.std(ivs, ddof=1))


def pwave_fib_amp(signal, fs, rpeaks):
    if rpeaks.size < 5:
        return np.nan
    f = bandpass(signal, fs, 4.0, 10.0)
    tqs = int(0.200 * fs); tqe = int(0.350 * fs)
    vals = []
    for i in range(rpeaks.size - 1):
        r = rpeaks[i]; nr = rpeaks[i + 1]
        s = min(r + tqs, nr - 1); e = min(r + tqe, nr - 1)
        if e <= s: continue
        vals.append(np.sqrt((f[s:e] ** 2).mean()))
    return float(np.mean(vals)) if vals else np.nan


# ---------------------------------------------------------------------
# Per-record computation
# ---------------------------------------------------------------------

def compute_record(signal, fs, label, name, K=2.0):
    """Return a dict of features for one record."""
    rr_s, rpeaks = rr_series(signal, fs)
    if rr_s.size < 20:
        return None

    csi = kuramoto_csi(rr_s, K=K)
    if csi is None:
        return None

    mean = rr_s.mean()
    sd = rr_s.std(ddof=1)
    rr_cv = sd / mean if mean > 1e-9 else np.nan

    # Poincaré sd1/sd2.
    if rr_s.size > 2:
        d = np.diff(rr_s)
        sd1 = np.sqrt(0.5) * d.std(ddof=1)
        sd2 = np.sqrt(max(0.0, 2.0 * sd**2 - 0.5 * d.std(ddof=1)**2))
        sd1_sd2 = sd1 / sd2 if sd2 > 1e-9 else np.nan
    else:
        sd1_sd2 = np.nan

    pwe = pwave_energy(signal, fs, rpeaks)
    pwp = pwave_pr_var(signal, fs, rpeaks)
    pwf = pwave_fib_amp(signal, fs, rpeaks)

    return dict(
        name=name, label=label, n_beats=int(rpeaks.size),
        rr_cv=float(rr_cv), csi_mean=float(csi),
        sd1_sd2=float(sd1_sd2),
        pwave_energy=float(pwe),
        pwave_pr_var=float(pwp),
        pwave_fib_amp=float(pwf),
    )


# ---------------------------------------------------------------------
# Database handlers
# ---------------------------------------------------------------------

def stream_nsrdb(out_dir):
    """All 18 records of NSRDB, first 10 minutes of each."""
    print("=== nsrdb ===")
    recs = wfdb.get_record_list("nsrdb")
    print(f"  {len(recs)} records")
    rows = []
    for i, name in enumerate(recs):
        t0 = time.time()
        try:
            # First 10 min at 128 Hz = 76800 samples.
            sig, fields = wfdb.rdsamp(name, pn_dir="nsrdb",
                                      sampfrom=0, sampto=10 * 60 * 128)
        except Exception as e:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: fetch failed: {e}")
            continue
        row = compute_record(sig[:, 0], fields["fs"], label=0, name=name)
        if row is None:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: no R-peaks")
            continue
        row["database"] = "nsrdb"
        rows.append(row)
        print(f"  [{i+1:2d}/{len(recs)}] {name}: "
              f"n={row['n_beats']} csi={row['csi_mean']:.4f} "
              f"cv={row['rr_cv']:.4f} ({time.time()-t0:.1f}s)")
    return rows


def stream_afdb(out_dir):
    """All 23 records of AFDB, first 10 minutes of each."""
    print("=== afdb ===")
    recs = wfdb.get_record_list("afdb")
    print(f"  {len(recs)} records")
    rows = []
    for i, name in enumerate(recs):
        t0 = time.time()
        try:
            # 10 min at 250 Hz = 150000 samples.
            sig, fields = wfdb.rdsamp(name, pn_dir="afdb",
                                      sampfrom=0, sampto=10 * 60 * 250)
        except Exception as e:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: fetch failed: {e}")
            continue
        row = compute_record(sig[:, 0], fields["fs"], label=1, name=name)
        if row is None:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: no R-peaks")
            continue
        row["database"] = "afdb"
        rows.append(row)
        print(f"  [{i+1:2d}/{len(recs)}] {name}: "
              f"n={row['n_beats']} csi={row['csi_mean']:.4f} "
              f"cv={row['rr_cv']:.4f} ({time.time()-t0:.1f}s)")
    return rows


def stream_mitdb(out_dir):
    """All 48 records of MIT-BIH Arrhythmia, full 30 min."""
    print("=== mitdb ===")
    recs = wfdb.get_record_list("mitdb")
    print(f"  {len(recs)} records")
    rows = []
    for i, name in enumerate(recs):
        t0 = time.time()
        try:
            sig, fields = wfdb.rdsamp(name, pn_dir="mitdb")
        except Exception as e:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: fetch failed: {e}")
            continue
        # Label from header comments is unreliable; use 0 for now and
        # we can map later. mitdb has mixed rhythms.
        row = compute_record(sig[:, 0], fields["fs"], label=-1, name=name)
        if row is None:
            print(f"  [{i+1:2d}/{len(recs)}] {name}: no R-peaks")
            continue
        row["database"] = "mitdb"
        rows.append(row)
        print(f"  [{i+1:2d}/{len(recs)}] {name}: "
              f"n={row['n_beats']} csi={row['csi_mean']:.4f} "
              f"cv={row['rr_cv']:.4f} ({time.time()-t0:.1f}s)")
    return rows


def stream_ltafdb(out_dir):
    """First 20 records of Long-Term AF (paroxysmal, transitions)."""
    print("=== ltafdb (first 20) ===")
    recs = wfdb.get_record_list("ltafdb")
    print(f"  {len(recs)} records available; fetching first 20")
    rows = []
    for i, name in enumerate(recs[:20]):
        t0 = time.time()
        try:
            # First 30 min at 128 Hz = 230400 samples.
            sig, fields = wfdb.rdsamp(name, pn_dir="ltafdb",
                                      sampfrom=0, sampto=30 * 60 * 128)
        except Exception as e:
            print(f"  [{i+1:2d}/20] {name}: fetch failed: {e}")
            continue
        row = compute_record(sig[:, 0], fields["fs"], label=-1, name=name)
        if row is None:
            print(f"  [{i+1:2d}/20] {name}: no R-peaks")
            continue
        row["database"] = "ltafdb"
        rows.append(row)
        print(f"  [{i+1:2d}/20] {name}: "
              f"n={row['n_beats']} csi={row['csi_mean']:.4f} "
              f"cv={row['rr_cv']:.4f} ({time.time()-t0:.1f}s)")
    return rows


# ---------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------

def write_csv(rows, path):
    if not rows:
        return
    fields = ["database", "name", "label", "n_beats", "rr_cv", "csi_mean",
              "sd1_sd2", "pwave_energy", "pwave_pr_var", "pwave_fib_amp"]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fields})


def main():
    out_dir = os.path.join("validation", "reports", "streamed")
    os.makedirs(out_dir, exist_ok=True)

    dbs = sys.argv[1:] or ["nsrdb", "afdb"]
    all_rows = []
    for db in dbs:
        if db == "nsrdb":
            rows = stream_nsrdb(out_dir)
        elif db == "afdb":
            rows = stream_afdb(out_dir)
        elif db == "mitdb":
            rows = stream_mitdb(out_dir)
        elif db == "ltafdb":
            rows = stream_ltafdb(out_dir)
        else:
            print(f"unknown db: {db}")
            continue
        all_rows.extend(rows)
        out = os.path.join(out_dir, f"{db}.csv")
        write_csv(rows, out)
        print(f"  -> wrote {out} ({len(rows)} records)")
        print()

    combined = os.path.join(out_dir, "combined.csv")
    write_csv(all_rows, combined)
    print(f"-> wrote {combined} ({len(all_rows)} records total)")


if __name__ == "__main__":
    main()
