# NeuroCardia — Revised Documentation

Revision 0.2 of the four NeuroCardia technical documents, incorporating
the empirical findings from the sensitivity sweep, the five-arm residual
test, and the orthogonality analysis.

The originals live in `docs/` and remain as revision 0.1. This directory
holds revision 0.2. Where the two disagree, 0.2 is authoritative.


## WHAT CHANGED IN 0.2

The revision adds four findings that were not present in 0.1.

1. **The residual is not robust across all frequency distributions.**
   Revision 0.1 reported residual AUCs of 0.89 / 0.85 / 0.84 for Arms A
   (Riemann zeros), C (Gaussian), and E (linear spacing) at K = 2. The
   full five-arm test adds Arms B (uniform random) and D (physiological
   scale), which yield AUCs of 0.54 and 0.56 -- indistinguishable from
   chance. The robustness claim in *The Kuramoto Residual* is narrowed
   from "not specific to the Riemann-zero parameterization" to "specific
   to frequency distributions with [the shared property of A, C, E]."

2. **Trajectory robustness and residual robustness are disjoint.**
   The trajectory of the CSI is non-robust to the frequency distribution
   at every K where it has dynamic range (mean Pearson correlation
   0.05-0.17 at K = 2, n = 48 records). The only arm that passes the
   pre-registered trajectory criterion (Arm E at K = 30) does so at a
   coupling strength where the metric is saturated and diagnostically
   useless. Across the five arms at K = 2, trajectory correlation with
   Arm A and residual AUC are uncorrelated.

3. **The residual is partly redundant with existing HRV features.**
   After regressing the residual on the two standard HRV features it is
   most correlated with (`sd1_sd2` and `rr_cv`), the residual separates
   sinus from AF with AUC 0.716, down from 0.889. Approximately one
   third of the residual's diagnostic value is not explained by
   conventional HRV; two thirds is.

4. **The useful K-window is narrow and two-sided.** The residual AUC
   peaks at K = 2 (0.89), inverts below K = 1 (AUC 0.17-0.21), and
   decays to noise above K = 3 (0.59-0.72). The useful regime is the
   unsaturated regime, which is the same regime in which the trajectory
   is least robust.

5. **The residual is a record-level statistic, not a state tracker.**
   The residual's within-record variance is 1.2-2.5 times its
   between-record variance. Window-level AUC plateaus at 0.66-0.68 for
   window lengths from 1 to 5 minutes (0.50 at 30 s); the whole-record
   AUC of 0.89 is recovered only by averaging across windows. The
   window-to-window variation of the residual does not correlate with
   window-level P-wave features (mean |r| < 0.15 in both sinus and AF
   cohorts). The "state-sensitive atrial detector" language of
   revision 0.1 is a between-record claim and is scoped accordingly in
   revision 0.2.

The empirical claims of revision 0.1 that survive unchanged are: the
CSI at K = 10 is a monotone function of RR variability; the residual
does not separate clean from ectopy-burdened sinus rhythm; the
mechanism analysis (94% of residual variance explained by P-wave
features in AF, 37% in sinus); and the mathematical treatment of the
engine.


## THE FOUR DOCUMENTS


whitepaper.tex - NeuroCardia White Paper (Revision 0.2)

  Technical and regulatory overview. Section 5.4 is revised with the
  five-arm residual result, the orthogonality finding, and the
  disjointness of trajectory and residual robustness. The regulatory
  strategy is unchanged; the list of "claims requiring additional
  validation" now includes residual orthogonality to standard HRV.


kuramoto-engine.tex - The Kuramoto Engine (Revision 0.2)

  Mathematical reference. Section 4.3 (coupling strength) is expanded:
  K = 2 is not merely empirically chosen, it is the only K in the tested
  range where the residual carries diagnostic information. Section 4.2
  (frequency distribution) notes that the trajectory is
  distribution-sensitive at every useful K. The mathematical treatment
  of the model is unchanged.


sensitivity-analysis.tex - Sensitivity Analysis Protocol (Revision 0.2)

  Methodology and results. Section 4.3 extends the evaluation metrics
  to include the residual AUC as a fourth criterion. Section 5 introduces
  a companion acceptance criterion for the residual. Section 7 is now
  populated with the results of the five-arm sweep. A new subsection
  (7.4) reports the disjointness finding.


residual-paper.tex - The Kuramoto Residual (Revision 0.2)

  The scientific contribution. Section 3.2 is revised with the five-arm
  table; the robustness language becomes a scope condition. A new
  Section 3.6 reports the orthogonality result. The discussion is revised
  to reflect the partial novelty of the residual. The mechanism analysis
  and the negative control are unchanged.


## COMPILING THE PDFS

Each .tex file is standalone and compiles with two passes of pdflatex:

    cd docs/revised
    pdflatex whitepaper.tex && pdflatex whitepaper.tex
    pdflatex kuramoto-engine.tex && pdflatex kuramoto-engine.tex
    pdflatex sensitivity-analysis.tex && pdflatex sensitivity-analysis.tex
    pdflatex residual-paper.tex && pdflatex residual-paper.tex

Requirements: TeX Live with texlive-latex-extra, texlive-science, and
texlive-fonts-recommended. Same as for the originals.


## WHAT WILL CHANGE IN 0.3

The full-corpus rerun (NSRDB n = 18, AFDB n = 25, replacing the pilot
n = 9 + 9) will update Table 2 of the residual paper and the
orthogonality number in Section 3.6. If the orthogonality result holds
on the full corpus, the scope of the residual claim expands; if it
collapses to chance, the paper's central empirical claim is revised
downward and the trajectory-and-residual finding becomes the primary
contribution.

See validation/TODO-full-corpus.md in the main repository for the plan.


## LICENSE

The documents are released under CC-BY-4.0. The software they describe
is released under GPL-3.0-or-later.
