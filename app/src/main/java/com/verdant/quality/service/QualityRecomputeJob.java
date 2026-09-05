package com.verdant.quality.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Nightly, real per-seller isolation (same pattern as PayoutCycleBuildJob/
 * ForecastRefreshJob): one seller's failure - a bad row, a transient DB
 * hiccup - must not abort the whole run and leave every seller after it
 * in iteration order un-scored for a day.
 *
 * Deliberately thin: every real unit of work (score, damp, persist,
 * write back to sellers.quality_score, notify on a tier change, emit the
 * outbox fan-out event) lives in QualityScoreService.recompute() - a
 * real cross-bean call from this loop, so its own @Transactional
 * actually applies via the AOP proxy. Doing that work inline here
 * instead would mean this class calling its own method via "this." self-
 * invocation, silently skipping the proxy - the exact
 * InventoryService.reserve() class of bug found and fixed earlier this
 * same week.
 */
@Component
public class QualityRecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(QualityRecomputeJob.class);

    private final JdbcTemplate jdbc;
    private final QualityScoreService scoreService;

    public QualityRecomputeJob(JdbcTemplate jdbc, QualityScoreService scoreService) {
        this.jdbc = jdbc;
        this.scoreService = scoreService;
    }

    @Scheduled(cron = "${verdant.quality.recompute-cron:0 0 2 * * *}")
    public void recomputeAll() {
        for (UUID sellerId : activeSellerIds()) {
            try {
                scoreService.recompute(sellerId);
            } catch (Exception e) {
                log.error("Quality recompute failed for seller {}", sellerId, e);
            }
        }
    }

    private List<UUID> activeSellerIds() {
        return jdbc.queryForList("SELECT id FROM sellers WHERE status = 'APPROVED'", UUID.class);
    }
}
