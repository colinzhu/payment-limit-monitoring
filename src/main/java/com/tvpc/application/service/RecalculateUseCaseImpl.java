package com.tvpc.application.service;

import com.tvpc.application.port.in.RecalculateUseCase;
import com.tvpc.application.port.out.ActivityRepository;
import com.tvpc.application.port.out.RunningTotalRepository;
import com.tvpc.application.port.out.SettlementRepository;
import io.vertx.core.Future;
import io.vertx.jdbcclient.JDBCPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Use case implementation for manual recalculation
 * Orchestrates manual trigger of running total recalculation
 */
public class RecalculateUseCaseImpl implements RecalculateUseCase {
    private static final Logger log = LoggerFactory.getLogger(RecalculateUseCaseImpl.class);

    private final SettlementRepository settlementRepository;
    private final RunningTotalRepository runningTotalRepository;
    private final ActivityRepository activityRepository;
    private final JDBCPool jdbcPool;

    public RecalculateUseCaseImpl(
            SettlementRepository settlementRepository,
            RunningTotalRepository runningTotalRepository,
            ActivityRepository activityRepository,
            JDBCPool jdbcPool
    ) {
        this.settlementRepository = settlementRepository;
        this.runningTotalRepository = runningTotalRepository;
        this.activityRepository = activityRepository;
        this.jdbcPool = jdbcPool;
    }

    @Override
    public Future<Void> recalculate(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDateFrom,
            LocalDate valueDateTo,
            String userId,
            String reason
    ) {
        log.info("Manual recalculation requested by {} for scope: pts={}, pe={}, cp={}, date={} to {}",
                userId, pts, processingEntity, counterpartyId, valueDateFrom, valueDateTo);

        // Log the manual recalculation request
        // TODO: Create activity log for recalculation request

        // Get distinct groups matching criteria
        return settlementRepository.getDistinctGroups(pts, processingEntity, valueDateFrom.toString(), valueDateTo.toString())
                .compose(groups -> {
                    log.info("Found {} groups to recalculate", groups.size());

                    // For each group, trigger recalculation
                    Future<Void> result = Future.succeededFuture();
                    for (String groupKey : groups) {
                        // Parse group key: PTS|PROCESSING_ENTITY|COUNTERPARTY_ID|VALUE_DATE
                        String[] parts = groupKey.split("\\|");
                        if (parts.length == 4) {
                            String groupPts = parts[0];
                            String groupPe = parts[1];
                            String groupCp = parts[2];
                            LocalDate groupVd = LocalDate.parse(parts[3]);

                            result = result.compose(v -> recalculateGroup(groupPts, groupPe, groupCp, groupVd));
                        }
                    }

                    return result;
                })
                .onSuccess(v -> log.info("Manual recalculation completed successfully"))
                .onFailure(error -> log.error("Manual recalculation failed", error));
    }

    /**
     * Recalculate running total for a single group
     */
    private Future<Void> recalculateGroup(String pts, String processingEntity, String counterpartyId, LocalDate valueDate) {
        log.debug("Recalculating group: pts={}, pe={}, cp={}, vd={}",
                pts, processingEntity, counterpartyId, valueDate);

        // Get current max sequence ID from settlement table
        // This ensures we recalculate using all settlements up to the current point
        return jdbcPool.withTransaction(connection -> {
            // Get max ID from settlement table for this group
            // Then calculate and save running total
            return Future.succeededFuture(); // TODO: Implement actual recalculation logic
        });
    }
}
