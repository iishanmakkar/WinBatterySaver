package com.batterysaver.service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Process usage estimated via ProcessHandle CPU duration deltas.
 * Single-sample CPU% is noisy - we sample every 5s, keep 6-sample ring (30s window), average.
 * Calls via ProcessCpuSampler; static facade for UI.
 */
public class ProcessUsageService {
    private static final ProcessCpuSampler sampler = new ProcessCpuSampler();

    public static Map<String, Double> getTopCpuProcesses(int max) {
        return sampler.getTopAveraged(max);
    }

    public static void startSampling() { sampler.start(); }
    public static void stopSampling() { sampler.stop(); }
    public static String getDisclaimer() {
        return "Estimated impact - Windows does not expose per-app battery draw.";
    }

    /**
     * Internal sampler: ring buffer per PID, 15s interval, 4 samples = 60s window.
     */
    static class ProcessCpuSampler {
        private static final int INTERVAL_SECONDS = 15;
        private static final int WINDOW = 4;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpu-sampler");
            t.setDaemon(true);
            return t;
        });
        // pid -> deque of last WINDOW cpu% samples
        private final Map<Long, Deque<Double>> ring = new ConcurrentHashMap<>();
        private final Map<Long, Duration> prevCpu = new ConcurrentHashMap<>();
        private final Map<Long, String> names = new ConcurrentHashMap<>();
        private volatile boolean running = false;

        void start() {
            if (running) return;
            running = true;
            // Seed prevCpu
            snapshot();
            scheduler.scheduleAtFixedRate(this::snapshot, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        void stop() {
            running = false;
            scheduler.shutdownNow();
        }

        private void snapshot() {
            try {
                int cpus = Runtime.getRuntime().availableProcessors();
                long intervalMs = INTERVAL_SECONDS * 1000L;
                for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
                    long pid = ph.pid();
                    Optional<Duration> cpuOpt = ph.info().totalCpuDuration();
                    if (cpuOpt.isEmpty()) continue;
                    Duration cur = cpuOpt.get();
                    Duration prev = prevCpu.put(pid, cur);
                    String name = ph.info().command().orElse("pid:" + pid);
                    // Shorten to exe name
                    int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
                    if (slash >= 0) name = name.substring(slash + 1);
                    names.put(pid, name);
                    if (prev == null) continue;
                    long deltaMs = cur.toMillis() - prev.toMillis();
                    if (deltaMs < 0) deltaMs = 0;
                    double pct = (deltaMs * 100.0) / intervalMs / cpus;
                    // Clamp absurd spikes
                    if (pct > 100.0 * cpus) pct = 100.0;
                    if (pct < 0) pct = 0;
                    // Only keep if meaningful
                    Deque<Double> dq = ring.computeIfAbsent(pid, k -> new ArrayDeque<>(WINDOW));
                    synchronized (dq) {
                        dq.addLast(pct);
                        while (dq.size() > WINDOW) dq.removeFirst();
                    }
                }
                // Cleanup dead pids: remove entries not seen in prevCpu recently
                // Simple: if pid not in current allProcesses, evict after a while. For now keep all.
            } catch (Exception e) {
                System.err.println("ProcessCpuSampler snapshot failed: " + e.getMessage());
            }
        }

        Map<String, Double> getTopAveraged(int max) {
            Map<Long, Double> averaged = new HashMap<>();
            for (Map.Entry<Long, Deque<Double>> e : ring.entrySet()) {
                Deque<Double> dq = e.getValue();
                if (dq.isEmpty()) continue;
                double avg;
                synchronized (dq) {
                    avg = dq.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                }
                // Only include if averaged >= 0.0% to avoid dropping low but real CPU users
                if (avg >= 0.0) averaged.put(e.getKey(), avg);
            }
            // Sort descending, collapse by name (sum if same name appears multiple pids e.g. chrome)
            Map<String, Double> byName = new HashMap<>();
            for (Map.Entry<Long, Double> e : averaged.entrySet()) {
                String n = names.getOrDefault(e.getKey(), "pid:" + e.getKey());
                byName.merge(n, e.getValue(), Double::sum);
            }
            return byName.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(max)
                    .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), Math.round(e.getValue() * 10.0) / 10.0), LinkedHashMap::putAll);
        }
    }
}
