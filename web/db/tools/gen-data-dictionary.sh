#!/usr/bin/env bash
# Regenerates docs/phase-1/04-data-dictionary.md from a database built by apply.sh.
#   PGDATABASE must point at a database that has the current schema (run-tests.sh leaves one: resort_acct_test).
#   --check : exit 1 if the committed file is out of date (used in CI).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=../docs/phase-1/04-data-dictionary.md
tmp=$(mktemp)
{
    cat <<'EOF'
# 4. Data Dictionary — พจนานุกรมข้อมูล

> ไฟล์นี้สร้างอัตโนมัติจากฐานข้อมูลจริงด้วย `web/db/tools/gen-data-dictionary.sh` (อ่าน COMMENT ของทุกตาราง/คอลัมน์)
> ห้ามแก้ด้วยมือ — แก้ที่ `web/db/schema/*.sql` แล้วสร้างใหม่ CI ตรวจว่าไฟล์นี้ตรงกับ schema เสมอ
>
> สัญลักษณ์: `money_thb (numeric(14,2))` = จำนวนเงินบาททศนิยม 2 ตำแหน่ง, `percent_rate` = ร้อยละ 0–100,
> PK = Primary Key, FK→ = Foreign Key ไปยังตาราง, identity = เลขรันอัตโนมัติ, generated = คำนวณอัตโนมัติ
> ความหมายของสถานะ (status) และกฎทางบัญชีอยู่ในไฟล์ 01 และ 03

EOF
    psql -X -q -v ON_ERROR_STOP=1 -f tools/data_dictionary.sql
    cat <<'EOF'

## Views (ตัวเลขที่คำนวณจากข้อมูลที่ยืนยันแล้ว)

EOF
    psql -X -q -At -v ON_ERROR_STOP=1 -c "
        SELECT string_agg(format('- \`%s\` — %s', c.relname, obj_description(c.oid, 'pg_class')), E'\n' ORDER BY c.relname)
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'v'"
} >"$tmp"

if [ "${1:-}" = "--check" ]; then
    if ! diff -q "$tmp" "$OUT" >/dev/null; then
        echo "data dictionary is out of date: run web/db/tools/gen-data-dictionary.sh" >&2
        diff "$OUT" "$tmp" | head -20 >&2
        rm -f "$tmp"; exit 1
    fi
    rm -f "$tmp"; echo "data dictionary is up to date"
else
    mv "$tmp" "$OUT"; echo "wrote $OUT"
fi
