-- A separate database for `docker compose --profile test run --rm tests`; tests drop and recreate its schema.
CREATE DATABASE wallet_test OWNER wallet;
