"""Generates LaTeX tables from the summary.csv produced by summarize_rewards.py.

One table per workload profile: each row is a bucket-system configuration
(M<buckets>_D<depth>), each column the mean value of a reward:

    E[Trej]     mean time to rejuvenation (from the TimeToRejuvenation CDF)
    Thr         mean throughput until rejuvenation (Throughput_timeAvg)
    Viol        mean violation rate until rejuvenation (ViolationRate_timeAvg)
    QoSViol     mean rate of violations of the fixed QoS threshold mu + sigma
                (QoSViolationRate_timeAvg)
    P(load)     probability that rejuvenation is triggered by load
                (final mass of RejuvenationDueToLoad), also split per
                aging level, and P(aging) for the aging-triggered case
    A           steady-state availability (taken as-is from
                experiments_index.csv by summarize_rewards.py)

Usage:
    python summary_to_latex.py [folder]
If 'folder' is not specified, the current folder is used.
Example (run from 'results'):
    python summary_to_latex.py 01
Output:
    <folder>/tables/<profile>.tex        one table per profile, ready to be
                                         \\input into a paper
    <folder>/tables/summary_tables.tex   standalone document including all
                                         tables, compilable with pdflatex
"""
import sys
from pathlib import Path

import pandas as pd

# (summary.csv column, LaTeX header)
COLUMNS = [
    ("TimeToRejuvenation_mean",                    r"$E[T_{\mathrm{rej}}]$"),
    ("Throughput_timeAvg",                         r"$\overline{\mathit{Thr}}$"),
    ("ViolationRate_timeAvg",                      r"$\overline{\mathit{Viol}}$"),
    ("QoSViolationRate_timeAvg",                   r"$\overline{\mathit{Viol}}_{\mu+\sigma}$"),
    ("RejuvenationDueToLoad_finalMass",            r"$P_{\mathrm{load}}$"),
    ("RejuvenationDueToLoad_AgingLevel0_finalMass", r"$P_{\mathrm{load}}^{(A0)}$"),
    ("RejuvenationDueToLoad_AgingLevel1_finalMass", r"$P_{\mathrm{load}}^{(A1)}$"),
    ("RejuvenationDueToLoad_AgingLevel2_finalMass", r"$P_{\mathrm{load}}^{(A2)}$"),
    ("RejuvenationDueToAging_finalMass",           r"$P_{\mathrm{aging}}$"),
    ("availability",                               r"$A$"),
]

CAPTION = (
    "Mean rewards for the \\textit{{{profile}}} workload profile. "
    "$E[T_{{\\mathrm{{rej}}}}]$ is the mean time to rejuvenation; "
    "$\\overline{{\\mathit{{Thr}}}}$ and $\\overline{{\\mathit{{Viol}}}}$ are the "
    "throughput and violation rate averaged until rejuvenation; "
    "$\\overline{{\\mathit{{Viol}}}}_{{\\mu+\\sigma}}$ is the mean rate of "
    "violations of the fixed QoS threshold $\\mu+\\sigma$; "
    "$P_{{\\mathrm{{load}}}}$ and $P_{{\\mathrm{{aging}}}}$ are the probabilities "
    "that rejuvenation is triggered by load (also split per aging level) "
    "or by reaching the maximum aging level; "
    "$A$ is the steady-state availability."
)


def fmt(v: float) -> str:
    """LaTeX-friendly number: fixed point in a readable range,
    scientific notation (a x 10^b) outside of it."""
    if pd.isna(v):
        return "--"
    if v == 0:
        return "$0$"
    if 0.001 <= abs(v) < 1000:
        return f"${v:.3f}$"
    mantissa, exp = f"{v:.2e}".split("e")
    return f"${mantissa} \\times 10^{{{int(exp)}}}$"


def profile_table(df: pd.DataFrame, profile: str, columns: list) -> str:
    lines = [
        r"\begin{table}[htbp]",
        r"\centering",
        r"\caption{" + CAPTION.format(profile=profile) + "}",
        r"\label{tab:rewards-" + profile.lower() + "}",
        r"\resizebox{\textwidth}{!}{%",
        r"\begin{tabular}{l" + "r" * len(columns) + "}",
        r"\toprule",
        "Config. & " + " & ".join(h for _, h in columns) + r" \\",
        r"\midrule",
    ]
    for _, row in df.iterrows():
        label = f"M{row['bucketsNumber']}\\_D{row['bucketDepth']}"
        values = " & ".join(fmt(row[c]) for c, _ in columns)
        lines.append(f"{label} & {values} \\\\")
    lines += [
        r"\bottomrule",
        r"\end{tabular}}",
        r"\end{table}",
    ]
    return "\n".join(lines)


def run(base: Path) -> None:
    summary_path = base / "summary.csv"
    if not summary_path.exists():
        print(f"{summary_path} not found: run summarize_rewards.py first")
        return

    df = pd.read_csv(summary_path)
    missing = [c for c, _ in COLUMNS if c not in df.columns]
    if missing:
        print(f"[note] columns not in summary.csv, left out: {missing}")
    columns = [(c, h) for c, h in COLUMNS if c in df.columns]
    if not columns:
        print("No known column found in summary.csv")
        return

    out_dir = base / "tables"
    out_dir.mkdir(exist_ok=True)

    tables = []
    for profile in df["profile"].unique():
        sub = df[df["profile"] == profile].sort_values(
            ["bucketsNumber", "bucketDepth"])
        table = profile_table(sub, profile, columns)
        tables.append(table)
        (out_dir / f"{profile}.tex").write_text(table + "\n")
        print(f"[ok]   {profile} ({len(sub)} configurations)")

    doc = "\n\n".join([
        "% Auto-generated by summary_to_latex.py from " + str(summary_path),
        r"\documentclass{article}",
        r"\usepackage[margin=2cm]{geometry}",
        r"\usepackage{booktabs}",
        r"\usepackage{graphicx}",
        r"\begin{document}",
        *tables,
        r"\end{document}",
    ])

    out_path = out_dir / "summary_tables.tex"
    out_path.write_text(doc)
    print(f"Done: {out_path}")


def main() -> None:
    base = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    run(base.resolve())


if __name__ == "__main__":
    main()
