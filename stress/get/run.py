#!/usr/bin/env python3
"""Запускает параметризованную копию GET JMX; сохраняет условия и проверяет результаты."""

import argparse
import csv
import hashlib
import http.client
import json
import os
from pathlib import Path
import statistics
import subprocess
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


def fingerprint(url):
    target = urlsplit(url)
    if target.scheme not in ("http", "https") or not target.hostname:
        raise ValueError("Expected an HTTP(S) URL")
    connection = (http.client.HTTPSConnection if target.scheme == "https" else http.client.HTTPConnection)(
        target.hostname, target.port, timeout=30)
    try:
        path = target.path or "/"
        connection.request("GET", path+("?"+target.query if target.query else ""))
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError(f"Preflight {url}: HTTP {response.status}")
        size, digest = 0, hashlib.sha256()
        while block := response.read(65536):
            digest.update(block)
            size += len(block)
        if not size:
            raise ValueError("Empty preflight body")
        return dict(size=size, sha256=digest.hexdigest())
    finally:
        connection.close()


def process_cpu(pid):
    if pid is None:
        return None
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11])+int(fields[12]))/os.sysconf("SC_CLK_TCK")


def run(args):
    if min(args.threads, args.duration) < 1 or args.ramp < 0:
        raise ValueError("threads/duration must be positive; ramp must be nonnegative")
    args.output.mkdir(parents=True, exist_ok=False)
    expected = fingerprint(args.reference_url)
    for _ in range(3):
        if fingerprint(args.url) != expected:
            raise ValueError("Preflight: proxy changed the response body")
    target = urlsplit(args.url)
    if target.query or target.fragment:
        raise ValueError("Use a URL without query/fragment; the plan adds q=UUID")
    tree = ET.parse(Path(__file__).with_name("getRootNav2 (500 rps)_.jmx"))
    values = {"ThreadGroup.num_threads": args.threads, "ThreadGroup.duration": args.duration,
              "ThreadGroup.ramp_time": args.ramp, "HTTPSampler.domain": target.hostname,
              "HTTPSampler.port": target.port or (443 if target.scheme == "https" else 80),
              "HTTPSampler.protocol": target.scheme,
              "HTTPSampler.path": (target.path or "/")+"?q=${__UUID()}"}
    for element in tree.iter():
        if element.get("name") in values:
            element.text = str(values[element.get("name")])
    for sampler in tree.iter("HTTPSamplerProxy"):
        for name, value in (("connect_timeout", "5000"), ("response_timeout", "30000")):
            ET.SubElement(sampler, "stringProp", name="HTTPSampler."+name).text = value
    # Проверяем размер каждого ответа; полное SHA-256 до/после нагрузки — вне измерения.
    group = next(tree.iter("ThreadGroup"))
    for parent in tree.iter("hashTree"):
        children = list(parent)
        if group not in children:
            continue
        scope = children[children.index(group)+1]
        assertion = ET.SubElement(scope, "JSR223Assertion", guiclass="TestBeanGUI",
                                  testclass="JSR223Assertion", testname="Complete GET body size", enabled="true")
        for key, value in (("scriptLanguage", "groovy"), ("cacheKey", "true"),
                           ("script", f"if (prev.getResponseData().length != {expected['size']}) {{ "
                            "AssertionResult.setFailure(true); "
                            "AssertionResult.setFailureMessage('Response body size changed'); }")):
            ET.SubElement(assertion, "stringProp", name=key).text = value
        ET.SubElement(scope, "hashTree")
        break
    plan = args.output/"plan.jmx"
    tree.write(plan, encoding="UTF-8", xml_declaration=True)
    before = process_cpu(args.proxy_pid)
    with (args.output/"summary.log").open("w") as log:
        subprocess.run([args.jmeter, "-n", "-t", str(plan), "-l", str(args.output/"results.jtl"),
                        "-j", str(args.output/"jmeter.log")], stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=args.duration+90)
    after = process_cpu(args.proxy_pid)
    with (args.output/"results.jtl").open(newline="") as source:
        rows = list(csv.DictReader(source))
    if not rows or any(r["success"] != "true" or r["responseCode"] != "200" for r in rows):
        raise ValueError("GET samples are missing or failed; inspect results.jtl and logs")
    elapsed = sorted(int(r["elapsed"]) for r in rows)
    duration = (max(int(r["timeStamp"])+int(r["elapsed"]) for r in rows)
                - min(int(r["timeStamp"]) for r in rows))/1000
    result = dict(url=args.url, reference_url=args.reference_url, body=expected,
                  threads=args.threads, ramp_seconds=args.ramp, timer_rps=300,
                  duration_seconds=duration, requests=len(rows), errors=0,
                  rps=len(rows)/duration, mean_ms=statistics.mean(elapsed),
                  p95_ms=elapsed[int(.95*(len(elapsed)-1))])
    if before is not None:
        result.update(proxy_cpu_seconds=after-before, proxy_cpu_ms_per_request=(after-before)*1000/len(rows))
    for _ in range(3):
        if fingerprint(args.url) != expected:
            raise ValueError("Postflight: proxy changed the response body")
    (args.output/"results.json").write_text(json.dumps(result, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080/proxy")
    parser.add_argument("--reference-url", default="http://127.0.0.1:9000/")
    parser.add_argument("--jmeter", default="jmeter")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=20)
    parser.add_argument("--duration", type=int, default=25)
    parser.add_argument("--ramp", type=int, default=2)
    parser.add_argument("--proxy-pid", type=int, help="PID Java для учёта CPU (Linux /proc)")
    args = parser.parse_args()
    try:
        run(args)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"FAIL: {error}\n")
