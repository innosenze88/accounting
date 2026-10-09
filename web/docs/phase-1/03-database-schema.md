# 3. Database Schema

- ต้นฉบับที่รันได้จริง: [`web/db/schema/`](../../db/schema) (PostgreSQL 16) — 40 ตาราง, 10 views, ทุกคอลัมน์มีคำอธิบาย
- รายละเอียดทุกคอลัมน์ (ชนิดข้อมูล, Null, ค่าเริ่มต้น, PK/FK, ดัชนีกันซ้ำ, trigger): [04-data-dictionary.md](04-data-dictionary.md) (สร้างอัตโนมัติจาก schema)
- ข้อมูลอ้างอิงที่ใช้จริง: [`web/db/reference/`](../../db/reference) — บทบาท 5 แบบ, **ผังบัญชีร่าง** 50 บัญชี, หมวดรายจ่าย 25 หมวด (นักบัญชีต้องยืนยัน)

## 3.1 ข้อกำหนดร่วม (conventions)

| เรื่อง | กำหนด |
|---|---|
| Primary key | `id bigint GENERATED ALWAYS AS IDENTITY` ทุกตาราง (ยกเว้น `roles.code`) |
| เงิน | domain `money_thb` = `NUMERIC(14,2)` (สูงสุด ±999,999,999,999.99) — เทสต์ยืนยันว่าไม่มีคอลัมน์ float และคอลัมน์เงินทุกคอลัมน์เป็น NUMERIC(14,2) |
| อัตรา % | domain `percent_rate` = `NUMERIC(7,4)` 0–100 |
| วันที่ | `date` สำหรับวันทางธุรกิจ (ค.ศ.), `timestamptz` สำหรับเวลาเกิดเหตุการณ์ |
| สถานะ/ประเภท | `text` + `CHECK (... IN (...))` แทน enum เพื่อเพิ่มค่าได้ง่าย |
| ข้อความบังคับ | `has_text(x)` ใน CHECK (เทสต์พบว่า `length(trim(x)) > 0` ปล่อย NULL ผ่าน จึงห้ามใช้) |
| หลายกิจการ | ทุกตารางธุรกรรมมี `property_id`; trigger ตรวจว่ารายการที่อ้างถึงกันเป็นของกิจการเดียวกัน (AC008) |
| Audit | trigger `trg_<table>_audit` บนทุกตารางธุรกิจ → `audit_logs` |
| รหัสข้อผิดพลาด | `AC001` แก้ไม่ได้ (ล็อก) · `AC002` งวดปิดแล้ว · `AC003` บัญชีไม่สมดุล · `AC004` เกินยอด/ยอดไม่ตรง · `AC005` ใช้บัญชี/ประเภทผิด · `AC006` ห้ามลบ · `AC007` ปิดงวดไม่ได้ · `AC008` ข้ามกิจการ — แอปแปลงเป็นข้อความภาษาไทย |

## 3.2 แผนภาพความสัมพันธ์ (ERD) แยกตามส่วน

### ก. ผู้ใช้ กิจการ Audit และไฟล์หลักฐาน

```mermaid
erDiagram
    properties ||--o{ user_property_roles : "ให้สิทธิ์"
    users ||--o{ user_property_roles : ""
    roles ||--o{ user_property_roles : ""
    users ||--o{ audit_logs : "ผู้กระทำ"
    properties ||--o{ audit_logs : ""
    properties ||--o{ attachments : ""
    attachments ||--o{ attachment_links : "ผูกกับรายการ (entity_type, entity_id)"
    properties {
        bigint id PK
        text code UK
        date accounting_start_date
        bool is_sandbox
    }
    users {
        bigint id PK
        text email UK
        text password_hash
    }
    roles {
        text code PK
    }
    user_property_roles {
        bigint user_id FK
        bigint property_id FK
        text role_code FK
        timestamptz revoked_at
    }
    audit_logs {
        bigint id PK
        text action
        text table_name
        bigint record_id
        jsonb old_data
        jsonb new_data
        text reason
    }
    attachments {
        bigint id PK
        text storage_key UK
        char64 sha256
    }
    attachment_links {
        bigint attachment_id FK
        text entity_type
        bigint entity_id
    }
```

### ข. ข้อมูลจาก PMS และการนำเข้า

```mermaid
erDiagram
    import_mapping_profiles ||--o{ imported_files : "อ่านด้วยแม่แบบ"
    imported_files ||--o{ import_rows : "แถวดิบ"
    import_rows |o--o| import_rows : "duplicate_of"
    properties ||--o{ rooms : ""
    properties ||--o{ ota_channels : ""
    ota_channels ||--o{ reservations : "ช่องทาง"
    reservations ||--o{ folios : ""
    rooms ||--o{ folios : ""
    import_rows ||--o{ reservations : "ที่มา"
    import_rows ||--o{ folios : "ที่มา"
    imported_files ||--o{ pms_daily_statistics : "สถิติ (ไม่ใช่บัญชี)"
    imported_files {
        bigint id PK
        char64 sha256 "UK ต่อกิจการ"
        text report_type
        text status
        jsonb source_control_totals
        bool totals_match
    }
    import_rows {
        bigint id PK
        int row_number
        jsonb raw_data "ห้ามแก้"
        text natural_key "UK เมื่อ COMMITTED"
        text status
    }
    reservations {
        bigint id PK
        text source_ref "UK"
        date arrival_date
        date departure_date
        text status
    }
    folios {
        bigint id PK
        text folio_no "UK"
        text folio_type
        money pms_balance
    }
    ota_channels {
        bigint id PK
        text code
        text collection_model
        percent commission_rate
        bool terms_confirmed
    }
```

### ค. เอกสารบัญชี (รายได้ เงินรับ รายจ่าย)

```mermaid
erDiagram
    folios ||--o{ revenue_entries : "รายได้ (service_date)"
    payments ||--o{ payment_allocations : "จัดสรร"
    folios ||--o{ payment_allocations : "รับชำระ"
    bank_accounts ||--o{ payments : "เงินเข้า"
    expense_categories ||--o{ expenses : "treatment"
    expenses ||--o{ expense_payments : "จ่ายจริง (paid_date)"
    bank_accounts ||--o{ expense_payments : "เงินออก"
    bank_accounts ||--o{ fund_transfers : "จาก/ไป"
    ota_channels ||--o{ ota_commissions : ""
    payments |o--o{ ota_commissions : "หักจาก payout"
    expenses |o--o{ ota_commissions : "ล้างหนี้ค่าคอม"
    loans |o--o{ expenses : "ชำระเงินต้น"
    expenses |o--o{ fixed_assets : "ซื้อสินทรัพย์"
    bank_accounts ||--o{ balance_confirmations : "ยอดที่ตรวจแล้ว"
    ledger_accounts ||--o{ expense_categories : ""
    ledger_accounts ||--o{ revenue_entries : ""
    ledger_accounts ||--o{ bank_accounts : ""
    revenue_entries {
        bigint id PK
        date service_date
        text revenue_type
        money total_amount
        text status
    }
    payments {
        bigint id PK
        date received_date
        text receipt_type
        text direction
        money amount
        money fee_amount
        text status
    }
    payment_allocations {
        bigint payment_id FK
        bigint folio_id FK
        money amount
        timestamptz voided_at
    }
    expenses {
        bigint id PK
        date expense_date
        money total_amount
        money wht_amount
        text status
    }
    expense_payments {
        bigint id PK
        date paid_date
        money amount
        text status
    }
    ota_commissions {
        bigint id PK
        date recognition_date
        money commission_amount
        text basis
        text settlement_method
    }
```

### ง. สมุดบัญชีคู่และงวดบัญชี

```mermaid
erDiagram
    ledger_accounts ||--o{ journal_lines : ""
    journal_entries ||--|{ journal_lines : "≥2 บรรทัด เดบิต=เครดิต"
    journal_entries |o--o| journal_entries : "reversal_of (ครั้งเดียว)"
    bank_accounts |o--o{ journal_lines : "มิติบัญชีเงิน"
    folios |o--o{ journal_lines : "มิติ folio"
    properties ||--o{ accounting_periods : "งวดรายเดือน"
    ledger_accounts {
        bigint id PK
        text code UK
        text account_type
        text account_subtype
        text pnl_section "NULL = งบดุล"
        bool is_system
    }
    journal_entries {
        bigint id PK
        text entry_no UK
        date entry_date
        text source_type
        bigint source_id
    }
    journal_lines {
        bigint journal_entry_id FK
        bigint ledger_account_id FK
        money debit
        money credit
    }
    accounting_periods {
        bigint id PK
        date period_start UK
        date period_end
        text status
    }
```

### จ. กระทบยอดธนาคาร

```mermaid
erDiagram
    bank_accounts ||--o{ bank_transactions : "statement"
    imported_files ||--o{ bank_transactions : ""
    reconciliations ||--|{ reconciliation_items : ""
    bank_transactions |o--o{ reconciliation_items : "ฝั่ง BANK"
    payments |o--o{ reconciliation_items : "ฝั่ง BOOK"
    expense_payments |o--o{ reconciliation_items : "ฝั่ง BOOK"
    fund_transfers |o--o{ reconciliation_items : "ฝั่ง BOOK (ขาออก/ขาเข้า)"
    bank_transactions {
        bigint id PK
        date txn_date
        money amount "± ทิศทาง"
        text dedupe_key "UK ต่อบัญชี"
        text review_status
    }
    reconciliations {
        bigint id PK
        text match_method
        text status
        money difference_amount
        text difference_reason
    }
    reconciliation_items {
        bigint id PK
        text side
        money amount "ใช้บางส่วนได้"
    }
```

### ฉ. ภาษีและแผนเงินทุน

```mermaid
erDiagram
    properties ||--o{ tax_profiles : "ช่วงเวลาไม่ซ้อน"
    properties ||--o{ tax_rules : ""
    tax_rules |o--o{ tax_estimates : ""
    expenses |o--o{ tax_estimates : "ชำระภาษี"
    properties ||--o{ capital_goals : ""
    capital_goals ||--o{ capital_scenarios : "CONSERVATIVE/BASE/OPTIMISTIC"
    capital_goals ||--o{ capital_fund_transactions : ""
    fund_transfers |o--o{ capital_fund_transactions : "หลักฐานการโอน"
    bank_accounts |o--o{ capital_goals : "บัญชีกองทุน"
    tax_profiles {
        text entity_type
        text vat_status
        bool confirmed_by_accountant
    }
    tax_estimates {
        money estimated_amount
        text estimate_status
        text data_completeness
    }
    capital_goals {
        money target_amount
        numeric operating_reserve_months
    }
    capital_scenarios {
        numeric revenue_change_pct
        numeric expense_change_pct
        percent allocation_pct
        money fallback_annual_revenue
    }
    capital_fund_transactions {
        date txn_date
        text txn_type
        money amount
        text status
    }
```

## 3.3 วงจรสถานะ (state machines) ที่ฐานข้อมูลบังคับ

```mermaid
stateDiagram-v2
    direction LR
    state "เอกสารบัญชี (revenue_entries, payments, expenses, expense_payments, fund_transfers, ota_commissions, balance_confirmations, capital_fund_transactions)" as D {
        [*] --> DRAFT
        DRAFT --> DRAFT: แก้ไขได้
        DRAFT --> [*]: ลบได้ (มี audit)
        DRAFT --> VERIFIED: verified_by + verified_at
        DRAFT --> VOIDED: เหตุผล
        VERIFIED --> VOIDED: เหตุผล (แก้อย่างอื่นไม่ได้)
        VOIDED --> [*]
    }
```

| วัตถุ | สถานะ | กฎ |
|---|---|---|
| `imported_files` | UPLOADED → PARSED/PARSE_FAILED → VALIDATED → COMMITTED หรือ REJECTED | COMMITTED ต้อง `totals_match` ไม่เป็น false; COMMITTED/REJECTED เปลี่ยนต่อไม่ได้; ลบไม่ได้; ไฟล์เดียวกัน (SHA-256) ซ้ำไม่ได้ยกเว้นตัวเดิมถูก REJECTED |
| `import_rows` | PENDING → VALID/INVALID/DUPLICATE/CONFLICT → COMMITTED/SKIPPED/SUPERSEDED | `raw_data`, `natural_key`, `row_hash` แก้ไม่ได้; ลบไม่ได้; `natural_key` COMMITTED ได้ครั้งเดียว |
| `reconciliations` | PROPOSED → CONFIRMED → REVERSED, หรือ PROPOSED → REJECTED | สร้างได้เฉพาะ PROPOSED; ยืนยันได้เมื่อ ฝั่งธนาคาร − ฝั่งบัญชี = `difference_amount` (≠0 ต้องมีเหตุผล) และรายการฝั่งบัญชีเป็น VERIFIED; รายการในชุดเพิ่ม/ลบได้เฉพาะตอน PROPOSED |
| `accounting_periods` | OPEN ⇄ CLOSED | สร้างได้เฉพาะ OPEN; ปิดได้เมื่อ `issue_count = 0` และงวดก่อนหน้าปิดแล้ว; เปิดใหม่ต้องมีผู้เปิด/เวลา/เหตุผล และเปิดย้อนจากงวดล่าสุด; งวดที่ปิดแก้ไม่ได้; ลบไม่ได้ |
| `tax_estimates` | SYSTEM_ESTIMATE → ACCOUNTANT_REVIEWED → FILED → PAID (หรือ CANCELLED) | FILED แก้ได้เฉพาะข้อมูลการชำระ; PAID/CANCELLED ถาวร; ลบได้เฉพาะ SYSTEM_ESTIMATE |
| `journal_entries/lines` | — | บันทึกเพิ่มอย่างเดียว; ตรวจสมดุลตอน COMMIT (deferred); แก้ด้วย REVERSAL ได้ครั้งเดียวต่อรายการ |
| `audit_logs`, `attachments` | — | บันทึกเพิ่มอย่างเดียว (ห้าม UPDATE/DELETE/TRUNCATE) |

## 3.4 สิ่งที่ฐานข้อมูลรับประกัน (และมีเทสต์)

| การรับประกัน | กลไก | เทสต์ |
|---|---|---|
| ไม่มีจำนวนเงินแบบ float | domain + เทสต์ catalog | `01` |
| นำเข้าไฟล์เดิมซ้ำไม่สร้างรายการซ้ำ | unique (property, sha256), unique natural_key เมื่อ COMMITTED, unique (property, source_system, source_ref) ของเอกสารที่ยังไม่ VOIDED | `04` |
| ข้อมูลดิบไม่สูญหาย | ห้ามแก้ `raw_data`, ห้ามลบแถว/ไฟล์, แถว INVALID เก็บพร้อม error | `04` |
| รายได้แยกจากเงินรับ | ตารางแยก เชื่อมผ่าน allocation ที่ไม่เกินยอด | `05` |
| ค่าใช้จ่ายไม่ถูกนับซ้ำ | treatment ↔ ประเภทบัญชี, ห้ามใช้บัญชีระบบ, ค่าคอมล้างหนี้ด้วยหมวดเฉพาะ | `06`, `07` |
| บัญชีสมดุล | deferred constraint trigger | `07` |
| กระทบยอดไม่สร้างเงิน | ตารางจับคู่อ้างถึงรายการที่มีอยู่เท่านั้น, ใช้ไม่เกินยอดจริง | `08` |
| กำไร "ยืนยันแล้ว" เฉพาะงวดที่ข้อมูลครบและปิดแล้ว | `v_period_completeness` + trigger ปิดงวด | `09` |
| Audit Trail ครบและแก้ไม่ได้ | trigger + append-only | `02` |
| ข้อมูลข้ามกิจการไม่ได้ | `assert_same_property` | `03`, `10` |

## 3.5 ดัชนี (indexes)

- ดัชนีตามวันที่ทางธุรกิจต่อกิจการ: `(property_id, service_date)`, `(property_id, received_date)`, `(property_id, expense_date)`, `(property_id, txn_date)` — ใช้กับรายงานรายวัน/เดือน/ปีและการตรวจความครบถ้วน
- ดัชนีกันซ้ำ (unique, บางตัวเป็น partial): ดูหัวข้อ "Unique" ของแต่ละตารางใน data dictionary
- ดัชนีจับคู่: `bank_transactions (bank_account_id, amount, txn_date)` สำหรับเสนอการจับคู่ยอดเท่ากันวันใกล้กัน
- ดัชนี FK ที่ใช้ join บ่อย: `revenue_entries.folio_id`, `payment_allocations.folio_id`, `expense_payments.expense_id`, `reconciliation_items.*`

## 3.6 การเปลี่ยนแปลง schema หลัง go-live

1. เพิ่มไฟล์ใหม่ `web/db/schema/0NN_<desc>.sql` (ไม่แก้ไฟล์เดิม)
2. ทุกคอลัมน์ใหม่ต้องมี COMMENT (เทสต์ `01` จะล้มถ้าไม่มี) และสร้าง data dictionary ใหม่
3. รัน `run-tests.sh` + ซ้อมกู้คืน ก่อน deploy; สำรองข้อมูลก่อน migrate ทุกครั้ง
