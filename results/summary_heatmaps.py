"""Generates M x D heatmaps from the summary.csv produced by summarize_rewards.py.

One PDF per (workload profile, metric): every metric column of summary.csv
gets its own single-panel heatmap, with bucketsNumber (M) on the y-axis,
bucketDepth (D) on the x-axis and the metric value both color-encoded
(single-hue sequential ramp) and printed in each cell.

Usage:
    python summary_heatmaps.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python summary_heatmaps.py 01
Output: <folder>/heatmaps/<profile>/<metric>.pdf
"""
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, to_rgb

# experiment keys / parameters: everything else in summary.csv is a metric
KEY_COLUMNS = ("profile", "bucketsNumber", "bucketDepth", "timeLimit")

def words(name: str) -> str:
    """CamelCase to spaced lowercase: 'RejuvenationDueToLoad' ->
    'rejuvenation due to load', 'AgingLevel0' -> 'aging level 0'."""
    return re.sub(r"(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=\d)", " ",
                  name).lower()


def event(name: str, parenthetical: bool = True) -> str:
    """The event a first-passage CDF refers to: 'TimeToRejuvenation' ->
    'rejuvenation'; qualifiers after '_' become a parenthetical (or a
    comma-separated tail), e.g. 'RejuvenationDueToLoad_AgingLevel1' ->
    'rejuvenation due to load (aging level 1)'."""
    base, *qualifiers = name.split("_")
    text = words(re.sub(r"^TimeTo", "", base))
    if qualifiers:
        tail = ", ".join(words(q) for q in qualifiers)
        text += f" ({tail})" if parenthetical else f", {tail}"
    return text


def title(col: str) -> str:
    """Uniform human-readable title derived from the summary.csv column:
    P(<event>) for CDF final masses, 'Mean time to <event>' for CDF
    conditional means, 'Mean/Total <reward>' for rate rewards."""
    if col.endswith("_finalMass"):
        return f"P({event(col.removesuffix('_finalMass'), False)})"
    if col.endswith("_mean"):
        return f"Mean time to {event(col.removesuffix('_mean'))}"
    if col.endswith("_timeAvg"):
        return f"Mean {words(col.removesuffix('_timeAvg'))}"
    if col.endswith("_total"):
        return f"Total {words(col.removesuffix('_total'))}"
    return words(col).capitalize()

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
    ax.set_ylabel("Buckets number (M)", fontsize=9, color=INK_SECONDARY)
    ax.set_xticks(np.arange(len(pivot.columns)) + 0.5,
                  pivot.columns, fontsize=9, color=INK_SECONDARY)
    ax.set_yticks(np.arange(len(pivot.index)) + 0.5,
                  pivot.index, fontsize=9, color=INK_SECONDARY)
    ax.tick_params(length=0)
    for spine in ax.spines.values():
        spine.set_visible(False)
    ax.set_aspect("equal")


def metric_figure(df: pd.DataFrame, profile: str, col: str,
                  out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(4.6, 4.4))
    fig.patch.set_facecolor(SURFACE)

    pivot = df.pivot(index="bucketsNumber", columns="bucketDepth",
                     values=col).sort_index()
    heatmap_panel(ax, pivot, title(col))

    fig.suptitle(profile, fontsize=12, fontweight="bold", color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(out_path, facecolor=SURFACE)
    plt.close(fig)


def run(base: Path) -> None:
    summary_path = base / "summary.csv"
    if not summary_path.exists():
        print(f"{summary_path} not found: run summarize_rewards.py first")
        return

    df = pd.read_csv(summary_path)
    metric_cols = [c for c in df.columns if c not in KEY_COLUMNS]
    if not metric_cols:
        print("No metric column found in summary.csv")
        return

    out_dir = base / "heatmaps"
    for profile in df["profile"].unique():
        profile_dir = out_dir / profile
        profile_dir.mkdir(parents=True, exist_ok=True)
        sub = df[df["profile"] == profile]
        for col in metric_cols:
            metric_figure(sub, profile, col, profile_dir / f"{col}.pdf")
        print(f"[ok]   {profile} ({len(metric_cols)} heatmaps)")

    print(f"Done: {out_dir}")


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    run(base.resolve())


if __name__ == "__main__":
    main()
