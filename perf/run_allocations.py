#!/usr/bin/env python3
"""Small HTTP/1.1 allocation experiment; not a production capacity benchmark."""
import argparse
import concurrent.futures
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

BODY = bytes(range(256)) * 4096  # One MiB, reused by the external fixture.
DIGEST = hashlib.sha256(BODY).hexdigest()
TINY_BODY = b"x" * 65536
TINY_DIGEST = hashlib.sha256(TINY_BODY).hexdigest()
TINY_CHUNKED = b"1\r\nx\r\n" * len(TINY_BODY) + b"0\r\n\r\n"


class Upstream(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()

    def do_GET(self):
        self.send_response(200)
        if self.path.startswith("/tiny"):
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            self.wfile.write(TINY_CHUNKED)
            return
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def do_POST(self):
        remaining = int(self.headers["Content-Length"])
        digest = hashlib.sha256()
        while remaining:
            block = self.rfile.read(min(65536, remaining))
            if not block:
                raise IOError("Truncated upload")
            digest.update(block)
            remaining -= len(block)
        if digest.hexdigest() != DIGEST:
            raise IOError("Upload content changed")
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def command(control, message):
    with socket.create_connection(("127.0.0.1", control["port"]), timeout=15) as sock:
        sock.sendall((control["token"] + " " + message + "\n").encode())
        with sock.makefile("rb") as reply:
            return json.loads(reply.readline())


def batch(port, mode, count, workers):
    latencies = []

    def work(number):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
        samples = []
        try:
            for _ in range(number):
                started = time.perf_counter_ns()
                if mode == "head":
                    connection.request("HEAD", "/proxy/head")
                elif mode == "upload":
                    connection.request("POST", "/proxy/upload", BODY, {"Content-Type": "application/octet-stream"})
                else:
                    connection.request("GET", "/proxy/" + mode)
                response = connection.getresponse()
                data = response.read()
                expected = 204 if mode == "upload" else 200
                if response.status != expected:
                    raise IOError(f"Unexpected {response.status}: {data[:100]!r}")
                if mode == "download" and hashlib.sha256(data).hexdigest() != DIGEST:
                    raise IOError("Download content changed")
                if mode == "tiny" and hashlib.sha256(data).hexdigest() != TINY_DIGEST:
                    raise IOError("Tiny-chunk download content changed")
                samples.append(time.perf_counter_ns() - started)
        finally:
            connection.close()
        return samples

    start = time.perf_counter_ns()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(work, count // workers + (index < count % workers)) for index in range(workers)]
        for future in futures:
            latencies.extend(future.result())
    elapsed = time.perf_counter_ns() - start
    ordered = sorted(latencies)
    return {"requests": len(ordered), "durationNanos": elapsed,
            "localRequestsPerSecond": len(ordered) * 1e9 / elapsed,
            "localP99Millis": ordered[min(len(ordered) - 1, int(len(ordered) * 0.99))] / 1e6}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"), required="JAVA_HOME" not in os.environ)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--head-count", type=int, default=5000)
    parser.add_argument("--body-count", type=int, default=1024)
    parser.add_argument("--tiny-count", type=int, default=64)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    java_home = args.java_home.resolve()
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["LD_LIBRARY_PATH"] = str(java_home / "lib") + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    source = Path(__file__).with_name("AllocationProbe.java")
    classes = output / "agent-classes"
    classes.mkdir(exist_ok=True)
    subprocess.run([str(java_home / "bin/javac"), "-d", str(classes), str(source)], env=env, check=True)
    manifest = output / "agent-manifest.mf"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: perf.AllocationProbe\n\n")
    agent = output / "allocation-probe.jar"
    subprocess.run([str(java_home / "bin/jar"), "--create", "--file", str(agent), "--manifest", str(manifest), "-C", str(classes), "."], env=env, check=True)
    server = Server(("127.0.0.1", 0), Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = free_port()
    log = (output / "application.log").open("w")
    control_file = output / "control.json"
    control_file.unlink(missing_ok=True)
    temporary = output / "tmp"
    temporary.mkdir(exist_ok=True)
    launch = [str(java_home / "bin/java"), "-Xms256m", "-Xmx256m", "-XX:+UseG1GC",
              "-Djava.io.tmpdir=" + str(temporary),
              "-Dio.netty.leakDetection.level=disabled", "-javaagent:" + str(agent) + "=" + str(output),
              "-jar", str(args.jar.resolve()), "--server.address=127.0.0.1", "--server.port=" + str(port),
              "--proxy.upstream=http://127.0.0.1:" + str(server.server_port),
              "--proxy.io-threads=2", "--proxy-command.timeout=30s", "--proxy.exchange-timeout=40s",
              "--spring.main.banner-mode=off", "--logging.level.root=WARN"]
    application = subprocess.Popen(launch, stdout=log, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.monotonic() + 30
        while True:
            if application.poll() is not None:
                raise RuntimeError("Application exited; inspect " + str(output / "application.log"))
            try:
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                connection.request("GET", "/local")
                response = connection.getresponse()
                response.read()
                connection.close()
                if response.status == 200 and control_file.exists():
                    break
            except (OSError, http.client.HTTPException):
                pass
            if time.monotonic() > deadline:
                raise TimeoutError("Application did not start")
            time.sleep(0.05)
        control = json.loads(control_file.read_text())
        results = {"label": args.label, "jarSha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
                   "javaVersion": subprocess.check_output([str(java_home / "bin/java"), "-version"], stderr=subprocess.STDOUT, env=env, text=True).strip(),
                   "workers": args.workers, "bodyBytes": len(BODY), "tinyBodyBytes": len(TINY_BODY), "heapBytes": 256 * 1024 * 1024,
                   "method": "ThreadMXBean deltas of live threads; control thread excluded; JFR allocation sample weights are estimates; local HTTP fixture is not a production benchmark",
                   "phases": {}}
        # Separate first-touch slab evidence from post-warmup allocation comparisons.
        command(control, "START allocator-warmup")
        for mode in ["head", "upload", "download", "tiny"]:
            batch(port, mode, args.workers * 2, args.workers)
        warmup = command(control, "STOP")
        warmup["allocationProfile"] = json.loads(subprocess.check_output(
                [str(java_home / "bin/java"), "-cp", str(agent), "perf.AllocationProbe", str(output / "allocator-warmup.jfr")], env=env, text=True))
        results["allocatorWarmup"] = warmup
        for mode in ["head", "upload", "download", "tiny"]:
            count = args.head_count if mode == "head" else (args.tiny_count if mode == "tiny" else args.body_count)
            batch(port, mode, max(args.workers, count // 5), args.workers)
            command(control, "START " + mode)
            timings = batch(port, mode, count, args.workers)
            counters = command(control, "STOP")
            analysis = json.loads(subprocess.check_output([str(java_home / "bin/java"), "-cp", str(agent), "perf.AllocationProbe", str(output / (mode + ".jfr"))], env=env, text=True))
            counters["allocatedBytesPerRequestSurvivingThreads"] = counters["allocatedBytesSurvivingThreads"] / count
            results["phases"][mode] = {**timings, **counters, "allocationProfile": analysis}
            (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
            print(json.dumps({"label": args.label, "phase": mode, "requests": count,
                              "allocatedBytesPerRequest": counters["allocatedBytesPerRequestSurvivingThreads"],
                              "disappearedThreads": counters["disappearedThreads"]}), flush=True)
    finally:
        application.terminate()
        try:
            application.wait(timeout=15)
        except subprocess.TimeoutExpired:
            application.kill()
            application.wait()
        server.shutdown()
        server.server_close()
        log.close()


if __name__ == "__main__":
    main()
