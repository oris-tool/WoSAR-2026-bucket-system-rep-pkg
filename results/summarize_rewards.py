"""Computes per-experiment mean values from the *_rewards.csv files.

Expected file naming convention (same as plot_rewards.py):
    workloadProfileName_N<buckets>_D<depth>_rewards.csv

Reward columns are not all averaged the same way:

  CDF-like columns (monotone non-decreasing, possibly defective, i.e.
  converging to a mass p_inf <= 1: TimeToRejuvenation, RejuvenationDueTo*):
      mean      = E[T | absorbed within horizon]
                = integral of (F_inf - F(t)) dt / F_inf
      finalMass = F_inf (last value; for RejuvenationDueTo* this is the
                  probability of that rejuvenation cause)
  The naive column average would be meaningless here.

  Instantaneous expected rates (Throughput, ViolationRate, QoSViolationRate;
  they drop to 0 after absorption):
      total   = integral of r(t) dt  (expected cumulative amount before
                rejuvenation, e.g. mean number of completed requests)
      timeAvg = total / E[TimeToRejuvenation]  (mean rate until rejuvenation)

Availability is not derived from the reward curves: it is already computed
by the experiment and stored as a column of <folder>/experiments_index.csv,
from which it is joined into the summary (older runs without that column
are summarized without it).

Usage:
    python summarize_rewards.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python summarize_rewards.py 01
Output: <folder>/summary.csv, one row per experiment.
"""
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# columns known to be CDFs of a (possibly defective) first-passage time
CDF_PREFIXES = ("TimeToRejuvenation", "RejuvenationDueTo")

FILE_RE = re.compile(r"(?P<profile>.+)_N(?P<n>\d+)_D(?P<d>\d+)_rewards$")


def is_cdf(name: str, values: np.ndarray) -> bool:
    """Name-based classification, with a monotonicity check as fallback
    for columns added in the future (a CDF never decreases)."""
    if name.startswith(CDF_PREFIXES):
        return True
    diffs = np.diff(values)
    monotone = np.all(diffs >= -1e-12)
    if monotone and values[-1] <= 1.0 + 1e-9:
        print(f"  [note] '{name}' looks monotone in [0,1]: treated as CDF")
        return True
    return False


def cdf_mean(time: np.ndarray, cdf: np.ndarray):
    """Conditional mean of the first-passage time given absorption within
    the horizon: integral of (F_inf - F) dt / F_inf."""
    f_inf = cdf[-1]
    if f_inf <= 0:
        return np.nan
    return np.trapezoid(f_inf - cdf, time) / f_inf


def summarize(csv_path: Path):
    m = FILE_RE.match(csv_path.stem)
    if m is None:
        print(f"[skip] {csv_path.name}: unexpected file name")
        return None

    df = pd.read_csv(csv_path)
    time = df["time"].to_numpy()

    row = {
        "profile": m["profile"],
        "bucketsNumber": int(m["n"]),
        "bucketDepth": int(m["d"]),
        "timeLimit": time[-1],
    }

    mean_ttr = None
    for col in (c for c in df.columns if c != "time"):
        y = df[col].to_numpy()
        if is_cdf(col, y):
            row[f"{col}_mean"] = cdf_mean(time, y)
            row[f"{col}_finalMass"] = y[-1]
            if col == "TimeToRejuvenation":
                mean_ttr = row[f"{col}_mean"]
        else:
            row[f"{col}_total"] = np.trapezoid(y, time)

    # rate columns averaged over the mean time to rejuvenation
    if mean_ttr:
        for col in [c for c in row if c.endswith("_total")]:
            row[col.replace("_total", "_timeAvg")] = row[col] / mean_ttr

    return row


def load_availability(base: Path):
    """Availability per (profile, N, D) from experiments_index.csv,
    or None if the index (or its availability column) is missing."""
    index_path = base / "experiments_index.csv"
    if not index_path.exists():
        print(f"[note] {index_path.name} not found: no availability column")
        return None
    index = pd.read_csv(index_path)
    if "availability" not in index.columns:
        print(f"[note] no 'availability' column in {index_path.name}")
        return None
    return index[["profile", "bucketsNumber", "bucketDepth", "availability"]]


def run(base: Path) -> None:
    csv_files = sorted(
        p for p in base.glob("*_rewards.csv")
        if not p.name.endswith("_rewards_mapping.csv")
    )
    if not csv_files:
        print(f"No *_rewards.csv files found in {base}")
        return

    rows = []
    for csv_path in csv_files:
        row = summarize(csv_path)
        if row is not None:
            rows.append(row)
            print(f"[ok]   {csv_path.name}")

    out = pd.DataFrame(rows)
    availability = load_availability(base)
    if availability is not None:
        out = out.merge(availability, how="left",
                        on=["profile", "bucketsNumber", "bucketDepth"])
    out = out.sort_values(["profile", "bucketsNumber", "bucketDepth"])
    out_path = base / "summary.csv"
    out.to_csv(out_path, index=False)
    print(f"Done: {out_path} ({len(out)} experiments)")


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    run(base.resolve())


if __name__ == "__main__":
    main()
