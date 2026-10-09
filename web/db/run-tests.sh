#!/usr/bin/env bash
# Database tests: builds a fresh database from schema/ + reference/, then runs every tests/NN_*.sql.
# Each test prints "ok - <name>" (NOTICE); any failed assertion raises and stops that file.
#   PGHOST / PGPORT / PGUSER / PGPASSWORD as usual for psql.
#   TEST_DB (default resort_acct_test) is DROPPED and re-created — never point it at a real database.
set -uo pipefail
cd "$(dirname "$0")"

TEST_DB="${TEST_DB:-resort_acct_test}"
case "$TEST_DB" in
    *test*) ;;
    *) echo "TEST_DB must contain 'test' (got '$TEST_DB')" >&2; exit 2 ;;
esac

dropdb --if-exists "$TEST_DB" && createdb -E UTF8 -T template0 "$TEST_DB" || exit 2
if ! PGDATABASE="$TEST_DB" ./apply.sh >/dev/null; then
    echo "FAILED: schema did not apply" >&2
    exit 1
fi
psql -X -q -v ON_ERROR_STOP=1 -o /dev/null -d "$TEST_DB" -f tests/_helpers.sql || exit 1

passed=0
failed_files=()
for f in tests/[0-9]*.sql; do
    out=$(psql -X -q -v ON_ERROR_STOP=1 -o /dev/null -d "$TEST_DB" -f "$f" 2>&1)
    status=$?
    n=$(grep -c "NOTICE:  ok - " <<<"$out")
    passed=$((passed + n))
    if [ $status -ne 0 ]; then
        failed_files+=("$f")
        echo "FAIL $f"
        grep -v "NOTICE:  ok - " <<<"$out" | sed 's/^/    /'
    else
        echo "PASS $f ($n checks)"
    fi
done

echo "----"
echo "checks passed: $passed"
if [ ${#failed_files[@]} -gt 0 ]; then
    echo "files failed: ${failed_files[*]}"
    exit 1
fi
echo "all test files passed"
