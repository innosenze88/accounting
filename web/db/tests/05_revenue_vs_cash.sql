-- Revenue (earned) is kept apart from money received; they are linked only through explicit allocations.
BEGIN;

CREATE TEMP TABLE t ON COMMIT DROP AS SELECT
    test.revenue(test.folio('F-1001'), '2026-03-10', 1500) AS a_n1,
    test.revenue(test.folio('F-1001'), '2026-03-11', 1500) AS a_n2,
    test.revenue(test.folio('F-1002'), '2026-03-10', 1200) AS b_n1;

SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1001')), 3000.00::numeric,
    'revenue without any payment is a receivable (3,000)');
SELECT test.assert_eq((SELECT balance_status FROM v_folio_balances WHERE folio_id = test.folio('F-1001')), 'RECEIVABLE',
    'folio with unpaid revenue is RECEIVABLE');

-- Deposit 1,000 then balance 2,000 (many payments -> one folio).
CREATE TEMP TABLE p ON COMMIT DROP AS SELECT test.payment(1000, '2026-03-01') AS dep, test.payment(2000, '2026-03-12') AS bal;
SELECT test.allocate((SELECT dep FROM p), test.folio('F-1001'), 1000);
SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1001')), 2000.00::numeric,
    'after the deposit 2,000 is still outstanding');
SELECT test.allocate((SELECT bal FROM p), test.folio('F-1001'), 2000);
SELECT test.assert_eq((SELECT balance_status || ':' || balance FROM v_folio_balances WHERE folio_id = test.folio('F-1001')), 'SETTLED:0.00',
    'two payments settle one folio');

-- One transfer pays two folios (one payment -> many folios).
CREATE TEMP TABLE p2 ON COMMIT DROP AS SELECT test.payment(2000, '2026-03-11') AS id;
SELECT test.allocate((SELECT id FROM p2), test.folio('F-1002'), 1200);
SELECT test.assert_eq((SELECT balance_status FROM v_folio_balances WHERE folio_id = test.folio('F-1002')), 'SETTLED',
    'part of one payment settles folio F-1002');
SELECT test.assert_eq((SELECT unallocated FROM v_payment_allocation_status WHERE payment_id = (SELECT id FROM p2)), 800.00::numeric,
    'the rest of the payment (800) stays unallocated, i.e. a customer deposit');
SELECT test.assert_eq((SELECT allocation_status FROM v_payment_allocation_status WHERE payment_id = (SELECT id FROM p2)), 'PARTIAL',
    'payment allocation status is PARTIAL');
SELECT test.allocate((SELECT id FROM p2), test.folio('F-1003'), 800);
SELECT test.assert_eq((SELECT balance_status || ':' || balance FROM v_folio_balances WHERE folio_id = test.folio('F-1003')), 'CREDIT:-800.00',
    'money for a stay that has not happened yet is a CREDIT (deposit), not revenue');

SELECT test.assert_raises(
    format('SELECT test.allocate(%s, test.folio(%L), 0.01)', (SELECT id FROM p2), 'F-1001'),
    'AC004', 'allocations can never exceed the payment');

-- Draft payments do not count.
CREATE TEMP TABLE p3 ON COMMIT DROP AS SELECT test.payment(500, '2026-03-12', 'DRAFT') AS id;
SELECT test.allocate((SELECT id FROM p3), test.folio('F-1002'), 500);
SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1002')), 0.00::numeric,
    'an allocation from a DRAFT payment is not counted');

-- Refund: overpaid folio gets money back.
CREATE TEMP TABLE p4 ON COMMIT DROP AS SELECT test.payment(300, '2026-03-12') AS extra, test.payment(300, '2026-03-13', p_type => 'GUEST_REFUND') AS refund;
SELECT test.allocate((SELECT extra FROM p4), test.folio('F-1002'), 300);
SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1002')), -300.00::numeric,
    'overpayment shows as credit -300');
SELECT test.allocate((SELECT refund FROM p4), test.folio('F-1002'), 300);
SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1002')), 0.00::numeric,
    'refunding the overpayment settles the folio again');

-- Non-guest money cannot be allocated to a folio.
CREATE TEMP TABLE p5 ON COMMIT DROP AS SELECT test.payment(50000, '2026-03-12', p_type => 'OWNER_CONTRIBUTION') AS id;
SELECT test.assert_raises(
    format('SELECT test.allocate(%s, test.folio(%L), 100)', (SELECT id FROM p5), 'F-1001'),
    'AC005', 'owner contributions cannot be allocated to guest folios');
SELECT test.assert_eq((SELECT allocation_status FROM v_payment_allocation_status WHERE payment_id = (SELECT id FROM p5)), 'NOT_APPLICABLE',
    'owner contribution is outside guest receivables');

-- OTA collects: gross revenue, payout net of commission, commission deducted from the folio.
SELECT test.revenue(test.folio('F-1003'), '2026-03-20', 1000);
SELECT test.assert_eq((SELECT balance FROM v_folio_balances WHERE folio_id = test.folio('F-1003')), 200.00::numeric,
    'OTA folio: 1,000 revenue less 800 already allocated');
CREATE TEMP TABLE p6 ON COMMIT DROP AS SELECT test.payment(50, '2026-03-25', p_type => 'OTA_PAYOUT', p_channel => test.channel('EXPEDIA')) AS payout;
SELECT test.allocate((SELECT payout FROM p6), test.folio('F-1003'), 50);
INSERT INTO ota_commissions (property_id, channel_id, folio_id, recognition_date, commission_amount, basis, settlement_method,
                             payout_payment_id, status, source_system, verified_by, verified_at)
VALUES (test.prop(), test.channel('EXPEDIA'), test.folio('F-1003'), '2026-03-20', 150, 'OTA_STATEMENT', 'DEDUCTED_FROM_PAYOUT',
        (SELECT payout FROM p6), 'VERIFIED', 'OTA', test.usr('accountant'), now());
SELECT test.assert_eq((SELECT balance_status || ':' || balance FROM v_folio_balances WHERE folio_id = test.folio('F-1003')), 'SETTLED:0.00',
    'payout + deducted commission settle the OTA folio');
SELECT test.assert_eq((SELECT commission_deducted FROM v_folio_balances WHERE folio_id = test.folio('F-1003')), 150.00::numeric,
    'deducted commission is shown separately from cash received');

SELECT test.assert_raises(
    $$INSERT INTO ota_commissions (property_id, channel_id, recognition_date, commission_amount, basis, settlement_method, payout_payment_id, source_system)
      VALUES (test.prop(), test.channel('EXPEDIA'), '2026-03-20', 10, 'OTA_STATEMENT', 'DEDUCTED_FROM_PAYOUT',
              (SELECT min(id) FROM payments WHERE receipt_type = 'GUEST_PAYMENT'), 'OTA')$$,
    'AC005', 'commission can only be deducted from an OTA payout');
SELECT test.assert_raises(
    $$INSERT INTO ota_commissions (property_id, channel_id, recognition_date, commission_amount, basis, settlement_method, source_system)
      VALUES (test.prop(), test.channel('EXPEDIA'), '2026-03-20', 10, 'ESTIMATED_FROM_RATE', 'UNKNOWN', 'SYSTEM')$$,
    '23514', 'an estimated commission must record the rate and base it used');

-- PMS cross-check.
UPDATE folios SET pms_total = 3000, pms_paid = 2500, pms_balance = 500, pms_snapshot_date = '2026-03-12' WHERE id = test.folio('F-1001');
SELECT test.assert_eq((SELECT difference_vs_pms FROM v_folio_balances WHERE folio_id = test.folio('F-1001')), -500.00::numeric,
    'difference between our folio balance and the PMS balance is reported for review');

ROLLBACK;
