"""Generates plots from the *_rewards.csv files produced by transient analyses.

Expected file naming convention:
    workloadProfileName_N<buckets>_D<depth>_rewards.csv
Each CSV contains a 'time' column (x-axis) and one or more reward columns.

Four PDF versions are generated for each CSV, in a subfolder per
configuration (<config> = workloadProfileName_N<buckets>_D<depth>):
    <folder>/plots/<config>/grid.pdf        -> grid of subplots (one per reward)
    <folder>/plots/<config>/single.pdf      -> single plot, all rewards, linear y
    <folder>/plots/<config>/single_log.pdf  -> single plot, all rewards, log y
    <folder>/plots/<config>/single_norm.pdf -> single plot, min-max normalized

Usage:
    python plot_rewards.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python plot_rewards.py 01
"""
import re
import sys
import math
from pathlib import Path

import pandas as pd
import matplotlib
matplotlib.use("Agg")  # non-interactive backend: no window, only file output
import matplotlib.pyplot as plt


def load_rewards(csv_path: Path):
    """Returns (df, reward_cols) or (None, None) if the file should be skipped."""
    df = pd.read_csv(csv_path)
    if "time" not in df.columns:
        print(f"[skip] {csv_path.name}: 'time' column is missing")
        return None, None
    reward_cols = [c for c in df.columns if c != "time"]
    if not reward_cols:
        print(f"[skip] {csv_path.name}: no rewards to plot")
        return None, None
    return df, reward_cols

def plot_grid(df, reward_cols, stem: str, out_path: Path) -> None:
    """A grid of subplots: one panel for each reward (independent scales)."""
    n = len(reward_cols)
    ncols = 2 if n > 1 else 1
    nrows = math.ceil(n / ncols)

    fig, axes = plt.subplots(nrows, ncols,
                             figsize=(6 * ncols, 3 * nrows),
                             squeeze=False)
    axes_flat = axes.flatten()

    for ax, col in zip(axes_flat, reward_cols):
        ax.plot(df["time"], df[col], linewidth=1.2)
        ax.set_title(col, fontsize=10)
        ax.set_xlabel("time")
        ax.set_ylabel(col, fontsize=8)
        ax.grid(True, alpha=0.3)

    for ax in axes_flat[n:]:  # hide any empty subplots
        ax.set_visible(False)

    fig.suptitle(stem, fontsize=13, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(out_path) # PDF: format inferred from the extension
    plt.close(fig)



def _normalize(series):
    """Min-max normalize a series to [0, 1]; constant series map to all zeros."""
    lo, hi = series.min(), series.max()
    span = hi - lo
    if span == 0:
        return series * 0.0
    return (series - lo) / span


def plot_single(df, reward_cols, stem: str, out_path: Path,
                yscale: str = "linear", normalize: bool = False) -> None:
    """A single plot with all rewards overlaid on one axis.

    yscale:    "linear" or "log" for the y-axis.
    normalize: min-max scale each reward to [0, 1] so different-scale
               rewards become comparable.
    """
    fig, ax = plt.subplots(figsize=(10, 6))

    for col in reward_cols:
        y = _normalize(df[col]) if normalize else df[col]
        ax.plot(df["time"], y, linewidth=1.2, label=col)

    ax.set_yscale(yscale)
    ax.set_title(stem, fontsize=13, fontweight="bold")
    ax.set_xlabel("time")
    ax.set_ylabel("normalized reward" if normalize else "reward")
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=8, loc="best")
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)


def run(base: Path) -> None:
    plots_dir = base / "plots"

    # only the reward files, excluding the *_mapping.csv files and the index
    csv_files = sorted(
        p for p in base.glob("*_rewards.csv")
        if not p.name.endswith("_rewards_mapping.csv")
    )

    if not csv_files:
        print(f"No *_rewards.csv files found in {base}")
        return

    print(f"Found {len(csv_files)} files in {base}: plots -> {plots_dir}")

    for csv_path in csv_files:
        df, reward_cols = load_rewards(csv_path)
        if df is None:
            continue
        stem = csv_path.stem  # e.g. MostlyMid_N1_D1_rewards
        config = stem.removesuffix("_rewards")  # e.g. MostlyMid_N1_D1
        # bucketsNumber is presented as M in the paper; filenames keep N
        title = re.sub(r"_N(\d+)_D(\d+)", r"_M\1_D\2", config)
        out_dir = plots_dir / config
        out_dir.mkdir(parents=True, exist_ok=True)
        plot_grid(df, reward_cols, title, out_dir / "grid.pdf")
        plot_single(df, reward_cols, title, out_dir / "single.pdf")
        plot_single(df, reward_cols, title, out_dir / "single_log.pdf",
                    yscale="log")
        plot_single(df, reward_cols, title, out_dir / "single_norm.pdf",
                    normalize=True)
        print(f"[ok]   {config}")

    print(f"Done: {plots_dir}")


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    run(base.resolve())


if __name__ == "__main__":
    main()
