package com.tvpc.repository;

import com.tvpc.domain.Settlement;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Settlement entity - handles CRUD operations for settlements
 */
public interface SettlementRepository {

    /**
     * Save a new settlement and return the auto-generated sequence ID
     * @param settlement The settlement to save
     * @param connection Database connection (for transaction support)
     * @return Future with the generated sequence ID
     */
    Future<Long> save(Settlement settlement, SqlConnection connection);

    /**
     * Mark old versions of a settlement as IS_OLD = true
     * @param settlementId Business settlement identifier
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param connection Database connection
     * @return Future indicating completion
     */
    Future<Void> markOldVersions(String settlementId, String pts, String processingEntity, SqlConnection connection);

    /**
     * Find the previous counterparty for a settlement (to detect changes)
     * @param settlementId Business settlement identifier
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param currentId Current settlement's sequence ID
     * @param connection Database connection
     * @return Future with optional previous counterparty ID
     */
    Future<Optional<String>> findPreviousCounterparty(String settlementId, String pts, String processingEntity, Long currentId, SqlConnection connection);

    /**
     * Find the latest version of a settlement
     * @param settlementId Business settlement identifier
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @return Future with optional settlement
     */
    Future<Optional<Settlement>> findLatestVersion(String settlementId, String pts, String processingEntity);

    /**
     * Find all settlements matching the group criteria
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param counterpartyId Counterparty identifier
     * @param valueDate Settlement value date
     * @param maxSeqId Maximum sequence ID to include
     * @param connection Database connection
     * @return Future with list of settlements
     */
    Future<List<Settlement>> findByGroup(String pts, String processingEntity, String counterpartyId, String valueDate, Long maxSeqId, SqlConnection connection);

    /**
     * Find settlements for a group with specific business status and direction
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param counterpartyId Counterparty identifier
     * @param valueDate Settlement value date
     * @param maxSeqId Maximum sequence ID to include
     * @param connection Database connection
     * @return Future with list of settlements
     */
    Future<List<Settlement>> findByGroupWithFilters(String pts, String processingEntity, String counterpartyId, String valueDate, Long maxSeqId, SqlConnection connection);
}
