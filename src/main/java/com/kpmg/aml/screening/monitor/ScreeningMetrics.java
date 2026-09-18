/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.monitor;

import com.kpmg.aml.screening.util.ConfigLoader;
import jakarta.annotation.PostConstruct;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
@Slf4j
public class ScreeningMetrics {

    private static final Logger metricsLog = LoggerFactory.getLogger("METRICS");
    private final ConfigLoader configLoader;

    // ── Oracle counters ───────────────────────────────────────────────
    private final AtomicLong recordsRead     = new AtomicLong();
    private final AtomicLong recordsEligible = new AtomicLong();  // interval counter — reset each flush
    private final AtomicLong cumulativeEligible = new AtomicLong(); // run total — never reset mid-run

    // ── Solr counters ─────────────────────────────────────────────────
    private final AtomicLong customersScreened = new AtomicLong();
    private final AtomicLong candidatesFound = new AtomicLong();

    // ── Python scoring counters ───────────────────────────────────────
    private final AtomicLong customersScored = new AtomicLong();
    private final AtomicLong aboveThreshold = new AtomicLong();
    private final AtomicLong scoringLatencyMs = new AtomicLong();
    private final AtomicLong scoringCallCount = new AtomicLong();

    // ── Alert counters ────────────────────────────────────────────────
    private final AtomicLong alertsPersisted = new AtomicLong();
    private final AtomicLong alertBufferDepth = new AtomicLong();

    // ── Run tracking ──────────────────────────────────────────────────
    private volatile long runStartMs = 0;
    private volatile long totalCustomers = 0;
    private volatile boolean running = false;

    // ── JVM ──────────────────────────────────────────────────────────
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private volatile long lastGcTimeMs = 0;

    private ScheduledExecutorService scheduler;

    public ScreeningMetrics(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void init() {
        long intervalMS = configLoader.getScreening().getMetricsIntervalMs();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual()
                    .name("metrics-flush")
                    .unstarted(r);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(this::flush, intervalMS, intervalMS, TimeUnit.MILLISECONDS);

        log.info("[ScreeningMetrics] Initialised — flush interval {}ms", intervalMS);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────
    public void startRun(long totalCustomerCount) {
        reset();
        this.totalCustomers = totalCustomerCount;
        this.runStartMs = System.currentTimeMillis();
        this.running = true;
        log.info("[ScreeningMetrics] Run started — total customers: {}", totalCustomerCount);
    }

    public void endRun() {
        this.running = false;
        flush(); // final flush
        log.info("[ScreeningMetrics] Run complete — elapsed: {}s", (System.currentTimeMillis() - runStartMs) / 1000);
    }

    public void reset() {
        recordsRead.set(0);
        recordsEligible.set(0);
        customersScreened.set(0);
        candidatesFound.set(0);
        customersScored.set(0);
        aboveThreshold.set(0);
        scoringLatencyMs.set(0);
        scoringCallCount.set(0);
        alertsPersisted.set(0);
        alertBufferDepth.set(0);
        cumulativeEligible.set(0);
        runStartMs = 0;
        totalCustomers = 0;
        lastGcTimeMs = 0;
    }

    // ── Increment API — called from pipeline stages ───────────────────
    public void recordRead(int count) {
        recordsRead.addAndGet(count);
    }

    public void recordEligible(int count) {
        recordsEligible.addAndGet(count);
        cumulativeEligible.addAndGet(count);
    }

    /** Returns the total eligible customers processed in the current run. */
    public long getTotalEligible() {
        return cumulativeEligible.get();
    }

    public void recordScreened(int count) {
        customersScreened.addAndGet(count);
    }

    public void recordCandidates(int count) {
        candidatesFound.addAndGet(count);
    }

    public void recordScored(int count) {
        customersScored.addAndGet(count);
    }

    public void recordAboveThreshold(int count) {
        aboveThreshold.addAndGet(count);
    }

    public void recordScoringLatency(long ms) {
        scoringLatencyMs.addAndGet(ms);
        scoringCallCount.incrementAndGet();
    }

    public void recordAlertsPersisted(int count) {
        alertsPersisted.addAndGet(count);
    }

    public void updateAlertBufferDepth(long depth) {
        alertBufferDepth.set(depth);
    }

    // ── Flush ─────────────────────────────────────────────────────────
    private void flush() {
        if (!running) {
            return;
        }

        long intervalMs = configLoader.getScreening().getMetricsIntervalMs();
        long elapsedMs = System.currentTimeMillis() - runStartMs;
        long elapsedSec = elapsedMs / 1000;

        // -- snapshot and reset interval counters
        long read = recordsRead.getAndSet(0);
        long eligible = recordsEligible.getAndSet(0);
        long screened = customersScreened.getAndSet(0);
        long candidates = candidatesFound.getAndSet(0);
        long scored = customersScored.getAndSet(0);
        long threshold = aboveThreshold.getAndSet(0);
        long latencyTotal = scoringLatencyMs.getAndSet(0);
        long callCount = scoringCallCount.getAndSet(0);
        long persisted = alertsPersisted.getAndSet(0);
        long bufferDepth = alertBufferDepth.get();

        // -- rates per second
        double intervalSec = intervalMs / 1000.0;
        double readRate = read / intervalSec;
        double scoredRate = scored / intervalSec;
        double persistRate = persisted / intervalSec;

        // -- avg scoring latency
        long avgLatency = callCount > 0 ? latencyTotal / callCount : 0;

        // -- ETA
        long totalScored = customersScored.get();
        String eta = "N/A";
        if (totalCustomers > 0 && scoredRate > 0) {
            long remaining = totalCustomers - totalScored;
            long etaSec = (long) (remaining / scoredRate);
            eta = formatDuration(etaSec);
        }

        // -- progress
        double progress = totalCustomers > 0
                ? (totalScored * 100.0 / totalCustomers)
                : 0.0;

        // -- JVM
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        int threadCount = threadBean.getThreadCount();

        // -- GC delta
        long gcTotal = gcBeans.stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
        long gcDelta = gcTotal - lastGcTimeMs;
        lastGcTimeMs = gcTotal;

        // -- write to metrics.log
        metricsLog.info("--- {} | elapsed: {} | progress: {}% | ETA: {} ---",
                Instant.now(), formatDuration(elapsedSec),
                String.format("%.1f", progress), eta);

        metricsLog.info("  [Oracle]  read={} ({}/s)  eligible={}",
                read, String.format("%.1f", readRate), eligible);

        metricsLog.info("  [Solr]    screened={}  candidates={}",
                screened, candidates);

        metricsLog.info("  [Python]  scored={} ({}/s)  above_threshold={}  avg_latency={}ms",
                scored, String.format("%.1f", scoredRate), threshold, avgLatency);

        metricsLog.info("  [Alerts]  persisted={} ({}/s)  buffer_depth={}",
                persisted, String.format("%.1f", persistRate), bufferDepth);

        metricsLog.info("  [JVM]     heap={}/{}MB ({}%)  threads={}  gc_delta={}ms",
                heapUsed, heapMax,
                String.format("%.1f", heapUsed * 100.0 / (heapMax > 0 ? heapMax : 1)),
                threadCount, gcDelta);
    }

    // ── Util ──────────────────────────────────────────────────────────
    private String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
