# NeuroCardia

An open-source Android application for offline, on-device analysis of
single-lead ECG recordings.

NeuroCardia loads an ECG file, detects R-peaks, computes conventional
heart-rate-variability (HRV) metrics, assesses signal quality on four
independent axes, and produces a report. It does all of this on the
handset, with no network connection, no user accounts, and no
telemetry. It runs identically in airplane mode.


## WHAT IT DOES

- **Loads** single-lead ECG recordings from local files (WFDB, CSV).
- **Detects** R-peaks with a pure-Kotlin implementation of the
  Pan-Tompkins algorithm.
- **Computes** time-domain HRV (RMSSD, SDNN, pNN50), frequency-domain
  HRV (LF, HF, LF/HF), and Poincare features (SD1, SD2, SD1/SD2).
- **Scores** signal quality on four independent axes (signal-to-noise
  ratio, flatline fraction, clipping fraction, baseline wander
  fraction), with a composite verdict.
- **Exports** CSV and PDF reports.
- **Runs a research feature** — the Cardiac Synchronization Index
  (CSI), a phase-domain metric derived from a Kuramoto coupled-
  oscillator network driven by the RR tachogram. The CSI is a research
  feature, not a clinical diagnostic. See the companion residual paper
  for its scope and limitations.


## WHY IT EXISTS

Most open-source ECG and HRV tools assume a workstation, a network
connection, and a command-line interface. NeuroCardia's contribution
is the **deployment envelope**: offline, on-device, open-source ECG
analysis on commodity Android hardware. The algorithms are standard.
The delivery is not.


## MODULES

  :engine      — Kuramoto network (K is a per-call parameter)
  :io          — CSV and WFDB readers/writers, EcgRecord, MetricKeys
  :dsp         — Pan-Tompkins, HRV, CSI, SignalQuality
  :batch       — batch processing with all metrics
  :validation  — validation harness (JVM-only, runs in <1 s per mode)
  :app         — Android UI (Compose, thin shell)

The `:engine`, `:io`, `:dsp`, `:batch`, and `:validation` modules are
pure Kotlin/JVM with no Android dependency. They can be consumed by
any JVM project.


## BUILD

    ./gradlew :app:assembleDebug     # Android APK
    ./gradlew :validation:run        # JVM validation harness


## REPRODUCIBILITY

Every validation experiment runs from a single Gradle command:

    ./gradlew :validation:run --args="<mode> <args>"

Reports are written to `validation/reports/` with timestamped
filenames. The full analysis in the companion documents is reproducible
from the repository on any JVM, using publicly available PhysioNet
datasets, without network access at analysis time.


## DOCUMENTATION

  docs/README.md                 Index of the four documents
  docs/whitepaper.tex            Technical and regulatory overview
  docs/kuramoto-engine.tex       Mathematics of the CSI
  docs/sensitivity-analysis.tex  Validation protocol and results
  docs/residual-paper.tex        The residual finding


## INTENDED USE

NeuroCardia is intended for research and educational use. It is not a
diagnostic device, does not replace standard ECG interpretation, and
does not produce clinical recommendations. The phase-domain CSI is a
research feature and is labeled as not-for-clinical-use in the
application. Every report includes a disclaimer requiring review by a
qualified clinician before any clinical decision.


## LICENSE

Software: GPL-3.0-or-later.
Documents: CC-BY-4.0.
