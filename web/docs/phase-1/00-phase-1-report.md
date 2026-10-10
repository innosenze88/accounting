# Phase 1 Report — Requirements และ Database Schema

> วิภูอนันต์ รีสอร์ท — Accounting & Capital Planning System
> สถานะ: **ส่งมอบเพื่อขอการยืนยัน** — ยังไม่เริ่ม Phase 2 จนกว่าจะได้รับการยืนยัน (ดูหัวข้อ "สิ่งที่ต้องยืนยัน")
> อัปเดต 9 ต.ค. 2569: เจ้าของยืนยันว่า PMS คือ **eZee Absolute (Cloud)** และไม่ย้าย PMS — ปรับเอกสารและข้อมูลทดสอบตามนี้แล้ว
> อัปเดต 10 ต.ค. 2569: ยืนยัน Django + PostgreSQL และขอบเขต Import Mapping 3 ประเภท; ได้ไฟล์ PDF จริง 5 ไฟล์ — ตรวจแล้ว ผลอยู่ใน 06 หัวข้อ 6.0.1 และปรับ schema ให้รับห้อง-คืนที่มีทศนิยม

## 1. สิ่งที่พัฒนา

| # | สิ่งที่ส่งมอบ (ตาม requirement ข้อ 10) | ไฟล์ |
|---|---|---|
| — | วิเคราะห์ requirement + กฎทางบัญชี 25 ข้อ (R1–R25) + นิยามที่ห้ามปนกัน | [01-requirements-and-accounting-rules.md](01-requirements-and-accounting-rules.md) |
| 1 | System Architecture: stack ที่แนะนำ, องค์ประกอบ, ชั้นข้อมูล, RBAC, ความปลอดภัย, deploy/สำรองข้อมูล, ความสัมพันธ์กับแอป Android เดิม | [02-system-architecture.md](02-system-architecture.md) |
| 2 | Database Schema: **SQL ที่รันได้จริง** 40 ตาราง + 10 views + trigger บังคับกฎ, ERD 6 ส่วน, state machines | [03-database-schema.md](03-database-schema.md), [`web/db/schema/`](../../db/schema) |
| 3 | Data Dictionary: ทุกตาราง ทุกคอลัมน์ สร้างอัตโนมัติจาก schema | [04-data-dictionary.md](04-data-dictionary.md) |
| 4 | Workflow Diagrams 9 แผนภาพ (นำเข้า, วงจรเอกสาร, รายได้→เงินรับ→ธนาคาร, OTA, กระทบยอด, ปิดงวด, รายจ่าย, ภาษี, แผนเงินทุน) | [05-workflows.md](05-workflows.md) |
| 5 | Import Mapping: Night Audit, Monthly Statistics, Front Desk Activities (+ Receipt Detail, Expense Voucher, Bank Statement) | [06-import-mapping.md](06-import-mapping.md) |
| 6 | รายการข้อมูลที่ยังขาด 38 รายการ (ตอบแล้ว 2) จัดลำดับความสำคัญ | [07-missing-information.md](07-missing-information.md) |
| 7 | Test Plan + ผลทดสอบ Phase 1 | [08-test-plan.md](08-test-plan.md) |

นอกจากเอกสาร Phase 1 ได้สร้าง schema เป็นโค้ดและทดสอบจริง เพื่อให้การออกแบบตรวจสอบได้ ไม่ใช่แค่แผนภาพ:

- กฎที่ "ผิดไม่ได้" อยู่ในฐานข้อมูล ได้แก่ เงินเป็นทศนิยมแน่นอน, รายการที่ยืนยันแล้วแก้/ลบไม่ได้, นำเข้าซ้ำไม่ได้, ข้อมูลดิบไม่สูญหาย, ไฟล์ที่ยอดไม่ตรงนำเข้าไม่ได้, บัญชีต้องสมดุล, ซื้อสินทรัพย์/โอน/ถอนเจ้าของ/ชำระหนี้ไม่ถูกนับเป็นค่าใช้จ่าย, กระทบยอดไม่สร้างเงินเอง, ปิดงวดได้เมื่อข้อมูลครบเท่านั้น, Audit Log แก้ไม่ได้
- ผลรวม: ทดสอบ 202 รายการผ่านทั้งหมด และซ้อมสำรอง/กู้คืนผ่าน

## 2. ไฟล์และโครงสร้างที่สร้าง

```
.github/workflows/web-db-ci.yml        CI: ทดสอบ schema + data dictionary + ซ้อมกู้คืน (เฉพาะเมื่อแก้ web/**)
web/README.md                          ภาพรวม สถานะ Phase วิธีติดตั้ง/ทดสอบฐานข้อมูล
web/docs/phase-1/00..08-*.md           เอกสาร Phase 1
web/db/schema/001_foundation.sql       domain เงิน, ฟังก์ชันกลาง, properties, users, roles, audit_logs, attachments
web/db/schema/002_ledger_accounts.sql  ผังบัญชี, งวดบัญชี, บัญชีเงิน
web/db/schema/003_imports.sql          แม่แบบ mapping, ไฟล์นำเข้า, แถวข้อมูลดิบ
web/db/schema/004_pms.sql              ห้อง, ช่องทางขาย, การจอง, folio, สถิติรายวัน
web/db/schema/005_documents.sql        รายได้, เงินรับ, การจัดสรร, หมวด/รายจ่าย, การจ่าย, โอน, ค่าคอม OTA, ทรัพย์สิน, เงินกู้, ยอดที่ยืนยัน
web/db/schema/006_journal.sql          สมุดบัญชีคู่
web/db/schema/007_bank_reconciliation.sql  รายการธนาคาร, การจับคู่
web/db/schema/008_tax_capital.sql      ภาษี, เป้าหมายและกองทุน
web/db/schema/009_views.sql            ยอด folio, สถานะจับคู่, เงินที่ตรวจแล้ว, ความครบถ้วน, P&L, ความคืบหน้ากองทุน, ล็อกปิดงวด
web/db/reference/001..003_*.sql        บทบาท 5, ผังบัญชีร่าง 50, หมวดรายจ่าย 25 (ข้อมูลจริง ไม่ใช่ข้อมูลทดสอบ)
web/db/tests/_helpers.sql              ตัวช่วยทดสอบ + fixture ที่ติดป้าย [TEST]
web/db/tests/01..10_*.sql              ชุดทดสอบ 10 ไฟล์
web/db/apply.sh, run-tests.sh          สร้าง schema / รันทดสอบ
web/db/tools/                          สร้าง data dictionary, ซ้อมสำรอง/กู้คืน
```

ไม่ได้แก้ไฟล์ใดของแอป Android เดิม

## 3. วิธีทดสอบ

```bash
web/db/run-tests.sh                                          # สร้าง DB ใหม่ + 204 checks
PGDATABASE=resort_acct_test web/db/tools/gen-data-dictionary.sh --check
web/db/tools/backup-restore-drill.sh
```

รายละเอียดใน [08-test-plan.md](08-test-plan.md) หัวข้อ 8.6

## 4. Test Results

- **204/204 checks ผ่าน** บน PostgreSQL 16.15 (10 ไฟล์ — ตารางรายไฟล์ใน 08 หัวข้อ 8.2)
- ซ้อมสำรอง/กู้คืน: ผ่าน — จำนวนแถวตรงกันทั้ง 40 ตาราง, งวดที่ปิดยังเป็น FINAL, ทุก journal สมดุล, trigger ทำงานหลังกู้คืน
- Data dictionary ตรงกับ schema
- ระหว่างพัฒนา เทสต์จับข้อบกพร่องได้ 3 จุดและแก้แล้ว (CHECK ที่ปล่อย NULL ผ่าน, สร้างงวดเป็น CLOSED ทันที, สร้างชุดจับคู่เป็น CONFIRMED ทันที)
- **ยังไม่ได้ทดสอบ**: การนำเข้าไฟล์จริง (ยังไม่มีไฟล์ตัวอย่างและยังไม่มี parser — Phase 2), สูตรในแอป (F01–F19 — Phase 3/7), สิทธิ์ผู้ใช้ (Phase 8), concurrency, ประสิทธิภาพ, CI บน GitHub (รันครั้งแรกเมื่อเปิด PR)

## 5. Known Issues

| # | ประเด็น | ผลกระทบ | แผน |
|---|---|---|---|
| K1 | โครงสร้างรายงาน eZee ที่ยืนยันแล้วมาจาก **PDF**; หัวคอลัมน์ของไฟล์ Excel/CSV และโครงสร้าง **Receipt Detail** ยังไม่เคยเห็น | แม่แบบ mapping ทั้งหมดยังเป็น DRAFT | ต้องได้ไฟล์ตัวอย่าง (07: A2, A3) ก่อนเขียน parser |
| K2 | ~~ระบบ PMS ไม่ชัดเจน~~ — **ยืนยันแล้ว: eZee Absolute (Cloud) ไม่ย้าย PMS** | ถ้า eZee ส่งออกได้เฉพาะ PDF ตัวอ่าน PDF ต้องทำใน Phase 2 (มีต้นแบบและเทสต์ในแอป Android) | ขึ้นกับข้อ A3 |
| K3 | ผังบัญชีและหมวดรายจ่ายเป็นร่าง | การลงบัญชีอาจเปลี่ยน | นักบัญชียืนยัน (B4) ก่อน Phase 3 |
| K4 | ยังไม่มี posting engine (เอกสาร → journal) | P&L views อ่านจาก journal จึงยังว่างจนถึง Phase 3 และงวดทั้งหมดเป็น INCOMPLETE | Phase 3 |
| K5 | สิทธิ์ผู้ใช้ (RBAC) อยู่ระดับแอป ยังไม่สร้าง; การแยก DB role (แอปไม่มีสิทธิ์ปิด trigger/TRUNCATE) ยังไม่ตั้ง | ผู้ที่มีสิทธิ์ superuser ข้าม trigger ได้ (ป้องกันไม่ได้ 100% ในฐานข้อมูล — ลดความเสี่ยงด้วยการแยก role + Audit + สำรองข้อมูล) | Phase 8 |
| K6 | กฎความครบถ้วนกำหนดให้ "Night Audit ทุกวัน" เป็นแหล่งรายได้รายวัน | ถ้า eZee ส่งออก Night Audit รายวันไม่ได้ ต้องปรับกฎใน `v_period_completeness` | ปรับเมื่อได้ข้อ A3 |
| K7 | Views คำนวณสดทุกครั้ง ยังไม่ได้วัดประสิทธิภาพ | น่าจะพอสำหรับ 15–30 ห้อง | วัดใน Phase 8 |
| K8 | `attachment_links` ชี้ไปยังรายการแบบ (entity_type, entity_id) ไม่มี FK จริง | แอปต้องตรวจว่ารายการปลายทางมีอยู่ | ทดสอบใน Phase 3 |
| K9 | การล็อกกันจัดสรรเงินซ้อนพร้อมกันใช้ row lock ยังไม่ได้ทดสอบหลาย session | ความเสี่ยงต่ำ (ผู้ใช้น้อย) | เทสต์ concurrency ใน Phase 3 |
| K10 | รองรับ THB เท่านั้น | OTA ที่จ่ายสกุลอื่นต้องออกแบบเพิ่ม | ตามข้อมูล B6 |

## 6. Acceptance Criteria ของ Phase 1

| เกณฑ์ | สถานะ |
|---|---|
| ส่งมอบครบ 7 รายการตาม requirement ข้อ 10 | ✅ |
| Schema รันได้บน PostgreSQL 16 จากฐานข้อมูลว่าง | ✅ |
| ตารางขั้นต่ำ 17 ตารางตาม requirement ข้อ 5 มีครบพร้อม PK/FK/constraint/index | ✅ (ทดสอบใน `01`) |
| จำนวนเงินไม่มี float | ✅ (ทดสอบใน `01`) |
| แยกข้อมูลนำเข้าดิบออกจากข้อมูลบัญชีที่ตรวจสอบแล้ว | ✅ (ชั้นข้อมูล 0–3, ทดสอบใน `04`) |
| ทุกตาราง/คอลัมน์มีคำอธิบาย และ data dictionary ตรงกับ schema | ✅ |
| กฎสำคัญทดสอบได้และผ่าน | ✅ 204/204 |
| ไม่มี mock data ในสิ่งที่ใช้ production (ข้อมูลทดสอบอยู่เฉพาะ `tests/` และติดป้าย) | ✅ |
| เจ้าของยืนยันการออกแบบและข้อมูลที่ขาด (หัวข้อ 7) | ⏳ รอการยืนยัน |

## 7. สิ่งที่ต้องยืนยันก่อนเริ่ม Phase 2

1. ~~ระบบ PMS ที่ใช้จริง (A1)~~ — ✅ **eZee Absolute (Cloud) ไม่ย้าย PMS** (ยืนยัน 9 ต.ค. 2569)
2. **ไฟล์ตัวอย่างจริงจาก eZee** — ✅ ได้ 5 ไฟล์ (Front Desk, Monthly/Yearly Statistics, Occupancy, No Show); **ยังขาด Night Audit**, Receipt Detail, Expense Voucher (A2); รูปแบบไฟล์: ✅ ตกลงใช้ตัวอ่าน PDF ใน Phase 2 ถ้า eZee ส่งออก Excel/CSV ไม่ได้ (A3)
3. การจอง OTA Collect — ไฟล์จริงแสดงว่า eZee บันทึก Expedia Collect เป็น **ยอดสุทธิ (NET)**; ต้องตัดสินว่าจะบันทึกรายได้สุทธิหรือเต็ม (A5)
4. **รายชื่อห้อง** — ✅ ขายได้ 15 ห้อง/วัน; ยังต้องการรายชื่อเลขห้อง (พบห้องเลข 16) (B1)
5. **รูปแบบกิจการ สถานะ VAT และนักบัญชีผู้ยืนยัน** (B2, B3) + การตั้งภาษีใน eZee (A10)
6. **รายชื่อ OTA และเงื่อนไข** (B6)
7. ~~Technology stack~~ — ✅ **Django + PostgreSQL** (ยืนยัน 10 ต.ค. 2569)
8. ~~Import Mapping 3 ประเภท~~ — ✅ **Night Audit, Monthly Statistics, Front Desk Activities** (ยืนยัน 10 ต.ค. 2569)
9. (ใหม่) เงินของการจองที่ไม่มีการรับเงินใน eZee บันทึกที่ไหน (A9)
