package com.tanzu.creditengine.controller;

import com.tanzu.creditengine.entity.CreditScoreCache;
import com.tanzu.creditengine.entity.UserFinancials;
import com.tanzu.creditengine.messaging.CreditApplicationMessage;
import com.tanzu.creditengine.repository.CreditScoreCacheRepository;
import com.tanzu.creditengine.repository.UserFinancialsRepository;
import com.tanzu.creditengine.service.CreditApplicationService;
import com.tanzu.creditengine.service.CreditScoreCalculator;
import com.tanzu.creditengine.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST Controller for credit application endpoints and dashboard API.
 */
@RestController
@RequestMapping("/api")
public class CreditApplicationController {

    private static final Logger logger = LoggerFactory.getLogger(CreditApplicationController.class);

    private final CreditApplicationService applicationService;
    private final CreditScoreCalculator scoreCalculator;
    private final MetricsService metricsService;
    private final CreditScoreCacheRepository cacheRepository;
    private final UserFinancialsRepository userFinancialsRepository;

    @Value("${VCAP_SERVICES:{}}")
    private String vcapServices;

    public CreditApplicationController(CreditApplicationService applicationService,
            CreditScoreCalculator scoreCalculator,
            MetricsService metricsService,
            CreditScoreCacheRepository cacheRepository,
            UserFinancialsRepository userFinancialsRepository) {
        this.applicationService = applicationService;
        this.scoreCalculator = scoreCalculator;
        this.metricsService = metricsService;
        this.cacheRepository = cacheRepository;
        this.userFinancialsRepository = userFinancialsRepository;
    }

    /**
     * Submits a credit application for processing.
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> submitApplication(@RequestBody CreditApplicationMessage application) {
        logger.info("Received credit application request for SSN: {}", application.getSsn());

        if (application.getSsn() == null || application.getSsn().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "SSN is required");
            return ResponseEntity.badRequest().body(error);
        }

        if (application.getFullName() == null || application.getFullName().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Full name is required");
            return ResponseEntity.badRequest().body(error);
        }

        applicationService.submitApplication(application);
        metricsService.recordApplication();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "accepted");
        response.put("message", "Credit application submitted for processing");
        response.put("ssn", application.getSsn());
        response.put("trackingInfo", "Check /api/score/" + application.getSsn() + " for results");

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Retrieves a cached credit score from GemFire.
     */
    @GetMapping("/score/{ssn}")
    public ResponseEntity<Map<String, Object>> getScore(@PathVariable String ssn) {
        logger.info("Retrieving cached credit score for SSN: {}", ssn);

        long startTime = System.currentTimeMillis();
        CreditScoreCache cachedScore = scoreCalculator.getCachedScore(ssn);
        long gemfireTime = System.currentTimeMillis() - startTime;
        metricsService.recordGemfireQuery(gemfireTime);

        if (cachedScore == null) {
            metricsService.recordCacheMiss();
            Map<String, Object> response = new HashMap<>();
            response.put("status", "not_found");
            response.put("message", "No credit score found for SSN: " + ssn);
            response.put("hint", "Submit an application first via POST /api/apply");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        metricsService.recordCacheHit();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("ssn", cachedScore.getSsn());
        response.put("fullName", cachedScore.getFullName());
        response.put("calculatedScore", cachedScore.getCalculatedScore());
        response.put("riskLevel", cachedScore.getRiskLevel());
        response.put("calculatedAt", cachedScore.getCalculatedAt());
        response.put("source", "GemFire Cache (sub-second retrieval)");
        response.put("queryTimeMs", gemfireTime);

        return ResponseEntity.ok(response);
    }

    /**
     * Get all cached scores from GemFire.
     */
    @GetMapping("/scores")
    public ResponseEntity<Map<String, Object>> getAllScores() {
        logger.info("Retrieving all cached scores from GemFire");

        long startTime = System.currentTimeMillis();
        List<CreditScoreCache> scores = new ArrayList<>();
        cacheRepository.findAll().forEach(scores::add);
        long queryTime = System.currentTimeMillis() - startTime;

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("count", scores.size());
        response.put("scores", scores);
        response.put("queryTimeMs", queryTime);
        response.put("source", "GemFire Cache");

        return ResponseEntity.ok(response);
    }

    /**
     * Get system status including bound services from VCAP_SERVICES.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Global Credit Scoring Engine");

        List<Map<String, Object>> boundServices = new ArrayList<>();
        String vcap = System.getenv("VCAP_SERVICES");

        if (vcap != null && !vcap.isEmpty() && !vcap.equals("{}")) {
            response.put("cloudFoundry", true);
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(vcap);

                // Check for services by looking through all service types
                checkServiceInJson(root, "credit-db", "PostgreSQL", boundServices);
                checkServiceInJson(root, "credit-msg", "RabbitMQ", boundServices);
                checkServiceInJson(root, "credit-cache", "VMware Tanzu GemFire", boundServices);

            } catch (Exception e) {
                logger.error("Error parsing VCAP_SERVICES, falling back to string check", e);
                boundServices.clear(); // Clear potentially partial results
                checkServiceBindingFallback(vcap, "credit-db", "PostgreSQL", boundServices);
                checkServiceBindingFallback(vcap, "credit-msg", "RabbitMQ", boundServices);
                checkServiceBindingFallback(vcap, "credit-cache", "VMware Tanzu GemFire", boundServices);
            }
        } else {
            response.put("cloudFoundry", false);
            addLocalService("credit-db", "PostgreSQL", boundServices);
            addLocalService("credit-msg", "RabbitMQ", boundServices);
            addLocalService("credit-cache", "VMware Tanzu GemFire", boundServices);
        }

        response.put("boundServices", boundServices);
        return ResponseEntity.ok(response);
    }

    private void checkServiceInJson(com.fasterxml.jackson.databind.JsonNode root, String name, String type,
            List<Map<String, Object>> boundServices) {
        Map<String, Object> service = new HashMap<>();
        service.put("name", name);
        service.put("type", type);
        service.put("status", "missing");

        // Iterate over all service types (keys in VCAP_SERVICES)
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String serviceType = fieldNames.next();
            com.fasterxml.jackson.databind.JsonNode services = root.get(serviceType);
            if (services.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode s : services) {
                    if (s.has("name") && s.get("name").asText().equals(name)) {
                        service.put("status", "bound");
                        break;
                    }
                }
            }
        }
        boundServices.add(service);
    }

    private void checkServiceBindingFallback(String vcap, String name, String type,
            List<Map<String, Object>> boundServices) {
        Map<String, Object> service = new HashMap<>();
        service.put("name", name);
        service.put("type", type);

        // Robust check: if the name appears anywhere in VCAP_SERVICES, assume it's
        // bound
        // This fixes the issue where JSON parsing might miss it or the structure varies
        if (vcap.contains(name)) {
            service.put("status", "bound");
        } else {
            service.put("status", "missing");
        }
        boundServices.add(service);
    }

    private void addLocalService(String name, String type, List<Map<String, Object>> boundServices) {
        Map<String, Object> service = new HashMap<>();
        service.put("name", name);
        service.put("type", type);
        service.put("status", "local");
        boundServices.add(service);
    }

    /**
     * Get application metrics.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> response = new HashMap<>();
        response.put("totalApplications", metricsService.getTotalApplications());
        response.put("messagesProcessed", metricsService.getMessagesProcessed());
        response.put("avgPostgresTimeMs", Math.round(metricsService.getAveragePostgresTimeMs() * 100.0) / 100.0);
        response.put("avgGemfireTimeMs", Math.round(metricsService.getAverageGemfireTimeMs() * 100.0) / 100.0);
        response.put("cacheHits", metricsService.getCacheHits());
        response.put("cacheMisses", metricsService.getCacheMisses());
        response.put("messagesProcessed", metricsService.getMessagesProcessed());
        response.put("recentEvents", metricsService.getRecentEvents());
        response.put("cacheHitRate", Math.round(metricsService.getCacheHitRate() * 100.0) / 100.0);
        response.put("speedupRatio", Math.round(metricsService.getSpeedupRatio() * 100.0) / 100.0);
        return ResponseEntity.ok(response);
    }

    /**
     * Latency comparison test - Postgres vs GemFire.
     */
    @GetMapping("/latency-test/{ssn}")
    public ResponseEntity<Map<String, Object>> latencyTest(@PathVariable String ssn) {
        logger.info("Running latency comparison test for SSN: {}", ssn);

        Map<String, Object> response = new HashMap<>();

        // Query Postgres (with simulated regional latency)
        long pgStart = System.currentTimeMillis();
        // Latency simulation removed to show actual Bound Service performance vs
        // GemFire
        Optional<UserFinancials> pgResult = userFinancialsRepository.findBySsn(ssn);
        long pgTime = System.currentTimeMillis() - pgStart;
        metricsService.recordPostgresQuery(pgTime);

        // Query GemFire (sub-second, typically < 10ms)
        long gfStart = System.currentTimeMillis();
        CreditScoreCache gfResult = scoreCalculator.getCachedScore(ssn);
        long gfTime = System.currentTimeMillis() - gfStart;
        metricsService.recordGemfireQuery(gfTime);

        response.put("ssn", ssn);
        response.put("postgresTimeMs", pgTime);
        response.put("gemfireTimeMs", gfTime);
        response.put("speedup", pgTime > 0 && gfTime > 0 ? Math.round((double) pgTime / gfTime * 10.0) / 10.0 : 0);
        response.put("postgresFound", pgResult.isPresent());
        response.put("gemfireFound", gfResult != null);

        if (gfResult != null) {
            response.put("cachedScore", gfResult.getCalculatedScore());
            response.put("riskLevel", gfResult.getRiskLevel());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Global Credit Scoring Engine");
        return ResponseEntity.ok(response);
    }
}
