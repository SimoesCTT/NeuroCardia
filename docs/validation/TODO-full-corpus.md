# Full-corpus validation TODO

When the complete MIT-BIH Normal Sinus Rhythm Database (18 records)
and MIT-BIH Atrial Fibrillation Database (25 records) are available
locally, the following analyses should be rerun at full scale.

The current results (documented in the three papers under `docs/`) were
obtained on a paired 9+9 subset. The purpose of the full-corpus rerun
is to sharpen the estimates and confirm that the K=2 residual finding
generalizes.

## 1. Download the full corpora

Not done yet. The current `sinus-10min/` and `af-10min/` folders hold
9 records each, trimmed to the first 10 minutes.

Full download commands:

    python3 -c "import wfdb; wfdb.dl_database('nsrdb', dl_dir='/home/americosimoes/physionet/nsrdb')"
    python3 -c "import wfdb; wfdb.dl_database('afdb', dl_dir='/home/americosimoes/physionet/afdb')"

Expected: 18 nsrdb records (24h each), 25 afdb records (10h each).

## 2. Rerun the residual analysis

    ./gradlew :validation:run --args="residual /home/americosimoes/physionet/nsrdb /home/americosimoes/physionet/afdb A 2"

Expected: 43 records total. Report the new AUC, ρ, and p-value.

If the AUC is still above 0.85 with p < 0.01, the K=2 residual result
is confirmed at full corpus scale.

If the AUC is between 0.75 and 0.85, the effect is real but weaker
than the pilot suggested. Update the paper accordingly.

If the AUC is below 0.75, the pilot result did not generalize and the
paper's §5.4 must be revised to state that the finding does not
reproduce at scale.

## 3. Rerun the correlation matrix

    ./gradlew :validation:run --args="correlate /home/americosimoes/physionet/nsrdb /home/americosimoes/physionet/afdb A 2"

Expected: 43 records × 16 comparison features. Confirm the residual
still correlates most strongly with sd1_sd2 and qrs_width_mean.

## 4. Rerun the arm robustness check

    ./gradlew :validation:run --args="residual /home/americosimoes/physionet/nsrdb /home/americosimoes/physionet/afdb C 2"
    ./gradlew :validation:run --args="residual /home/americosimoes/physionet/nsrdb /home/americosimoes/physionet/afdb E 2"

Confirm the AUC remains above 0.80 for both arms.

## 5. Rerun the K sensitivity on the full corpus

    ./gradlew :validation:run --args="sensitivity /home/americosimoes/physionet/mitdb 0.5,1,2,3,5,10,30"

Expected: same monotone pattern as the pilot, with sharper confidence
intervals.

## 6. Timing estimates

- Full download (nsrdb + afdb): 30–60 minutes.
- Residual analysis on 43 records: 2–3 minutes per arm.
- Correlation matrix on 43 records: 3–5 minutes.
- Arm robustness (2 arms): 5–6 minutes.
- K sensitivity on 48 mitdb records: 15 minutes.

Total compute after download: about 30 minutes.

## 7. What to update in the papers

If the full-corpus results confirm the pilot:

- `whitepaper.tex` §5.4: replace the "9+9" phrasing with "18+25" or
  the actual counts. Update the AUC confidence interval if computed.
- `residual-paper.tex` §3: same updates. The numbers change from
  pilot-scale to full-scale.
- `sensitivity-analysis.tex` §8: same updates.

If the full-corpus results contradict the pilot:

- Do NOT revise the thresholds to match the new data. Report the
  contradiction. The paper becomes a negative result at full scale.
  This is a valid scientific outcome and more valuable than a
  positive result that doesn't reproduce.

## 8. What NOT to do

Do not cherry-pick the subset that reproduces the pilot result. The
purpose of the full-corpus rerun is a fair test. If the result
depends on the choice of which records to include, that is itself a
finding, and it must be reported.
