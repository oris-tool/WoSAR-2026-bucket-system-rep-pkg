"""Generates aggregate presentation figures from the summary.csv produced
by summarize_rewards.py.

Three figures, PDF (vector, for slides/LaTeX) + PNG preview each:

    fig1_pareto_thr_avail   throughput vs availability scatter, one panel
                            per workload profile; color = bucketsNumber (M),
                            marker shape = bucketDepth (D), gray line = Pareto
                            front. Shows which profiles have a real trade-off.
    fig2_capacity_lines     throughput and availability vs bucket capacity
                            M*D (log x), one line per M. Shows the capacity
                            effect and that at equal M*D higher M wins.
    fig3_violation_fraction violating fraction of requests vs capacity, one
                            line per profile (per-capacity mean, per-config
                            points). Uses the fixed-threshold QoSViolationRate
                            when the summary has it, ViolationRate otherwise.

Usage:
    python summary_figures.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python summary_figures.py 02
Output: <folder>/figures/*.pdf|png
"""
import sys
from pathlib import Path

import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

# light-mode palette: ordinal blue ramp for M, categorical hues for profiles
N_RAMP = {1: "#86b6ef", 2: "#3987e5", 3: "#1c5cab", 4: "#0d366b"}
PROFILE_COLORS = ["#2a78d6", "#008300", "#e87ba4", "#eda100"]
SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9"

# marker shape per D; per-shape areas tuned to read at the same visual size
D_MARKER = {1: "o", 2: "s", 3: "^", 4: "D"}
D_SIZE = {1: 75, 2: 62, 3: 85, 4: 58}

# preferred panel order; profiles not listed are appended as found
PROFILE_ORDER = ["MostlyMid", "Spiky", "DailyPattern"]

# configurations annotated in fig1, per profile (fallback: extremes only)
FIG1_LABELS = {"MostlyMid": ["M1D1", "M1D4", "M4D1", "M4D4"]}
FIG1_DEFAULT_LABELS = ["M1D1", "M4D4"]

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "text.color": INK, "axes.labelcolor": INK2, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.edgecolor": "#c3c2b7", "axes.linewidth": 0.8,
    "grid.color": GRID, "grid.linewidth": 0.6,
    "font.family": "sans-serif", "font.size": 9,
})


def style(ax):
    ax.grid(True, alpha=0.8)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.tick_params(length=0)


def save(fig, out_dir: Path, name: str):
    for ext in ("pdf", "png"):
        fig.savefig(out_dir / f"{name}.{ext}", dpi=200)
    plt.close(fig)
    print(f"[ok]   {name}")


def profiles_of(df) -> list:
    found = list(df.profile.unique())
    return [p for p in PROFILE_ORDER if p in found] + \
           [p for p in found if p not in PROFILE_ORDER]


def pareto_front(sub):
    """Configs not dominated in (throughput, availability), by availability."""
    front = []
    for r in sub.itertuples():
        dominated = ((sub.Throughput_timeAvg >= r.Throughput_timeAvg)
                     & (sub.availability >= r.availability)
                     & ((sub.Throughput_timeAvg > r.Throughput_timeAvg)
                        | (sub.availability > r.availability))).any()
        if not dominated:
            front.append(r)
    front.sort(key=lambda r: r.availability)
    return front


def fig1_pareto(df, profiles, out_dir: Path):
    fig, axes = plt.subplots(1, len(profiles), figsize=(3.4 * len(profiles), 3.5),
                             squeeze=False)
    for ax, p in zip(axes[0], profiles):
        sub = df[df.profile == p]
        front = pareto_front(sub)
        ax.plot([r.availability for r in front],
                [r.Throughput_timeAvg for r in front],
                color=MUTED, lw=1, zorder=1, alpha=0.7)
        for r in sub.itertuples():
            ax.scatter(r.availability, r.Throughput_timeAvg,
                       s=D_SIZE[r.bucketDepth], marker=D_MARKER[r.bucketDepth],
                       color=N_RAMP[r.bucketsNumber],
                       edgecolors=SURFACE, linewidths=1.2, zorder=3)
        labels = FIG1_LABELS.get(p, FIG1_DEFAULT_LABELS)
        for r in sub[sub.cfg.isin(labels)].itertuples():
            ax.annotate(r.cfg, (r.availability, r.Throughput_timeAvg),
                        textcoords="offset points", xytext=(0, 8),
                        ha="center", fontsize=7.5, color=INK2)
        ax.set_title(p, fontsize=10, color=INK)
        ax.set_xlabel("Availability (A)")
        style(ax)
    axes[0][0].set_ylabel("Throughput (T)")
    handles = ([Line2D([], [], ls="", marker="o", ms=8, mfc=N_RAMP[n], mec=SURFACE,
                       label=f"M = {n}") for n in sorted(df.bucketsNumber.unique())]
               + [Line2D([], [], ls="", marker=D_MARKER[d], ms=7.5, mfc=MUTED,
                         mec=SURFACE, label=f"D = {d}")
                  for d in sorted(df.bucketDepth.unique())])
    fig.legend(handles=handles, loc="lower center", ncol=len(handles), frameon=False,
               fontsize=8, bbox_to_anchor=(0.5, -0.03),
               handletextpad=0.15, columnspacing=0.7)
    fig.suptitle("Throughput–availability trade-off of bucket configurations",
                 fontsize=11, fontweight="bold", color=INK)
    fig.tight_layout(rect=(0, 0.06, 1, 0.94))
    save(fig, out_dir, "fig1_pareto_thr_avail")


def fig2_capacity(df, profiles, out_dir: Path):
    ns = sorted(df.bucketsNumber.unique())
    fig, axes = plt.subplots(2, len(profiles), figsize=(3.4 * len(profiles), 5.6),
                             sharex=True, squeeze=False)
    for j, p in enumerate(profiles):
        sub = df[df.profile == p]
        for i, (col, lab) in enumerate([("Throughput_timeAvg", "Mean throughput"),
                                        ("availability", "Availability")]):
            ax = axes[i, j]
            for n in ns:
                line = sub[sub.bucketsNumber == n].sort_values("cap")
                ax.plot(line.cap, line[col], color=N_RAMP[n], lw=2,
                        marker="o", ms=5, mec=SURFACE, mew=0.8)
                if j == len(profiles) - 1:  # direct labels, rightmost panel
                    dy = {1: -8, 2: -8, 3: -9, 4: 5}.get(n, 0)
                    ax.annotate(f"M={n}", (line.cap.iloc[-1], line[col].iloc[-1]),
                                textcoords="offset points", xytext=(4, dy),
                                fontsize=7.5, color=N_RAMP[n])
            ax.set_xscale("log", base=2)
            ticks = sorted(set(df.cap.unique()) & {1, 2, 4, 8, 16})
            ax.set_xticks(ticks, ticks)
            ax.margins(x=0.12)
            style(ax)
            if i == 0:
                ax.set_title(p, fontsize=10, color=INK)
            if j == 0:
                ax.set_ylabel(lab)
            if i == 1:
                ax.set_xlabel("Bucket capacity M·D")
    handles = [Line2D([], [], color=N_RAMP[n], lw=2, marker="o", ms=5,
                      mec=SURFACE, label=f"M = {n}") for n in ns]
    fig.legend(handles=handles, loc="lower center", ncol=len(ns), frameon=False,
               fontsize=8, bbox_to_anchor=(0.5, -0.015))
    fig.suptitle("Effect of bucket capacity: at equal M·D, "
                 "more buckets (M) beat deeper buckets (D)",
                 fontsize=11, fontweight="bold", color=INK)
    fig.tight_layout(rect=(0, 0.04, 1, 0.95))
    save(fig, out_dir, "fig2_capacity_lines")


def fig3_violation_fraction(df, profiles, out_dir: Path, fixed_threshold: bool):
    fig, ax = plt.subplots(figsize=(4.6, 3.4))
    for p, color in zip(profiles, PROFILE_COLORS):
        sub = df[df.profile == p]
        ax.scatter(sub.cap, sub.violFrac, s=18, color=color, alpha=0.45,
                   edgecolors="none", zorder=2)
        mean = sub.groupby("cap").violFrac.mean()
        ax.plot(mean.index, mean.values, color=color, lw=2, zorder=3)
        ax.annotate(p, (mean.index[-1], mean.values[-1]),
                    textcoords="offset points", xytext=(6, -2),
                    fontsize=8, color=color)
    ax.set_xscale("log", base=2)
    ticks = sorted(set(df.cap.unique()) & {1, 2, 4, 8, 16})
    ax.set_xticks(ticks, ticks)
    ax.margins(x=0.22)
    ax.set_xlabel("Bucket capacity M·D")
    ax.set_ylabel("Fraction of requests over $\\mu+\\sigma$" if fixed_threshold
                  else "Violating fraction of requests")
    ax.set_title("Per-request quality cost of larger buckets", fontsize=10,
                 fontweight="bold", color=INK)
    style(ax)
    fig.tight_layout()
    save(fig, out_dir, "fig3_violation_fraction")


def run(base: Path) -> None:
    summary_path = base / "summary.csv"
    if not summary_path.exists():
        print(f"{summary_path} not found: run summarize_rewards.py first")
        return

    df = pd.read_csv(summary_path)
    df["cap"] = df.bucketsNumber * df.bucketDepth
    fixed_threshold = "QoSViolationRate_timeAvg" in df.columns
    rate = df.QoSViolationRate_timeAvg if fixed_threshold else df.ViolationRate_timeAvg
    df["violFrac"] = rate / df.Throughput_timeAvg
    df["cfg"] = "M" + df.bucketsNumber.astype(str) + "D" + df.bucketDepth.astype(str)
    profiles = profiles_of(df)

    out_dir = base / "figures"
    out_dir.mkdir(exist_ok=True)
    fig1_pareto(df, profiles, out_dir)
    fig2_capacity(df, profiles, out_dir)
    fig3_violation_fraction(df, profiles, out_dir, fixed_threshold)
    print(f"Done: {out_dir}")


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    run(base.resolve())


if __name__ == "__main__":
    main()
