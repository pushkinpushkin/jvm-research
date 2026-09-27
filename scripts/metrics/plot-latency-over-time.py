#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import pandas as pd
import plotly.graph_objects as go
from plotly.subplots import make_subplots


DEFAULT_RESULTS_ROOT = Path("results/local-idle-rare-quick")
DEFAULT_OUT = Path("reports/latency-over-time.html")
DEFAULT_BUCKET = "10s"


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
    name = run_dir.name.lower()

    if "fresh-idle" in name:
        return "fresh-idle"
    if "rare-requests" in name:
        return "rare-requests"
    if "low-load" in name:
        return "low-load"
    if "load" in name:
        return "load"
    if "idle" in name:
        return "idle"

    return "unknown"


def load_json_records(path: Path) -> list[dict[str, Any]]:
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        return []

    try:
        loaded = json.loads(text)
    except json.JSONDecodeError:
        records = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                records.append(value)
        return records

    if isinstance(loaded, list):
        return [item for item in loaded if isinstance(item, dict)]
    if isinstance(loaded, dict):
        for key in ("metrics", "samples", "points", "records", "data"):
            value = loaded.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
        return [loaded]

    return []


def extract_point(record: dict[str, Any]) -> dict[str, Any] | None:
    metric = record.get("metric") or record.get("name")
    data = record.get("data") if isinstance(record.get("data"), dict) else record

    if metric != "http_req_duration":
        return None

    timestamp = data.get("time") or data.get("timestamp")
    value = data.get("value")

    if timestamp is None or value is None:
        return None

    tags = data.get("tags") if isinstance(data.get("tags"), dict) else {}

    try:
        duration_ms = float(value)
    except (TypeError, ValueError):
        return None

    return {
        "timestamp": pd.to_datetime(timestamp, utc=True, errors="coerce"),
        "duration_ms": duration_ms,
        "status": tags.get("status"),
        "error": tags.get("error"),
        "expected_response": tags.get("expected_response"),
    }


def load_latency_samples(results_root: Path) -> pd.DataFrame:
    rows = []

    for path in sorted(results_root.rglob("k6-timeseries.json")):
        run_dir = path.parent
        runtime = detect_runtime(run_dir)
        scenario = detect_scenario(run_dir)

        for record in load_json_records(path):
            point = extract_point(record)
            if point is None:
                continue

            point["runtime"] = runtime
            point["scenario"] = scenario
            point["run_dir"] = str(run_dir)
            rows.append(point)

    if not rows:
        return pd.DataFrame()

    df = pd.DataFrame(rows)
    df = df.dropna(subset=["timestamp", "duration_ms"])
    df["failed"] = df["expected_response"].astype(str).str.lower().eq("false")
    return df.sort_values(["scenario", "runtime", "timestamp"])


def aggregate_latency(df: pd.DataFrame, bucket: str) -> pd.DataFrame:
    grouped = (
        df.set_index("timestamp")
        .groupby(["scenario", "runtime", "run_dir", pd.Grouper(freq=bucket)])
        .agg(
            p50_ms=("duration_ms", lambda values: values.quantile(0.50)),
            p95_ms=("duration_ms", lambda values: values.quantile(0.95)),
            p99_ms=("duration_ms", lambda values: values.quantile(0.99)),
            avg_ms=("duration_ms", "mean"),
            max_ms=("duration_ms", "max"),
            requests=("duration_ms", "size"),
            failures=("failed", "sum"),
        )
        .reset_index()
        .rename(columns={"timestamp": "bucket_start"})
    )

    starts = grouped.groupby("run_dir")["bucket_start"].transform("min")
    grouped["seconds_from_start"] = (grouped["bucket_start"] - starts).dt.total_seconds()
    grouped["label"] = grouped["runtime"] + " / " + grouped["scenario"]
    return grouped.sort_values(["scenario", "runtime", "seconds_from_start"])


def build_figure(agg: pd.DataFrame) -> go.Figure:
    scenarios = list(agg["scenario"].drop_duplicates())
    fig = make_subplots(
        rows=max(len(scenarios), 1),
        cols=1,
        shared_xaxes=False,
        vertical_spacing=0.10,
        subplot_titles=[f"Latency over time: {scenario}" for scenario in scenarios],
    )

    colors = {
        "HotSpot": "#1f77b4",
        "OpenJ9": "#ff7f0e",
        "GraalVM JIT": "#2ca02c",
        "GraalVM Native": "#d62728",
    }

    for row, scenario in enumerate(scenarios, start=1):
        part = agg[agg["scenario"] == scenario]

        for runtime in part["runtime"].drop_duplicates():
            series = part[part["runtime"] == runtime]
            color = colors.get(runtime)
            show_legend = row == 1

            fig.add_trace(
                go.Scatter(
                    x=series["seconds_from_start"],
                    y=series["p95_ms"],
                    mode="lines+markers",
                    name=f"{runtime} p95",
                    legendgroup=runtime,
                    showlegend=show_legend,
                    line={"color": color, "width": 2},
                    hovertemplate=(
                        "runtime=%{customdata[0]}<br>"
                        "scenario=%{customdata[1]}<br>"
                        "t=%{x:.0f}s<br>"
                        "p50=%{customdata[2]:.2f} ms<br>"
                        "p95=%{y:.2f} ms<br>"
                        "p99=%{customdata[3]:.2f} ms<br>"
                        "avg=%{customdata[4]:.2f} ms<br>"
                        "max=%{customdata[5]:.2f} ms<br>"
                        "requests=%{customdata[6]}<br>"
                        "failures=%{customdata[7]}"
                        "<extra></extra>"
                    ),
                    customdata=series[
                        [
                            "runtime",
                            "scenario",
                            "p50_ms",
                            "p99_ms",
                            "avg_ms",
                            "max_ms",
                            "requests",
                            "failures",
                        ]
                    ],
                ),
                row=row,
                col=1,
            )

            fig.add_trace(
                go.Scatter(
                    x=series["seconds_from_start"],
                    y=series["p99_ms"],
                    mode="lines",
                    name=f"{runtime} p99",
                    legendgroup=runtime,
                    showlegend=False,
                    line={"color": color, "width": 1, "dash": "dot"},
                    hovertemplate=(
                        "runtime=%{customdata[0]}<br>"
                        "scenario=%{customdata[1]}<br>"
                        "t=%{x:.0f}s<br>"
                        "p99=%{y:.2f} ms"
                        "<extra></extra>"
                    ),
                    customdata=series[["runtime", "scenario"]],
                ),
                row=row,
                col=1,
            )

            failures = series[series["failures"] > 0]
            if not failures.empty:
                fig.add_trace(
                    go.Scatter(
                        x=failures["seconds_from_start"],
                        y=failures["max_ms"],
                        mode="markers",
                        name=f"{runtime} failures",
                        legendgroup=runtime,
                        showlegend=False,
                        marker={"color": color, "symbol": "x", "size": 10},
                        hovertemplate=(
                            "runtime=%{customdata[0]}<br>"
                            "scenario=%{customdata[1]}<br>"
                            "t=%{x:.0f}s<br>"
                            "failures=%{customdata[2]}<br>"
                            "max=%{y:.2f} ms"
                            "<extra></extra>"
                        ),
                        customdata=failures[["runtime", "scenario", "failures"]],
                    ),
                    row=row,
                    col=1,
                )

        fig.update_yaxes(title_text="duration, ms", row=row, col=1)
        fig.update_xaxes(title_text="seconds from run start", row=row, col=1)

    fig.update_layout(
        title="Latency Over Time",
        height=max(420 * len(scenarios), 520),
        hovermode="x unified",
        template="plotly_white",
        legend_title_text="Runtime",
    )
    return fig


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build latency-over-time report from k6 time series files."
    )
    parser.add_argument("--results-root", type=Path, default=DEFAULT_RESULTS_ROOT)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--bucket", default=DEFAULT_BUCKET)
    args = parser.parse_args()

    df = load_latency_samples(args.results_root)
    if df.empty:
        raise SystemExit(
            f"No http_req_duration samples found under {args.results_root}. "
            "Expected k6-timeseries.json files with k6 Point records."
        )

    agg = aggregate_latency(df, args.bucket)
    args.out.parent.mkdir(parents=True, exist_ok=True)

    fig = build_figure(agg)
    fig.write_html(args.out, include_plotlyjs="cdn")

    csv_out = args.out.with_suffix(".csv")
    agg.to_csv(csv_out, index=False)

    print(f"Wrote {args.out}")
    print(f"Wrote {csv_out}")
    print(f"Samples: {len(df)}")
    print(f"Buckets: {len(agg)}")


if __name__ == "__main__":
    main()
