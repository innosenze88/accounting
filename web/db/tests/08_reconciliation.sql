-- Bank reconciliation: many-to-many and partial matches, statuses, and "never invent money".
BEGIN;

CREATE TEMP TABLE t ON COMMIT DROP AS SELECT
    test.payment(1000, '2026-03-10') AS pay_a,
    test.payment(2000, '2026-03-10') AS pay_b,
    test.payment(4000, '2026-03-11') AS pay_c,
    test.payment(1000, '2026-03-12', p_fee => 30) AS pay_card,
    test.payment(700, '2026-03-12', 'DRAFT') AS pay_draft,
    test.bank_txn(3000, '2026-03-10', 'k1') AS bt_ab,
    test.bank_txn(1500, '2026-03-11', 'k2') AS bt_c1,
    test.bank_txn(2500, '2026-03-12', 'k3') AS bt_c2,
    test.bank_txn(970, '2026-03-13', 'k4') AS bt_card,
    test.bank_txn(5000, '2026-03-14', 'k5') AS bt_big,
    test.bank_txn(-450, '2026-03-15', 'k6') AS bt_unknown,
    test.bank_txn(700, '2026-03-15', 'k7') AS bt_draft;

SELECT count(*) AS n INTO TEMP t_before FROM payments;

-- helper: create a reconciliation and return its id
CREATE FUNCTION pg_temp.rec(p_method text DEFAULT 'MANUAL') RETURNS bigint LANGUAGE sql AS $$
    INSERT INTO reconciliations (property_id, match_method, rule_code, created_by)
    VALUES (test.prop(), p_method, CASE WHEN p_method <> 'MANUAL' THEN 'SAME_AMOUNT_SAME_DAY' END, test.usr('accountant'))
    RETURNING id $$;
CREATE FUNCTION pg_temp.item(p_rec bigint, p_side text, p_bank bigint, p_pay bigint, p_amount numeric) RETURNS void LANGUAGE sql AS $$
    INSERT INTO reconciliation_items (reconciliation_id, side, bank_transaction_id, payment_id, amount)
    VALUES (p_rec, p_side, p_bank, p_pay, p_amount) $$;
CREATE FUNCTION pg_temp.confirm(p_rec bigint, p_diff numeric DEFAULT 0, p_reason text DEFAULT NULL) RETURNS void LANGUAGE sql AS $$
    UPDATE reconciliations SET status = 'CONFIRMED', confirmed_by = test.usr('accountant'), confirmed_at = now(),
                               difference_amount = p_diff, difference_reason = p_reason
    WHERE id = p_rec $$;

-- 1 bank deposit = 2 receipts.
CREATE TEMP TABLE r ON COMMIT DROP AS SELECT pg_temp.rec() AS r1;
SELECT pg_temp.item((SELECT r1 FROM r), 'BANK', (SELECT bt_ab FROM t), NULL, 3000);
SELECT pg_temp.item((SELECT r1 FROM r), 'BOOK', NULL, (SELECT pay_a FROM t), 1000);
SELECT pg_temp.item((SELECT r1 FROM r), 'BOOK', NULL, (SELECT pay_b FROM t), 2000);
SELECT test.assert_eq((SELECT match_status FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_ab FROM t)), 'NEEDS_REVIEW',
    'a proposed (unconfirmed) match shows as NEEDS_REVIEW');
SELECT pg_temp.confirm((SELECT r1 FROM r));
SELECT test.assert_eq((SELECT match_status FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_ab FROM t)), 'MATCHED',
    'one bank deposit matched to two receipts is MATCHED');
SELECT test.assert_eq((SELECT string_agg(match_status, ',') FROM v_book_reconciliation_status
                       WHERE item_type = 'PAYMENT' AND item_id IN ((SELECT pay_a FROM t), (SELECT pay_b FROM t))),
    'MATCHED,MATCHED', 'both receipts are MATCHED');

-- 1 receipt = 2 bank deposits (guest paid in two transfers).
INSERT INTO r SELECT pg_temp.rec();
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_c1 FROM t), NULL, 1500);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_c2 FROM t), NULL, 2500);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BOOK', NULL, (SELECT pay_c FROM t), 4000);
SELECT pg_temp.confirm((SELECT max(r1) FROM r));
SELECT test.assert_eq((SELECT match_status FROM v_book_reconciliation_status WHERE item_type = 'PAYMENT' AND item_id = (SELECT pay_c FROM t)),
    'MATCHED', 'one receipt matched to two bank deposits is MATCHED');

-- Card payment: bank receives amount - fee.
INSERT INTO r SELECT pg_temp.rec();
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_card FROM t), NULL, 970);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BOOK', NULL, (SELECT pay_card FROM t), 970);
SELECT pg_temp.confirm((SELECT max(r1) FROM r));
SELECT test.assert_eq((SELECT match_status FROM v_book_reconciliation_status WHERE item_type = 'PAYMENT' AND item_id = (SELECT pay_card FROM t)),
    'MATCHED', 'card receipt 1,000 with 30 fee matches a 970 deposit');

-- Partial: 5,000 deposit, only 1,000 of it explained so far.
CREATE TEMP TABLE t2 ON COMMIT DROP AS SELECT test.payment(1000, '2026-03-14') AS pay_part;
INSERT INTO r SELECT pg_temp.rec();
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_big FROM t), NULL, 1000);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BOOK', NULL, (SELECT pay_part FROM t2), 1000);
SELECT pg_temp.confirm((SELECT max(r1) FROM r));
SELECT test.assert_eq((SELECT match_status || ':' || unmatched_amount FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_big FROM t)),
    'PARTIALLY_MATCHED:4000.00', 'a deposit explained only in part is PARTIALLY_MATCHED with 4,000 open');

SELECT test.assert_eq((SELECT match_status FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_unknown FROM t)), 'UNMATCHED',
    'a bank line with nothing behind it stays UNMATCHED for a person to review');
SELECT test.assert_eq((SELECT count(*) FROM payments) - (SELECT n FROM t_before), 1::bigint,
    'reconciliation created no receipts or payments by itself (only the one the test added)');

-- Guards
INSERT INTO r SELECT pg_temp.rec();
SELECT test.assert_raises(
    format('SELECT pg_temp.item(%s, %L, %s, NULL, 0.01)', (SELECT max(r1) FROM r), 'BANK', (SELECT bt_ab FROM t)),
    'AC004', 'a bank line cannot be matched beyond its amount');
SELECT test.assert_raises(
    format('SELECT pg_temp.item(%s, %L, NULL, %s, 1)', (SELECT max(r1) FROM r), 'BOOK', (SELECT pay_card FROM t)),
    'AC004', 'a receipt cannot be matched beyond the money that reached the bank');
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_draft FROM t), NULL, 700);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BOOK', NULL, (SELECT pay_draft FROM t), 700);
SELECT test.assert_raises(format('SELECT pg_temp.confirm(%s)', (SELECT max(r1) FROM r)), 'AC004',
    'a match cannot be confirmed while a receipt is still DRAFT');
UPDATE reconciliations SET status = 'REJECTED', rejected_by = test.usr('accountant'), rejected_at = now() WHERE id = (SELECT max(r1) FROM r);
SELECT test.assert_eq((SELECT match_status FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_draft FROM t)), 'UNMATCHED',
    'rejecting a proposal frees the bank line again');

-- Amount mismatch must be explained, never silently absorbed.
CREATE TEMP TABLE t3 ON COMMIT DROP AS SELECT test.payment(2500, '2026-03-14') AS pay_short;
INSERT INTO r SELECT pg_temp.rec();
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BANK', (SELECT bt_big FROM t), NULL, 2400);
SELECT pg_temp.item((SELECT max(r1) FROM r), 'BOOK', NULL, (SELECT pay_short FROM t3), 2500);
SELECT test.assert_raises(format('SELECT pg_temp.confirm(%s)', (SELECT max(r1) FROM r)), 'AC004',
    'bank 2,400 vs book 2,500 cannot be confirmed as an exact match');
SELECT test.assert_raises(format('SELECT pg_temp.confirm(%s, -100)', (SELECT max(r1) FROM r)), '23514',
    'a difference needs a written reason');
SELECT test.assert_succeeds(format('SELECT pg_temp.confirm(%s, -100, %L)', (SELECT max(r1) FROM r), 'ธนาคารหักค่าธรรมเนียม 100 รอบันทึก'),
    'a difference with a reason can be confirmed (no adjusting entry is created automatically)');
SELECT test.assert_raises(
    format('SELECT pg_temp.item(%s, %L, %s, NULL, 1)', (SELECT max(r1) FROM r), 'BANK', (SELECT bt_unknown FROM t)),
    'AC001', 'items cannot be added to a confirmed match');
SELECT test.assert_raises(
    format($$UPDATE reconciliations SET status = 'REVERSED', reversed_by = %s, reversed_at = now() WHERE id = %s$$,
           test.usr('accountant'), (SELECT max(r1) FROM r)),
    '23514', 'undoing a confirmed match requires a reason');
SELECT test.assert_raises(
    format($$UPDATE reconciliations SET status = 'PROPOSED' WHERE id = %s$$, (SELECT max(r1) FROM r)),
    'AC001', 'a confirmed match cannot go back to proposed');

SELECT test.assert_raises(
    $$INSERT INTO reconciliations (property_id, match_method, status, confirmed_by, confirmed_at)
      VALUES (test.prop(), 'MANUAL', 'CONFIRMED', test.usr('accountant'), now())$$,
    'AC004', 'a match cannot be created already CONFIRMED (would skip the balance checks)');

-- Statement data is the bank's record.
SELECT test.assert_raises(format('UPDATE bank_transactions SET amount = 1 WHERE id = %s', (SELECT bt_unknown FROM t)), 'AC001',
    'statement amounts cannot be edited');
SELECT test.assert_raises(format('DELETE FROM bank_transactions WHERE id = %s', (SELECT bt_unknown FROM t)), 'AC006',
    'statement lines cannot be deleted');
SELECT test.assert_raises($$SELECT test.bank_txn(10, '2026-03-15', 'k1')$$, '23505',
    'the same statement line imported twice is refused (dedupe key)');
SELECT test.assert_raises(
    format($$UPDATE bank_transactions SET review_status = 'IGNORED', reviewed_by = %s, reviewed_at = now() WHERE id = %s$$,
           test.usr('accountant'), (SELECT bt_unknown FROM t)),
    '23514', 'ignoring a bank line requires a note');
UPDATE bank_transactions SET review_status = 'NEEDS_REVIEW', reviewed_by = test.usr('accountant'), reviewed_at = now(), review_note = 'ไม่ทราบที่มา'
WHERE id = (SELECT bt_unknown FROM t);
SELECT test.assert_eq((SELECT match_status FROM v_bank_transaction_status WHERE bank_transaction_id = (SELECT bt_unknown FROM t)), 'NEEDS_REVIEW',
    'a flagged line shows as NEEDS_REVIEW');

-- Cash receipts are checked by counting cash, not against a bank.
CREATE TEMP TABLE t4 ON COMMIT DROP AS SELECT test.payment(300, '2026-03-15', p_bank => 'Cash drawer') AS cash_pay;
SELECT test.assert_eq((SELECT match_status FROM v_book_reconciliation_status WHERE item_type = 'PAYMENT' AND item_id = (SELECT cash_pay FROM t4)),
    'NOT_APPLICABLE', 'cash-drawer receipts do not need a bank match');

ROLLBACK;
