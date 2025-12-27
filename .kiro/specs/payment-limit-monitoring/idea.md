# Receiving settlement
## Save settlement
When receive a settlement, save it into `SETTLEMENT` table, with an auto-incrementing sequence ID (it will be used as `REF_ID` in the `RUNNING_TOTAL` table), the sequence ID must only increase monotonically, it means that later saved settlement must have the higher sequence ID.

## `SETTLEMENT` table
`SETTLEMENT` table fields are: `ID`(auto-incrementing, the primary key, the `REF_ID`), `SETTLEMENT_ID`, `SETTLEMENT_VERSION`, `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`, `CURRENCY`, `AMOUNT`, `BUSINESS_STATUS`, `DIRECTION`, `GROSS_NET`, `CREATE_TIME`.

## `SETTLEMENT_HIST` table
`SETTLEMENT_HIST` table stores old settlement versions (non-latest): same fields as `SETTLEMENT` table. A daily job moves non-latest versions from `SETTLEMENT` to `SETTLEMENT_HIST`.

## `EXCHANGE_RATE` table
`EXCHANGE_RATE` table stores the latest exchange rates for currency conversion: `CURRENCY`, `RATE_TO_USD`, `UPDATE_TIME`. Only the latest rate per currency is kept.

## Generate events for calculation
- One or two events should be generated and saved into `EVENT` table, with the following fields: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`, `SETTLEMENT_ID`, `REF_ID`, `CREATE_TIME` and an auto-generated sequence ID.
- By default, one event should be generated, the `REF_ID` is the value of current settlement `ID` in the `SETTLEMENT` table. 
- But for settlement `COUNTERPARTY_ID` changed case (same `SETTLEMENT_ID` with different `COUNTERPARTY_ID`), two events should be generated, one for previous `COUNTERPARTY_ID`, one for new `COUNTERPARTY_ID`. 

### How to detect settlement `COUNTERPARTY_ID` changed
- When receive a settlement (current), by SQL, select the previous + latest `SETTLEMENT_VERSION` of the current settlement `SETTLEMENT_ID`, and has different `COUNTERPARTY_ID`.
- If such settlement exists in the SQL result, it means settlement `COUNTERPARTY_ID` changed.

## Consume events - calculate running total - batch process
A batch process should run periodically to calculate running totals and persist the result into `RUNNING_TOTAL` table.

### Batch schedule / interval
- 5 seconds after previous run
- to optimize performance, only delay when previous run processed no events (it means low traffic), if previous run processed some events, it will be immediately after the previous run, no need to delay.

### `RUNNING_TOTAL` table
Fields are: `ID`(auto-incrementing, the primary key), `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `RUNNING_TOTAL`, `REF_ID`, `SETTLEMENT_COUNT`, `CREATE_TIME`.

### Batch process flow
- Fetch 1000 events from the `EVENT` table
- In memory remove those duplicate/out-dated (for same `SETTLEMENT_ID` but with smaller `REF_ID`), the event list will be <= 1000
- And then calculate running totals for each group, using the calculation algorithm below.
- Within one transaction: save results into `RUNNING_TOTAL` table along with the `REF_ID` that is the biggest `REF_ID` within all these 1000 events, and delete the processed events from `EVENT` table.
- The `REF_ID` is very important, because it tells the system to which record in the `SETTLEMENT` table, the calculation was using.

### Calculation Algorithm
- Calculate the records in `SETTLEMENT` table by SQL, join the `EXCHANGE_RATE` table, convert settlement amount to USD and sum them
- For the `where` conditions
  - `ID` <= the biggest `REF_ID` within all these 1000 events
  - `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` in those event list
  - `BUSINESS_STATUS` filtered by rules fetched from external rule system (stored in memory)
  - For settlements with same `SETTLEMENT_ID`, only the `SETTLEMENT_VERSION` latest one is used

# Manual recalculation
User can manually recalculate the running total by providing criteria to search for groups (`PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`)

## Generate events for recalculation
Search the groups from `RUNNING_TOTAL` table with the provided criteria, and generate events for each group. The `REF_ID` in the event should be the current biggest number in `SETTLEMENT` table.

## Consume events - recalculate running total
The same batch process will consume the events and recalculate the running total. No differences, because event format is the same.