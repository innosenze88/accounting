-- Prints the data dictionary (Markdown) from the live catalog: table/column comments, types, keys, checks.
-- Used by tools/gen-data-dictionary.sh; the generated file must not be edited by hand.
\pset format unaligned
\pset tuples_only on
\pset footer off

WITH tables AS (
    SELECT c.oid, c.relname, obj_description(c.oid, 'pg_class') AS descr,
           row_number() OVER (ORDER BY min(t.ord), c.relname) AS n
    FROM pg_class c
    JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
    LEFT JOIN (VALUES
        ('properties', 1), ('users', 2), ('roles', 3), ('user_property_roles', 4), ('audit_logs', 5), ('attachments', 6),
        ('attachment_links', 7), ('ledger_accounts', 10), ('accounting_periods', 11), ('bank_accounts', 12),
        ('import_mapping_profiles', 20), ('imported_files', 21), ('import_rows', 22),
        ('rooms', 30), ('ota_channels', 31), ('reservations', 32), ('folios', 33), ('pms_daily_statistics', 34),
        ('loans', 40), ('revenue_entries', 41), ('payments', 42), ('payment_allocations', 43), ('expense_categories', 44),
        ('expenses', 45), ('expense_payments', 46), ('fund_transfers', 47), ('ota_commissions', 48), ('fixed_assets', 49),
        ('balance_confirmations', 50), ('journal_entries', 60), ('journal_lines', 61),
        ('bank_transactions', 70), ('reconciliations', 71), ('reconciliation_items', 72),
        ('tax_profiles', 80), ('tax_rules', 81), ('tax_estimates', 82),
        ('capital_goals', 90), ('capital_scenarios', 91), ('capital_fund_transactions', 92)) t(name, ord) ON t.name = c.relname
    WHERE c.relkind = 'r'
    GROUP BY c.oid, c.relname
),
cols AS (
    SELECT a.attrelid, a.attnum, a.attname,
           CASE WHEN ty.typtype = 'd' THEN ty.typname || ' (' || format_type(ty.typbasetype, ty.typtypmod) || ')'
                ELSE format_type(a.atttypid, a.atttypmod) END AS typ,
           CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE '' END AS nn,
           CASE WHEN a.attidentity <> '' THEN 'identity'
                WHEN a.attgenerated = 's' THEN 'generated'
                ELSE COALESCE(pg_get_expr(d.adbin, d.adrelid), '') END AS dflt,
           concat_ws(' ',
               CASE WHEN EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = a.attrelid AND k.contype = 'p' AND a.attnum = ANY (k.conkey)) THEN 'PK' END,
               (SELECT string_agg('FK→' || k.confrelid::regclass, ' ') FROM pg_constraint k
                WHERE k.conrelid = a.attrelid AND k.contype = 'f' AND a.attnum = ANY (k.conkey))) AS keys,
           COALESCE(col_description(a.attrelid, a.attnum), '') AS descr
    FROM pg_attribute a
    JOIN pg_type ty ON ty.oid = a.atttypid
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attnum > 0 AND NOT a.attisdropped
)
SELECT string_agg(section, E'\n' ORDER BY n)
FROM (
    SELECT t.n,
           format(E'### %s. `%s`\n\n%s\n\n| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |\n|---|---|---|---|---|---|\n%s\n%s%s',
                  t.n, t.relname, t.descr,
                  (SELECT string_agg(format('| `%s` | %s | %s | %s | %s | %s |', c.attname, c.typ, c.nn,
                                            replace(c.dflt, '|', '\|'), c.keys, replace(c.descr, '|', '\|')), E'\n' ORDER BY c.attnum)
                   FROM cols c WHERE c.attrelid = t.oid),
                  COALESCE((SELECT E'\n**Unique / ดัชนีกันซ้ำ:** ' || string_agg('`' || i.relname || '` ' ||
                                    regexp_replace(pg_get_indexdef(i.oid), '^.* USING btree ', ''), '; ' ORDER BY i.relname) || E'\n'
                            FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
                            WHERE x.indrelid = t.oid AND x.indisunique AND NOT x.indisprimary), ''),
                  COALESCE((SELECT E'\n**Triggers:** ' || string_agg('`' || g.tgname || '`', ', ' ORDER BY g.tgname) || E'\n'
                            FROM pg_trigger g WHERE g.tgrelid = t.oid AND NOT g.tgisinternal), '')
           ) AS section
    FROM tables t
) s;
