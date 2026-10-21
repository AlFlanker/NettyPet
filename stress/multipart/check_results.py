#!/usr/bin/env python3
"""Проверяет CSV JTL: оба метода выполнялись, каждый ответ прошёл integrity assertion."""

import argparse
import csv
import statistics
import sys


def check(path):
    with open(path, newline="", encoding="utf-8-sig") as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise ValueError("JTL contains no samples")
    labels = {"POST multipart (verified)", "PUT multipart (verified)"}
    if {r["label"] for r in rows} != labels:
        raise ValueError("JTL must contain both POST and PUT multipart samples only")
    failures = [r for r in rows if r["success"].lower() != "true" or r["responseCode"] != "200"]
    for label in sorted(labels):
        samples = [r for r in rows if r["label"] == label]
        elapsed = sorted(int(r["elapsed"]) for r in samples)
        print(f"{label}: {len(samples)} requests, mean {statistics.mean(elapsed):.1f} ms, "
              f"p95 {elapsed[int(.95*(len(elapsed)-1))]} ms")
    if failures:
        for row in failures[:5]:
            print(f"FAIL {row['label']}: HTTP {row['responseCode']}; "
                  f"{row.get('failureMessage', '')}", file=sys.stderr)
        raise ValueError(f"{len(failures)} of {len(rows)} requests failed")
    print(f"PASS: all {len(rows)} requests passed upstream and JMeter integrity checks")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jtl", help="CSV JTL from a fresh multipart run")
    args = parser.parse_args()
    try:
        check(args.jtl)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"FAIL: {error}\n")
