package com.verdant.finance.service;

import com.verdant.finance.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);

    /** Only accounts with enough new entries to be worth re-snapshotting. */
    private static final int SNAPSHOT_THRESHOLD = 1000;

    private final LedgerService ledger;
    private final LedgerEntryRepository entries;

    public SnapshotJob(LedgerService ledger, LedgerEntryRepository entries) {
        this.ledger = ledger;
        this.entries = entries;
    }

    @Scheduled(cron = "${verdant.ledger.snapshot-cron:0 0 3 * * *}")
    public void snapshotActiveAccounts() {
        for (UUID accountId : entries.accountsWithEntriesSinceLastSnapshot(SNAPSHOT_THRESHOLD)) {
            try {
                ledger.snapshot(accountId);
            } catch (Exception e) {
                // one account's failure must never abort the whole run
                log.error("Snapshot failed for account {}", accountId, e);
            }
        }
    }
}
