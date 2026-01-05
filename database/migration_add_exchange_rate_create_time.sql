-- Migration: Add CREATE_TIME column to EXCHANGE_RATE table
-- Date: 2026-01-05
-- Description: Adds CREATE_TIME column to track when exchange rates were first created

-- Add CREATE_TIME column if it doesn't exist
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE EXCHANGE_RATE ADD CREATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP';
    DBMS_OUTPUT.PUT_LINE('CREATE_TIME column added successfully');
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -1430 THEN
            DBMS_OUTPUT.PUT_LINE('CREATE_TIME column already exists');
        ELSE
            RAISE;
        END IF;
END;
/

-- Update existing rows to set CREATE_TIME = UPDATE_TIME for historical data
UPDATE EXCHANGE_RATE 
SET CREATE_TIME = UPDATE_TIME 
WHERE CREATE_TIME IS NULL;

COMMIT;
