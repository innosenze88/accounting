#!/usr/bin/env bash
# Creates the schema and reference data in an EMPTY database.
#   PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE as usual for psql.
# Reference data (roles, draft chart of accounts, expense categories) is production data, not test data.
set -euo pipefail
cd "$(dirname "$0")"

run() { psql -X -q -v ON_ERROR_STOP=1 --single-transaction -o /dev/null "$@"; }

files=(schema/*.sql reference/*.sql)
args=()
for f in "${files[@]}"; do args+=(-f "$f"); done
run "${args[@]}"
echo "applied ${#files[@]} files to database ${PGDATABASE:-$(whoami)}"
