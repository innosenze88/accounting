-- Completeness labels and month close: profit is "FINAL" only for a closed month, and a month can only be
-- closed when every day has a revenue report, every bank account is covered and reconciled, nothing is DRAFT,
-- and the counted / statement balance equals the book balance. Closed months are locked.
BEGIN;

INSERT INTO accounting_periods (property_id, period_start) VALUES (test.prop(), '2026-02-01');
CREATE TEMP TABLE per ON COMMIT DROP AS SELECT id FROM accounting_periods WHERE property_id = test.prop() AND period_start = '2026-02-01';
CREATE FUNCTION pg_temp.c(p_col text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
    EXECUTE format('SELECT (%I)::text FROM v_period_completeness WHERE period_id = (SELECT id FROM per)', p_col) INTO v;
    RETURN v;
END $$;
CREATE FUNCTION pg_temp.close_feb() RETURNS void LANGUAGE sql AS $$
    UPDATE accounting_periods SET status = 'CLOSED', closed_by = test.usr('owner'), closed_at = now()
    WHERE id = (SELECT id FROM per) $$;
CREATE FUNCTION pg_temp.import_file(p_type text, p_bank text, p_sha text) RETURNS bigint LANGUAGE sql AS $$
    INSERT INTO imported_files (property_id, source_system, report_type, bank_account_id, original_filename, mime_type, size_bytes,
                                sha256, storage_key, report_date_from, report_date_to, status, totals_match, committed_by, committed_at)
    VALUES (test.prop(), CASE WHEN p_type = 'BANK_STATEMENT' THEN 'BANK' ELSE 'EZEE' END, p_type, test.bank(p_bank),
            p_type || '.xlsx', 'text/csv', 100, repeat(p_sha, 64), 'imports/test/' || p_sha, '2026-02-01', '2026-02-28',
            'COMMITTED', true, test.usr('accountant'), now())
    RETURNING id $$;

SELECT test.assert_raises(
    $$INSERT INTO accounting_periods (property_id, period_start, status, closed_by, closed_at)
      VALUES (test.prop(), '2026-05-01', 'CLOSED', test.usr('owner'), now())$$,
    'AC007', 'a period cannot be created already CLOSED (would skip the completeness check)');
SELECT test.assert_eq(pg_temp.c('completeness'), 'INCOMPLETE', 'an empty month is INCOMPLETE');
SELECT test.assert_eq(pg_temp.c('missing_revenue_report_days'), '28', 'all 28 days of February lack a revenue report');
SELECT test.assert_eq(pg_temp.c('accounts_missing_statement'), '2', 'both bank accounts lack a statement');
SELECT test.assert_eq(pg_temp.c('accounts_missing_month_end_balance'), '3', 'two bank accounts and the cash drawer lack a month-end balance');
SELECT test.assert_raises('SELECT pg_temp.close_feb()', 'AC007', 'an incomplete month cannot be closed');

-- Opening balance, then February activity, all posted and reconciled.
SELECT test.post('2026-01-31', '1111', '3101', 10000, 'JV-OB', 'KBank operating', NULL);
CREATE TEMP TABLE t ON COMMIT DROP AS SELECT
    test.revenue(test.folio('F-1001'), '2026-02-10', 1500) AS rev,
    test.payment(1500, '2026-02-10') AS pay,
    test.bank_txn(1500, '2026-02-10', 'feb-1') AS bt;
SELECT test.post('2026-02-10', '1131', '4101', 1500, 'JV-FEB-REV');
SELECT test.post('2026-02-10', '1111', '1131', 1500, 'JV-FEB-RCPT', 'KBank operating', NULL);
SELECT test.allocate((SELECT pay FROM t), test.folio('F-1001'), 1500, '2026-02-10');
WITH r AS (INSERT INTO reconciliations (property_id, match_method) VALUES (test.prop(), 'MANUAL') RETURNING id),
     i AS (INSERT INTO reconciliation_items (reconciliation_id, side, bank_transaction_id, payment_id, amount)
           SELECT id, 'BANK', (SELECT bt FROM t), NULL, 1500 FROM r
           UNION ALL SELECT id, 'BOOK', NULL, (SELECT pay FROM t), 1500 FROM r RETURNING reconciliation_id)
SELECT 1;
UPDATE reconciliations SET status = 'CONFIRMED', confirmed_by = test.usr('accountant'), confirmed_at = now()
WHERE property_id = test.prop() AND status = 'PROPOSED';

-- Reports and statements for every day / account, and month-end balances.
CREATE TEMP TABLE f ON COMMIT DROP AS SELECT
    pg_temp.import_file('NIGHT_AUDIT', NULL, '1') AS na,
    pg_temp.import_file('BANK_STATEMENT', 'KBank operating', '2') AS st_op,
    pg_temp.import_file('BANK_STATEMENT', 'Capital fund', '3') AS st_fund;
INSERT INTO balance_confirmations (property_id, bank_account_id, as_of_date, confirmed_balance, method, imported_file_id, counted_by,
                                   status, verified_by, verified_at)
SELECT test.prop(), b.id, '2026-02-28', v.bal, v.method, v.file, CASE WHEN v.method = 'CASH_COUNT' THEN test.usr('frontdesk') END,
       'VERIFIED', test.usr('accountant'), now()
FROM (VALUES ('KBank operating', 11500.00, 'BANK_STATEMENT', (SELECT st_op FROM f)),
             ('Capital fund', 0.00, 'BANK_STATEMENT', (SELECT st_fund FROM f)),
             ('Cash drawer', 0.00, 'CASH_COUNT', NULL::bigint)) v(name, bal, method, file)
JOIN bank_accounts b ON b.account_name = v.name AND b.property_id = test.prop();

SELECT test.assert_eq(pg_temp.c('issue_count'), '0', 'with every check satisfied there are no issues');
SELECT test.assert_eq(pg_temp.c('completeness'), 'PRELIMINARY', 'complete but not closed = PRELIMINARY');
SELECT test.assert_eq((SELECT completeness || ':' || net_profit FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-02-01'),
    'PRELIMINARY:1500.00', 'P&L carries the PRELIMINARY label');
SELECT test.assert_eq((SELECT difference FROM v_verified_cash WHERE bank_account_id = test.bank('KBank operating')), 0.00::numeric,
    'verified cash equals the book balance (11,500)');

-- Each kind of gap makes the month INCOMPLETE again.
CREATE TEMP TABLE d ON COMMIT DROP AS SELECT test.expense('WATER', 300, '2026-02-20', 'DRAFT') AS draft_exp;
SELECT test.assert_eq(pg_temp.c('draft_documents'), '1', 'a DRAFT document in the month is an issue');
DELETE FROM expenses WHERE id = (SELECT draft_exp FROM d);

SELECT test.post('2026-02-20', '5291', '1111', 100, 'JV-FEB-MISC', NULL, 'KBank operating');
SELECT test.assert_eq(pg_temp.c('accounts_with_balance_difference'), '1', 'book balance different from the statement balance is an issue');
WITH e AS (INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type, reversal_of_id)
           SELECT test.prop(), 'JV-FEB-MISC-REV', '2026-02-20', 'กลับรายการ', 'REVERSAL', id FROM journal_entries WHERE entry_no = 'JV-FEB-MISC'
           RETURNING id)
INSERT INTO journal_lines (journal_entry_id, line_no, ledger_account_id, debit, credit, bank_account_id)
SELECT id, 1, test.acct('1111'), 100, 0, test.bank('KBank operating') FROM e UNION ALL SELECT id, 2, test.acct('5291'), 0, 100, NULL FROM e;

CREATE TEMP TABLE b2 ON COMMIT DROP AS SELECT test.bank_txn(-50, '2026-02-25', 'feb-2') AS id;
SELECT test.assert_eq(pg_temp.c('unmatched_bank_transactions'), '1', 'an unmatched bank line is an issue');
UPDATE bank_transactions SET review_status = 'IGNORED', review_note = 'ค่าธรรมเนียมธนาคาร บันทึกรวมใน JV แล้ว (ทดสอบ)',
                             reviewed_by = test.usr('accountant'), reviewed_at = now()
WHERE id = (SELECT id FROM b2);

INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256, storage_key,
                            report_date_from, report_date_to)
VALUES (test.prop(), 'EZEE', 'RECEIPT_DETAIL', 'pending.xlsx', 'text/csv', 10, repeat('9', 64), 'imports/test/pending', '2026-02-27', '2026-02-27');
SELECT test.assert_eq(pg_temp.c('imports_pending_review'), '1', 'an uploaded file not yet committed or rejected is an issue');
UPDATE imported_files SET status = 'REJECTED', rejected_by = test.usr('accountant'), rejected_at = now(), reject_reason = 'ไฟล์ซ้ำ (ทดสอบ)'
WHERE sha256 = repeat('9', 64);

INSERT INTO ota_commissions (property_id, channel_id, recognition_date, commissionable_amount, commission_rate, commission_amount,
                             basis, settlement_method, status, source_system, verified_by, verified_at)
VALUES (test.prop(), test.channel('BOOKING_COM'), '2026-02-10', 1500, 15, 225, 'ESTIMATED_FROM_RATE', 'INVOICED_TO_HOTEL',
        'VERIFIED', 'SYSTEM', test.usr('accountant'), now());
SELECT test.assert_eq(pg_temp.c('estimated_commissions'), '1', 'a commission still estimated from a rate is an issue');
UPDATE ota_commissions SET status = 'VOIDED', voided_by = test.usr('accountant'), voided_at = now(), void_reason = 'ได้ใบแจ้งหนี้จริงแล้ว (ทดสอบ)'
WHERE recognition_date = '2026-02-10' AND basis = 'ESTIMATED_FROM_RATE';

SELECT test.assert_eq(pg_temp.c('issue_count'), '0', 'all issues resolved');

SELECT test.assert_succeeds('SELECT pg_temp.close_feb()', 'a complete month can be closed');
SELECT test.assert_eq(pg_temp.c('completeness'), 'FINAL', 'a closed month is FINAL');
SELECT test.assert_eq((SELECT completeness FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-02-01'), 'FINAL',
    'P&L shows FINAL only now');

-- Locked.
SELECT test.assert_raises($$SELECT test.revenue(test.folio('F-1001'), '2026-02-15', 100)$$, 'AC002', 'no new revenue inside a closed month');
SELECT test.assert_raises(
    format($$UPDATE revenue_entries SET status = 'VOIDED', voided_by = %s, voided_at = now(), void_reason = 'x' WHERE id = %s$$,
           test.usr('accountant'), (SELECT rev FROM t)),
    'AC002', 'a document in a closed month cannot even be voided (correct it in an open month)');
SELECT test.assert_raises($$SELECT test.post('2026-02-20', '5291', '2101', 10, 'JV-LATE')$$, 'AC002', 'no journal entry dated in a closed month');
SELECT test.assert_raises($$SELECT test.bank_txn(10, '2026-02-26', 'late-line')$$, 'AC002', 'no statement line dated in a closed month');
CREATE TEMP TABLE m ON COMMIT DROP AS SELECT test.payment(100, '2026-03-03') AS pay;
SELECT test.assert_raises($$SELECT test.allocate((SELECT pay FROM m), test.folio('F-1002'), 1, '2026-02-26')$$, 'AC002',
    'no allocation dated in a closed month');
SELECT test.assert_raises(
    format($$UPDATE accounting_periods SET close_note = 'changed' WHERE id = %s$$, (SELECT id FROM per)),
    'AC001', 'a closed period record cannot be edited');
SELECT test.assert_raises(format('DELETE FROM accounting_periods WHERE id = %s', (SELECT id FROM per)), 'AC006', 'periods cannot be deleted');
SELECT test.assert_succeeds($$SELECT test.post('2026-03-02', '5291', '2101', 10, 'JV-MAR')$$, 'the next (open) month still accepts entries');

INSERT INTO accounting_periods (property_id, period_start) VALUES (test.prop(), '2026-03-01'), (test.prop(), '2026-04-01');
SELECT test.assert_raises(
    $$UPDATE accounting_periods SET status = 'CLOSED', closed_by = test.usr('owner'), closed_at = now()
      WHERE property_id = test.prop() AND period_start = '2026-04-01'$$,
    'AC007', 'April cannot close before March');

-- Reopen needs who, when and why.
SELECT test.assert_raises(
    format($$UPDATE accounting_periods SET status = 'OPEN' WHERE id = %s$$, (SELECT id FROM per)),
    'AC007', 'reopening without recording who/when is refused');
SELECT test.assert_raises(
    format($$UPDATE accounting_periods SET status = 'OPEN', reopened_by = %s, reopened_at = now() WHERE id = %s$$,
           test.usr('owner'), (SELECT id FROM per)),
    '23514', 'reopening without a reason is refused');
SELECT test.assert_succeeds(
    format($$UPDATE accounting_periods SET status = 'OPEN', reopened_by = %s, reopened_at = now(), reopen_reason = %L WHERE id = %s$$,
           test.usr('owner'), 'นักบัญชีพบใบแจ้งหนี้ตกหล่น (ทดสอบ)', (SELECT id FROM per)),
    'the owner can reopen with a reason');
SELECT test.assert_eq((SELECT action FROM audit_logs WHERE table_name = 'accounting_periods' AND record_id = (SELECT id FROM per)
                       ORDER BY id DESC LIMIT 1), 'PERIOD_REOPEN', 'reopening is in the audit log');
SELECT test.assert_eq(pg_temp.c('completeness'), 'PRELIMINARY', 'a reopened month is no longer FINAL');

ROLLBACK;
