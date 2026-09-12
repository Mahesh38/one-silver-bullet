-- FUNC-026: persist ULIP fund rows from quote poll (additive, nullable).
ALTER TABLE integration_job_offer ADD COLUMN funds_json TEXT;
