-- Expenses: incurred date vs paid date, partial payments, withholding tax, and category rules that keep
-- balance-sheet payments (assets, owner drawings, loan principal, commission settlement) out of expenses.
BEGIN;

CREATE TEMP TABLE t ON COMMIT DROP AS SELECT
    test.expense('ELECTRICITY', 4280, '2026-03-05') AS elec,
    test.expense('REPAIR_MAINTENANCE', 10000, '2026-03-06', p_wht => 300) AS repair;

SELECT test.assert_eq((SELECT payment_status FROM v_expense_payment_status WHERE expense_id = (SELECT elec FROM t)), 'UNPAID',
    'a recorded bill without payment is UNPAID (accounts payable)');
SELECT test.expense_payment((SELECT elec FROM t), 2000, '2026-03-10');
SELECT test.assert_eq((SELECT payment_status || ':' || outstanding_amount FROM v_expense_payment_status WHERE expense_id = (SELECT elec FROM t)),
    'PARTIALLY_PAID:2280.00', 'partial payment leaves 2,280 outstanding');
SELECT test.expense_payment((SELECT elec FROM t), 2280, '2026-03-20');
SELECT test.assert_eq((SELECT payment_status FROM v_expense_payment_status WHERE expense_id = (SELECT elec FROM t)), 'PAID',
    'second payment marks it PAID');
SELECT test.assert_raises(
    format('SELECT test.expense_payment(%s, 0.01, %L)', (SELECT elec FROM t), '2026-03-21'),
    'AC004', 'paying more than the bill is refused');
SELECT test.assert_eq(
    (SELECT string_agg(paid_date::text, ',' ORDER BY paid_date) FROM expense_payments WHERE expense_id = (SELECT elec FROM t)),
    '2026-03-10,2026-03-20', 'each payment keeps its own paid date, separate from the bill date');

SELECT test.assert_eq((SELECT payable_amount FROM v_expense_payment_status WHERE expense_id = (SELECT repair FROM t)), 9700.00::numeric,
    'withholding tax reduces what is paid to the vendor (10,000 - 300)');
SELECT test.assert_raises(
    format('SELECT test.expense_payment(%s, 10000, %L)', (SELECT repair FROM t), '2026-03-10'),
    'AC004', 'cannot pay the vendor the withheld tax as well');

CREATE TEMP TABLE t2 ON COMMIT DROP AS SELECT test.expense_payment((SELECT repair FROM t), 9700, '2026-03-10', 'DRAFT') AS draft_pay;
SELECT test.assert_eq((SELECT payment_status || ':' || pending_payment_amount FROM v_expense_payment_status WHERE expense_id = (SELECT repair FROM t)),
    'UNPAID:9700.00', 'a DRAFT payment is shown as pending, not as paid');

SELECT test.assert_raises(
    $$INSERT INTO expenses (property_id, expense_date, category_id, description, amount_excl_vat, vat_amount, total_amount, source_system)
      VALUES (test.prop(), '2026-03-05', test.category('WATER'), 'ค่าน้ำ', 1000, 70, 1000, 'MANUAL')$$,
    '23514', 'total must equal amount before VAT + VAT');
SELECT test.assert_raises(
    $$INSERT INTO expenses (property_id, expense_date, category_id, description, amount_excl_vat, total_amount, source_system)
      VALUES (test.prop(), '2026-03-05', test.category('LOAN_PRINCIPAL'), 'ค่างวด', 5000, 5000, 'MANUAL')$$,
    'AC005', 'loan principal must name the loan');

SELECT test.assert_raises(
    $$INSERT INTO expense_categories (code, name_th, name_en, treatment, ledger_account_id)
      VALUES ('BAD_ASSET', 'ทดสอบ', 'test', 'OPERATING_EXPENSE', test.acct('1202'))$$,
    'AC005', 'an operating-expense category cannot point at an asset account');
SELECT test.assert_raises(
    $$INSERT INTO expense_categories (code, name_th, name_en, treatment, ledger_account_id)
      VALUES ('BAD_COMMISSION', 'ทดสอบ', 'test', 'OPERATING_EXPENSE', test.acct('5101'))$$,
    'AC005', 'OTA commission expense cannot be entered as an expense voucher (only via ota_commissions)');
SELECT test.assert_raises(
    $$INSERT INTO expense_categories (code, name_th, name_en, treatment, ledger_account_id)
      VALUES ('BAD_DRAWING', 'ทดสอบ', 'test', 'OWNER_DRAWING', test.acct('5291'))$$,
    'AC005', 'owner drawings must go to the drawings (equity) account, not an expense');
SELECT test.assert_raises(
    $$INSERT INTO expense_categories (code, name_th, name_en, treatment, ledger_account_id)
      VALUES ('BAD_DEPR', 'ทดสอบ', 'test', 'OPERATING_EXPENSE', test.acct('5301'))$$,
    'AC005', 'depreciation cannot be entered manually as an expense voucher');

SELECT test.assert_eq(
    (SELECT string_agg(c.code, ',' ORDER BY c.code) FROM expense_categories c JOIN ledger_accounts a ON a.id = c.ledger_account_id
     WHERE c.code IN ('ASSET_FURNITURE_EQUIPMENT', 'OWNER_DRAWING', 'LOAN_PRINCIPAL', 'OTA_COMMISSION_INVOICE', 'VAT_REMITTANCE')
       AND a.pnl_section IS NULL),
    'ASSET_FURNITURE_EQUIPMENT,LOAN_PRINCIPAL,OTA_COMMISSION_INVOICE,OWNER_DRAWING,VAT_REMITTANCE',
    'asset purchase, owner drawing, loan principal, commission settlement and VAT remittance are not P&L accounts');

-- Paying an OTA invoice settles the commission payable; it must not be an expense category.
CREATE TEMP TABLE t3 ON COMMIT DROP AS SELECT test.expense('OTHER_EXPENSE', 150, '2026-03-31') AS wrong_cat,
                                              test.expense('OTA_COMMISSION_INVOICE', 150, '2026-03-31') AS settle;
SELECT test.assert_raises(
    format($$INSERT INTO ota_commissions (property_id, channel_id, recognition_date, commission_amount, basis, settlement_method, settlement_expense_id, source_system)
             VALUES (test.prop(), test.channel('BOOKING_COM'), '2026-03-10', 150, 'OTA_INVOICE', 'INVOICED_TO_HOTEL', %s, 'OTA')$$, (SELECT wrong_cat FROM t3)),
    'AC005', 'an OTA invoice cannot be settled by an ordinary expense (would count commission twice)');
SELECT test.assert_succeeds(
    format($$INSERT INTO ota_commissions (property_id, channel_id, recognition_date, commission_amount, basis, settlement_method, settlement_expense_id, source_system)
             VALUES (test.prop(), test.channel('BOOKING_COM'), '2026-03-10', 150, 'OTA_INVOICE', 'INVOICED_TO_HOTEL', %s, 'OTA')$$, (SELECT settle FROM t3)),
    'an OTA invoice is settled by an OTA_COMMISSION_SETTLEMENT payment');

SELECT test.assert_raises(
    $$INSERT INTO fund_transfers (property_id, transfer_date, from_bank_account_id, to_bank_account_id, amount, purpose, source_system)
      VALUES (test.prop(), '2026-03-31', test.bank('KBank operating'), test.bank('KBank operating'), 100, 'INTERNAL_TRANSFER', 'MANUAL')$$,
    '23514', 'a transfer needs two different accounts');

ROLLBACK;
