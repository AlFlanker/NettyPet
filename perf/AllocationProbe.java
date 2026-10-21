package perf;

import com.sun.management.ThreadMXBean;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Измерительный Java-агент для локального сравнения выделений памяти.
 * Подключается только к экспериментальному запуску, а не к рабочему приложению.
 */
public final class AllocationProbe {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static Recording recording;
    private static Map<Long, Long> before;
    private static long started;
    private static Path recordingPath;
    private static long controlThread;

    /**
     * Включает счётчики памяти потоков и локальный канал команд START/STOP для стенда.
     * Один управляющий поток последовательно меняет состояние записи JFR и снимков счётчиков.
     */
    public static void premain(String outputDirectory, Instrumentation unused) throws Exception {
        Path directory = Path.of(outputDirectory).toAbsolutePath();
        Files.createDirectories(directory);
        if (!THREADS.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("This JVM does not expose per-thread allocation counters");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        String token = UUID.randomUUID().toString();
        Files.writeString(directory.resolve("control.json"), json(Map.of("port", server.getLocalPort(), "token", token)));
        Thread thread = new Thread(() -> {
            controlThread = Thread.currentThread().threadId();
            for (;;) {
                try (var socket = server.accept();
                     var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    socket.setSoTimeout(10000);
                    String[] request = input.readLine().split(" ");
                    if (!request[0].equals(token)) throw new IllegalArgumentException("Invalid measurement token");
                    Object answer;
                    if (request.length == 3 && request[1].equals("START") && request[2].matches("[a-z0-9_-]+")) {
                        if (recording != null) throw new IllegalStateException("Recording is already running");
                        recordingPath = directory.resolve(request[2] + ".jfr");
                        recording = new Recording();
                        recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "500/s");
                        recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                        recording.enable("jdk.GarbageCollection");
                        recording.setMaxSize(128L * 1024 * 1024);
                        recording.start();
                        // Снимок после запуска JFR исключает его первоначальную настройку из дельты.
                        before = allocated();
                        started = System.nanoTime();
                        answer = Map.of("started", true);
                    } else if (request.length == 2 && request[1].equals("STOP")) {
                        if (recording == null) throw new IllegalStateException("No recording");
                        Map<Long, Long> after = allocated();
                        long elapsed = System.nanoTime() - started;
                        long bytes = 0;
                        int created = 0;
                        List<Map<String, Object>> perThread = new ArrayList<>();
                        // Считаем дельты только потоков, доступных в конечном снимке. Для нового
                        // потока начальное значение принимается за ноль; умершие потоки не учтены.
                        for (var item : after.entrySet()) {
                            Long previous = before.get(item.getKey());
                            if (previous == null) created++;
                            long delta = Math.max(0, item.getValue() - (previous == null ? 0 : previous));
                            bytes += delta;
                            var info = THREADS.getThreadInfo(item.getKey());
                            perThread.add(Map.of("id", item.getKey(), "name", info == null ? "terminated" : info.getThreadName(), "allocatedBytes", delta));
                        }
                        long disappeared = before.keySet().stream().filter(id -> !after.containsKey(id)).count();
                        recording.stop();
                        recording.dump(recordingPath);
                        recording.close();
                        recording = null;
                        perThread.sort(Comparator.comparingLong(value -> -((Number) value.get("allocatedBytes")).longValue()));
                        answer = Map.of("allocatedBytesSurvivingThreads", bytes,
                                "elapsedNanos", elapsed, "newThreads", created, "disappearedThreads", disappeared,
                                "threads", perThread, "jfr", recordingPath.toString(),
                                "g1RegionBytes", Long.parseLong(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("G1HeapRegionSize").getValue()));
                    } else throw new IllegalArgumentException("Unknown measurement command");
                    output.write(json(answer));
                    output.newLine();
                    output.flush();
                } catch (Throwable failure) {
                    failure.printStackTrace(System.err);
                }
            }
        }, "allocation-measurement-control");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Снимает доступные счётчики выделенной памяти живых платформенных потоков.
     * Управляющий поток исключён, чтобы обработка измерительных команд не увеличивала итог.
     */
    private static Map<Long, Long> allocated() {
        long[] ids = THREADS.getAllThreadIds();
        long[] bytes = THREADS.getThreadAllocatedBytes(ids);
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != controlThread && bytes[i] >= 0) result.put(ids[i], bytes[i]);
        }
        return result;
    }

    /**
     * Разбирает готовую запись JFR в отдельной JVM после измерения.
     * Выделения памяти парсером поэтому не попадают в счётчики исследуемого процесса.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one JFR filename");
        Map<String, long[]> groups = new HashMap<>();
        List<Map<String, Object>> largeArrays = new ArrayList<>();
        long totalSampleWeight = 0;
        var sampledThreads = new HashSet<Long>();
        long discardedInitialSamples = 0;
        long gcCount = 0;
        long gcPauseNanos = 0;
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.GarbageCollection")) {
                    gcCount++;
                    gcPauseNanos += event.getDuration("sumOfPauses").toNanos();
                    continue;
                }
                if (!type.equals("jdk.ObjectAllocationSample") && !type.equals("jdk.ObjectAllocationOutsideTLAB")) continue;
                String allocatedClass = event.getClass("objectClass").getName();
                String firstApplicationFrame = "";
                boolean initialize = false;
                boolean heapArena = false;
                if (event.getStackTrace() != null) {
                    for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                        String method = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                        if (firstApplicationFrame.isEmpty() && method.startsWith("dev.example.")) firstApplicationFrame = method;
                        initialize |= method.equals("dev.example.proxy.transport.ProxyExchange.initialize");
                        heapArena |= method.contains("PoolArena$HeapArena");
                    }
                }
                if (type.equals("jdk.ObjectAllocationSample")) {
                    // Вес первой выборки потока может включать выделения памяти до запуска записи JFR.
                    // Пропускаем её: выборки указывают места выделений, а не точный объём за окно.
                    if (event.getThread() != null && sampledThreads.add(event.getThread().getJavaThreadId())) {
                        discardedInitialSamples++;
                        continue;
                    }
                    long weight = event.getLong("weight");
                    totalSampleWeight += weight;
                    if (!firstApplicationFrame.isEmpty()) {
                        long[] counters = groups.computeIfAbsent(allocatedClass + " at " + firstApplicationFrame, ignored -> new long[2]);
                        counters[0]++;
                        counters[1] += weight;
                    }
                    if (initialize && (allocatedClass.equals("[B") || allocatedClass.equals("byte[]"))) {
                        long[] counters = groups.computeIfAbsent("INITIALIZE_BYTE_ARRAYS", ignored -> new long[2]);
                        counters[0]++;
                        counters[1] += weight;
                    }
                } else {
                    long size = event.getLong("allocationSize");
                    if (size >= 512 * 1024 && (allocatedClass.equals("[B") || allocatedClass.equals("byte[]")))
                        largeArrays.add(Map.of("allocatedBytes", size, "nettyHeapArena", heapArena, "applicationFrame", firstApplicationFrame));
                }
            }
        }
        List<Map<String, Object>> sorted = groups.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> -entry.getValue()[1]))
                .limit(30).map(entry -> Map.<String, Object>of("site", entry.getKey(), "samples", entry.getValue()[0], "estimatedBytes", entry.getValue()[1])).toList();
        System.out.println(json(Map.of("sampledAllocationWeightBytesAfterFirstPerThread", totalSampleWeight,
                "discardedInitialSamples", discardedInitialSamples,
                "applicationAllocationSites", sorted, "largeByteArrayOutsideTlabEvents", largeArrays,
                "gcCount", gcCount, "gcPauseNanos", gcPauseNanos)));
    }

    /** Сериализует используемые в отчёте строки, числа, логические значения, карты и списки. */
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof Iterable<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object entry : list) entries.add(json(entry));
            return "[" + String.join(",", entries) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON type " + value.getClass());
    }
}
