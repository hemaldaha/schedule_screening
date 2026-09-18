-- Truncate all tables used by integration tests.
-- Runs BEFORE each test method in ISOLATED transaction mode (committed independently).
TRUNCATE TABLE aml.screening_exceptions CASCADE;
TRUNCATE TABLE aml.screening_exceptions_archive CASCADE;
TRUNCATE TABLE aml.sch_screening_cus CASCADE;
TRUNCATE TABLE aml.screening_runs CASCADE;
