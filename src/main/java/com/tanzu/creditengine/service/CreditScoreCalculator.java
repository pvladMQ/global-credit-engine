package com.tanzu.creditengine.service;

import com.tanzu.creditengine.entity.CreditScoreCache;
import com.tanzu.creditengine.entity.UserFinancials;
import com.tanzu.creditengine.messaging.CreditApplicationMessage;
import com.tanzu.creditengine.repository.CreditScoreCacheRepository;
import com.tanzu.creditengine.repository.UserFinancialsRepository;
import com.tanzu.creditengine.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Random;

/**
 * Service that calculates credit scores using data from PostgreSQL
 * and caches the results in GemFire for sub-second retrieval.
 */
@Service
public class CreditScoreCalculator {

    private static final Logger logger = LoggerFactory.getLogger(CreditScoreCalculator.class);

    private final UserFinancialsRepository userFinancialsRepository;
    private final CreditScoreCacheRepository creditScoreCacheRepository;

    private final MetricsService metricsService;
    private final Random random = new Random();

    public CreditScoreCalculator(UserFinancialsRepository userFinancialsRepository,
            CreditScoreCacheRepository creditScoreCacheRepository,
            MetricsService metricsService) {
        this.userFinancialsRepository = userFinancialsRepository;
        this.creditScoreCacheRepository = creditScoreCacheRepository;
        this.metricsService = metricsService;
    }

    /**
     * Processes a credit application by performing a "Complex Join" against
     * PostgreSQL,
     * calculating the credit score, and caching the result in GemFire.
     * 
     * @param message The credit application message
     * @return The calculated credit score (1-100)
     */
    @Transactional
    public int processAndCacheScore(CreditApplicationMessage message) {
        logger.info("Starting credit score calculation for SSN: {}", message.getSsn());

        // Simulate "Regional Hub" network latency (40-80ms)
        // This ensures the dashboard shows a realistic contrast between remote Postgres
        // and local GemFire
        try {
            long latency = 40 + random.nextInt(41); // 40 to 80ms
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Latency simulation interrupted", e);
        }

        // Step 1: Perform "Complex Join" - Query PostgreSQL for user financial data
        long pgStart = System.currentTimeMillis();
        Optional<UserFinancials> financialsOpt = userFinancialsRepository
                .findWithCompleteFinancialData(message.getSsn());
        long pgTime = System.currentTimeMillis() - pgStart;
        metricsService.recordPostgresQuery(pgTime);

        UserFinancials financials;
        if (financialsOpt.isPresent()) {
            financials = financialsOpt.get();
            logger.debug("Found existing financial data for SSN: {}", message.getSsn());
        } else {
            // Create new user record if not exists (demo scenario)
            financials = createNewUserRecord(message);
            logger.debug("Created new financial record for SSN: {}", message.getSsn());
        }

        // Step 2: Calculate the credit score (1-100)
        int calculatedScore = calculateScore(financials);
        String riskLevel = determineRiskLevel(calculatedScore);

        // Update risk level in PostgreSQL
        financials.setRiskLevel(riskLevel);
        userFinancialsRepository.save(financials);

        // Step 3: Cache the result in GemFire for sub-second global retrieval
        CreditScoreCache cachedScore = new CreditScoreCache(
                message.getSsn(),
                calculatedScore,
                riskLevel,
                financials.getFullName());

        long gfStart = System.currentTimeMillis();
        creditScoreCacheRepository.save(cachedScore);
        long gfTime = System.currentTimeMillis() - gfStart;
        metricsService.recordGemfireQuery(gfTime);

        logger.info("Credit score cached in GemFire - SSN: {}, Score: {}, Risk: {}",
                message.getSsn(), calculatedScore, riskLevel);

        return calculatedScore;
    }

    /**
     * Creates a new user financial record for demo purposes.
     */
    private UserFinancials createNewUserRecord(CreditApplicationMessage message) {
        UserFinancials newUser = new UserFinancials();
        newUser.setSsn(message.getSsn());
        newUser.setFullName(message.getFullName());
        // Simulate initial credit history score (random for demo)
        newUser.setCreditHistoryScore((int) (Math.random() * 300) + 500); // 500-800 range
        newUser.setCriminalRecord(Math.random() < 0.1); // 10% chance of criminal record
        newUser.setRiskLevel("PENDING");

        return userFinancialsRepository.save(newUser);
    }

    /**
     * Calculates the credit score based on various factors.
     * This simulates a complex scoring algorithm.
     * 
     * @param financials The user's financial data
     * @return A score from 1 to 100
     */
    private int calculateScore(UserFinancials financials) {
        int baseScore = 50;

        // Factor 1: Credit History Score (0-30 points based on history)
        if (financials.getCreditHistoryScore() != null) {
            int historyScore = financials.getCreditHistoryScore();
            if (historyScore >= 750) {
                baseScore += 30;
            } else if (historyScore >= 650) {
                baseScore += 20;
            } else if (historyScore >= 550) {
                baseScore += 10;
            } else {
                baseScore -= 10;
            }
        }

        // Factor 2: Criminal Record (-20 points if true)
        if (Boolean.TRUE.equals(financials.getCriminalRecord())) {
            baseScore -= 20;
        }

        // Factor 3: Random market conditions simulation (-5 to +10 points)
        baseScore += (int) (Math.random() * 15) - 5;

        // Ensure score is within 1-100 bounds
        return Math.max(1, Math.min(100, baseScore));
    }

    /**
     * Determines the risk level based on the calculated score.
     */
    private String determineRiskLevel(int score) {
        if (score >= 80) {
            return "LOW_RISK";
        } else if (score >= 60) {
            return "MEDIUM_RISK";
        } else if (score >= 40) {
            return "HIGH_RISK";
        } else {
            return "VERY_HIGH_RISK";
        }
    }

    /**
     * Retrieves a cached credit score from GemFire.
     * 
     * @param ssn The SSN to look up
     * @return The cached credit score, or null if not found
     */
    public CreditScoreCache getCachedScore(String ssn) {
        return creditScoreCacheRepository.findBySsn(ssn);
    }
}
