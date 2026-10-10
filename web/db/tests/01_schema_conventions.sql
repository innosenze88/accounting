-- Schema-wide rules: no floating point money, required tables exist, everything documented, audit on financial tables.
BEGIN;

SELECT test.assert_eq(
    (SELECT string_agg(table_name || '.' || column_name, ', ')
     FROM information_schema.columns
     WHERE table_schema = 'public' AND data_type IN ('real', 'double precision')),
    NULL, 'no REAL / DOUBLE PRECISION column anywhere');

SELECT test.assert_eq(
    (SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ', ')
     FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name IN (SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE')
       AND column_name ~ '(amount|balance|_total$|charges$|^receipts$|^expenses$|^adr$|^revpar$|_cost$|_value$|^debit$|^credit$|^monthly_installment$)'
       AND NOT (data_type = 'numeric' AND numeric_precision = 14 AND numeric_scale = 2)),
    NULL, 'every money column is NUMERIC(14,2)');

SELECT test.assert_eq(
    (SELECT string_agg(t, ', ') FROM unnest(ARRAY[
        'users', 'properties', 'rooms', 'reservations', 'folios', 'revenue_entries', 'payments', 'expenses',
        'bank_accounts', 'bank_transactions', 'reconciliations', 'ota_commissions', 'tax_estimates',
        'capital_fund_transactions', 'imported_files', 'import_rows', 'audit_logs']) t
     WHERE to_regclass('public.' || t) IS NULL),
    NULL, 'all 17 tables required by the specification exist');

SELECT test.assert_eq(
    (SELECT string_agg(c.relname, ', ') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind = 'r'
       AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p')),
    NULL, 'every table has a primary key');

SELECT test.assert_eq(
    (SELECT string_agg(c.relname || '.' || COALESCE(a.attname, '<table>'), ', ')
     FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
     LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
     WHERE c.relkind = 'r'
       AND (obj_description(c.oid, 'pg_class') IS NULL
            OR (a.attname IS NOT NULL AND col_description(c.oid, a.attnum) IS NULL))),
    NULL, 'every table and column has a data-dictionary comment');

SELECT test.assert_eq(
    (SELECT string_agg(t, ', ') FROM unnest(ARRAY[
        'properties', 'users', 'user_property_roles', 'ledger_accounts', 'accounting_periods', 'bank_accounts',
        'imported_files', 'rooms', 'ota_channels', 'reservations', 'folios', 'revenue_entries', 'payments',
        'payment_allocations', 'expense_categories', 'expenses', 'expense_payments', 'fund_transfers',
        'ota_commissions', 'fixed_assets', 'loans', 'balance_confirmations', 'journal_entries', 'bank_transactions',
        'reconciliations', 'reconciliation_items', 'tax_profiles', 'tax_rules', 'tax_estimates', 'capital_goals',
        'capital_scenarios', 'capital_fund_transactions', 'attachment_links', 'import_mapping_profiles']) t
     WHERE NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid = to_regclass('public.' || t)
                       AND g.tgname = 'trg_' || t || '_audit')),
    NULL, 'audit trigger attached to every business table');

SELECT test.assert_eq(
    (SELECT string_agg(t, ', ') FROM unnest(ARRAY[
        'revenue_entries', 'payments', 'expenses', 'expense_payments', 'fund_transfers', 'ota_commissions',
        'balance_confirmations', 'capital_fund_transactions']) t
     WHERE NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid = to_regclass('public.' || t) AND g.tgname = 'trg_' || t || '_guard')
        OR NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid = to_regclass('public.' || t) AND g.tgname = 'trg_' || t || '_period')),
    NULL, 'document guard and closed-period guard on every accounting document table');

SELECT test.assert_eq(
    (SELECT string_agg(conrelid::regclass || '.' || conname, ', ') FROM pg_constraint
     WHERE contype = 'c' AND pg_get_constraintdef(oid) ~* 'length\(\s*btrim'),
    NULL, 'no CHECK uses length(trim(x)) > 0 (NULL would pass); has_text() is used instead');

SELECT test.assert_eq((SELECT count(*)::int FROM roles), 5, 'reference roles loaded');
SELECT test.assert_true((SELECT count(*) FROM ledger_accounts) >= 40, 'draft chart of accounts loaded');
SELECT test.assert_eq(
    (SELECT count(*)::int FROM expense_categories
     WHERE code IN ('SALARY', 'ELECTRICITY', 'WATER', 'INTERNET_PHONE', 'REPAIR_MAINTENANCE', 'ROOM_SUPPLIES',
                    'OTHER_EXPENSE', 'OTA_COMMISSION_INVOICE', 'VAT_REMITTANCE')),
    9, 'expense categories from the requirements are present');

ROLLBACK;
