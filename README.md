# WOSAR 2026 — Microservice Software Rejuvenation


This repository is a companion page for the following publication submitted 
to the 18th International Workshop on Software Aging and Rejuvenation ([WoSAR 2026](https://www.wosar.net/)):

> Leonardo Scommegna, Alberto Avritzer, Laura Carnevali, Andrea Janes, Helena Rodrigues, and Enrico Vicario. 2026.
> Optimizing Bucket-Based Software Rejuvenation in Microservices Subject To Variable Workload.
> In *18th International Workshop on Software Aging and Rejuvenation (WoSAR 2026), October 20-23, 2026, Limassol, Cyprus.*

---

## Requirements

**Experiments (Java)**

- **JDK 24** (the project is compiled with `maven.compiler.source/target = 24`)
- **Maven 3.9+**
- Dependencies (`sirio` 2.0.5, `xchart` 4.0.3) are resolved automatically by Maven

**Graphical results (Python)**

- **Python 3.11.9** — in the dev setup via pyenv:
  `~/.pyenv/versions/3.11.9/bin/python`
  (the system `python3` lacks `pandas`/`matplotlib`)
- Packages: `pandas`, `matplotlib`

```bash
~/.pyenv/versions/3.11.9/bin/python -m pip install pandas matplotlib
```

---

## Running the experiments

All commands must be run from the **project root** (output paths are relative).

Compile once:

```bash
mvn compile
```

### 1. Main experiment — `WosarExperiment`

Explores 3 workload profiles × 16 bucket configurations (48 runs), with an
adaptive time horizon. Writes to **`results/experiment_output/`**:
`experiments_index.csv`, `summary.csv` and the `*_rewards.csv` files for each
configuration.

```bash
mvn exec:java -Dexec.mainClass=it.unifi.dinfo.stlab.WosarExperiment
```

> ⚠️ The full run  writes into a fresh
> `results/experiment_output/` folder, separate from the reference results in
> `results/precomputed_results/`.

### 2. Simulation-based validation — `BphThresholdValidationExperiment`

Validates the threshold-exceedance probabilities by simulation. Writes to
**`results/simulation/`**: `threshold_validation.csv` and the
`completion_times_aging*_conc*.csv` files.

```bash
mvn exec:java -Dexec.mainClass=it.unifi.dinfo.stlab.simulation.BphThresholdValidationExperiment
```

> Alternatively, you can run the classes directly from IntelliJ IDEA
> (the `main` method).

---

## Getting the graphical results

The post-processing pipeline lives in `results/` and is launched with
`run_all.py`, passing one or more result folders. Run it with Python 3.11.9
**from the `results/` folder**.

To reproduce the paper figures from the pre-computed reference results:

```bash
cd results
~/.pyenv/versions/3.11.9/bin/python run_all.py precomputed_results
```

To process the results of a fresh run instead, pass its folder:

```bash
cd results
~/.pyenv/versions/3.11.9/bin/python run_all.py experiment_output
```

The pipeline runs, in order:

1. `summarize_rewards.py` → `<folder>/summary.csv` (with availability joined from `experiments_index.csv`)
2. `plot_rewards.py` → `<folder>/plots/<config>/*.pdf`
3. `summary_heatmaps.py` → `<folder>/heatmaps/<profile>/<metric>.pdf`
4. `summary_to_latex.py` → `<folder>/tables/*.tex`
5. `summary_figures.py` → `<folder>/figures/*.pdf|png`

The main figures are generated in `<folder>/figures/`

---

## Repository layout

```
src/main/java/it/unifi/dinfo/stlab/
├── WosarExperiment.java        # main experiment entry point -> results/experiment_output/
├── Main.java                   # single run / debug (chart viewer)
├── MicroserviceModel.java      # STPN model construction
├── *RepresentationStrategy.java# processing / rejuvenation strategies
└── simulation/                 # validation entry point      -> results/simulation/
results/
├── precomputed_results/        # reference results shipped with the repo (CSV + figures)
├── simulation/                 # simulation validation output
├── run_all.py                  # post-processing pipeline
└── summary_*.py, plot_*.py     # individual pipeline steps
```
