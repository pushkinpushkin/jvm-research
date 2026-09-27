# scripts/metrics/plot-memory-over-time.py

from pathlib import Path

import pandas as pd
import plotly.express as px


RESULTS_ROOT = Path("results/local-idle-rare-quick")
OUT = Path("reports/memory-over-time.html")

SCENARIO_LABELS = {
    "fresh-idle": "Свежий простой: сервис запущен и почти ничего не делает",
    "rare-requests": "Редкие запросы: сервис почти простаивает, но иногда обрабатывает трафик",
    "low-load": "Слабая нагрузка: постоянный небольшой поток запросов",
    "unknown": "Неизвестный сценарий",
}


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


def detect_scenario(run_dir: Path) -> str:
    parts = [p.lower() for p in run_dir.parts]

    for scenario in ["fresh-idle", "rare-requests", "low-load"]:
        if scenario in parts:
            return scenario

    return "unknown"


def detect_pass(run_dir: Path) -> str:
    parts = [p.lower() for p in run_dir.parts]

    for part in parts:
        if part.startswith("pass"):
            return part

    return "unknown"


def read_memory_run(run_dir: Path) -> pd.DataFrame | None:
    metrics_file = run_dir / "runtime-metrics.csv"
    if not metrics_file.exists():
        return None

    df = pd.read_csv(metrics_file)

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
        raise ValueError(
            f"No memory column found in {metrics_file}. Columns: {list(df.columns)}"
        )

    if "elapsed_seconds" in df.columns:
        df["time_seconds"] = df["elapsed_seconds"]
    else:
        time_col = "timestamp"
        if time_col not in df.columns:
            raise ValueError(
                f"No elapsed_seconds or timestamp column found in {metrics_file}. "
                f"Columns: {list(df.columns)}"
            )

        df[time_col] = pd.to_datetime(df[time_col], utc=True, errors="coerce")
        df = df.dropna(subset=[time_col])
        df["time_seconds"] = (df[time_col] - df[time_col].min()).dt.total_seconds()

    scenario = detect_scenario(run_dir)

    df["runtime"] = detect_runtime(run_dir)
    df["scenario"] = scenario
    df["scenario_label"] = SCENARIO_LABELS.get(scenario, scenario)
    df["pass"] = detect_pass(run_dir)
    df["run_id"] = run_dir.name
    df["run_dir"] = str(run_dir)
    df["memory_metric"] = memory_col

    if memory_col.endswith("_bytes") or memory_col.endswith("Bytes"):
        df["memory_mb"] = df[memory_col] / 1024 / 1024
    else:
        df["memory_mb"] = df[memory_col]

    if "phase" not in df.columns:
        df["phase"] = "unknown"

    return df[
        [
            "time_seconds",
            "memory_mb",
            "runtime",
            "scenario",
            "scenario_label",
            "pass",
            "phase",
            "run_id",
            "memory_metric",
            "run_dir",
        ]
    ]


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

    all_df = all_df.sort_values(
        ["scenario", "pass", "runtime", "run_id", "time_seconds"]
    )

    fig = px.line(
        all_df,
        x="time_seconds",
        y="memory_mb",
        color="runtime",
        line_group="run_id",
        facet_col="scenario_label",
        hover_data=[
            "scenario_label",
            "pass",
            "phase",
            "run_id",
            "memory_metric",
            "run_dir",
        ],
        title="Память во времени",
        labels={
            "time_seconds": "Время от старта run, сек",
            "memory_mb": "Память, MB",
            "runtime": "Runtime",
            "scenario_label": "Сценарий",
            "pass": "Pass",
            "phase": "Фаза",
            "run_id": "Run",
            "memory_metric": "Метрика памяти",
            "run_dir": "Папка run",
        },
    )

    fig.update_layout(
        template="plotly_white",
        hovermode="x unified",
    )

    max_memory = all_df["memory_mb"].max()
    y_max = max_memory * 1.10

    fig.update_yaxes(
        matches="y",
        range=[0, y_max],
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.write_html(OUT)
    print(f"Saved: {OUT}")


if __name__ == "__main__":
    main()