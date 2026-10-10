-- Imports: the same file or the same source row can never create accounting records twice;
-- raw rows are kept unchanged; a file whose totals differ from the printed report cannot be committed.
BEGIN;

CREATE TEMP TABLE t ON COMMIT DROP AS SELECT 0::bigint AS file_id, 0::bigint AS row1, 0::bigint AS row2;

INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256,
                            storage_key, report_date_from, report_date_to, uploaded_by)
VALUES (test.prop(), 'EZEE', 'RECEIPT_DETAIL', 'receipts_2026-03-10.xlsx',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 2048, repeat('a', 64),
        'imports/test/receipts-1.xlsx', '2026-03-10', '2026-03-10', test.usr('frontdesk'));
UPDATE t SET file_id = (SELECT id FROM imported_files WHERE sha256 = repeat('a', 64));

SELECT test.assert_raises(
    $$INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256, storage_key)
      VALUES (test.prop(), 'EZEE', 'RECEIPT_DETAIL', 'receipts_copy.xlsx', 'text/csv', 2048, repeat('a', 64), 'imports/test/receipts-2.xlsx')$$,
    '23505', 'uploading the very same file again is refused (same SHA-256)');

INSERT INTO import_rows (imported_file_id, row_number, section, raw_data, normalized_data, natural_key, row_hash, status)
SELECT file_id, 1, 'receipts', '{"Receipt No":"R-0001","Amount":"1,000.00","Date":"10/03/2569"}',
       '{"receipt_no":"R-0001","amount":"1000.00","date":"2026-03-10"}',
       test.prop() || '|EZEE|RECEIPT_DETAIL|R-0001', repeat('b', 64), 'VALID'
FROM t;
UPDATE t SET row1 = (SELECT id FROM import_rows WHERE imported_file_id = t.file_id AND row_number = 1);

-- Commit row 1 into an accounting document.
CREATE TEMP TABLE t_pay ON COMMIT DROP AS SELECT test.payment(1000, '2026-03-10', 'DRAFT', p_ref => 'R-0001') AS id;
UPDATE payments SET import_row_id = (SELECT row1 FROM t) WHERE id = (SELECT id FROM t_pay);
UPDATE import_rows SET status = 'COMMITTED', target_table = 'payments', target_id = (SELECT id FROM t_pay) WHERE id = (SELECT row1 FROM t);

-- The same receipt arrives again in a second, different file (e.g. a re-exported report).
INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256,
                            storage_key, report_date_from, report_date_to)
VALUES (test.prop(), 'EZEE', 'RECEIPT_DETAIL', 'receipts_2026-03-10_v2.xlsx', 'text/csv', 2050, repeat('c', 64),
        'imports/test/receipts-3.xlsx', '2026-03-10', '2026-03-10');
INSERT INTO import_rows (imported_file_id, row_number, raw_data, natural_key, row_hash, status)
SELECT id, 1, '{"Receipt No":"R-0001"}', test.prop() || '|EZEE|RECEIPT_DETAIL|R-0001', repeat('b', 64), 'VALID'
FROM imported_files WHERE sha256 = repeat('c', 64);
UPDATE t SET row2 = (SELECT r.id FROM import_rows r JOIN imported_files f ON f.id = r.imported_file_id WHERE f.sha256 = repeat('c', 64));

SELECT test.assert_raises(
    format($$UPDATE import_rows SET status = 'COMMITTED', target_table = 'payments', target_id = 1 WHERE id = %s$$, (SELECT row2 FROM t)),
    '23505', 'a natural key that is already committed cannot be committed again');
SELECT test.assert_raises(
    $$INSERT INTO payments (property_id, receipt_type, received_date, method, bank_account_id, amount, source_system, source_ref)
      VALUES (test.prop(), 'GUEST_PAYMENT', '2026-03-10', 'CASH', test.bank('Cash drawer'), 1000, 'EZEE', 'R-0001')$$,
    '23505', 'the same PMS receipt number cannot become two payments');
SELECT test.assert_succeeds(
    format($$UPDATE import_rows SET status = 'DUPLICATE', duplicate_of_row_id = %s WHERE id = %s$$, (SELECT row1 FROM t), (SELECT row2 FROM t)),
    'the repeated row is marked DUPLICATE and linked to the original row');
SELECT test.assert_eq((SELECT count(*)::int FROM payments WHERE source_ref = 'R-0001'), 1, 'still exactly one payment for R-0001');

SELECT test.assert_raises(
    format($$UPDATE import_rows SET raw_data = '{"Amount":"9,999.00"}' WHERE id = %s$$, (SELECT row1 FROM t)),
    'AC001', 'raw imported data cannot be edited');
SELECT test.assert_raises(
    format($$DELETE FROM import_rows WHERE id = %s$$, (SELECT row1 FROM t)),
    'AC006', 'raw imported rows cannot be deleted');
SELECT test.assert_raises(
    format($$UPDATE imported_files SET sha256 = repeat('d', 64) WHERE id = %s$$, (SELECT file_id FROM t)),
    'AC001', 'the fingerprint of an imported file cannot be changed');
SELECT test.assert_raises(
    format($$DELETE FROM imported_files WHERE id = %s$$, (SELECT file_id FROM t)),
    'AC006', 'import history cannot be deleted');
SELECT test.assert_raises(
    format($$UPDATE imported_files SET status = 'COMMITTED', committed_at = now(), totals_match = false WHERE id = %s$$, (SELECT file_id FROM t)),
    '23514', 'a file whose totals do not match the report cannot be committed');
SELECT test.assert_succeeds(
    format($$UPDATE imported_files SET status = 'COMMITTED', committed_at = now(), committed_by = %s, totals_match = true WHERE id = %s$$,
           test.usr('accountant'), (SELECT file_id FROM t)),
    'a file whose totals match is committed');
SELECT test.assert_raises(
    format($$UPDATE imported_files SET status = 'REJECTED', rejected_at = now(), reject_reason = 'x' WHERE id = %s$$, (SELECT file_id FROM t)),
    'AC001', 'a committed file cannot change status afterwards');

-- A rejected upload may be uploaded again (e.g. after fixing the mapping profile).
UPDATE imported_files SET status = 'REJECTED', rejected_at = now(), rejected_by = test.usr('accountant'), reject_reason = 'ผิดรูปแบบ'
WHERE sha256 = repeat('c', 64);
SELECT test.assert_succeeds(
    $$INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256, storage_key)
      VALUES (test.prop(), 'EZEE', 'RECEIPT_DETAIL', 'receipts_2026-03-10_v2.xlsx', 'text/csv', 2050, repeat('c', 64), 'imports/test/receipts-4.xlsx')$$,
    'a rejected file can be uploaded again');
SELECT test.assert_raises(
    $$UPDATE imported_files SET status = 'REJECTED', rejected_at = now() WHERE sha256 = repeat('c', 64) AND status = 'UPLOADED'$$,
    '23514', 'rejecting a file requires a reason');

SELECT test.assert_raises(
    $$INSERT INTO imported_files (property_id, source_system, report_type, original_filename, mime_type, size_bytes, sha256, storage_key)
      VALUES (test.prop(), 'BANK', 'BANK_STATEMENT', 'kbank.csv', 'text/csv', 10, repeat('e', 64), 'imports/test/kbank.csv')$$,
    '23514', 'a bank statement must say which bank account it belongs to');
SELECT test.assert_raises(
    format($$INSERT INTO import_rows (imported_file_id, row_number, raw_data, natural_key, row_hash, status)
             VALUES (%s, 99, '{}', 'R-0001', repeat('b', 64), 'VALID')$$, (SELECT file_id FROM t)),
    '23514', 'natural keys must carry property, source and report type');
SELECT test.assert_raises(
    format($$INSERT INTO import_rows (imported_file_id, row_number, raw_data, status) VALUES (%s, 98, '{}', 'INVALID')$$, (SELECT file_id FROM t)),
    '23514', 'an INVALID row must say what is wrong');
SELECT test.assert_succeeds(
    format($$INSERT INTO import_rows (imported_file_id, row_number, raw_data, status, errors)
             VALUES (%s, 97, '{"Date":"31/02/2569"}', 'INVALID', '[{"field":"date","code":"INVALID_DATE","message":"วันที่ไม่มีจริง"}]')$$,
           (SELECT file_id FROM t)),
    'an INVALID row is kept with its error list (shown to the user, nothing lost)');

-- Statistics rows keep the PMS figures as printed (real eZee reports have half room-nights, e.g. 134.5 in a month).
SELECT test.assert_succeeds(
    format($$INSERT INTO pms_daily_statistics (property_id, stat_date, is_forecast, rooms_available, rooms_sold,
                                               complimentary_rooms, occupancy_pct, room_charges, imported_file_id)
             VALUES (test.prop(), '2026-04-10', false, 15, 4.5, 0, 30.00, 3780.00, %s)$$, (SELECT file_id FROM t)),
    'a half room-night (day use) is stored as printed');
SELECT test.assert_raises(
    format($$INSERT INTO pms_daily_statistics (property_id, stat_date, is_forecast, rooms_available, rooms_sold,
                                               complimentary_rooms, imported_file_id)
             VALUES (test.prop(), '2026-04-11', false, 15, 15, 1, %s)$$, (SELECT file_id FROM t)),
    '23514', 'sold + complimentary rooms cannot exceed rooms available');

-- A corrected document may replace a voided one with the same source reference.
UPDATE payments SET status = 'VOIDED', voided_by = test.usr('accountant'), voided_at = now(), void_reason = 'ยอดผิด นำเข้าใหม่'
WHERE id = (SELECT id FROM t_pay);
SELECT test.assert_succeeds(
    $$INSERT INTO payments (property_id, receipt_type, received_date, method, bank_account_id, amount, source_system, source_ref)
      VALUES (test.prop(), 'GUEST_PAYMENT', '2026-03-10', 'CASH', test.bank('Cash drawer'), 1100, 'EZEE', 'R-0001')$$,
    'after voiding, the corrected receipt can be recorded under the same reference');

ROLLBACK;
