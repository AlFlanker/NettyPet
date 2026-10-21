# Allocation experiment

This harness compares **two already built executable app JARs**. It adds no runtime
dependency and no Gradle module. Python 3 and a full JDK 21 are required.

The checked-in `results.json` describes the optimization revision before the later
idle-pool/JMX review changes. Match its candidate JAR SHA-256 against the artifact
being evaluated; those historical percentages are not measurements of a newer build.

```bash
python3 perf/run_allocations.py --jar ../baseline/app.jar \
  --java-home "$JAVA_HOME" --output ../measurements/baseline --label baseline
python3 perf/run_allocations.py --jar app/build/libs/app-1.0.0.jar \
  --java-home "$JAVA_HOME" --output ../measurements/candidate --label candidate
```

Use a different output directory for each run. Run the two versions on the same
otherwise idle machine. The harness launches a loopback HTTP/1.1 upstream and the
application with `-Xms256m -Xmx256m -XX:+UseG1GC`, two Netty I/O loops and four
concurrent clients. Each phase has warmup followed by a measurement window:

| Phase | Default measured requests | Payload |
| --- | ---: | --- |
| HEAD | 5000 | No response body |
| Upload | 1024 | 1 MiB POST, backend validates SHA-256 |
| Download | 1024 | 1 MiB GET, client validates SHA-256 |
| Tiny HTTP chunks | 64 | 64 KiB body in one-byte HTTP chunks; upstream emits framing in a large write |

The measurement-only Java agent starts JFR **after warmup**, takes per-platform-thread
allocation counters from `com.sun.management.ThreadMXBean`, and stops recording when
the batch finishes. Its control socket binds only loopback and requires a random
per-run token. The agent is not part of the executable application and must not be
used in a production command.

A separate `allocator-warmup.jfr` records the first few proxy requests before the
main warmups. It can expose initial backing-array allocations of Netty heap arenas;
these results are not mixed into the steady-state bytes/request comparison. The
agent records the actual G1 region size for interpreting large heap arrays.

`results.json` contains JAR SHA-256, JVM version, workload, per-thread allocation
deltas, local timing diagnostics and JFR allocation sites. The `.jfr` files can be
opened in JDK Mission Control. An abbreviated result is printed after every phase.

Create a compact comparison without machine-local paths using:

```bash
python3 perf/summarize_results.py \
  --baseline ../measurements/baseline/results.json \
  --candidate ../measurements/candidate/results.json \
  --output perf/results.json
```

The checked-in comparison also preserves the first rejected candidate using
`--initial-candidate`: it exposed a regression for one-byte HTTP chunks and prompted
bounded coalescing of already queued fragments before native writes.

## Recorded comparison

One comparison on 2026-10-20, JDK 21.0.12.1, using the default protocol above.
Numbers are heap allocation bytes per request from surviving platform-thread counters.

| Phase | Baseline B/request | Final B/request | Change in this run |
| --- | ---: | ---: | ---: |
| HEAD | 30,592.55 | 13,441.11 | -56.06% |
| Upload 1 MiB | 78,961.61 | 59,572.51 | -24.56% |
| Download 1 MiB | 96,208.25 | 71,376.87 | -25.81% |
| Tiny HTTP chunks | 2,809,241.50 | 2,810,232.38 | +0.035% |

The first native-write candidate allocated 36,683,564 B/request for the tiny-chunk
case. Bounded coalescing removed that regression; final allocation was approximately
the baseline level. This preserves an intentional copy for queued tiny fragments to
avoid thousands of native writes and callbacks, while larger chunks use direct views.

The separate first-touch recording captured two Netty heap-arena arrays of 4,194,320
bytes each in the baseline and none in the final candidate. G1 used 1 MiB regions,
so those baseline arrays were humongous allocations outside TLAB. This observation
does not measure direct-memory consumption. No measured phase lost a previously
observed thread; baseline HEAD added one thread and final phases added none.

JAR hashes, exact counters, stack evidence and diagnostic timings are preserved in
`perf/results.json`. These percentages describe this comparison only, not a production
capacity guarantee or a statistically established improvement.

## Interpretation limits

- `allocatedBytesSurvivingThreads` measures Java heap allocations on threads alive
  at the end of the window, excluding the measurement-control thread. New surviving
  threads are included from allocation counter zero. Allocations of threads that
  exit inside the window may be missed; check `disappearedThreads`. A thread born
  and terminated between both snapshots is not detected even when this count is zero. Platform-thread
  counters are not a complete accounting for an application using virtual threads.
- These are allocation **bytes**, not retained heap, direct/native memory, or RSS.
  They include application/framework activity and JFR overhead. Netty pooling
  caches must be warmed; large allocator backing arrays may be allocated before
  the recording window.
- `ObjectAllocationSample` weights estimate allocation volume and identify stacks;
  sample counts are not exact object counts. The analyzer discards each thread's
  first sample because its weight can include allocations before the recording.
  Sparse samples still must not be used for exact bytes/op claims. `ObjectAllocationOutsideTLAB` captures
  large-array events, but absence during the measured window does not prove that
  no large arrays were allocated during startup/warmup.
- Measurements use loopback cleartext HTTP, a Python fixture, a small heap and a
  short run. Timing numbers are diagnostics, not production capacity/SLO evidence.
  Compare multiple runs before treating a percentage change as a stable improvement.
- The tiny-chunk case detects the cost of decoding many content objects and issuing
  many downstream writes. It should not be replaced by an equal-sized single chunk.
- Netty leak detection is disabled **only in this allocation experiment**. The normal
  Gradle test suite keeps its paranoid leak detector enabled.

Do not infer that zero-copy across all layers is possible from a reduction here:
Servlet/container input still needs a destination buffer, and TLS/kernel layers have
their own processing. Correct buffer lifetime remains a separate regression gate.
