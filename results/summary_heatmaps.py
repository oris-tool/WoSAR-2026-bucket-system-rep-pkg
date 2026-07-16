"""Generates N x D heatmaps from the summary.csv produced by summarize_rewards.py.

One figure per workload profile: a panel for each key metric, with
bucketsNumber (N) on the y-axis, bucketDepth (D) on the x-axis and the
metric value both color-encoded (single-hue sequential ramp) and printed
in each cell.

Usage:
    python summary_heatmaps.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python summary_heatmaps.py 01
Output: <folder>/heatmap/<profile>.pdf
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, to_rgb

# (summary.csv column, panel title)
METRICS = [
    ("TimeToRejuvenation_mean",          "Mean time to rejuvenation"),
    ("Throughput_timeAvg",               "Mean throughput"),
    ("ViolationRate_timeAvg",            "Mean violation rate"),
    ("RejuvenationDueToAging_finalMass", "P(rejuvenation due to aging)"),
]

# single-hue sequential ramp (blue, light -> dark) and chart ink roles
SEQ_RAMP = ["#cde2fb", "#b7d3f6", "#9ec5f4", "#86b6ef", "#6da7ec", "#5598e7",
            "#3987e5", "#2a78d6", "#256abf", "#1c5cab", "#184f95", "#104281",
            "#0d366b"]
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"

CMAP = LinearSegmentedColormap.from_list("seq_blue", SEQ_RAMP)


def fmt(v: float) -> str:
    if pd.isna(v):
        return "--"
    if v == 0:
        return "0"
    if 0.001 <= abs(v) < 1000:
        return f"{v:.3g}"
    return f"{v:.1e}"


def cell_ink(rgba) -> str:
    """Dark ink on light cells, white on dark cells (WCAG luminance)."""
    r, g, b = (c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
               for c in to_rgb(rgba))
    lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return INK if lum > 0.35 else "#ffffff"


def heatmap_panel(ax, pivot: pd.DataFrame, title: str) -> None:
    values = pivot.to_numpy(dtype=float)
    vmin, vmax = np.nanmin(values), np.nanmax(values)
    span = (vmax - vmin) or 1.0

    # 2px-like surface gap between cells via cell edges
    ax.pcolormesh(values, cmap=CMAP, vmin=vmin, vmax=vmax,
                  edgecolors=SURFACE, linewidth=2)

    for i in range(values.shape[0]):
        for j in range(values.shape[1]):
            v = values[i, j]
            color = CMAP((v - vmin) / span)
            ax.text(j + 0.5, i + 0.5, fmt(v), ha="center", va="center",
                    fontsize=9, color=cell_ink(color))

    ax.set_title(title, fontsize=10, color=INK)
    ax.set_xlabel("Bucket depth (D)", fontsize=9, color=INK_SECONDARY)
    ax.set_ylabel("Buckets number (N)", fontsize=9, color=INK_SECONDARY)
    ax.set_xticks(np.arange(len(pivot.columns)) + 0.5,
                  pivot.columns, fontsize=9, color=INK_SECONDARY)
    ax.set_yticks(np.arange(len(pivot.index)) + 0.5,
                  pivot.index, fontsize=9, color=INK_SECONDARY)
    ax.tick_params(length=0)
    for spine in ax.spines.values():
        spine.set_visible(False)
    ax.set_aspect("equal")


def profile_figure(df: pd.DataFrame, profile: str, out_path: Path) -> None:
    ncols = 2
    nrows = math.ceil(len(METRICS) / ncols)
    fig, axes = plt.subplots(nrows, ncols,
                             figsize=(4.6 * ncols, 4.2 * nrows),
                             squeeze=False)
    fig.patch.set_facecolor(SURFACE)

    axes_flat = axes.flatten()
    for ax, (col, title) in zip(axes_flat, METRICS):
        pivot = df.pivot(index="bucketsNumber", columns="bucketDepth",
                         values=col).sort_index()
        heatmap_panel(ax, pivot, title)
    for ax in axes_flat[len(METRICS):]:
        ax.set_visible(False)

    fig.suptitle(profile, fontsize=13, fontweight="bold", color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.96))
    fig.savefig(out_path, facecolor=SURFACE)
    plt.close(fig)


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    base = base.resolve()

    summary_path = base / "summary.csv"
    if not summary_path.exists():
        print(f"{summary_path} not found: run summarize_rewards.py first")
        return

    df = pd.read_csv(summary_path)
    missing = [c for c, _ in METRICS if c not in df.columns]
    if missing:
        print(f"Missing columns in summary.csv: {missing}")
        return

    out_dir = base / "heatmap"
    out_dir.mkdir(exist_ok=True)

    for profile in df["profile"].unique():
        out_path = out_dir / f"{profile}.pdf"
        profile_figure(df[df["profile"] == profile], profile, out_path)
        print(f"[ok]   {out_path.name}")

    print(f"Done: {out_dir}")


if __name__ == "__main__":
    main()
