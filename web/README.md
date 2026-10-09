# Viphuanan Resort — Accounting & Capital Planning (Web)

ระบบบัญชีบนเว็บสำหรับ วิภูอนันต์ รีสอร์ท: นำเข้ารายงาน PMS, แยกรายได้กับเงินรับจริง, กระทบยอดธนาคาร,
งบกำไรขาดทุนที่บอกความครบถ้วนของข้อมูล, เตรียมข้อมูลภาษี และวางแผนสะสมทุน 2,000,000 บาทเพื่อเปิดร้านสะดวกซื้อ

ระบบนี้แยกจากแอป Android ในโฟลเดอร์ `app/` ของ repo เดียวกัน

## สถานะ

| Phase | เนื้อหา | สถานะ |
|---|---|---|
| 1 | Requirements, Database Schema | ✅ ส่งมอบแล้ว — **รอการยืนยัน** ([รายงาน Phase 1](docs/phase-1/00-phase-1-report.md)) |
| 2 | Import รายงาน PMS + Data Validation | ⏸ รอการยืนยันและไฟล์ตัวอย่าง |
| 3 | Income & Expense | — |
| 4 | Reconciliation | — |
| 5 | Profit & Loss | — |
| 6 | Tax Planning | — |
| 7 | Capital Planning | — |
| 8 | Security, Testing, Deployment | — |

## เอกสาร Phase 1

0. [รายงาน Phase 1](docs/phase-1/00-phase-1-report.md)
1. [Requirements และ Accounting Rules](docs/phase-1/01-requirements-and-accounting-rules.md)
2. [System Architecture](docs/phase-1/02-system-architecture.md)
3. [Database Schema (ERD)](docs/phase-1/03-database-schema.md)
4. [Data Dictionary](docs/phase-1/04-data-dictionary.md) (สร้างอัตโนมัติ)
5. [Workflow Diagrams](docs/phase-1/05-workflows.md)
6. [Import Mapping](docs/phase-1/06-import-mapping.md)
7. [ข้อมูลที่ยังขาด](docs/phase-1/07-missing-information.md)
8. [Test Plan และผลทดสอบ](docs/phase-1/08-test-plan.md)

## ฐานข้อมูล: ติดตั้งและทดสอบ

ต้องมี PostgreSQL 16 (server + client) และตั้งค่าการเชื่อมต่อด้วยตัวแปรมาตรฐานของ psql (`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`)

```bash
# สร้าง schema + ข้อมูลอ้างอิง (บทบาท, ผังบัญชีร่าง, หมวดรายจ่าย) ในฐานข้อมูลว่าง
createdb resort_acct
PGDATABASE=resort_acct web/db/apply.sh

# ทดสอบ: สร้างฐานข้อมูล resort_acct_test ใหม่ทุกครั้ง แล้วรันทุกไฟล์ใน web/db/tests
web/db/run-tests.sh

# สร้าง data dictionary ใหม่หลังแก้ schema (ใช้ฐานข้อมูลที่ run-tests.sh สร้างไว้)
PGDATABASE=resort_acct_test web/db/tools/gen-data-dictionary.sh

# ซ้อมสำรองและกู้คืน
web/db/tools/backup-restore-drill.sh
```

`apply.sh` ไม่โหลดข้อมูลทดสอบ — fixture ที่ติดป้าย `[TEST]` อยู่ใน `web/db/tests/_helpers.sql` และถูกโหลดเฉพาะในฐานข้อมูลทดสอบ

### สำรองและกู้คืน (หลักการ — ขั้นตอนเต็มใน Phase 8)

```bash
pg_dump -Fc -d resort_acct -f resort_acct_$(date +%Y%m%d).dump      # สำรอง (เข้ารหัสแล้วส่งออกนอกเครื่อง)
createdb resort_acct_restored && pg_restore --single-transaction --exit-on-error -d resort_acct_restored resort_acct_YYYYMMDD.dump
```

กู้คืนแบบเต็ม (schema + ข้อมูล) ใช้ `pg_restore` ธรรมดาได้ — trigger ถูกสร้างหลังโหลดข้อมูล
กู้คืนแบบ data-only เข้าฐานข้อมูลที่มี schema อยู่แล้ว ต้องใช้ `--disable-triggers` (superuser)
