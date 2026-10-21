#!/usr/bin/env python3
"""Готовит компактное сравнение без локальных путей и токенов доступа к управляющему каналу."""
import argparse
import json
from pathlib import Path


def compact_run(raw):
    """Оставляет параметры запуска, измеренные дельты и свидетельства JFR для сравнения."""
    result = {key: raw[key] for key in
              ("label", "jarSha256", "javaVersion", "workers", "bodyBytes", "tinyBodyBytes", "heapBytes")}
    warmup = raw["allocatorWarmup"]
    result["allocatorWarmup"] = {
        "g1RegionBytes": warmup["g1RegionBytes"],
        "largeByteArrayOutsideTlabEvents": warmup["allocationProfile"]["largeByteArrayOutsideTlabEvents"],
    }
    result["phases"] = {}
    for name, phase in raw["phases"].items():
        result["phases"][name] = {key: phase[key] for key in (
            "requests", "allocatedBytesSurvivingThreads", "allocatedBytesPerRequestSurvivingThreads",
            "newThreads", "disappearedThreads", "durationNanos", "elapsedNanos",
            "localRequestsPerSecond", "localP99Millis", "g1RegionBytes")}
        result["phases"][name]["jfrEvidence"] = {
            "gcCount": phase["allocationProfile"]["gcCount"],
            "gcPauseNanos": phase["allocationProfile"]["gcPauseNanos"],
            "largeByteArrayOutsideTlabEvents": phase["allocationProfile"]["largeByteArrayOutsideTlabEvents"],
            "allocationSites": [{"site": site["site"], "samples": site["samples"]}
                                for site in phase["allocationProfile"]["applicationAllocationSites"]],
        }
    return result


def compare(baseline, candidate):
    """Проверяет сопоставимость настроек и числа запросов; вычисляет изменение выделений на запрос."""
    for setting in ("javaVersion", "workers", "bodyBytes", "tinyBodyBytes", "heapBytes"):
        if baseline[setting] != candidate[setting]:
            raise ValueError("Non-comparable setting: " + setting)
    result = {}
    for name, phase in baseline["phases"].items():
        other = candidate["phases"][name]
        if phase["requests"] != other["requests"]:
            raise ValueError("Request counts differ: " + name)
        before = phase["allocatedBytesPerRequestSurvivingThreads"]
        after = other["allocatedBytesPerRequestSurvivingThreads"]
        result[name] = {"baselineBytesPerRequest": before, "candidateBytesPerRequest": after,
                        "changeBytesPerRequest": after - before,
                        "changePercentInThisRun": 100 * (after / before - 1)}
    return result


def main():
    """Читает отчёты запусков и сохраняет сравнение с ограничениями метода измерения."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--initial-candidate", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    baseline = compact_run(json.loads(args.baseline.read_text()))
    candidate = compact_run(json.loads(args.candidate.read_text()))
    result = {
        "protocol": "perf/run_allocations.py defaults; one comparison per revision; HTTP/1.1 loopback",
        "allocationMetric": "ThreadMXBean heap allocation deltas on surviving platform threads, excluding measurement-control thread",
        "limits": [
            "One short comparison is evidence for these workloads, not a stable production percentage or capacity claim.",
            "New surviving threads count from zero; terminated threads may be missed, including threads born and terminated between snapshots.",
            "Counters include application, framework and recording overhead; they exclude direct/native memory and retained-heap/RSS measurements.",
            "JFR sample counts identify stacks, not exact object counts. All byte arrays under initialize share a stack group; it does not identify their sizes.",
            "Warmup records first-touch heap-array events separately; an absent event does not prove global absence of large arrays.",
            "Local Python fixture timings are diagnostics; JFR and the fixture affect results.",
        ],
        "baseline": baseline,
        "candidate": candidate,
        "comparison": compare(baseline, candidate),
    }
    if args.initial_candidate:
        initial = compact_run(json.loads(args.initial_candidate.read_text()))
        result["rejectedCandidateWithoutCoalescing"] = initial
        result["rejectedCandidateComparison"] = compare(baseline, initial)
        result["regressionResolution"] = (
            "An initial native-write implementation caused a per-small-chunk write/callback allocation regression. "
            "The final candidate coalesces only already queued small fragments into one bounded pooled direct buffer, "
            "without waiting for more input; contiguous chunks retain the direct-view path."
        )
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
