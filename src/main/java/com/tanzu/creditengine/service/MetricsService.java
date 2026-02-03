package com.tanzu.creditengine.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for tracking application metrics and response times.
 */
@Service
public class MetricsService {

    private final AtomicLong totalApplications = new AtomicLong(0);
    private final AtomicLong totalPostgresQueries = new AtomicLong(0);
    private final AtomicLong totalGemfireQueries = new AtomicLong(0);
    private final AtomicLong totalPostgresTimeMs = new AtomicLong(0);
    private final AtomicLong totalGemfireTimeMs = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong messagesProcessed = new AtomicLong(0);

    // Store last 20 events for the dashboard log
    private final Deque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_EVENTS = 20;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Record a new application submission.
     */
    public void recordApplication() {
        totalApplications.incrementAndGet();
    }

    /**
     * Record a successfully processed message from RabbitMQ.
     */
    public void recordMessageProcessed() {
        messagesProcessed.incrementAndGet();
    }

    /**
     * Record a Postgres query with its execution time.
     */
    public void recordPostgresQuery(long timeMs) {
        totalPostgresQueries.incrementAndGet();
        totalPostgresTimeMs.addAndGet(timeMs);
    }

    /**
     * Record a GemFire query with its execution time.
     */
    public void recordGemfireQuery(long timeMs) {
        totalGemfireQueries.incrementAndGet();
        totalGemfireTimeMs.addAndGet(timeMs);
    }

    /**
     * Record a cache hit.
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    /**
     * Record a cache miss.
     */
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public long getTotalApplications() {
        return totalApplications.get();
    }

    public double getAveragePostgresTimeMs() {
        long queries = totalPostgresQueries.get();
        return queries > 0 ? (double) totalPostgresTimeMs.get() / queries : 0;
    }

    public double getAverageGemfireTimeMs() {
        long queries = totalGemfireQueries.get();
        return queries > 0 ? (double) totalGemfireTimeMs.get() / queries : 0;
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    /**
     * Log an event for the dashboard.
     */
    public void logEvent(String message) {
        String timestamp = LocalTime.now().format(TIME_FORMATTER);
        String event = timestamp + " - " + message;
        recentEvents.addFirst(event);

        // Keep size bounded
        while (recentEvents.size() > MAX_EVENTS) {
            recentEvents.removeLast();
        }
    }

    public List<String> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public double getCacheHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        return total > 0 ? (double) cacheHits.get() / total * 100 : 0;
    }

    public double getSpeedupRatio() {
        double pgAvg = getAveragePostgresTimeMs();
        double gfAvg = getAverageGemfireTimeMs();
        return gfAvg > 0 ? pgAvg / gfAvg : 0;
    }
}
