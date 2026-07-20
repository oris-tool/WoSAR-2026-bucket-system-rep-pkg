"""Runs the whole post-processing pipeline on one or more result folders.

For each folder it runs, in order:
    1. summarize_rewards.py   -> <folder>/summary.csv (with availability
                                 joined from experiments_index.csv)
    2. plot_rewards.py        -> <folder>/plots/<config>/*.pdf
    3. summary_heatmaps.py    -> <folder>/heatmaps/<profile>/<metric>.pdf
    4. summary_to_latex.py    -> <folder>/tables/*.tex
    5. summary_figures.py     -> <folder>/figures/*.pdf|png

Usage:
    python run_all.py [folder ...]
If no folder is specified, the current folder is used.
Example (run from 'results'):
    python run_all.py 01 02
"""
import sys
from pathlib import Path

import summarize_rewards
import plot_rewards
import summary_heatmaps
import summary_to_latex
import summary_figures

STEPS = [
    ("summarize", summarize_rewards.run),
    ("plots", plot_rewards.run),
    ("heatmaps", summary_heatmaps.run),
    ("tables", summary_to_latex.run),
    ("figures", summary_figures.run),
]


def run(base: Path) -> None:
    print(f"=== {base} ===")
    for name, step in STEPS:
        print(f"--- {name} ---")
        step(base)


def main() -> None:
    folders = sys.argv[1:] or ["."]
    for folder in folders:
        run(Path(folder).resolve())


if __name__ == "__main__":
    main()
