"""
Decompose the residual's variance into within-record and between-record
components at several window lengths.

If within << between, the residual is a record-level quantity.
If within ≈ between, it's genuinely a window-level quantity.
"""
import os
import numpy as np
from scipy.signal import find_peaks, butter, filtfilt

RIEMANN_16 = np.array([
    14.134725, 21.022040, 25.010858, 30.424876,
    32.935062, 37.586178, 40.918719, 48.005151,
    49.773832, 52.970321, 56.446248, 59.347044,
    60.831779, 65.112544, 67.079811, 69.546402,
])
OMEGA = (RIEMANN_16 - RIEMANN_16.mean()) / RIEMANN_16.std(ddof=0)

def kuramoto_csi(rr_seconds, K=2.0, dt=0.05):
    rr = np.asarray(rr_seconds, dtype=float)
    if rr.size < 5: return None
    R_bar = float(rr.mean())
    if R_bar <= 0: return None
    N = 16; theta = np.zeros(N)
    phi = np.cumsum(2.0 * np.pi * R_bar / rr)
    r_trace = []
    for k in range(rr.size):
        theta[0] = phi[k] % (2.0 * np.pi)
        for _ in range(max(1, min(200, int(rr[k] / dt)))):
            diff = theta[None, :] - theta[:, None]
            coupling = np.sin(diff).sum(axis=1) / N
            theta = theta + dt * (OMEGA + K * coupling)
            r_trace.append(np.abs(np.mean(np.exp(1j * theta))))
    return float(np.mean(r_trace))

def detect_rpeaks(signal, fs):
    b, a = butter(2, [5/(fs/2), 15/(fs/2)], btype="band")
    y = filtfilt(b, a, signal); y = y*y
    w = max(1, int(0.150*fs))
    y = np.convolve(y, np.ones(w)/w, mode="same")
    thr = 0.35 * np.percentile(y, 99)
    peaks, _ = find_peaks(y, height=thr, distance=int(0.25*fs))
    return peaks

def read_wfdb(base):
    hea = open(base + ".hea").read().splitlines()
    first = hea[0].split()
    fs_str = first[2]
    fs = float(fs_str.split("/")[0]) / float(fs_str.split("/")[1]) \
         if "/" in fs_str else float(fs_str)
    n_sig = int(first[1])
    sig_line = hea[1].split()
    fmt = int(sig_line[1])
    gain = 200.0; baseline = 0
    if len(sig_line) >= 3:
        g = sig_line[2]
        if "(" in g:
            gain = float(g.split("(")[0])
            baseline = int(g.split("(")[1].split(")")[0])
        else:
            try: gain = float(g)
            except: pass
    if len(sig_line) >= 5:
        try: baseline = int(sig_line[4])
        except: pass
    raw = np.fromfile(base + ".dat", dtype=np.uint8)
    if fmt == 16:
        n = raw.size // 2
        samples = raw[:n*2].view(np.int16).astype(float)[::n_sig]
    elif fmt == 212:
        n_pairs = raw.size // 3
        samples = np.zeros(n_pairs * 2, dtype=np.int16)
        for i in range(n_pairs):
            b0 = int(raw[3*i]); b1 = int(raw[3*i+1]); b2 = int(raw[3*i+2])
            s1 = b0 | ((b1 & 0x0F) << 8)
            s2 = b2 | ((b1 & 0xF0) << 4)
            if s1 & 0x800: s1 -= 0x1000
            if s2 & 0x800: s2 -= 0x1000
            samples[2*i] = s1; samples[2*i+1] = s2
        samples = samples[::n_sig].astype(float)
    else:
        raise ValueError(f"fmt {fmt}")
    return (samples - baseline) / gain, fs

SINUS = "/home/americosimoes/physionet/sinus-10min"
AF    = "/home/americosimoes/physionet/af-10min"

sinus_recs = sorted(f[:-4] for f in os.listdir(SINUS) if f.endswith(".hea"))
af_recs    = sorted(f[:-4] for f in os.listdir(AF)    if f.endswith(".hea"))

loaded = {}
for folder, recs, lab in [(SINUS, sinus_recs, 0), (AF, af_recs, 1)]:
    for r in recs:
        try:
            sig, fs = read_wfdb(os.path.join(folder, r))
            loaded[r] = (sig, fs, lab)
        except Exception as e:
            print(f"{r}: LOAD FAIL {e}")

rpeaks_by_rec = {r: detect_rpeaks(sig, fs) for r, (sig, fs, lab) in loaded.items()}

for window_s in [60, 120, 300]:
    # Per record: list of residuals in each window.
    # We need the reference regression, so first collect (csi, cv, label, record) tuples
    # across all windows, fit, then decompose.
    rows = []  # (record, csi, cv, label)
    for r, (sig, fs, lab) in loaded.items():
        win = int(window_s * fs); hop = win // 2
        peaks = rpeaks_by_rec[r]
        if peaks.size < 5: continue
        for start in range(0, len(sig) - win + 1, hop):
            end = start + win
            local = peaks[(peaks >= start) & (peaks < end)]
            if local.size < 10: continue
            rr_s = np.diff(local) / fs
            if rr_s.size < 5: continue
            csi = kuramoto_csi(rr_s)
            if csi is None: continue
            m = rr_s.mean(); sd = rr_s.std(ddof=1)
            cv = sd / m if m > 1e-9 else np.nan
            rows.append((r, csi, cv, lab))
    if not rows: continue
    # Fit reference regression.
    cvs = np.array([x[2] for x in rows])
    csis = np.array([x[1] for x in rows])
    X = np.column_stack([np.ones_like(cvs), cvs])
    coef, *_ = np.linalg.lstsq(X, csis, rcond=None)
    resid = csis - X @ coef
    # Attach residuals to records.
    by_rec = {}
    for (r, _, _, lab), res in zip(rows, resid):
        by_rec.setdefault(r, []).append(res)
    # Within-record variance and between-record variance.
    rec_means = np.array([np.mean(v) for v in by_rec.values()])
    within_var = np.mean([np.var(v, ddof=1) for v in by_rec.values() if len(v) > 1])
    between_var = np.var(rec_means, ddof=1)
    print(f"W={window_s:4d}s  n_recs={len(by_rec):2d}  "
          f"within_var={within_var:.6f}  "
          f"between_var={between_var:.6f}  "
          f"ratio={within_var/between_var:.3f}")
