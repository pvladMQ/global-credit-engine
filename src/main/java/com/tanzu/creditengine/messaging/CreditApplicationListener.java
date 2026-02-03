package com.tanzu.creditengine.messaging;

import com.tanzu.creditengine.service.CreditScoreCalculator;
import com.tanzu.creditengine.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ listener that processes credit applications from the queue.
 * This listener picks up messages, performs the Complex Join simulation,
 * calculates the credit score, and caches it in GemFire.
 */
@Component
public class CreditApplicationListener {

    private static final Logger logger = LoggerFactory.getLogger(CreditApplicationListener.class);

    private final CreditScoreCalculator creditScoreCalculator;
    private final MetricsService metricsService;

    public CreditApplicationListener(CreditScoreCalculator creditScoreCalculator,
            MetricsService metricsService) {
        this.creditScoreCalculator = creditScoreCalculator;
        this.metricsService = metricsService;
    }

    /**
     * Listens to the application-requests queue and processes credit applications.
     * 
     * @param message The credit application message from RabbitMQ
     */
    @RabbitListener(queues = "${credit-engine.queue.name:application-requests}")
    public void processCreditApplication(CreditApplicationMessage message) {
        logger.info("Received credit application for SSN: {}", message.getSsn());
        metricsService.logEvent("Received application from RabbitMQ for SSN: " + message.getSsn());

        try {
            // Process the application through the credit score calculator
            // This performs the "Complex Join" and caches the result in GemFire
            creditScoreCalculator.processAndCacheScore(message);

            // Record successful message processing
            metricsService.recordMessageProcessed();
            metricsService.logEvent("Verified & Cached score for SSN: " + message.getSsn());

            logger.info("Successfully processed credit application for SSN: {}", message.getSsn());
        } catch (Exception e) {
            metricsService.logEvent("Error processing SSN " + message.getSsn() + ": " + e.getMessage());
            logger.error("Failed to process credit application for SSN: {}", message.getSsn(), e);
            throw e; // Re-throw to trigger message requeue/dead-letter handling
        }
    }
}
