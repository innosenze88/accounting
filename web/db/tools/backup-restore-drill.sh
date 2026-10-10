#!/usr/bin/env bash
# Backup / restore drill on a throw-away database:
#   build sample data (closed month, ledger, reconciliation, audit log) -> pg_dump -> pg_restore into a new DB
#   -> compare row counts of every table -> check the restored DB still enforces its rules.
# A full custom-format dump restores with a plain pg_restore: pg_dump creates the triggers in its post-data
# section, after the rows are loaded, so the closed-period lock, journal-balance check and audit triggers do not
# fire during the reload and the audit history is restored exactly once. A DATA-ONLY restore into an existing
# schema is different: it needs --disable-triggers (superuser), otherwise those triggers would fire on reload.
#   PGHOST / PGPORT / PGUSER / PGPASSWORD as usual. Databases used: resort_acct_drill_test, resort_acct_restore_test.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=resort_acct_drill_test
DST=resort_acct_restore_test
DUMP="${DRILL_DUMP_DIR:-$(mktemp -d)}/drill.dump"
q() { psql -X -q -At -v ON_ERROR_STOP=1 "$@"; }

for db in "$SRC" "$DST"; do dropdb --if-exists "$db"; done
createdb -E UTF8 -T template0 "$SRC"
PGDATABASE="$SRC" ./apply.sh >/dev/null
q -o /dev/null -d "$SRC" -f tests/_helpers.sql
q -o /dev/null -d "$SRC" -f tools/drill_data.sql

pg_dump -Fc -d "$SRC" -f "$DUMP"
echo "dump: $(du -h "$DUMP" | cut -f1)"

counts() {
    q -d "$1" -c "SELECT string_agg(c.relname || '=' || (xpath('/row/n/text()',
                    query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text, ' ' ORDER BY c.relname)
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE c.relkind = 'r' AND n.nspname = 'public'"
}

createdb -E UTF8 -T template0 "$DST"
pg_restore --single-transaction --exit-on-error -d "$DST" "$DUMP"

before=$(counts "$SRC")
after=$(counts "$DST")
if [ "$before" != "$after" ]; then
    echo "FAIL: row counts differ"; diff <(tr ' ' '\n' <<<"$before") <(tr ' ' '\n' <<<"$after") || true; exit 1
fi
echo "ok - row counts identical in all $(wc -w <<<"$after") tables"

[ "$(q -d "$DST" -c "SELECT completeness FROM v_period_completeness WHERE period_start = '2026-02-01'")" = "FINAL" ] \
    && echo "ok - closed February is still FINAL after restore" || { echo "FAIL: February not FINAL"; exit 1; }

[ "$(q -d "$DST" -c "SELECT count(*) FROM (SELECT journal_entry_id FROM journal_lines GROUP BY 1 HAVING sum(debit) <> sum(credit)) x")" = "0" ] \
    && echo "ok - every restored journal entry balances" || { echo "FAIL: unbalanced journal"; exit 1; }

[ "$(q -d "$DST" -c "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'public' AND NOT t.tgisinternal AND t.tgenabled = 'D'")" = "0" ] \
    && echo "ok - all triggers are enabled again after restore" || { echo "FAIL: disabled triggers remain"; exit 1; }

if q -d "$DST" -c "SELECT test.revenue(test.folio('F-1001'), '2026-02-15', 1)" >/dev/null 2>&1; then
    echo "FAIL: restored DB accepted a posting into a closed month"; exit 1
fi
echo "ok - restored DB still refuses postings into the closed month"

if q -d "$DST" -c "DELETE FROM audit_logs" >/dev/null 2>&1; then
    echo "FAIL: restored audit log could be deleted"; exit 1
fi
echo "ok - restored audit log is still append-only"

echo "drill passed"
