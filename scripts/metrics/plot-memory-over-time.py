# scripts/metrics/plot-memory-over-time.py

import json
from pathlib import Path

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go


RESULTS_ROOT = Path("results/local-idle-rare-quick")
OUT = Path("reports/memory-over-time.html")


def detect_runtime(run_dir: Path) -> str:
    name = run_dir.name.lower()

    if "openj9" in name:
        return "OpenJ9"
    if "native" in name:
        return "GraalVM Native"
    if "graalvm" in name:
        return "GraalVM JIT"
    if "hotspot" in name:
        return "HotSpot"

    return run_dir.name


def read_memory_run(run_dir: Path) -> pd.DataFrame | None:
    metrics_file = run_dir / "runtime-metrics.csv"
    if not metrics_file.exists():
        return None

    df = pd.read_csv(metrics_file)

    # Подстрой под реальные названия колонок, если отличаются
    time_col = "timestamp"
    memory_col = None

    for candidate in [
        "memory_current_bytes",
        "memory_used_bytes",
        "memory_peak_bytes",
        "rss_bytes",
        "rssBytes",
        "rss_mb",
        "memory_current_mb",
        "cgroup_memory_mb",
        "working_set_mb",
    ]:
        if candidate in df.columns:
            memory_col = candidate
            break

    if memory_col is None:
        raise ValueError(f"No memory column found in {metrics_file}. Columns: {list(df.columns)}")

    df[time_col] = pd.to_datetime(df[time_col], utc=True, errors="coerce")
    df = df.dropna(subset=[time_col])

    df["time_seconds"] = (df[time_col] - df[time_col].min()).dt.total_seconds()
    df["runtime"] = detect_runtime(run_dir)
    df["run_dir"] = str(run_dir)

    if memory_col.endswith("_bytes") or memory_col.endswith("Bytes"):
        df["memory_mb"] = df[memory_col] / 1024 / 1024
    else:
        df["memory_mb"] = df[memory_col]

    return df[["time_seconds", "memory_mb", "runtime", "run_dir"]]


def add_phase_markers(fig: go.Figure, run_dir: Path):
    phases_file = run_dir / "phases.json"
    if not phases_file.exists():
        return

    phases = json.loads(phases_file.read_text())

    # Тут зависит от формата phases.json.
    # Если там есть timestamps фаз, их нужно привести к seconds from run start.
    # Для первого v1 можно начать без phase markers,
    # а потом адаптировать этот блок под фактическую структуру файла.


def main():
    run_dirs = [
        p for p in RESULTS_ROOT.rglob("*")
        if p.is_dir() and (p / "runtime-metrics.csv").exists()
    ]

    frames = []
    for run_dir in run_dirs:
        df = read_memory_run(run_dir)
        if df is not None:
            frames.append(df)

    if not frames:
        raise SystemExit(f"No runtime-metrics.csv found under {RESULTS_ROOT}")

    all_df = pd.concat(frames, ignore_index=True)

    fig = px.line(
        all_df,
        x="time_seconds",
        y="memory_mb",
        color="runtime",
        hover_data=["run_dir"],
        title="Memory Over Time",
        labels={
            "time_seconds": "Time from run start, sec",
            "memory_mb": "Memory, MB",
            "runtime": "Runtime",
        },
    )

    fig.update_layout(
        template="plotly_white",
        hovermode="x unified",
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.write_html(OUT)
    print(f"Saved: {OUT}")


if __name__ == "__main__":
    main()