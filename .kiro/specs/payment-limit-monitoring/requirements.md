# Requirements Document

## Introduction

The Payment Limit Monitoring System is a financial risk management application that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits for operational review. The system ensures compliance with risk management policies by providing real-time monitoring and manual approval workflows for high-value settlement groups.

## Glossary

- **Payment_Limit_Monitoring_System**: The software system that monitors settlement flows and enforces exposure limits
- **System**: The Payment Limit Monitoring System (used throughout requirements for brevity)
- **Settlement**: A financial transaction record containing payment information between entities
- **Settlement_Direction**: The direction of a settlement transaction, either PAY (outgoing payment) or RECEIVE (incoming payment)
- **Settlement_Type**: The settlement processing type, either NET (netted from multiple settlements) or GROSS (individual settlement)
- **Business_Status**: The settlement status from the PTS system, which can be PENDING, INVALID, VERIFIED, or CANCELLED
- **PAY_Settlement**: A settlement with direction PAY that represents an outgoing payment and contributes to risk exposure
- **RECEIVE_Settlement**: A settlement with direction RECEIVE that represents an incoming payment and does not contribute to risk exposure
- **NET_Settlement**: A netted settlement that can frequently change between PAY and RECEIVE directions based on the net result of multiple underlying settlements
- **GROSS_Settlement**: An individual settlement that maintains a consistent direction throughout its lifecycle
- **VERIFIED_Settlement**: A settlement with business status VERIFIED that is confirmed in PTS but can still be cancelled, and is eligible for manual approval workflows when blocked
- **CANCELLED_Settlement**: A settlement with business status CANCELLED that should be excluded from running total calculations
- **PENDING_Settlement**: A settlement with business status PENDING that is incomplete in PTS but should be included in running total calculations, however not eligible for manual approval workflows until verified
- **INVALID_Settlement**: A settlement with business status INVALID that is incomplete in PTS but should be included in running total calculations, however not eligible for manual approval workflows until verified
- **PTS**: Primary Trading System - the source system generating settlement data
- **Processing_Entity**: A business unit within a trading system that processes settlements
- **Counterparty**: The external party involved in a settlement transaction
- **Value_Date**: The date when a settlement is scheduled to be processed
- **Settlement_ID**: Unique identifier for a settlement transaction
- **Settlement_Version**: Version number for a settlement, as settlements can be modified over time, the format is a time stamp in long integer format since 1970-01-01T00:00:00Z
- **Running_Total**: Aggregated USD equivalent amount for settlements grouped by PTS, Processing Entity, Counterparty, and Value Date
- **Exposure_Limit**: Maximum allowed USD amount for a settlement group, either fixed at 500 million USD (MVP) or counterparty-specific limits fetched from external systems
- **Operation_Team**: Users responsible for reviewing and approving settlements that exceed limits
- **CREATED**: Settlement status when the group running total is within the exposure limit
- **BLOCKED**: Settlement status when the group running total exceeds the exposure limit
- **PENDING_AUTHORISE**: Settlement status after an operation team member requests release but before authorization
- **AUTHORISED**: Settlement status after a second operation team member authorizes the release
- **Exchange_Rate**: Currency conversion rate automatically fetched from external systems and used for USD equivalent calculations
- **Filtering_Rules**: Configurable criteria stored in an external rule system that determine which settlements should be included in running total calculations
- **Rule_System**: External system that manages and provides filtering rules for settlement inclusion criteria

## Requirements

### Requirement 1

**User Story:** As a risk manager, I want to receive and process settlement flows from trading systems, so that I can monitor payment exposures in real-time.

#### Acceptance Criteria

1. WHEN a settlement is received from an endpoint, the System SHALL validate and store the settlement data including PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version, Settlement_Direction (PAY or RECEIVE), Settlement_Type (NET or GROSS), and Business_Status (PENDING, INVALID, VERIFIED, or CANCELLED)
2. WHEN a settlement is received, the System SHALL evaluate the settlement against current filtering rules to determine if it should be included in running total calculations
3. WHEN a settlement has direction PAY and business status is PENDING, INVALID, or VERIFIED (standard filtering criteria), the System SHALL include it in group running total calculations for risk exposure monitoring
4. WHEN a settlement has direction RECEIVE or business status is CANCELLED (standard filtering criteria), the System SHALL store the settlement but exclude it from running total calculations
5. WHEN a NET settlement is received, the System SHALL handle potential direction changes between PAY and RECEIVE as the netted result may fluctuate based on underlying settlement updates
6. WHEN a settlement matches the filtering criteria, has direction PAY, and business status is not CANCELLED, the System SHALL include it in group running total calculations and limit monitoring
7. WHEN a settlement does not match the filtering criteria, has direction RECEIVE, or has business status CANCELLED, the System SHALL store the settlement but exclude it from running total calculations and limit monitoring
8. WHEN multiple versions of the same Settlement_ID are received, the System SHALL maintain the latest version and preserve historical versions for audit purposes
9. WHEN settlement versions are received out of chronological order, the System SHALL determine the correct latest version based on Settlement_Version number and apply only the most recent version to running total calculations
10. WHEN an older version of a settlement arrives after a newer version has already been processed, the System SHALL store the historical version but SHALL NOT recalculate running totals or update settlement statuses
11. WHEN multiple settlement updates for the same group are processed concurrently across different system instances, the System SHALL ensure running total calculation consistency through appropriate concurrency control mechanisms
12. WHEN concurrent settlement processing occurs, the System SHALL prevent running total overwrites and ensure that the final running total reflects all valid settlement updates within the group
13. WHEN a settlement version updates the business status from VERIFIED to CANCELLED, the System SHALL exclude the settlement from running total calculations and recalculate the group total using appropriate concurrency control to prevent race conditions
14. WHEN a settlement version updates the business status from CANCELLED to PENDING, INVALID, or VERIFIED, the System SHALL include the settlement in running total calculations if it has direction PAY and recalculate the group total using appropriate concurrency control to prevent race conditions
15. WHEN settlement data is stored, the System SHALL preserve the original currency and amount while enabling USD equivalent calculation for running total aggregation
16. WHEN a settlement flow contains invalid or incomplete data (missing required fields, invalid currency codes, non-numeric amounts, or invalid date formats), the System SHALL reject the settlement and log the error for investigation
17. THE System SHALL fetch the latest filtering rules from the external rule system every 5 minutes to ensure current criteria are applied
18. THE System SHALL process settlement flows continuously without interruption
19. THE System SHALL apply cached filtering rules to settlements received between rule fetches, and re-evaluate affected groups when new rules are fetched

### Requirement 2

**User Story:** As a risk manager, I want settlements to be grouped and aggregated by key dimensions, so that I can calculate total exposure per counterparty and value date.

#### Acceptance Criteria

1. WHEN settlements are processed, the System SHALL group PAY settlements with business status PENDING, INVALID, or VERIFIED by PTS, Processing_Entity, Counterparty_ID, and Value_Date
2. WHEN calculating running totals, the System SHALL recalculate the complete group running total from all included settlements rather than using incremental updates
3. WHEN any settlement change occurs (new version, status change, direction change, or rule update), the System SHALL recalculate the complete affected group(s) to ensure data consistency
4. WHEN a settlement moves between groups (due to Counterparty_ID), the System SHALL recalculate both the old and new groups completely
5. THE System SHALL maintain accurate running totals across all groups by always performing complete recalculation rather than incremental updates

### Requirement 3

**User Story:** As a risk manager, I want to track settlement status in the UI based on group exposure limits, so that I can ensure proper oversight of high-value transactions.

#### Acceptance Criteria

1. **CREATED status**: When group running total < limit, all settlements in group show CREATED
2. **BLOCKED status**: When group running total ≥ limit, PAY settlements show BLOCKED
3. **Non-contributing**: RECEIVE or CANCELLED settlements always show CREATED (don't affect exposure)
4. **Dynamic limit check**: Settlement status is always calculated against the latest limit in real-time

### Requirement 4

**User Story:** As an operations team member, I want to review and approve individual settlements that are blocked due to limit exceedance, so that I can ensure compliance with risk management policies through a two-step approval process.

#### Acceptance Criteria

1. WHEN viewing BLOCKED settlements, the System SHALL display only PAY settlements with business status VERIFIED that are blocked due to limit exceedance, along with settlement details and group information including PTS, Processing_Entity, Counterparty_ID, Value_Date, Settlement_Direction, Settlement_Type, Business_Status, and current group running total
2. WHEN an operation team member clicks REQUEST RELEASE for a BLOCKED PAY settlement with business status VERIFIED, the System SHALL change the settlement status to PENDING_AUTHORISE and record the action with user identity and timestamp
3. WHEN a different operation team member clicks AUTHORISE for a PENDING_AUTHORISE settlement with business status VERIFIED, the System SHALL change the settlement status to AUTHORISED and record the action with user identity and timestamp
4. WHEN the same operation team member attempts to perform both REQUEST RELEASE and AUTHORISE actions on the same settlement, the System SHALL prevent the action and display an error message
5. WHEN a settlement receives a new version that changes its business status from VERIFIED to PENDING, INVALID, or CANCELLED, the System SHALL reset the settlement status based on the new business status and group running total, and invalidate all previous approval actions
6. WHEN a settlement with business status PENDING or INVALID is blocked due to limit exceedance, the System SHALL display the settlement as BLOCKED but SHALL NOT provide REQUEST RELEASE functionality until the business status becomes VERIFIED
7. WHEN selecting multiple settlements for bulk actions, the System SHALL only allow selection of VERIFIED settlements that belong to the same group (same PTS, Processing_Entity, Counterparty_ID, and Value_Date)
8. WHEN performing bulk REQUEST RELEASE or AUTHORISE actions, the System SHALL apply the action to all selected VERIFIED settlements and record individual audit entries for each settlement
9. THE System SHALL maintain a complete audit trail of all user actions including REQUEST RELEASE and AUTHORISE operations with timestamps, user identities, settlement details, and version information for VERIFIED settlements only
10. WHEN a settlement status is PENDING_AUTHORISE or AUTHORISED, the System SHALL display approval workflow information including request timestamp, authorize timestamp (if applicable), and user identities who performed the actions

### Requirement 5

**User Story:** As a system user, I want the system to handle high-volume settlement processing with acceptable performance, so that I can access current settlement status without excessive delays during peak trading periods.

#### Acceptance Criteria

1. WHEN processing settlement flows during peak periods, the System SHALL handle up to 200,000 settlements within 30 minutes without system failure while maintaining data consistency across multiple system instances
2. WHEN a settlement is received and processed, the System SHALL make the updated status available in the user interface within 30 seconds of receipt, ensuring consistency across all system instances
3. WHEN calculating running totals for PAY settlement groups, the System SHALL complete the recalculation within 10 seconds of receiving a new settlement, accounting for potential direction changes in NET settlements and ensuring complete group recalculation rather than incremental updates, while preventing concurrent calculation conflicts
4. WHEN users query settlement status via API, the System SHALL respond within 3 seconds under normal load conditions
5. THE System SHALL maintain acceptable response times for user interface operations even during peak settlement processing periods

### Requirement 6

**User Story:** As an operations team member, I want to search and filter settlements using multiple criteria, so that I can efficiently locate specific settlements for review and management.

#### Acceptance Criteria

1. WHEN using the search interface, the System SHALL allow filtering by PTS, Processing_Entity, Value_Date, Counterparty_ID, Settlement_Direction (PAY or RECEIVE), Settlement_Type (NET or GROSS), and Business_Status (PENDING, INVALID, VERIFIED, or CANCELLED)
2. WHEN searching settlements, the System SHALL provide a filter option to show only PAY settlements with business status not CANCELLED in groups that exceed the limit, only PAY settlements that do not exceed the limit, or all settlements regardless of direction and business status
3. WHEN multiple search criteria are applied, the System SHALL return settlements that match all specified criteria
4. WHEN displaying search results, the System SHALL show settlement details including direction, type, and business status, current status, and group running total information calculated from PAY settlements with business status not CANCELLED, while displaying all settlements regardless of direction and business status in the results
5. THE System SHALL provide search results in a paginated format for efficient browsing of large result sets
6. WHEN users want to export search results, the System SHALL allow downloading the filtered settlements as an Excel file containing all relevant settlement details and status information for settlements of all directions and business statuses
7. WHEN displaying the user interface, the System SHALL show settlement groups in the upper section and individual settlements in the lower section, including settlements of all directions and business statuses for complete visibility
8. WHEN a user clicks on a settlement group in the upper section, the System SHALL display all settlements belonging to that group in the lower section with their individual details, statuses, direction, and business status indicators
9. WHEN displaying settlement information, the System SHALL show sufficient context including settlement direction, type, and business status, group running total in USD calculated from PAY settlements with business status not CANCELLED, exposure limit, current exchange rates as reference for currency conversion, and filtering rule application status to explain why PAY settlements are BLOCKED or not BLOCKED

### Requirement 7

**User Story:** As an external system, I want to query settlement status via API, so that I can make informed processing decisions based on current settlement approval status.

#### Acceptance Criteria

1. WHEN an external system queries by Settlement_ID, the System SHALL return the current settlement status (CREATED, BLOCKED, PENDING_AUTHORISE, or AUTHORISED) along with settlement direction, type, and business status information for all settlements
2. WHEN a settlement status is BLOCKED, the System SHALL include detailed information explaining why the PAY settlement is blocked, including group running total calculated from PAY settlements with business status not CANCELLED, exposure limit, and affected counterparty details
3. WHEN a settlement status is PENDING_AUTHORISE or AUTHORISED, the System SHALL include approval workflow information including timestamps and user actions taken
4. WHEN a Settlement_ID is not found, the System SHALL return an appropriate error response with clear messaging
5. THE System SHALL provide API responses in a structured format with sufficient detail to prevent follow-up queries for clarification
6. WHEN a settlement status changes to AUTHORISED, the System SHALL send a notification to external systems containing the Settlement_ID and authorization details to trigger downstream processing
7. WHEN a manual recalculation is requested via API endpoint with scope criteria (PTS, Processing_Entity, from_Value_Date), the System SHALL recalculate running totals from PAY settlements with business status not CANCELLED and update settlement statuses for all settlements matching the specified criteria
8. WHEN performing manual recalculation, the System SHALL apply current filtering rules and exposure limits to determine updated settlement statuses within the specified scope
9. WHEN a manual recalculation is requested, the System SHALL require appropriate authorization and log the request with user identity, timestamp, and scope
10. WHEN external systems are unavailable to receive notifications, the System SHALL implement retry mechanism with exponential backoff for up to 24 hours

### Requirement 8

**User Story:** As a system administrator, I want to configure exposure limits and currency conversion rates, so that the system can adapt to changing business requirements and market conditions.

#### Acceptance Criteria

1. WHEN operating in MVP mode, the System SHALL use a fixed Exposure_Limit of 500 million USD for all counterparties
2. WHEN configured for advanced mode, the System SHALL fetch counterparty-specific exposure limits from an external system daily and apply the appropriate limit based on the settlement's Counterparty_ID
3. THE System SHALL automatically fetch and store exchange rates from external systems daily for currency conversion
4. WHEN currency conversion is required, the System SHALL use the latest available exchange rate at the time of settlement processing to convert amounts to USD equivalent
5. WHEN new exchange rates are fetched and stored, the System SHALL make them available for future settlement processing without recalculating existing running totals
6. WHEN counterparty-specific limits are updated, the System SHALL re-evaluate all affected settlement groups against their respective new limits
7. THE System SHALL log all configuration changes and limit updates with timestamps and system identity
8. WHEN exchange rates are older than 24 hours, the System SHALL flag this in the UI and API responses to indicate potential rate staleness
9. THE System SHALL maintain a history of exchange rate changes for audit purposes

### Requirement 9

**User Story:** As a system architect, I want the system to handle distributed processing correctly, so that settlement data remains consistent across multiple system instances and concurrent operations.

#### Acceptance Criteria

1. WHEN the system operates with multiple instances behind a load balancer, the System SHALL ensure that settlement processing and running total calculations remain consistent across all instances
2. WHEN settlement versions arrive out of chronological order due to network delays or processing differences, the System SHALL apply only the latest version based on Settlement_Version number to running total calculations
3. WHEN multiple settlements within the same group are processed concurrently by different system instances, the System SHALL use appropriate locking or atomic operations to prevent running total calculation race conditions
4. WHEN a settlement version update conflicts with concurrent processing, the System SHALL ensure data consistency through proper transaction isolation or retry mechanisms
5. WHEN system instances restart or fail during settlement processing, the System SHALL maintain data integrity and resume processing without data loss or corruption
6. THE System SHALL provide idempotent settlement processing to handle duplicate settlement submissions without creating inconsistent state

### Requirement 10

**User Story:** As a compliance officer, I want to access historical settlement data and review decisions, so that I can perform audits and ensure regulatory compliance.

#### Acceptance Criteria

1. WHEN querying historical data, the System SHALL provide access to all settlement versions and their timestamps, including settlements of all directions, types, and business statuses with their complete information
2. WHEN reviewing audit trails, the System SHALL display all review actions, approvals, and system calculations with full traceability for settlements of all directions and business statuses
3. WHEN generating reports, the System SHALL export settlement data including direction, type, and business status, running totals, and review status in standard formats, showing settlements of all directions and business statuses for complete transaction visibility
4. WHEN data retention policies apply, the System SHALL archive historical data while maintaining accessibility for compliance periods (7 years as defined in glossary)
5. THE System SHALL ensure data integrity and prevent unauthorized modifications to historical records
6. THE System SHALL provide audit reports showing all settlement version changes, approval workflow actions, and system recalculations with timestamps and user identities

## Implementation Clarifications

This section provides additional context to resolve ambiguities in the acceptance criteria above.

### Performance vs. Consistency Trade-offs

**Clarification for Requirement 2 AC 2 (Complete Recalculation):**
- The requirement to recalculate complete group running totals on every change is intentional for data consistency
- To meet performance targets (Requirement 5), the system should use:
  - Materialized views or cached group running totals that are updated asynchronously
  - A single-threaded background processor to avoid race conditions
  - Event sourcing pattern to replay settlements for recalculation
- Status availability within 30 seconds (Requirement 5 AC 2) is achievable through:
  - Immediate settlement storage (ingestion)
  - Async background processing for group updates
  - Query-time status computation from cached running totals

### Settlement Validation Rules

**Clarification for Requirement 1 AC 16:**
Invalid or incomplete data includes:
- Missing required fields: PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version, Settlement_Direction, Settlement_Type, Business_Status
- Invalid currency codes (not ISO 4217 compliant)
- Non-numeric amounts or negative amounts
- Invalid date formats or dates in the past (for future-dated settlements)
- Invalid Settlement_Direction (not PAY or RECEIVE)
- Invalid Settlement_Type (not NET or GROSS)
- Invalid Business_Status (not PENDING, INVALID, VERIFIED, or CANCELLED)

### Approval Workflow Security

**Clarification for Requirement 4 AC 4:**
- The system should prevent the same user from both requesting and authorizing, regardless of session
- User identity should be tracked by user ID, not session
- System should check audit trail to ensure requester ≠ authorizer

**Clarification for Requirement 4 AC 9:**
- Audit trail is maintained for VERIFIED settlements only because:
  - PENDING/INVALID settlements cannot be approved (Requirement 4 AC 6)
  - CANCELLED settlements are excluded from monitoring
  - This reduces audit volume while maintaining compliance for actionable settlements

### Exchange Rate Consistency

**Clarification for Requirement 8 AC 4-5:**
- Settlements use exchange rates at processing time
- Groups may contain settlements processed at different rates
- This is intentional - each settlement's USD equivalent is fixed at processing time
- Group running totals sum these fixed USD amounts
- Rate changes only affect future settlements, not historical calculations

### NET Settlement Direction Changes

**Clarification for Requirement 2 AC 6:**
- NET settlement direction changes come as new versions of the same Settlement_ID
- The version number increments, but Settlement_ID remains constant
- The system detects direction changes by comparing Settlement_Direction between versions
- When direction changes, the system:
  1. Identifies the old group (based on old direction)
  2. Identifies the new group (based on new direction)
  3. Recalculates both groups completely

### Manual Recalculation Authorization

**Clarification for Requirement 7 AC 9:**
- Manual recalculation should require admin or supervisor-level privileges
- The request should be logged with: user ID, timestamp, scope (PTS, Processing_Entity, Value_Date range), and reason
- This prevents unauthorized mass status changes

### Notification Retry Policy

**Clarification for Requirement 7 AC 10:**
- Retry mechanism should use exponential backoff: 1min, 2min, 4min, 8min, 16min, 32min, 64min, etc.
- Maximum 24 hours of retry attempts
- After 24 hours, notification is marked as failed and logged for manual intervention
- Failed notifications should appear in admin dashboard

### Filtering Rules Application

**Clarification for Requirement 1 AC 19:**
- Settlements received between rule fetches (every 5 minutes) use cached rules
- When new rules are fetched, the system identifies affected groups
- Affected groups are recalculated using new rules
- This ensures consistency without requiring real-time rule fetching

### Data Retention and Archiving

**Clarification for Requirement 10 AC 4:**
- Compliance period: 7 years from settlement date (as per glossary)
- After 7 years, data can be moved to cold storage
- Archived data must remain searchable for audit purposes
- Archived data should be restorable within reasonable time (e.g., 24-48 hours)

### User Interface Requirements

**Additional Clarification:**
- The UI should distinguish between settlement types visually:
  - PAY settlements: Show amount, direction, and status
  - RECEIVE settlements: Show amount but indicate "not included in exposure"
  - NET settlements: Show direction indicator and note that direction can change
  - CANCELLED settlements: Struck through or grayed out
- Business Status should be clearly visible: PENDING (yellow), INVALID (orange), VERIFIED (green), CANCELLED (red)
- Group running total should show: USD amount, exposure limit, and percentage of limit used