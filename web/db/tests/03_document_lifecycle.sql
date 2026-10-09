-- DRAFT -> VERIFIED -> VOIDED: verified records cannot be edited or deleted, only voided with a reason.
BEGIN;

CREATE TEMP TABLE t ON COMMIT DROP AS
SELECT test.revenue(test.folio('F-1001'), '2026-03-10', 1500, 'DRAFT') AS draft_id,
       test.revenue(test.folio('F-1001'), '2026-03-11', 1500, 'VERIFIED') AS verified_id;

SELECT test.assert_succeeds(
    format('UPDATE revenue_entries SET description = %L WHERE id = %s', 'แก้ร่าง', (SELECT draft_id FROM t)),
    'a DRAFT document can be edited');
SELECT test.assert_succeeds(
    format('DELETE FROM revenue_entries WHERE id = %s', (SELECT draft_id FROM t)),
    'a DRAFT document can be deleted');

SELECT test.assert_raises(
    format('UPDATE revenue_entries SET amount_excl_tax = 1, total_amount = 1 WHERE id = %s', (SELECT verified_id FROM t)),
    'AC001', 'a VERIFIED document cannot be edited');
SELECT test.assert_raises(
    format('DELETE FROM revenue_entries WHERE id = %s', (SELECT verified_id FROM t)),
    'AC006', 'a VERIFIED document cannot be deleted');
SELECT test.assert_raises(
    format('UPDATE revenue_entries SET status = %L WHERE id = %s', 'DRAFT', (SELECT verified_id FROM t)),
    'AC001', 'a VERIFIED document cannot go back to DRAFT');
SELECT test.assert_raises(
    format('UPDATE revenue_entries SET status = %L, voided_by = %s, voided_at = now() WHERE id = %s',
           'VOIDED', test.usr('accountant'), (SELECT verified_id FROM t)),
    '23514', 'voiding requires a reason');
SELECT test.assert_succeeds(
    format('UPDATE revenue_entries SET status = %L, voided_by = %s, voided_at = now(), void_reason = %L WHERE id = %s',
           'VOIDED', test.usr('accountant'), 'บันทึกซ้ำ', (SELECT verified_id FROM t)),
    'a VERIFIED document can be voided with a reason');
SELECT test.assert_raises(
    format('UPDATE revenue_entries SET notes = %L WHERE id = %s', 'x', (SELECT verified_id FROM t)),
    'AC001', 'a VOIDED document is final');
SELECT test.assert_raises(
    format('DELETE FROM revenue_entries WHERE id = %s', (SELECT verified_id FROM t)),
    'AC006', 'a VOIDED document cannot be deleted');

SELECT test.assert_raises(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                   ledger_account_id, status, source_system)
      VALUES (test.prop(), test.folio('F-1001'), '2026-03-10', 'ROOM', 100, 100, test.acct('4101'), 'VERIFIED', 'MANUAL')$$,
    '23514', 'VERIFIED requires who verified and when');
SELECT test.assert_raises(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, vat_amount, total_amount,
                                   ledger_account_id, source_system)
      VALUES (test.prop(), test.folio('F-1001'), '2026-03-10', 'ROOM', 1000, 70, 1000, test.acct('4101'), 'MANUAL')$$,
    '23514', 'total must equal amount before tax + VAT + service charge');
SELECT test.assert_raises(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                   ledger_account_id, source_system)
      VALUES (test.prop(), test.folio('F-1001'), '2026-03-10', 'ROOM', -100, -100, test.acct('4101'), 'MANUAL')$$,
    '23514', 'negative revenue is only allowed for ADJUSTMENT');
SELECT test.assert_succeeds(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                   ledger_account_id, source_system, description)
      VALUES (test.prop(), test.folio('F-1001'), '2026-03-10', 'ADJUSTMENT', -100, -100, test.acct('4101'), 'MANUAL', 'ส่วนลดหลังเช็คเอาท์')$$,
    'a negative ADJUSTMENT is allowed');
SELECT test.assert_raises(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                   ledger_account_id, source_system)
      VALUES (test.prop(), test.folio('F-1001'), '2026-03-10', 'ROOM', 100, 100, test.acct('5211'), 'MANUAL')$$,
    'AC005', 'revenue cannot be booked to an expense account');
SELECT test.assert_raises(
    $$INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                   ledger_account_id, source_system)
      VALUES (test.prop(), test.folio('F-OTHER', 'TEST_OTHER'), '2026-03-10', 'ROOM', 100, 100, test.acct('4101'), 'MANUAL')$$,
    'AC008', 'a document cannot point at another property''s folio');
SELECT test.assert_raises(
    $$INSERT INTO payments (property_id, receipt_type, received_date, method, bank_account_id, amount, source_system)
      VALUES (test.prop(), 'GUEST_PAYMENT', '2026-03-10', 'CASH', test.bank('Other bank', 'TEST_OTHER'), 100, 'MANUAL')$$,
    'AC008', 'money cannot land in another property''s bank account');
SELECT test.assert_raises(
    $$INSERT INTO payments (property_id, receipt_type, received_date, method, bank_account_id, amount, source_system)
      VALUES (test.prop(), 'GUEST_PAYMENT', '2026-03-10', 'CASH', test.bank('Cash drawer'), 0, 'MANUAL')$$,
    '23514', 'a payment must be positive (direction comes from its type)');
CREATE TEMP TABLE t_refund ON COMMIT DROP AS SELECT test.payment(500, '2026-03-12', 'DRAFT', 'GUEST_REFUND') AS id;
SELECT test.assert_eq((SELECT direction FROM payments WHERE id = (SELECT id FROM t_refund)), 'OUT', 'a guest refund is money out');

ROLLBACK;
