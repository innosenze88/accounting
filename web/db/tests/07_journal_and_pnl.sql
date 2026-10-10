-- Ledger: every entry balances and is append-only; P&L is built only from revenue/expense accounts,
-- so transfers, asset purchases, owner drawings, loan principal and commission settlement are not expenses.
BEGIN;

SELECT test.assert_raises(
    $$WITH e AS (INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
                 VALUES (test.prop(), 'JV-BAD-1', '2026-03-31', 'unbalanced', 'MANUAL_ADJUSTMENT') RETURNING id)
      INSERT INTO journal_lines (journal_entry_id, line_no, ledger_account_id, debit, credit)
      SELECT id, 1, test.acct('5291'), 100, 0 FROM e UNION ALL SELECT id, 2, test.acct('2101'), 0, 99 FROM e$$,
    'AC003', 'an entry whose debits and credits differ is rejected at commit');
SELECT test.assert_raises(
    $$INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
      VALUES (test.prop(), 'JV-BAD-2', '2026-03-31', 'no lines', 'MANUAL_ADJUSTMENT')$$,
    'AC003', 'an entry without lines is rejected');
SELECT test.assert_raises(
    $$WITH e AS (INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
                 VALUES (test.prop(), 'JV-BAD-3', '2026-03-31', 'line with both sides', 'MANUAL_ADJUSTMENT') RETURNING id)
      INSERT INTO journal_lines (journal_entry_id, line_no, ledger_account_id, debit, credit)
      SELECT id, 1, test.acct('5291'), 100, 100 FROM e$$,
    '23514', 'a line is either a debit or a credit');
SELECT test.assert_raises(
    $$SELECT test.post('2026-03-31', '5291', '1111', 100, 'JV-BAD-4')$$,
    'AC005', 'a bank line must name the bank account (to know each account''s balance)');
SELECT test.assert_raises(
    $$SELECT test.post('2026-03-31', '5291', '1111', 100, 'JV-BAD-5', NULL, 'Cash drawer')$$,
    'AC005', 'the bank account must belong to the ledger account used');

-- A month of postings (amounts in THB).
SELECT test.post('2026-03-01', '1111', '3101', 100000, 'JV-OPEN', 'KBank operating', NULL);                 -- opening capital
SELECT test.post('2026-03-10', '1131', '4101', 10000, 'JV-REV');                                          -- room revenue earned
SELECT test.post('2026-03-12', '1111', '1131', 8000, 'JV-RCPT', 'KBank operating', NULL);                 -- 8,000 received
SELECT test.post('2026-03-15', '5211', '1111', 2000, 'JV-ELEC', NULL, 'KBank operating');                 -- electricity
SELECT test.post('2026-03-16', '1202', '1111', 30000, 'JV-ASSET', NULL, 'KBank operating');               -- furniture (asset)
SELECT test.post('2026-03-17', '3102', '1111', 5000, 'JV-DRAW', NULL, 'KBank operating');                 -- owner drawing
SELECT test.post('2026-03-18', '1113', '1111', 20000, 'JV-FUND', 'Capital fund', 'KBank operating');      -- transfer to fund
SELECT test.post('2026-03-19', '2201', '1111', 3000, 'JV-LOAN', NULL, 'KBank operating');                 -- loan principal
SELECT test.post('2026-03-20', '5101', '2102', 1500, 'JV-COMM');                                          -- OTA commission (expense)
SELECT test.post('2026-03-25', '2102', '1111', 1500, 'JV-COMMPAY', NULL, 'KBank operating');              -- pay OTA invoice
SELECT test.post('2026-03-31', '5301', '1209', 500, 'JV-DEPR');                                           -- depreciation
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT test.ok('a month of balanced entries posts');

INSERT INTO accounting_periods (property_id, period_start) VALUES (test.prop(), '2026-03-01');

SELECT test.assert_eq((SELECT revenue FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 10000.00::numeric,
    'P&L revenue = earned revenue (10,000), not cash received (8,000)');
SELECT test.assert_eq((SELECT distribution_cost FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 1500.00::numeric,
    'OTA commission counted once (1,500) although the invoice was also paid');
SELECT test.assert_eq((SELECT operating_expense FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 2000.00::numeric,
    'operating expenses exclude asset purchase, owner drawing, transfer and loan principal');
SELECT test.assert_eq((SELECT depreciation FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 500.00::numeric,
    'depreciation is its own P&L line');
SELECT test.assert_eq((SELECT net_profit FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 6000.00::numeric,
    'net profit = 10,000 - 1,500 - 2,000 - 500');
SELECT test.assert_eq((SELECT completeness FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 'INCOMPLETE',
    'the month is labelled INCOMPLETE (no reports / statements imported), never as a confirmed profit');

SELECT test.assert_eq(book_balance(test.bank('KBank operating'), '2026-03-31'), 46500.00::numeric,
    'operating bank book balance = 100,000 + 8,000 - 2,000 - 30,000 - 5,000 - 20,000 - 3,000 - 1,500');
SELECT test.assert_eq(book_balance(test.bank('Capital fund'), '2026-03-31'), 20000.00::numeric,
    'transfer moved 20,000 into the capital fund account');
SELECT test.assert_eq(book_balance(test.bank('KBank operating'), '2026-03-11'), 100000.00::numeric,
    'book balance as of an earlier date');

SELECT test.assert_raises($$UPDATE journal_lines SET debit = 1 WHERE line_no = 1$$, 'AC001', 'journal lines cannot be edited');
SELECT test.assert_raises($$DELETE FROM journal_entries WHERE entry_no = 'JV-ELEC'$$, 'AC001', 'journal entries cannot be deleted');
SELECT test.assert_raises(
    $$INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
      VALUES (test.prop(), 'JV-REV', '2026-03-31', 'dup no', 'MANUAL_ADJUSTMENT')$$,
    '23505', 'entry numbers are unique per property');
SELECT test.assert_raises(
    $$INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
      VALUES (test.prop(), 'JV-X', '2026-03-31', 'reversal without original', 'REVERSAL')$$,
    '23514', 'a reversal must point at the entry it reverses');

-- Correction by reversal.
WITH e AS (INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type, reversal_of_id)
           SELECT test.prop(), 'JV-ELEC-REV', '2026-03-31', 'กลับรายการค่าไฟ', 'REVERSAL', id FROM journal_entries WHERE entry_no = 'JV-ELEC'
           RETURNING id)
INSERT INTO journal_lines (journal_entry_id, line_no, ledger_account_id, debit, credit, bank_account_id)
SELECT id, 1, test.acct('1111'), 2000, 0, test.bank('KBank operating') FROM e
UNION ALL SELECT id, 2, test.acct('5211'), 0, 2000, NULL FROM e;
SELECT test.assert_eq((SELECT operating_expense FROM v_pnl_monthly WHERE property_id = test.prop() AND period_start = '2026-03-01'), 0.00::numeric,
    'a reversal entry cancels the original in the P&L');
SELECT test.assert_raises(
    $$INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type, reversal_of_id)
      SELECT test.prop(), 'JV-ELEC-REV2', '2026-03-31', 'again', 'REVERSAL', id FROM journal_entries WHERE entry_no = 'JV-ELEC'$$,
    '23505', 'an entry can be reversed only once');

ROLLBACK;
