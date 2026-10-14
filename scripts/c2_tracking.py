"""Within-record correlation of the residual with P-wave features."""
import os
import numpy as np
from scipy.signal import find_peaks, butter, filtfilt
from collections import defaultdict

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

def bandpass(x, fs, low, high):
    b, a = butter(2, [low/(fs/2), high/(fs/2)], btype="band")
    return filtfilt(b, a, x)

def pwave_features(sig, fs, rpeaks):
    if rpeaks.size < 5: return (np.nan, np.nan, np.nan)
    f1 = bandpass(sig, fs, 0.5, 15.0)
    pre = int(0.200*fs); post = int(0.060*fs)
    acc = []
    for r in rpeaks:
        s = max(0, r-pre); e = max(s+1, r-post)
        if e > f1.size: continue
        acc.append(np.abs(f1[s:e]).mean())
    e = float(np.mean(acc)) if acc else np.nan
    ivs = []
    for r in rpeaks:
        s = max(0, r-pre); e = max(s+1, r-post)
        if e > f1.size: continue
        seg = np.abs(f1[s:e])
        if seg.size == 0: continue
        ivs.append((r - (s + int(np.argmax(seg)))) / fs * 1000.0)
    pr = float(np.std(ivs, ddof=1)) if len(ivs) >= 5 else np.nan
    f2 = bandpass(sig, fs, 4.0, 10.0)
    tqs = int(0.200*fs); tqe = int(0.350*fs)
    vals = []
    for i in range(rpeaks.size-1):
        r = rpeaks[i]; nr = rpeaks[i+1]
        s = min(r+tqs, nr-1); e = min(r+tqe, nr-1)
        if e <= s: continue
        vals.append(np.sqrt((f2[s:e]**2).mean()))
    fa = float(np.mean(vals)) if vals else np.nan
    return e, pr, fa

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

WINDOW_S = 60.0

all_rows = []
for r, (sig, fs, lab) in loaded.items():
    win = int(WINDOW_S * fs); hop = win // 2
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
        pw_e, pw_pr, pw_fa = pwave_features(sig[start:end], fs, local - start)
        all_rows.append((r, csi, cv, pw_e, pw_pr, pw_fa, lab))

cvs = np.array([x[2] for x in all_rows])
csis = np.array([x[1] for x in all_rows])
X = np.column_stack([np.ones_like(cvs), cvs])
coef, *_ = np.linalg.lstsq(X, csis, rcond=None)
resid = csis - X @ coef

by_rec = defaultdict(list)
for row, res in zip(all_rows, resid):
    by_rec[row[0]].append((res, row[3], row[4], row[5], row[6]))

def corr(a, b):
    if np.isnan(a).any() or np.isnan(b).any(): return np.nan
    if a.std() < 1e-12 or b.std() < 1e-12: return np.nan
    return float(np.corrcoef(a, b)[0, 1])

print(f"W = {WINDOW_S:.0f}s")
print(f"{'record':>8} {'lab':>4} {'n':>4} "
      f"{'r_e':>8} {'r_pr':>8} {'r_fa':>8}")
print("-" * 46)
for r in sorted(by_rec.keys()):
    vals = by_rec[r]
    if len(vals) < 5: continue
    res_a = np.array([v[0] for v in vals])
    e_a   = np.array([v[1] for v in vals])
    pr_a  = np.array([v[2] for v in vals])
    fa_a  = np.array([v[3] for v in vals])
    lab   = vals[0][4]
    print(f"{r:>8} {lab:>4} {len(vals):>4} "
          f"{corr(res_a, e_a):>8.3f} {corr(res_a, pr_a):>8.3f} "
          f"{corr(res_a, fa_a):>8.3f}")

print()
for lab, name in [(0, "sinus"), (1, "AF")]:
    rows_ = [(r, v) for r, v in by_rec.items() if v[0][4] == lab and len(v) >= 5]
    ce, cp, cf = [], [], []
    for r, vals in rows_:
        res_a = np.array([v[0] for v in vals])
        e_a = np.array([v[1] for v in vals])
        pr_a = np.array([v[2] for v in vals])
        fa_a = np.array([v[3] for v in vals])
        ce.append(corr(res_a, e_a)); cp.append(corr(res_a, pr_a))
        cf.append(corr(res_a, fa_a))
    ce = [x for x in ce if not np.isnan(x)]
    cp = [x for x in cp if not np.isnan(x)]
    cf = [x for x in cf if not np.isnan(x)]
    def stats(xs):
        if not xs: return "n/a"
        return f"{np.mean(xs):+.3f} (sd {np.std(xs,ddof=1):.3f}, n={len(xs)})"
    print(f"{name:>5}: r_e {stats(ce)}  r_pr {stats(cp)}  r_fa {stats(cf)}")
