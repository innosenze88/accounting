-- Committed sample data for the backup/restore drill (test database only; uses tests/_helpers.sql).
-- Produces: opening balance, a reconciled and CLOSED February, March drafts, audit history.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('app.user_id', test.usr('accountant')::text, true);
INSERT INTO accounting_periods (property_id, period_start) VALUES (test.prop(), '2026-02-01'), (test.prop(), '2026-03-01');
SELECT test.post('2026-01-31', '1111', '3101', 10000, 'JV-OB', 'KBank operating', NULL);
CREATE TEMP TABLE t ON COMMIT DROP AS SELECT
    test.revenue(test.folio('F-1001'), '2026-02-10', 1500) AS rev,
    test.payment(1500, '2026-02-10') AS pay,
    test.bank_txn(1500, '2026-02-10', 'drill-1') AS bt;
SELECT test.post('2026-02-10', '1131', '4101', 1500, 'JV-FEB-REV');
SELECT test.post('2026-02-10', '1111', '1131', 1500, 'JV-FEB-RCPT', 'KBank operating', NULL);
SELECT test.allocate((SELECT pay FROM t), test.folio('F-1001'), 1500, '2026-02-10');
INSERT INTO reconciliations (property_id, match_method) VALUES (test.prop(), 'MANUAL');
INSERT INTO reconciliation_items (reconciliation_id, side, bank_transaction_id, payment_id, amount)
SELECT max(id), 'BANK', (SELECT bt FROM t), NULL, 1500 FROM reconciliations
UNION ALL SELECT max(id), 'BOOK', NULL, (SELECT pay FROM t), 1500 FROM reconciliations;
UPDATE reconciliations SET status = 'CONFIRMED', confirmed_by = test.usr('accountant'), confirmed_at = now() WHERE status = 'PROPOSED';
INSERT INTO imported_files (property_id, source_system, report_type, bank_account_id, original_filename, mime_type, size_bytes,
                            sha256, storage_key, report_date_from, report_date_to, status, totals_match, committed_by, committed_at)
SELECT test.prop(), v.src, v.typ, test.bank(v.bank), v.typ || '.xlsx', 'text/csv', 100, repeat(v.sha, 64), 'imports/drill/' || v.sha,
       '2026-02-01', '2026-02-28', 'COMMITTED', true, test.usr('accountant'), now()
FROM (VALUES ('EZEE', 'NIGHT_AUDIT', NULL, '1'), ('BANK', 'BANK_STATEMENT', 'KBank operating', '2'),
             ('BANK', 'BANK_STATEMENT', 'Capital fund', '3')) v(src, typ, bank, sha);
INSERT INTO balance_confirmations (property_id, bank_account_id, as_of_date, confirmed_balance, method, imported_file_id, counted_by,
                                   status, verified_by, verified_at)
SELECT test.prop(), b.id, '2026-02-28', v.bal, v.method,
       (SELECT id FROM imported_files WHERE bank_account_id = b.id AND report_type = 'BANK_STATEMENT'),
       CASE WHEN v.method = 'CASH_COUNT' THEN test.usr('frontdesk') END, 'VERIFIED', test.usr('accountant'), now()
FROM (VALUES ('KBank operating', 11500.00, 'BANK_STATEMENT'), ('Capital fund', 0.00, 'BANK_STATEMENT'),
             ('Cash drawer', 0.00, 'CASH_COUNT')) v(name, bal, method)
JOIN bank_accounts b ON b.account_name = v.name AND b.property_id = test.prop();
UPDATE accounting_periods SET status = 'CLOSED', closed_by = test.usr('owner'), closed_at = now()
WHERE property_id = test.prop() AND period_start = '2026-02-01';
SELECT test.expense('ELECTRICITY', 4280, '2026-03-05', 'DRAFT');
SELECT test.revenue(test.folio('F-1002'), '2026-03-10', 1200);
COMMIT;
