-- Tax settings are only "confirmed" with an accountant's name; estimates are labelled and locked once filed.
-- Capital fund progress counts verified movements only.
BEGIN;

SELECT test.assert_raises(
    $$INSERT INTO tax_profiles (property_id, effective_from, confirmed_by_accountant, accountant_name, confirmed_on)
      VALUES (test.prop(), '2026-01-01', true, 'นักบัญชีทดสอบ', '2026-01-05')$$,
    '23514', 'a tax profile with unknown entity type / VAT status cannot be marked confirmed');
SELECT test.assert_raises(
    $$INSERT INTO tax_profiles (property_id, effective_from, entity_type, vat_status, confirmed_by_accountant)
      VALUES (test.prop(), '2026-01-01', 'INDIVIDUAL', 'NOT_REGISTERED', true)$$,
    '23514', 'confirmation requires the accountant''s name and date');
SELECT test.assert_succeeds(
    $$INSERT INTO tax_profiles (property_id, effective_from, effective_to) VALUES (test.prop(), '2026-01-01', '2026-06-30')$$,
    'an unconfirmed profile (all UNKNOWN) is allowed as a starting point');
SELECT test.assert_raises(
    $$INSERT INTO tax_profiles (property_id, effective_from) VALUES (test.prop(), '2026-06-01')$$,
    'AC005', 'tax profiles cannot overlap in time');
SELECT test.assert_succeeds(
    $$INSERT INTO tax_profiles (property_id, effective_from) VALUES (test.prop(), '2026-07-01')$$,
    'the next profile starts after the previous one ends');
SELECT test.assert_raises(
    $$INSERT INTO tax_profiles (property_id, effective_from, vat_status) VALUES (test.prop('TEST_OTHER'), '2026-01-01', 'REGISTERED')$$,
    '23514', 'VAT REGISTERED needs the registration date');

SELECT test.assert_raises(
    $$INSERT INTO tax_rules (property_id, tax_type, name_th, calc_method, effective_from, source_reference)
      VALUES (test.prop(), 'VAT', 'ภาษีมูลค่าเพิ่ม', 'PERCENT_OF_REVENUE', '2026-01-01', 'นักบัญชี')$$,
    '23514', 'a percentage rule needs its rate (no rate is assumed by the system)');
SELECT test.assert_raises(
    $$INSERT INTO tax_rules (property_id, tax_type, name_th, calc_method, rate, effective_from)
      VALUES (test.prop(), 'OTHER', 'ทดสอบ', 'PERCENT_OF_REVENUE', 1, '2026-01-01')$$,
    '23502', 'every tax rule must say where it came from');

INSERT INTO tax_estimates (property_id, tax_type, period_start, period_end, basis_amount, basis_description, estimated_amount, data_completeness)
VALUES (test.prop(), 'OTHER', '2026-01-01', '2026-06-30', 100000, '[TEST] รายได้ VERIFIED ม.ค.–มิ.ย.', 1000, 'PRELIMINARY');
CREATE TEMP TABLE te ON COMMIT DROP AS SELECT max(id) AS id FROM tax_estimates WHERE property_id = test.prop();

SELECT test.assert_raises(format($$UPDATE tax_estimates SET estimate_status = 'FILED' WHERE id = %s$$, (SELECT id FROM te)),
    '23514', 'FILED needs the filed amount and date');
SELECT test.assert_raises(format($$UPDATE tax_estimates SET estimate_status = 'ACCOUNTANT_REVIEWED' WHERE id = %s$$, (SELECT id FROM te)),
    '23514', 'ACCOUNTANT_REVIEWED needs the accountant''s name and date');
UPDATE tax_estimates SET estimate_status = 'FILED', filed_amount = 950, filed_on = '2026-07-20' WHERE id = (SELECT id FROM te);
SELECT test.assert_raises(format($$UPDATE tax_estimates SET estimated_amount = 1 WHERE id = %s$$, (SELECT id FROM te)),
    'AC001', 'a filed estimate cannot be rewritten');
SELECT test.assert_raises(format($$DELETE FROM tax_estimates WHERE id = %s$$, (SELECT id FROM te)),
    'AC006', 'a filed estimate cannot be deleted');
SELECT test.assert_succeeds(format($$UPDATE tax_estimates SET estimate_status = 'PAID', paid_amount = 950 WHERE id = %s$$, (SELECT id FROM te)),
    'a filed estimate can be marked PAID');
SELECT test.assert_raises(format($$UPDATE tax_estimates SET notes = 'x' WHERE id = %s$$, (SELECT id FROM te)),
    'AC001', 'a paid tax record is final');

-- Capital planning
SELECT test.assert_raises(
    $$INSERT INTO capital_goals (property_id, name, target_amount, start_date) VALUES (test.prop(), 'x', 0, '2026-01-01')$$,
    '23514', 'a capital goal needs a positive target');
INSERT INTO capital_goals (property_id, name, target_amount, start_date, operating_reserve_months)
VALUES (test.prop(), '[TEST] ทุนเปิดร้านสะดวกซื้อ', 2000000, '2026-01-01', 3);
CREATE TEMP TABLE g ON COMMIT DROP AS SELECT max(id) AS id FROM capital_goals WHERE property_id = test.prop();

INSERT INTO capital_scenarios (goal_id, scenario_type, name, revenue_change_pct, expense_change_pct, allocation_pct)
SELECT (SELECT id FROM g), v.t, v.t, v.r, v.e, v.a
FROM (VALUES ('CONSERVATIVE', -15.0, 10.0, 50.0), ('BASE', 0.0, 0.0, 70.0), ('OPTIMISTIC', 10.0, 0.0, 80.0)) v(t, r, e, a);
SELECT test.assert_eq((SELECT count(*)::int FROM capital_scenarios WHERE goal_id = (SELECT id FROM g)), 3,
    'Conservative, Base and Optimistic scenarios exist');
SELECT test.assert_raises(
    format($$INSERT INTO capital_scenarios (goal_id, scenario_type, name, allocation_pct) VALUES (%s, 'BASE', 'dup', 50)$$, (SELECT id FROM g)),
    '23505', 'only one BASE scenario per goal');
SELECT test.assert_raises(
    format($$INSERT INTO capital_scenarios (goal_id, scenario_type, name, allocation_pct, fallback_annual_revenue)
             VALUES (%s, 'CUSTOM', 'revenue only', 50, 1900000)$$, (SELECT id FROM g)),
    '23514', 'the owner''s revenue estimate cannot be used without an expense estimate (no surplus from revenue alone)');

INSERT INTO capital_fund_transactions (property_id, goal_id, txn_date, txn_type, amount, status, verified_by, verified_at)
VALUES (test.prop(), (SELECT id FROM g), '2026-01-01', 'OPENING_BALANCE', 300000, 'VERIFIED', test.usr('owner'), now()),
       (test.prop(), (SELECT id FROM g), '2026-02-28', 'ALLOCATION', 50000, 'VERIFIED', test.usr('owner'), now()),
       (test.prop(), (SELECT id FROM g), '2026-03-31', 'ALLOCATION', 20000, 'DRAFT', NULL, NULL),
       (test.prop(), (SELECT id FROM g), '2026-03-15', 'WITHDRAWAL', -10000, 'VERIFIED', test.usr('owner'), now());
SELECT test.assert_eq((SELECT accumulated_verified FROM v_capital_goal_progress WHERE goal_id = (SELECT id FROM g)), 340000.00::numeric,
    'accumulated fund counts verified movements only (300,000 + 50,000 - 10,000)');
SELECT test.assert_eq((SELECT remaining_to_target FROM v_capital_goal_progress WHERE goal_id = (SELECT id FROM g)), 1660000.00::numeric,
    'remaining to the 2,000,000 target');
SELECT test.assert_eq((SELECT pending_draft FROM v_capital_goal_progress WHERE goal_id = (SELECT id FROM g)), 20000.00::numeric,
    'a DRAFT allocation is shown as pending, not counted');
SELECT test.assert_eq((SELECT progress_pct FROM v_capital_goal_progress WHERE goal_id = (SELECT id FROM g)), 17.00::numeric,
    'progress 17.00 %');

SELECT test.assert_raises(
    format($$INSERT INTO capital_fund_transactions (property_id, goal_id, txn_date, txn_type, amount) VALUES (test.prop(), %s, '2026-03-01', 'WITHDRAWAL', 100)$$, (SELECT id FROM g)),
    '23514', 'a withdrawal must be negative');
SELECT test.assert_raises(
    format($$INSERT INTO capital_fund_transactions (property_id, goal_id, txn_date, txn_type, amount) VALUES (test.prop(), %s, '2026-03-01', 'ADJUSTMENT', 100)$$, (SELECT id FROM g)),
    '23514', 'an adjustment needs a written reason');
SELECT test.assert_raises(
    $$UPDATE capital_fund_transactions SET amount = 999999 WHERE txn_type = 'OPENING_BALANCE' AND property_id = test.prop()$$,
    'AC001', 'a verified fund movement cannot be edited');
SELECT test.assert_raises(
    format($$INSERT INTO capital_fund_transactions (property_id, goal_id, txn_date, txn_type, amount) VALUES (test.prop('TEST_OTHER'), %s, '2026-03-01', 'ALLOCATION', 100)$$, (SELECT id FROM g)),
    'AC008', 'a fund movement must belong to the goal''s property');

ROLLBACK;
