-- Audit trail: create / edit / verify / void are logged with the user and reason; the log cannot be changed.
BEGIN;

SELECT set_config('app.user_id', test.usr('frontdesk')::text, true);
CREATE TEMP TABLE t_ids ON COMMIT DROP AS
SELECT test.revenue(test.folio('F-1001'), '2026-03-10', 1500, 'DRAFT', 'AUD-1') AS rev_id;

SELECT test.assert_eq(
    (SELECT action || '/' || user_id::text FROM audit_logs WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids)),
    'INSERT/' || test.usr('frontdesk')::text, 'insert is logged with the acting user');

UPDATE revenue_entries SET amount_excl_tax = 1600, total_amount = 1600 WHERE id = (SELECT rev_id FROM t_ids);
SELECT test.assert_eq(
    (SELECT changed_fields FROM audit_logs WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids) AND action = 'UPDATE'),
    ARRAY['amount_excl_tax', 'total_amount'], 'edit is logged with the changed fields');
SELECT test.assert_eq(
    (SELECT (old_data ->> 'total_amount') || '->' || (new_data ->> 'total_amount') FROM audit_logs
     WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids) AND action = 'UPDATE'),
    '1500.00->1600.00', 'edit keeps the old and the new value');

SELECT set_config('app.user_id', test.usr('accountant')::text, true);
UPDATE revenue_entries SET status = 'VERIFIED', verified_by = test.usr('accountant'), verified_at = now()
WHERE id = (SELECT rev_id FROM t_ids);
SELECT test.assert_eq(
    (SELECT user_id FROM audit_logs WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids) AND action = 'VERIFY'),
    test.usr('accountant'), 'verification is logged as VERIFY by the verifier');

SELECT set_config('app.reason', 'ลูกค้าแจ้งยกเลิก (ทดสอบ)', true);
UPDATE revenue_entries SET status = 'VOIDED', voided_by = test.usr('accountant'), voided_at = now(), void_reason = 'ลูกค้าแจ้งยกเลิก (ทดสอบ)'
WHERE id = (SELECT rev_id FROM t_ids);
SELECT test.assert_eq(
    (SELECT reason FROM audit_logs WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids) AND action = 'VOID'),
    'ลูกค้าแจ้งยกเลิก (ทดสอบ)', 'void is logged as VOID with the reason');

SELECT test.assert_eq(
    (SELECT property_id FROM audit_logs WHERE table_name = 'revenue_entries' AND record_id = (SELECT rev_id FROM t_ids) AND action = 'INSERT'),
    test.prop(), 'audit row carries the property');

SELECT test.assert_raises($$UPDATE audit_logs SET reason = 'tampered'$$, 'AC001', 'audit log rows cannot be updated');
SELECT test.assert_raises($$DELETE FROM audit_logs$$, 'AC001', 'audit log rows cannot be deleted');
SELECT test.assert_raises($$TRUNCATE audit_logs$$, 'AC001', 'audit log cannot be truncated');

UPDATE users SET password_hash = '$argon2id$v=19$test-only' WHERE id = test.usr('owner');
SELECT test.assert_eq(
    (SELECT count(*)::int FROM audit_logs WHERE table_name = 'users' AND (new_data ? 'password_hash' OR old_data ? 'password_hash')),
    0, 'password hashes are never copied into the audit log');

SELECT test.assert_raises(
    $$INSERT INTO user_property_roles (user_id, property_id, role_code) VALUES (test.usr('owner'), test.prop(), 'OWNER')$$,
    '23505', 'the same active role cannot be granted twice');

ROLLBACK;
