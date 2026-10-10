# 4. Data Dictionary — พจนานุกรมข้อมูล

> ไฟล์นี้สร้างอัตโนมัติจากฐานข้อมูลจริงด้วย `web/db/tools/gen-data-dictionary.sh` (อ่าน COMMENT ของทุกตาราง/คอลัมน์)
> ห้ามแก้ด้วยมือ — แก้ที่ `web/db/schema/*.sql` แล้วสร้างใหม่ CI ตรวจว่าไฟล์นี้ตรงกับ schema เสมอ
>
> สัญลักษณ์: `money_thb (numeric(14,2))` = จำนวนเงินบาททศนิยม 2 ตำแหน่ง, `percent_rate` = ร้อยละ 0–100,
> PK = Primary Key, FK→ = Foreign Key ไปยังตาราง, identity = เลขรันอัตโนมัติ, generated = คำนวณอัตโนมัติ
> ความหมายของสถานะ (status) และกฎทางบัญชีอยู่ในไฟล์ 01 และ 03

### 1. `properties`

กิจการ/สถานประกอบการ (รีสอร์ต และในอนาคตร้านสะดวกซื้อ) ทุกตารางบัญชีผูกกับ property_id

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสภายใน (PK) |
| `code` | text | NOT NULL |  |  | รหัสสั้น ตัวพิมพ์ใหญ่ เช่น VIPHUANAN |
| `name_th` | text | NOT NULL |  |  | ชื่อภาษาไทย เช่น วิภูอนันต์ รีสอร์ท |
| `name_en` | text |  |  |  | ชื่อภาษาอังกฤษ |
| `business_type` | text | NOT NULL | 'RESORT'::text |  | RESORT / RETAIL_STORE / OTHER |
| `legal_entity_name` | text |  |  |  | ชื่อผู้ประกอบการตามกฎหมาย (บุคคล/ห้างฯ/บริษัท) — รอยืนยัน |
| `tax_id` | text |  |  |  | เลขประจำตัวผู้เสียภาษี 13 หลัก |
| `branch_code` | text |  |  |  | รหัสสาขาตามทะเบียนภาษี (ถ้ามี) |
| `address` | text |  |  |  | ที่อยู่สำหรับเอกสาร |
| `timezone` | text | NOT NULL | 'Asia/Bangkok'::text |  | เขตเวลาที่ใช้ตัดวันทำการ (ค่าเริ่มต้น Asia/Bangkok) |
| `pms_system` | text | NOT NULL | 'NONE'::text |  | ระบบ PMS ที่ใช้: HOTELRUNNER / EZEE / NONE |
| `pms_property_ref` | text |  |  |  | รหัสโรงแรมในระบบ PMS |
| `accounting_start_date` | date | NOT NULL |  |  | วันแรกที่ระบบนี้เป็นสมุดบัญชีหลัก (ก่อนหน้านี้ไม่ตรวจความครบถ้วน) |
| `fiscal_year_start_month` | smallint | NOT NULL | 1 |  | เดือนเริ่มปีบัญชี (1 = มกราคม) |
| `is_sandbox` | boolean | NOT NULL | false |  | true = ข้อมูลทดสอบ แอปต้องแสดงป้าย "ข้อมูลทดสอบ" และห้ามใช้ใน Production |
| `is_active` | boolean | NOT NULL | true |  | ยังใช้งานอยู่ |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `properties_code_key` (code)

**Triggers:** `trg_properties_audit`, `trg_properties_updated_at`

### 2. `users`

ผู้ใช้งานระบบ (สิทธิ์อยู่ใน user_property_roles)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสผู้ใช้ (PK) |
| `email` | text | NOT NULL |  |  | อีเมลสำหรับเข้าสู่ระบบ (ไม่ซ้ำ ไม่สนตัวพิมพ์) |
| `display_name` | text | NOT NULL |  |  | ชื่อที่แสดงในระบบและ Audit Log |
| `password_hash` | text |  |  |  | รหัสผ่านแบบ hash (argon2id) — NULL = ยังตั้งรหัสไม่ได้/เชิญอยู่; ไม่ถูกคัดลอกเข้า audit_logs |
| `is_active` | boolean | NOT NULL | true |  | false = ปิดการใช้งาน (ห้ามลบผู้ใช้ที่เคยบันทึกรายการ) |
| `mfa_enabled` | boolean | NOT NULL | false |  | เปิดยืนยันตัวตนสองชั้น (บังคับสำหรับ OWNER/ACCOUNTANT) |
| `failed_login_count` | integer | NOT NULL | 0 |  | จำนวนครั้งที่ล็อกอินผิดติดกัน |
| `locked_until` | timestamp with time zone |  |  |  | ล็อกบัญชีชั่วคราวถึงเวลานี้ |
| `last_login_at` | timestamp with time zone |  |  |  | เข้าสู่ระบบครั้งล่าสุด |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `users_email_uq` (lower(email))

**Triggers:** `trg_users_audit`, `trg_users_updated_at`

### 3. `roles`

บทบาทผู้ใช้ (สิทธิ์ของแต่ละบทบาทกำหนดในโค้ดตามตาราง RBAC ในเอกสารสถาปัตยกรรม)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `code` | text | NOT NULL |  | PK | รหัสบทบาท เช่น OWNER, ACCOUNTANT |
| `name_th` | text | NOT NULL |  |  | ชื่อบทบาทภาษาไทย |
| `name_en` | text | NOT NULL |  |  | ชื่อบทบาทภาษาอังกฤษ |
| `description` | text | NOT NULL |  |  | คำอธิบายหน้าที่ |

### 4. `user_property_roles`

การให้สิทธิ์ผู้ใช้ตามกิจการ (RBAC) — ไม่ลบ ใช้ revoked_at แทน

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `user_id` | bigint | NOT NULL |  | FK→users | ผู้ใช้ |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการที่ได้สิทธิ์ |
| `role_code` | text | NOT NULL |  | FK→roles | บทบาท |
| `granted_by` | bigint |  |  | FK→users | ผู้ให้สิทธิ์ |
| `granted_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่ให้สิทธิ์ |
| `revoked_by` | bigint |  |  | FK→users | ผู้ถอนสิทธิ์ |
| `revoked_at` | timestamp with time zone |  |  |  | เวลาที่ถอนสิทธิ์ (NULL = ยังมีสิทธิ์) |

**Unique / ดัชนีกันซ้ำ:** `user_property_roles_active_uq` (user_id, property_id, role_code) WHERE (revoked_at IS NULL)

**Triggers:** `trg_user_property_roles_audit`

### 5. `audit_logs`

ประวัติการสร้าง แก้ไข ยืนยัน ยกเลิก อนุมัติ นำเข้า และการเข้าระบบ — บันทึกเพิ่มได้อย่างเดียว

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | ลำดับ (PK) |
| `occurred_at` | timestamp with time zone | NOT NULL | clock_timestamp() |  | เวลาที่เกิดเหตุการณ์ |
| `user_id` | bigint |  |  | FK→users | ผู้กระทำ (NULL = ระบบ/งานอัตโนมัติ) |
| `property_id` | bigint |  |  | FK→properties | กิจการที่เกี่ยวข้อง |
| `action` | text | NOT NULL |  |  | ประเภทเหตุการณ์ เช่น INSERT, VERIFY, VOID, PERIOD_CLOSE, LOGIN |
| `table_name` | text |  |  |  | ตารางที่ถูกเปลี่ยน |
| `record_id` | bigint |  |  |  | id ของแถวที่ถูกเปลี่ยน |
| `old_data` | jsonb |  |  |  | ข้อมูลก่อนเปลี่ยน (JSON) |
| `new_data` | jsonb |  |  |  | ข้อมูลหลังเปลี่ยน (JSON) |
| `changed_fields` | text[] |  |  |  | ชื่อคอลัมน์ที่เปลี่ยน |
| `reason` | text |  |  |  | เหตุผลที่ผู้ใช้ระบุ (app.reason) |
| `ip_address` | inet |  |  |  | IP ของผู้ใช้ |
| `request_id` | text |  |  |  | รหัส request สำหรับไล่ปัญหา |

**Triggers:** `trg_audit_logs_append_only`, `trg_audit_logs_no_truncate`

### 6. `attachments`

ไฟล์หลักฐาน (สลิป ใบเสร็จ ใบกำกับภาษี) เก็บใน private storage — ห้ามลบ/แก้

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสไฟล์ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการเจ้าของไฟล์ |
| `storage_key` | text | NOT NULL |  |  | ตำแหน่งไฟล์ใน private storage (ไม่ใช่ URL สาธารณะ เปิดได้ผ่าน endpoint ที่ตรวจสิทธิ์เท่านั้น) |
| `original_filename` | text | NOT NULL |  |  | ชื่อไฟล์เดิมตอนอัปโหลด |
| `mime_type` | text | NOT NULL |  |  | ชนิดไฟล์ที่อนุญาต (PDF รูปภาพ CSV Excel) |
| `size_bytes` | bigint | NOT NULL |  |  | ขนาดไฟล์ (สูงสุด 25 MB) |
| `sha256` | character(64) | NOT NULL |  |  | ลายนิ้วมือไฟล์ ใช้กันไฟล์เดียวกันถูกเก็บซ้ำ |
| `uploaded_by` | bigint |  |  | FK→users | ผู้อัปโหลด |
| `uploaded_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่อัปโหลด |

**Unique / ดัชนีกันซ้ำ:** `attachments_same_file_uq` (property_id, sha256); `attachments_storage_key_key` (storage_key)

**Triggers:** `trg_attachments_append_only`

### 7. `attachment_links`

ผูกไฟล์หลักฐานกับรายการบัญชี (หนึ่งรายการมีได้หลายไฟล์) — ไม่ลบ ใช้ unlinked_at

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `attachment_id` | bigint | NOT NULL |  | FK→attachments | ไฟล์หลักฐาน |
| `entity_type` | text | NOT NULL |  |  | ชนิดรายการที่ผูก |
| `entity_id` | bigint | NOT NULL |  |  | id ของรายการที่ผูก |
| `linked_by` | bigint |  |  | FK→users | ผู้ผูกไฟล์ |
| `linked_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่ผูก |
| `unlinked_by` | bigint |  |  | FK→users | ผู้ยกเลิกการผูก |
| `unlinked_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิกการผูก (NULL = ยังผูกอยู่) |
| `unlink_reason` | text |  |  |  | เหตุผลที่ยกเลิกการผูก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `attachment_links_active_uq` (attachment_id, entity_type, entity_id) WHERE (unlinked_at IS NULL)

**Triggers:** `trg_attachment_links_audit`, `trg_attachment_links_guard`

### 8. `ledger_accounts`

ผังบัญชี (Chart of Accounts) — ร่างเริ่มต้นต้องให้นักบัญชียืนยัน

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสบัญชี (PK) |
| `code` | text | NOT NULL |  |  | เลขที่บัญชี 4–6 หลัก เช่น 4101 |
| `name_th` | text | NOT NULL |  |  | ชื่อบัญชีภาษาไทย |
| `name_en` | text | NOT NULL |  |  | ชื่อบัญชีภาษาอังกฤษ |
| `account_type` | text | NOT NULL |  |  | หมวด: ASSET สินทรัพย์ / LIABILITY หนี้สิน / EQUITY ส่วนของเจ้าของ / REVENUE รายได้ / EXPENSE ค่าใช้จ่าย |
| `account_subtype` | text | NOT NULL |  |  | หมวดย่อยที่ระบบใช้ตรวจการใช้งาน เช่น BANK, RECEIVABLE, CUSTOMER_DEPOSIT, OWNER_DRAWING |
| `pnl_section` | text |  |  |  | ตำแหน่งในงบกำไรขาดทุน (เฉพาะบัญชีรายได้/ค่าใช้จ่าย; บัญชีงบดุลเป็น NULL จึงไม่ถูกนับเป็นค่าใช้จ่าย) |
| `parent_id` | bigint |  |  | FK→ledger_accounts | บัญชีแม่ (สำหรับจัดกลุ่มรายงาน) |
| `is_system` | boolean | NOT NULL | false |  | บัญชีที่ระบบใช้ลงรายการอัตโนมัติ (ห้ามปิด) |
| `is_active` | boolean | NOT NULL | true |  | ยังใช้งานอยู่ |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `ledger_accounts_code_key` (code)

**Triggers:** `trg_ledger_accounts_audit`, `trg_ledger_accounts_updated_at`

### 9. `accounting_periods`

งวดบัญชีรายเดือน — งวดที่ CLOSED ล็อกรายการทุกประเภท และเป็นเงื่อนไขของคำว่า "ยืนยันแล้ว (FINAL)"

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสงวด (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `period_start` | date | NOT NULL |  |  | วันแรกของเดือน |
| `period_end` | date |  | generated |  | วันสุดท้ายของเดือน (คำนวณอัตโนมัติ) |
| `status` | text | NOT NULL | 'OPEN'::text |  | OPEN เปิดอยู่ / CLOSED ปิดงวดแล้ว (ปิดได้เมื่อข้อมูลครบเท่านั้น) |
| `closed_by` | bigint |  |  | FK→users | ผู้ปิดงวด |
| `closed_at` | timestamp with time zone |  |  |  | เวลาที่ปิดงวด |
| `close_note` | text |  |  |  | หมายเหตุการปิดงวด |
| `reopened_by` | bigint |  |  | FK→users | ผู้เปิดงวดใหม่ (เฉพาะ OWNER) |
| `reopened_at` | timestamp with time zone |  |  |  | เวลาที่เปิดงวดใหม่ |
| `reopen_reason` | text |  |  |  | เหตุผลที่เปิดงวดใหม่ (บังคับ) |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `accounting_periods_property_id_period_start_key` (property_id, period_start)

**Triggers:** `trg_accounting_periods_audit`, `trg_accounting_periods_guard`, `trg_accounting_periods_updated_at`

### 10. `bank_accounts`

บัญชีเงิน: บัญชีธนาคาร ลิ้นชักเงินสด e-wallet และบัญชีพักเงินบัตร (ยอดยกมาบันทึกเป็น journal OPENING_BALANCE)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสบัญชีเงิน (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `account_kind` | text | NOT NULL |  |  | BANK ธนาคาร / CASH เงินสด / EWALLET / CARD_CLEARING เงินบัตรระหว่างทาง |
| `purpose` | text | NOT NULL | 'OPERATING'::text |  | วัตถุประสงค์ เช่น OPERATING ใช้จ่าย, CAPITAL_FUND กองทุนเปิดร้าน, TAX_RESERVE กันไว้จ่ายภาษี |
| `bank_name` | text |  |  |  | ชื่อธนาคาร (บังคับเมื่อเป็น BANK) |
| `account_name` | text | NOT NULL |  |  | ชื่อที่ใช้เรียกในระบบ เช่น "KBank ออมทรัพย์ ใช้จ่ายรีสอร์ต" |
| `account_no_last4` | text |  |  |  | เลขบัญชี 4 หลักท้าย (ไม่เก็บเลขเต็มเพื่อความปลอดภัย) |
| `ledger_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีแยกประเภทที่ผูก (ต้องเป็น CASH/BANK/CLEARING) |
| `currency` | character(3) | NOT NULL | 'THB'::bpchar |  | สกุลเงิน (รองรับเฉพาะ THB) |
| `opened_on` | date | NOT NULL |  |  | วันที่เริ่มใช้บัญชีนี้ในระบบ (ก่อนวันนี้ไม่ตรวจ statement) |
| `closed_on` | date |  |  |  | วันที่ปิดบัญชี |
| `is_active` | boolean | NOT NULL | true |  | ยังใช้งานอยู่ |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `bank_accounts_property_id_account_name_key` (property_id, account_name)

**Triggers:** `trg_bank_accounts_audit`, `trg_bank_accounts_updated_at`, `trg_bank_accounts_validate`

### 11. `import_mapping_profiles`

แม่แบบจับคู่คอลัมน์ของไฟล์ต้นทางกับฟิลด์มาตรฐาน (แก้ได้โดยไม่ต้องแก้โค้ดเมื่อ PMS เปลี่ยนรูปแบบไฟล์)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสแม่แบบ (PK) |
| `source_system` | text | NOT NULL |  |  | ระบบต้นทาง: HOTELRUNNER, EZEE, BANK, OTA, MANUAL_TEMPLATE |
| `report_type` | text | NOT NULL |  |  | ประเภทรายงานตามหน้าที่ (ไม่ใช่ชื่อเมนูของผู้ขาย) |
| `file_format` | text | NOT NULL |  |  | CSV / XLSX / PDF (PDF รองรับในอนาคต) |
| `variant` | text | NOT NULL | 'default'::text |  | รูปแบบย่อย เช่น ชื่อธนาคาร KBANK หรือภาษาของรายงาน |
| `version` | integer | NOT NULL |  |  | เวอร์ชันของแม่แบบ (เพิ่มเมื่อหัวคอลัมน์เปลี่ยน) |
| `header_signature` | text[] | NOT NULL | '{}'::text[] |  | หัวคอลัมน์ที่คาดหวัง ใช้ตรวจจับชนิดไฟล์อัตโนมัติ |
| `column_map` | jsonb | NOT NULL |  |  | JSON: ฟิลด์มาตรฐาน -> {source, type, required, transform} |
| `date_format` | text |  |  |  | รูปแบบวันที่ในไฟล์ เช่น DD/MM/YYYY (รองรับปี พ.ศ.) |
| `status` | text | NOT NULL | 'DRAFT'::text |  | DRAFT ยังไม่ได้ทดสอบกับไฟล์จริง / VERIFIED_WITH_SAMPLE ทดสอบกับไฟล์จริงแล้ว / RETIRED เลิกใช้ |
| `verified_sample_sha256` | character(64) |  |  |  | ลายนิ้วมือของไฟล์ตัวอย่างจริงที่ใช้ยืนยันแม่แบบ |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้สร้าง |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `import_mapping_profiles_source_system_report_type_file_form_key` (source_system, report_type, file_format, variant, version)

**Triggers:** `trg_import_mapping_profiles_audit`, `trg_import_mapping_profiles_updated_at`

### 12. `imported_files`

ไฟล์ที่นำเข้า (เก็บไฟล์ต้นฉบับแบบอ่านอย่างเดียว) พร้อมชื่อไฟล์ ต้นทาง วันที่นำเข้า ผลตรวจยอดรวม

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสการนำเข้า (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `source_system` | text | NOT NULL |  |  | ระบบต้นทางของไฟล์ |
| `report_type` | text | NOT NULL |  |  | ประเภทรายงาน |
| `mapping_profile_id` | bigint |  |  | FK→import_mapping_profiles | แม่แบบที่ใช้อ่านไฟล์ |
| `bank_account_id` | bigint |  |  | FK→bank_accounts | บัญชีธนาคารของ statement (บังคับเมื่อเป็น BANK_STATEMENT) |
| `original_filename` | text | NOT NULL |  |  | ชื่อไฟล์เดิม |
| `mime_type` | text | NOT NULL |  |  | ชนิดไฟล์ |
| `size_bytes` | bigint | NOT NULL |  |  | ขนาดไฟล์ |
| `sha256` | character(64) | NOT NULL |  |  | ลายนิ้วมือไฟล์ ไฟล์เดิมซ้ำจะถูกปฏิเสธ (ไม่สร้างรายการซ้ำ) |
| `storage_key` | text | NOT NULL |  |  | ตำแหน่งไฟล์ต้นฉบับใน private storage |
| `report_date_from` | date |  |  |  | วันที่เริ่มของข้อมูลในรายงาน |
| `report_date_to` | date |  |  |  | วันที่สิ้นสุดของข้อมูลในรายงาน |
| `status` | text | NOT NULL | 'UPLOADED'::text |  | UPLOADED -> PARSED/PARSE_FAILED -> VALIDATED -> COMMITTED หรือ REJECTED |
| `total_rows` | integer | NOT NULL | 0 |  | จำนวนแถวที่อ่านได้ |
| `valid_rows` | integer | NOT NULL | 0 |  | แถวที่ผ่านการตรวจ |
| `invalid_rows` | integer | NOT NULL | 0 |  | แถวที่ไม่ผ่าน (แสดงให้ผู้ใช้แก้) |
| `duplicate_rows` | integer | NOT NULL | 0 |  | แถวที่เคยนำเข้าแล้ว (ข้าม) |
| `conflict_rows` | integer | NOT NULL | 0 |  | แถวที่ key เดิมแต่ค่าต่างจากที่บันทึกไว้ (ต้องให้คนตรวจ) |
| `committed_rows` | integer | NOT NULL | 0 |  | แถวที่บันทึกเป็นรายการบัญชีแล้ว |
| `source_control_totals` | jsonb |  |  |  | ยอดรวมที่พิมพ์อยู่ในรายงานต้นฉบับ (JSON) |
| `computed_totals` | jsonb |  |  |  | ยอดรวมที่ระบบคำนวณจากแถวที่อ่านได้ (JSON) |
| `totals_match` | boolean |  |  |  | ยอดรวมตรงกับต้นฉบับหรือไม่ (false = ห้าม COMMIT, NULL = รายงานไม่มียอดรวมให้ตรวจ) |
| `parse_error` | text |  |  |  | ข้อความผิดพลาดเมื่ออ่านไฟล์ไม่ได้ |
| `uploaded_by` | bigint |  |  | FK→users | ผู้อัปโหลด |
| `uploaded_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่อัปโหลด |
| `committed_by` | bigint |  |  | FK→users | ผู้ยืนยันนำเข้า |
| `committed_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยันนำเข้า |
| `rejected_by` | bigint |  |  | FK→users | ผู้ปฏิเสธไฟล์ |
| `rejected_at` | timestamp with time zone |  |  |  | เวลาที่ปฏิเสธ |
| `reject_reason` | text |  |  |  | เหตุผลที่ปฏิเสธ (บังคับ) |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `imported_files_same_file_uq` (property_id, sha256) WHERE (status <> 'REJECTED'::text); `imported_files_storage_key_key` (storage_key)

**Triggers:** `trg_imported_files_audit`, `trg_imported_files_guard`, `trg_imported_files_updated_at`

### 13. `import_rows`

แถวข้อมูลดิบจากไฟล์นำเข้า (staging) — raw_data ไม่ถูกแก้ไข เพื่อไม่ให้ข้อมูลต้นฉบับสูญหาย

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสแถว (PK) |
| `imported_file_id` | bigint | NOT NULL |  | FK→imported_files | ไฟล์ที่มาของแถวนี้ |
| `row_number` | integer | NOT NULL |  |  | ลำดับแถวในไฟล์ (นับรวมทุกส่วนของรายงาน) |
| `section` | text |  |  |  | ส่วนของรายงาน/ชื่อชีต เช่น room_charges, daily_sales |
| `raw_data` | jsonb | NOT NULL |  |  | ค่าทุกช่องตามต้นฉบับเป็นข้อความ (ห้ามแก้) |
| `normalized_data` | jsonb |  |  |  | ค่าที่แปลงชนิดแล้วตามฟิลด์มาตรฐาน |
| `natural_key` | text |  |  |  | กุญแจกันซ้ำ รูปแบบ {property_id}\|{source_system}\|{report_type}\|{ref...} |
| `row_hash` | character(64) |  |  |  | SHA-256 ของ normalized_data ใช้ตรวจว่า key เดิมแต่ค่าเปลี่ยนหรือไม่ |
| `status` | text | NOT NULL | 'PENDING'::text |  | PENDING/VALID/INVALID/DUPLICATE/CONFLICT/COMMITTED/SKIPPED/SUPERSEDED |
| `errors` | jsonb | NOT NULL | '[]'::jsonb |  | รายการข้อผิดพลาด [{field, code, message}] |
| `duplicate_of_row_id` | bigint |  |  | FK→import_rows | แถวเดิมที่ซ้ำกัน |
| `target_table` | text |  |  |  | ตารางบัญชีที่สร้างจากแถวนี้ |
| `target_id` | bigint |  |  |  | id ของรายการบัญชีที่สร้าง |
| `reviewed_by` | bigint |  |  | FK→users | ผู้ตรวจแถวที่มีปัญหา |
| `reviewed_at` | timestamp with time zone |  |  |  | เวลาที่ตรวจ |
| `review_note` | text |  |  |  | บันทึกการตรวจ (บังคับเมื่อข้ามแถว) |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่อ่านแถว |

**Unique / ดัชนีกันซ้ำ:** `import_rows_committed_key_uq` (natural_key) WHERE (status = 'COMMITTED'::text); `import_rows_imported_file_id_row_number_key` (imported_file_id, row_number)

**Triggers:** `trg_import_rows_guard`

### 14. `rooms`

ห้องพัก (ปัจจุบัน 15 ห้อง เพิ่มห้องได้โดยกำหนด active_from) ใช้คำนวณจำนวนห้องที่ขายได้ต่อวัน

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสห้อง (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `room_no` | text | NOT NULL |  |  | เลขห้องตามที่ใช้ใน PMS |
| `room_type` | text |  |  |  | ประเภทห้อง เช่น Deluxe, Superior |
| `pms_room_ref` | text |  |  |  | รหัสห้องใน PMS (ถ้าต่างจากเลขห้อง) |
| `max_occupancy` | smallint |  |  |  | จำนวนผู้เข้าพักสูงสุด |
| `is_sellable` | boolean | NOT NULL | true |  | false = ไม่นับเป็นห้องที่ขายได้ (เช่น ห้องพนักงาน) |
| `active_from` | date | NOT NULL |  |  | วันที่เริ่มขายห้องนี้ |
| `active_to` | date |  |  |  | วันสุดท้ายที่ขาย (NULL = ยังขายอยู่) |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `rooms_property_id_room_no_key` (property_id, room_no)

**Triggers:** `trg_rooms_audit`, `trg_rooms_updated_at`

### 15. `ota_channels`

ช่องทางขาย (OTA, จองตรง, Walk-in) และเงื่อนไขค่าคอมมิชชัน — ค่าว่าง = ยังไม่ทราบ ห้ามเดา

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสช่องทาง (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `code` | text | NOT NULL |  |  | รหัส เช่น BOOKING_COM, AGODA, EXPEDIA, DIRECT, WALK_IN |
| `name` | text | NOT NULL |  |  | ชื่อที่แสดง |
| `channel_type` | text | NOT NULL |  |  | ประเภทช่องทาง |
| `collection_model` | text |  |  |  | ใครเก็บเงินลูกค้า: HOTEL_COLLECT เราเก็บเอง / OTA_COLLECT OTA เก็บแล้วโอนให้ / MIXED |
| `commission_rate` | percent_rate (numeric(7,4)) |  |  |  | อัตราค่าคอมมิชชันตามสัญญา (%) — NULL = ยังไม่ยืนยัน |
| `commission_basis` | text |  |  |  | ฐานคิดค่าคอม: รวมภาษี หรือ ก่อนภาษี |
| `terms_confirmed` | boolean | NOT NULL | false |  | เจ้าของ/นักบัญชียืนยันเงื่อนไขกับสัญญาแล้ว |
| `receivable_account_id` | bigint |  |  | FK→ledger_accounts | บัญชีลูกหนี้ของช่องทางนี้ (กรณี OTA เก็บเงินแทน) |
| `pms_source_names` | text[] | NOT NULL | '{}'::text[] |  | ชื่อ Source ที่ปรากฏในรายงาน PMS ที่ให้จับคู่มาช่องทางนี้ เช่น {"Booking.com","Booking.com-1"} |
| `is_active` | boolean | NOT NULL | true |  | ยังใช้งานอยู่ |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `ota_channels_property_id_code_key` (property_id, code)

**Triggers:** `trg_ota_channels_audit`, `trg_ota_channels_updated_at`, `trg_ota_channels_validate`

### 16. `reservations`

การจองจาก PMS (ข้อมูลอ้างอิง ไม่ใช่รายได้) — เก็บข้อมูลส่วนบุคคลของแขกเท่าที่จำเป็น

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสการจอง (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `channel_id` | bigint |  |  | FK→ota_channels | ช่องทางขาย |
| `source_system` | text | NOT NULL |  |  | ระบบที่สร้างการจอง |
| `source_ref` | text |  |  |  | เลขที่การจองใน PMS (Res No.) ใช้กันซ้ำ |
| `ota_booking_ref` | text |  |  |  | เลขยืนยันของ OTA |
| `guest_name` | text |  |  |  | ชื่อแขก (ไม่เก็บเบอร์โทร/เลขบัตร) |
| `adults` | smallint |  |  |  | จำนวนผู้ใหญ่ |
| `children` | smallint |  |  |  | จำนวนเด็ก |
| `room_count` | smallint | NOT NULL | 1 |  | จำนวนห้องในการจอง |
| `arrival_date` | date | NOT NULL |  |  | วันเช็คอิน |
| `departure_date` | date | NOT NULL |  |  | วันเช็คเอาท์ |
| `nights` | integer |  | generated |  | จำนวนคืน (คำนวณ) |
| `status` | text | NOT NULL |  |  | CONFIRMED / IN_HOUSE / CHECKED_OUT / CANCELLED / NO_SHOW |
| `booked_at` | timestamp with time zone |  |  |  | เวลาที่จอง |
| `cancelled_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `pms_total_amount` | money_thb (numeric(14,2)) |  |  |  | ยอดรวมตาม PMS (ใช้เทียบเท่านั้น ไม่ใช่ยอดบัญชี) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่มา |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `reservations_source_uq` (property_id, source_system, source_ref) WHERE (source_ref IS NOT NULL)

**Triggers:** `trg_reservations_audit`, `trg_reservations_updated_at`

### 17. `folios`

บัญชีค่าใช้จ่ายของแขก (Folio) — ศูนย์กลางเชื่อมรายได้กับเงินรับ ยอดค้าง = รายได้ − เงินที่จัดสรรมา

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส folio (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `reservation_id` | bigint |  |  | FK→reservations | การจองที่เกี่ยวข้อง |
| `folio_type` | text | NOT NULL | 'GUEST'::text |  | GUEST แขก / OTA_CITY_LEDGER ลูกหนี้ OTA / HOUSE ภายใน / OTHER_INCOME รายได้อื่นที่ไม่มีการจอง |
| `source_system` | text | NOT NULL |  |  | ระบบที่สร้าง folio |
| `folio_no` | text | NOT NULL |  |  | เลขที่ folio ใน PMS (กันซ้ำร่วมกับ source_system) |
| `bill_to_name` | text |  |  |  | ชื่อผู้ถูกเรียกเก็บ |
| `channel_id` | bigint |  |  | FK→ota_channels | ช่องทางขาย |
| `room_id` | bigint |  |  | FK→rooms | ห้องพัก |
| `opened_on` | date | NOT NULL |  |  | วันที่เปิด folio |
| `closed_on` | date |  |  |  | วันที่ปิด folio |
| `status` | text | NOT NULL | 'OPEN'::text |  | OPEN / CLOSED / VOID |
| `pms_total` | money_thb (numeric(14,2)) |  |  |  | ยอดรวมตาม PMS ล่าสุด (ใช้เทียบ ไม่ใช่ยอดบัญชี) |
| `pms_paid` | money_thb (numeric(14,2)) |  |  |  | ยอดชำระตาม PMS ล่าสุด |
| `pms_balance` | money_thb (numeric(14,2)) |  |  |  | ยอดค้างตาม PMS ล่าสุด |
| `pms_snapshot_date` | date |  |  |  | วันที่ของตัวเลข PMS ข้างต้น |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่มา |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `folios_property_id_source_system_folio_no_key` (property_id, source_system, folio_no)

**Triggers:** `trg_folios_audit`, `trg_folios_updated_at`, `trg_folios_validate`

### 18. `pms_daily_statistics`

สถิติรายวันจาก Monthly Statistics (อัตราเข้าพัก ADR RevPAR) — ใช้แสดงผลและตรวจทานเท่านั้น ไม่นับเป็นรายการบัญชี

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `stat_date` | date | NOT NULL |  |  | วันที่ของสถิติ |
| `is_forecast` | boolean | NOT NULL |  |  | true = วันที่ยังไม่ถึงตอนออกรายงาน (ยอดจองล่วงหน้า ไม่ใช่ยอดจริง) |
| `rooms_available` | integer |  |  |  | ห้องที่ขายได้ |
| `rooms_sold` | numeric(7,2) |  |  |  | ห้อง-คืนที่ขายแล้ว (มีทศนิยมได้ เช่น 0.5 สำหรับ day use — พบในรายงานจริง) |
| `complimentary_rooms` | numeric(7,2) |  |  |  | ห้องฟรี (eZee นับรวมในอัตราเข้าพักและ ADR) |
| `occupancy_pct` | numeric(6,2) |  |  |  | อัตราเข้าพัก (%) ตาม PMS = (ขาย + ห้องฟรี) ÷ ห้องที่ขายได้ |
| `adr` | money_thb (numeric(14,2)) |  |  |  | ราคาห้องเฉลี่ย (ADR) ตาม PMS = ค่าห้อง ÷ (ขาย + ห้องฟรี) |
| `revpar` | money_thb (numeric(14,2)) |  |  |  | รายได้ต่อห้องที่มี (RevPAR) |
| `guests` | integer |  |  |  | จำนวนแขก |
| `room_charges` | money_thb (numeric(14,2)) |  |  |  | ค่าห้องตาม PMS |
| `extra_charges` | money_thb (numeric(14,2)) |  |  |  | ค่าบริการเสริมตาม PMS |
| `tax_amount` | money_thb (numeric(14,2)) |  |  |  | ภาษีตาม PMS |
| `receipts` | money_thb (numeric(14,2)) |  |  |  | เงินรับตาม PMS (ใช้เทียบกับ Receipt Detail) |
| `expenses` | money_thb (numeric(14,2)) |  |  |  | รายจ่ายตาม PMS (ใช้เทียบกับ Expense Voucher) |
| `imported_file_id` | bigint | NOT NULL |  | FK→imported_files | ไฟล์ที่มา (ไฟล์ใหม่กว่าของวันเดียวกันแทนที่ในการแสดงผล) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่มา |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |

**Unique / ดัชนีกันซ้ำ:** `pms_daily_statistics_property_id_stat_date_imported_file_id_key` (property_id, stat_date, imported_file_id)

### 19. `loans`

เงินกู้/ผ่อนชำระ — ใช้คำนวณภาระผูกพันที่ต้องกันเงินไว้

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `lender_name` | text | NOT NULL |  |  | ผู้ให้กู้ |
| `loan_type` | text | NOT NULL |  |  | ประเภทหนี้ |
| `principal_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | เงินต้นตั้งต้น |
| `start_date` | date | NOT NULL |  |  | วันที่เริ่มกู้ |
| `interest_rate_annual` | percent_rate (numeric(7,4)) |  |  |  | ดอกเบี้ยต่อปี (%) ตามสัญญา |
| `monthly_installment` | money_thb (numeric(14,2)) |  |  |  | ค่างวดต่อเดือน |
| `installment_day` | smallint |  |  |  | วันที่ครบกำหนดชำระของทุกเดือน |
| `maturity_date` | date |  |  |  | วันครบกำหนดสัญญา |
| `ledger_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีเงินกู้ (ต้องเป็น LOAN) |
| `status` | text | NOT NULL | 'ACTIVE'::text |  | ACTIVE ยังผ่อนอยู่ / PAID_OFF ปิดแล้ว / CANCELLED บันทึกผิด |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_loans_audit`, `trg_loans_updated_at`, `trg_loans_validate`

### 20. `revenue_entries`

รายได้ที่เกิดขึ้นตามวันที่ให้บริการ (Accrual) — แยกจากเงินรับจริง ผูกกับ folio เสมอ

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `folio_id` | bigint | NOT NULL |  | FK→folios | folio ที่รายได้นี้ถูกเรียกเก็บ |
| `reservation_id` | bigint |  |  | FK→reservations | การจองที่เกี่ยวข้อง |
| `room_id` | bigint |  |  | FK→rooms | ห้องพัก (สำหรับค่าห้องรายคืน) |
| `channel_id` | bigint |  |  | FK→ota_channels | ช่องทางขาย |
| `service_date` | date | NOT NULL |  |  | วันที่เกิดรายได้ (คืนที่เข้าพัก/วันที่ให้บริการ/วันที่ยกเลิก) |
| `revenue_type` | text | NOT NULL |  |  | ประเภทรายได้ (ADJUSTMENT เท่านั้นที่ติดลบได้) |
| `description` | text |  |  |  | รายละเอียด |
| `document_no` | text |  |  |  | เลขที่เอกสาร (ใบแจ้งหนี้/ใบเสร็จของเรา) |
| `discount_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ส่วนลดที่หักไปแล้ว (ข้อมูลประกอบ ยอดสุทธิอยู่ใน amount_excl_tax) |
| `amount_excl_tax` | money_thb (numeric(14,2)) | NOT NULL |  |  | รายได้ก่อนภาษีหลังหักส่วนลด |
| `vat_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ภาษีมูลค่าเพิ่ม (0 ถ้ายังไม่ได้จด VAT ตาม tax_profiles) |
| `service_charge_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ค่าบริการ (Service charge) ถ้ามี |
| `total_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดรวม = ก่อนภาษี + VAT + ค่าบริการ |
| `ledger_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีรายได้ (ต้องเป็นหมวดรายได้) |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `revenue_entries_source_uq` (property_id, source_system, source_ref) WHERE ((source_ref IS NOT NULL) AND (status <> 'VOIDED'::text))

**Triggers:** `trg_revenue_entries_audit`, `trg_revenue_entries_guard`, `trg_revenue_entries_period`, `trg_revenue_entries_updated_at`, `trg_revenue_entries_validate`

### 21. `payments`

เงินรับจริง (และการคืนเงินลูกค้า) ตามวันที่รับเงิน — ไม่ถือว่าเท่ากับรายได้หรือยอดเข้าธนาคารโดยอัตโนมัติ

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `receipt_type` | text | NOT NULL |  |  | GUEST_PAYMENT ลูกค้าจ่าย / OTA_PAYOUT OTA โอนให้ / OTHER_INCOME_RECEIPT รายได้อื่น / GUEST_REFUND คืนเงิน / OWNER_CONTRIBUTION เจ้าของนำเงินเข้า / LOAN_PROCEEDS รับเงินกู้ / OTHER_RECEIPT อื่นๆ |
| `direction` | text |  | generated |  | IN เงินเข้า / OUT เงินออก (คำนวณจาก receipt_type) |
| `received_date` | date | NOT NULL |  |  | วันที่รับเงินจริง (หรือวันที่คืนเงิน) |
| `receipt_no` | text |  |  |  | เลขที่ใบเสร็จ (จาก PMS หรือของเรา) |
| `payer_name` | text |  |  |  | ชื่อผู้จ่าย |
| `channel_id` | bigint |  |  | FK→ota_channels | ช่องทางขาย (บังคับเมื่อเป็น OTA_PAYOUT) |
| `loan_id` | bigint |  |  | FK→loans | เงินกู้ที่รับ (บังคับเมื่อเป็น LOAN_PROCEEDS) |
| `method` | text | NOT NULL |  |  | ช่องทางชำระ: เงินสด โอน พร้อมเพย์ บัตร OTA ฯลฯ |
| `bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีเงินที่เงินเข้า (ลิ้นชักเงินสดก็เป็นบัญชีเงินชนิด CASH) |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงินที่ลูกค้าจ่าย (บวกเสมอ ทิศทางดูจาก direction) |
| `fee_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ค่าธรรมเนียมที่ถูกหัก (บัตร/OTA payout) — เงินเข้าบัญชีจริง = amount − fee_amount |
| `external_ref` | text |  |  |  | เลขอ้างอิงธนาคาร/รหัสอนุมัติบัตร/รหัส payout |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `payments_source_uq` (property_id, source_system, source_ref) WHERE ((source_ref IS NOT NULL) AND (status <> 'VOIDED'::text))

**Triggers:** `trg_payments_audit`, `trg_payments_guard`, `trg_payments_period`, `trg_payments_updated_at`, `trg_payments_validate`

### 22. `payment_allocations`

การจับคู่เงินรับกับ folio: เงินรับ 1 รายการจ่ายได้หลาย folio และ folio 1 ใบรับเงินได้หลายรายการ

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `payment_id` | bigint | NOT NULL |  | FK→payments | เงินรับ |
| `folio_id` | bigint | NOT NULL |  | FK→folios | folio ที่ได้รับชำระ |
| `allocation_date` | date | NOT NULL |  |  | วันที่จับคู่ (ใช้ล็อกงวด) |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงินที่จัดสรร (รวมทุกแถวของเงินรับหนึ่งรายการต้องไม่เกินยอดเงินรับ) |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `payment_allocations_active_uq` (payment_id, folio_id) WHERE (voided_at IS NULL)

**Triggers:** `trg_payment_allocations_audit`, `trg_payment_allocations_guard`, `trg_payment_allocations_period`

### 23. `expense_categories`

หมวดรายจ่าย — treatment กำหนดว่ารายการเข้างบกำไรขาดทุนหรือไม่ (ซื้อสินทรัพย์ ถอนเงินเจ้าของ ชำระหนี้ ไม่ใช่ค่าใช้จ่าย)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสหมวด (PK) |
| `code` | text | NOT NULL |  |  | รหัสหมวด เช่น SALARY, ELECTRICITY |
| `name_th` | text | NOT NULL |  |  | ชื่อหมวดภาษาไทย |
| `name_en` | text | NOT NULL |  |  | ชื่อหมวดภาษาอังกฤษ |
| `treatment` | text | NOT NULL |  |  | วิธีบันทึกบัญชี: OPERATING_EXPENSE ค่าใช้จ่าย / CAPITAL_EXPENDITURE ซื้อสินทรัพย์ / OWNER_DRAWING ถอนใช้ส่วนตัว / LOAN_PRINCIPAL ชำระเงินต้น / TAX_REMITTANCE นำส่งภาษีที่เก็บไว้ / OTA_COMMISSION_SETTLEMENT จ่ายหนี้ค่าคอม OTA ฯลฯ |
| `ledger_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีแยกประเภทที่ลง (ต้องสอดคล้องกับ treatment) |
| `pms_charge_names` | text[] | NOT NULL | '{}'::text[] |  | ชื่อ Charge ใน Expense Voucher ของ PMS ที่จับคู่มาหมวดนี้ |
| `needs_accountant_review` | boolean | NOT NULL | false |  | หมวดที่ต้องให้นักบัญชียืนยันวิธีบันทึก (เช่น ภาษีเงินได้เจ้าของ) |
| `is_active` | boolean | NOT NULL | true |  | ยังใช้งานอยู่ |
| `sort_order` | integer | NOT NULL | 0 |  | ลำดับการแสดงผล |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `expense_categories_code_key` (code)

**Triggers:** `trg_expense_categories_audit`, `trg_expense_categories_updated_at`, `trg_expense_categories_validate`

### 24. `expenses`

รายการจ่าย/ค่าใช้จ่าย ตามวันที่เกิด (ใบแจ้งหนี้/ใบเสร็จ) — การจ่ายเงินจริงอยู่ที่ expense_payments

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `expense_date` | date | NOT NULL |  |  | วันที่เกิดค่าใช้จ่าย (วันที่ในเอกสารผู้ขาย) |
| `due_date` | date |  |  |  | วันครบกำหนดจ่าย |
| `document_no` | text |  |  |  | เลขที่ใบเสร็จ/ใบกำกับภาษีของผู้ขาย |
| `internal_voucher_no` | text |  |  |  | เลขที่ใบสำคัญจ่ายของเรา (ไม่ซ้ำ) |
| `vendor_name` | text |  |  |  | ชื่อผู้ขาย/ผู้รับเงิน |
| `vendor_tax_id` | text |  |  |  | เลขผู้เสียภาษีผู้ขาย 13 หลัก |
| `category_id` | bigint | NOT NULL |  | FK→expense_categories | หมวดรายจ่าย (กำหนดการลงบัญชี) |
| `description` | text | NOT NULL |  |  | รายละเอียด |
| `amount_excl_vat` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดก่อน VAT |
| `vat_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | VAT ตามใบกำกับ |
| `total_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดรวม = ก่อน VAT + VAT |
| `wht_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ภาษีหัก ณ ที่จ่ายที่เราหักไว้ (จ่ายผู้ขายสุทธิ = total − wht) |
| `vat_claimable` | boolean | NOT NULL | false |  | ขอคืนภาษีซื้อได้ (เฉพาะเมื่อจด VAT และมีใบกำกับเต็มรูป — นักบัญชียืนยัน) |
| `service_period_start` | date |  |  |  | ช่วงที่ใช้บริการ เริ่ม (เช่น รอบบิลค่าไฟ) |
| `service_period_end` | date |  |  |  | ช่วงที่ใช้บริการ สิ้นสุด |
| `loan_id` | bigint |  |  | FK→loans | เงินกู้ (บังคับเมื่อหมวดเป็น LOAN_PRINCIPAL/INTEREST_EXPENSE ของเงินกู้) |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `expenses_source_uq` (property_id, source_system, source_ref) WHERE ((source_ref IS NOT NULL) AND (status <> 'VOIDED'::text)); `expenses_voucher_no_uq` (property_id, internal_voucher_no) WHERE ((internal_voucher_no IS NOT NULL) AND (status <> 'VOIDED'::text))

**Triggers:** `trg_expenses_audit`, `trg_expenses_guard`, `trg_expenses_period`, `trg_expenses_updated_at`, `trg_expenses_validate`

### 25. `expense_payments`

การจ่ายเงินจริงของรายจ่าย (จ่ายบางส่วน/หลายครั้งได้) — ยอดค้างจ่าย = total − wht − ที่จ่ายแล้ว

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `expense_id` | bigint | NOT NULL |  | FK→expenses | รายจ่ายที่ชำระ |
| `paid_date` | date | NOT NULL |  |  | วันที่จ่ายเงินจริง |
| `bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีเงินที่จ่ายออก |
| `method` | text | NOT NULL |  |  | ช่องทางจ่าย |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงินที่จ่าย |
| `external_ref` | text |  |  |  | เลขอ้างอิงการโอน/เช็ค |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `expense_payments_source_uq` (property_id, source_system, source_ref) WHERE ((source_ref IS NOT NULL) AND (status <> 'VOIDED'::text))

**Triggers:** `trg_expense_payments_audit`, `trg_expense_payments_guard`, `trg_expense_payments_period`, `trg_expense_payments_updated_at`, `trg_expense_payments_validate`

### 26. `fund_transfers`

โอนเงินระหว่างบัญชีของกิจการเอง (ฝากเงินสด กันเงินเข้ากองทุน) — ไม่ใช่รายได้หรือค่าใช้จ่าย

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `transfer_date` | date | NOT NULL |  |  | วันที่โอน |
| `from_bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีต้นทาง |
| `to_bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีปลายทาง (ต้องไม่ใช่บัญชีเดียวกัน) |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงินที่เข้าบัญชีปลายทาง |
| `fee_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ค่าธรรมเนียมโอน (ลงเป็นค่าธรรมเนียมธนาคาร) |
| `purpose` | text | NOT NULL |  |  | วัตถุประสงค์ของการโอน |
| `reference` | text |  |  |  | เลขอ้างอิงการโอน |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Triggers:** `trg_fund_transfers_audit`, `trg_fund_transfers_guard`, `trg_fund_transfers_period`, `trg_fund_transfers_updated_at`, `trg_fund_transfers_validate`

### 27. `ota_commissions`

ค่าคอมมิชชัน OTA — เป็นค่าใช้จ่ายครั้งเดียวที่นี่ การจ่ายใบแจ้งหนี้ OTA เป็นการล้างหนี้ (OTA_COMMISSION_SETTLEMENT) ไม่ใช่ค่าใช้จ่ายซ้ำ

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `channel_id` | bigint | NOT NULL |  | FK→ota_channels | OTA |
| `reservation_id` | bigint |  |  | FK→reservations | การจองที่เกิดค่าคอม |
| `folio_id` | bigint |  |  | FK→folios | folio ที่เกี่ยวข้อง (ถ้าถูกหักจาก payout จะลดยอดลูกหนี้ของ folio นี้) |
| `ota_booking_ref` | text |  |  |  | เลขการจองของ OTA |
| `recognition_date` | date | NOT NULL |  |  | วันที่รับรู้ค่าใช้จ่าย (วันที่ของรายได้ที่เกี่ยวข้อง) |
| `commissionable_amount` | money_thb (numeric(14,2)) |  |  |  | ฐานที่ใช้คิดค่าคอม |
| `commission_rate` | percent_rate (numeric(7,4)) |  |  |  | อัตราที่ใช้คิด (%) |
| `commission_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | ค่าคอมมิชชัน |
| `vat_on_commission` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | VAT ของค่าคอม (ถ้า OTA เรียกเก็บ — นักบัญชียืนยันการบันทึก) |
| `basis` | text | NOT NULL |  |  | ESTIMATED_FROM_RATE ประมาณจากอัตรา (ทำให้งวดเป็น Preliminary) / OTA_STATEMENT / OTA_INVOICE ตัวเลขจริง |
| `settlement_method` | text | NOT NULL |  |  | วิธีชำระค่าคอม: หักจาก payout / OTA ส่งใบแจ้งหนี้ / ตัดบัตร / ยังไม่ทราบ |
| `payout_payment_id` | bigint |  |  | FK→payments | payout ที่ถูกหักค่าคอมนี้ |
| `settlement_expense_id` | bigint |  |  | FK→expenses | รายการจ่ายที่ล้างหนี้ค่าคอมนี้ |
| `ota_invoice_no` | text |  |  |  | เลขที่ใบแจ้งหนี้ของ OTA |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `source_system` | text | NOT NULL |  |  | ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM |
| `source_ref` | text |  |  |  | เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system) |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ) |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `ota_commissions_source_uq` (property_id, source_system, source_ref) WHERE ((source_ref IS NOT NULL) AND (status <> 'VOIDED'::text))

**Triggers:** `trg_ota_commissions_audit`, `trg_ota_commissions_guard`, `trg_ota_commissions_period`, `trg_ota_commissions_updated_at`, `trg_ota_commissions_validate`

### 28. `fixed_assets`

ทะเบียนทรัพย์สิน — คิดค่าเสื่อมราคาเฉพาะเมื่อนักบัญชียืนยันอายุการใช้งาน (policy_confirmed)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `asset_name` | text | NOT NULL |  |  | ชื่อทรัพย์สิน |
| `asset_class` | text | NOT NULL |  |  | ประเภททรัพย์สิน |
| `acquisition_date` | date | NOT NULL |  |  | วันที่ได้มา |
| `acquisition_cost` | money_thb (numeric(14,2)) | NOT NULL |  |  | ราคาทุน |
| `salvage_value` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | มูลค่าซาก |
| `useful_life_months` | integer |  |  |  | อายุการใช้งาน (เดือน) — NULL = ยังไม่กำหนด ไม่คิดค่าเสื่อม |
| `depreciation_start_date` | date |  |  |  | วันเริ่มคิดค่าเสื่อม |
| `source_expense_id` | bigint |  |  | FK→expenses | รายการจ่ายที่ซื้อทรัพย์สินนี้ (หมวด CAPITAL_EXPENDITURE) |
| `asset_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีสินทรัพย์ (FIXED_ASSET) |
| `accumulated_depreciation_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีค่าเสื่อมราคาสะสม |
| `depreciation_expense_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีค่าเสื่อมราคา (ค่าใช้จ่าย) |
| `policy_confirmed` | boolean | NOT NULL | false |  | นักบัญชียืนยันนโยบายค่าเสื่อมแล้ว |
| `status` | text | NOT NULL | 'ACTIVE'::text |  | ACTIVE ใช้งาน / DISPOSED ขายหรือทิ้งแล้ว / CANCELLED บันทึกผิด |
| `disposed_on` | date |  |  |  | วันที่จำหน่าย |
| `disposal_note` | text |  |  |  | หมายเหตุการจำหน่าย |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_fixed_assets_audit`, `trg_fixed_assets_updated_at`, `trg_fixed_assets_validate`

### 29. `balance_confirmations`

ยอดเงินที่ตรวจสอบแล้ว ณ วันที่ (ยอดปลาย statement หรือการนับเงินสด) — ใช้แสดง "เงินสดและเงินฝากที่ตรวจสอบแล้ว"

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีเงิน |
| `as_of_date` | date | NOT NULL |  |  | ยอด ณ สิ้นวันที่ |
| `confirmed_balance` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดคงเหลือที่ยืนยัน (ตามธนาคาร/ตามที่นับได้) |
| `method` | text | NOT NULL |  |  | BANK_STATEMENT จาก statement / CASH_COUNT นับเงินสด / BANK_PORTAL ดูจากแอปธนาคารพร้อมหลักฐาน |
| `imported_file_id` | bigint |  |  | FK→imported_files | statement ที่ใช้ยืนยัน |
| `counted_by` | bigint |  |  | FK→users | ผู้นับเงินสด |
| `witnessed_by` | bigint |  |  | FK→users | พยานการนับ |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Unique / ดัชนีกันซ้ำ:** `balance_confirmations_day_uq` (bank_account_id, as_of_date) WHERE (status <> 'VOIDED'::text)

**Triggers:** `trg_balance_confirmations_audit`, `trg_balance_confirmations_guard`, `trg_balance_confirmations_period`, `trg_balance_confirmations_updated_at`, `trg_balance_confirmations_validate`

### 30. `journal_entries`

สมุดรายวันทั่วไป (บัญชีคู่) — บันทึกเพิ่มได้อย่างเดียว แก้ไขด้วยรายการกลับ (REVERSAL)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `entry_no` | text | NOT NULL |  |  | เลขที่รายการ เช่น JV-2026-10-0001 (ไม่ซ้ำในกิจการ) |
| `entry_date` | date | NOT NULL |  |  | วันที่ลงบัญชี (ใช้จัดงวด) |
| `description` | text | NOT NULL |  |  | คำอธิบายรายการ |
| `source_type` | text | NOT NULL |  |  | เอกสารต้นทางที่สร้างรายการนี้ |
| `source_id` | bigint |  |  |  | id ของเอกสารต้นทาง |
| `reversal_of_id` | bigint |  |  | FK→journal_entries | รายการเดิมที่ถูกกลับรายการ (กลับได้ครั้งเดียว) |
| `posted_by` | bigint |  |  | FK→users | ผู้ลงบัญชี (NULL = ระบบ) |
| `posted_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่ลงบัญชี |

**Unique / ดัชนีกันซ้ำ:** `journal_entries_property_id_entry_no_key` (property_id, entry_no); `journal_entries_reversal_of_id_key` (reversal_of_id)

**Triggers:** `trg_journal_entries_append_only`, `trg_journal_entries_audit`, `trg_journal_entries_balanced`, `trg_journal_entries_period`

### 31. `journal_lines`

บรรทัดเดบิต/เครดิตของรายการบัญชี — ทุกรายการต้องมียอดเดบิตเท่ากับเครดิต

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสบรรทัด (PK) |
| `journal_entry_id` | bigint | NOT NULL |  | FK→journal_entries | รายการบัญชี |
| `line_no` | smallint | NOT NULL |  |  | ลำดับบรรทัด |
| `ledger_account_id` | bigint | NOT NULL |  | FK→ledger_accounts | บัญชีแยกประเภท |
| `debit` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | เดบิต |
| `credit` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | เครดิต |
| `memo` | text |  |  |  | คำอธิบายบรรทัด |
| `bank_account_id` | bigint |  |  | FK→bank_accounts | บัญชีเงินที่เกี่ยวข้อง (บังคับเมื่อบัญชีเป็นเงินสด/ธนาคาร เพื่อหายอดคงเหลือรายบัญชี) |
| `folio_id` | bigint |  |  | FK→folios | มิติ folio (ลูกหนี้/เงินรับล่วงหน้า) |
| `channel_id` | bigint |  |  | FK→ota_channels | มิติช่องทางขาย |

**Unique / ดัชนีกันซ้ำ:** `journal_lines_journal_entry_id_line_no_key` (journal_entry_id, line_no)

**Triggers:** `trg_journal_lines_append_only`, `trg_journal_lines_balanced`, `trg_journal_lines_validate`

### 32. `bank_transactions`

รายการเดินบัญชีจาก statement ธนาคาร (ข้อมูลของธนาคาร แก้ไม่ได้ ยกเว้นสถานะการตรวจ)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `bank_account_id` | bigint | NOT NULL |  | FK→bank_accounts | บัญชีธนาคาร |
| `txn_date` | date | NOT NULL |  |  | วันที่ทำรายการตาม statement |
| `txn_time` | time without time zone |  |  |  | เวลาทำรายการ (ถ้ามี) |
| `value_date` | date |  |  |  | วันที่มีผล (ถ้าต่างจากวันทำรายการ) |
| `description` | text |  |  |  | คำอธิบายจากธนาคาร |
| `bank_channel` | text |  |  |  | ช่องทาง เช่น K PLUS, ATM, EDC |
| `counterparty` | text |  |  |  | ชื่อ/บัญชีคู่โอน (ถ้ามี) |
| `bank_reference` | text |  |  |  | เลขอ้างอิงของธนาคาร |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงิน: บวก = เงินเข้า, ลบ = เงินออก |
| `balance_after` | money_thb (numeric(14,2)) |  |  |  | ยอดคงเหลือหลังรายการตาม statement (ใช้ตรวจความต่อเนื่อง) |
| `dedupe_key` | text | NOT NULL |  |  | กุญแจกันซ้ำที่ importer คำนวณ (วันที่ เวลา ยอด ยอดคงเหลือ อ้างอิง ลำดับในวัน) |
| `imported_file_id` | bigint |  |  | FK→imported_files | statement ที่นำเข้า |
| `import_row_id` | bigint |  |  | FK→import_rows | แถวข้อมูลดิบที่มา |
| `review_status` | text | NOT NULL | 'OPEN'::text |  | OPEN ปกติ / NEEDS_REVIEW ต้องให้คนดู / IGNORED ไม่ต้องจับคู่ (ต้องมีเหตุผล เช่น ดอกเบี้ยที่บันทึกรวมแล้ว) |
| `review_note` | text |  |  |  | บันทึกการตรวจ |
| `reviewed_by` | bigint |  |  | FK→users | ผู้ตรวจ |
| `reviewed_at` | timestamp with time zone |  |  |  | เวลาที่ตรวจ |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่นำเข้า |

**Unique / ดัชนีกันซ้ำ:** `bank_transactions_bank_account_id_dedupe_key_key` (bank_account_id, dedupe_key)

**Triggers:** `trg_bank_transactions_audit`, `trg_bank_transactions_guard`, `trg_bank_transactions_period`

### 33. `reconciliations`

ชุดการจับคู่ระหว่างรายการธนาคารกับรายการในระบบ (ระบบเสนอได้ แต่คนต้องยืนยัน)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสชุดจับคู่ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `match_method` | text | NOT NULL |  |  | AUTO_EXACT ยอด+วันที่+อ้างอิงตรง / AUTO_RULE ตามกฎ / MANUAL คนจับคู่เอง |
| `rule_code` | text |  |  |  | กฎที่ใช้เสนอ (บังคับเมื่อเป็น AUTO) |
| `confidence` | numeric(5,4) |  |  |  | ความมั่นใจของการเสนอ 0–1 |
| `status` | text | NOT NULL | 'PROPOSED'::text |  | PROPOSED เสนอ / CONFIRMED ยืนยัน / REJECTED ปฏิเสธ / REVERSED ยกเลิกหลังยืนยัน |
| `difference_amount` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ผลต่าง = ยอดฝั่งธนาคาร − ยอดฝั่งบัญชี (ไม่ใช่ 0 ต้องมีเหตุผล ระบบไม่สร้างรายการปรับให้เอง) |
| `difference_reason` | text |  |  |  | เหตุผลของผลต่าง |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้สร้าง (NULL = ระบบเสนอ) |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `confirmed_by` | bigint |  |  | FK→users | ผู้ยืนยัน |
| `confirmed_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `rejected_by` | bigint |  |  | FK→users | ผู้ปฏิเสธ |
| `rejected_at` | timestamp with time zone |  |  |  | เวลาที่ปฏิเสธ |
| `reversed_by` | bigint |  |  | FK→users | ผู้ยกเลิกการจับคู่ |
| `reversed_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิกการจับคู่ |
| `reverse_reason` | text |  |  |  | เหตุผลที่ยกเลิกการจับคู่ (บังคับ) |

**Triggers:** `trg_reconciliations_audit`, `trg_reconciliations_guard`, `trg_reconciliations_updated_at`

### 34. `reconciliation_items`

รายการในชุดจับคู่ (ฝั่งธนาคาร/ฝั่งบัญชี) และยอดที่ใช้ — รายการเดียวถูกจับคู่บางส่วนได้หลายชุด แต่รวมแล้วต้องไม่เกินยอดจริง

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `reconciliation_id` | bigint | NOT NULL |  | FK→reconciliations | ชุดจับคู่ |
| `side` | text | NOT NULL |  |  | BANK รายการธนาคาร / BOOK รายการในระบบ |
| `bank_transaction_id` | bigint |  |  | FK→bank_transactions | รายการเดินบัญชี (ฝั่ง BANK) |
| `payment_id` | bigint |  |  | FK→payments | เงินรับ/คืนเงิน (ฝั่ง BOOK) |
| `expense_payment_id` | bigint |  |  | FK→expense_payments | การจ่ายเงิน (ฝั่ง BOOK) |
| `fund_transfer_id` | bigint |  |  | FK→fund_transfers | การโอนระหว่างบัญชี (ฝั่ง BOOK) |
| `transfer_leg` | text |  |  |  | ขาโอนออก/โอนเข้า (บังคับเมื่อเป็น fund_transfer) |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดที่ใช้ในชุดนี้ (บวกเสมอ) |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่เพิ่ม |

**Triggers:** `trg_reconciliation_items_audit`, `trg_reconciliation_items_guard`

### 35. `tax_profiles`

สถานะทางภาษีของกิจการตามช่วงเวลา — ค่าเริ่มต้น UNKNOWN จนกว่านักบัญชียืนยัน

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `effective_from` | date | NOT NULL |  |  | มีผลตั้งแต่ |
| `effective_to` | date |  |  |  | มีผลถึง (NULL = ปัจจุบัน) ห้ามซ้อนกัน |
| `entity_type` | text | NOT NULL | 'UNKNOWN'::text |  | รูปแบบกิจการ: บุคคลธรรมดา / ห้างหุ้นส่วนสามัญ / ห้างหุ้นส่วนจดทะเบียน / บริษัท / ยังไม่ทราบ |
| `vat_status` | text | NOT NULL | 'UNKNOWN'::text |  | สถานะจดทะเบียนภาษีมูลค่าเพิ่ม |
| `vat_registered_on` | date |  |  |  | วันที่จด VAT |
| `room_prices_include_vat` | boolean |  |  |  | ราคาห้องรวม VAT แล้วหรือไม่ |
| `hotel_license_status` | text | NOT NULL | 'UNKNOWN'::text |  | สถานะใบอนุญาตประกอบธุรกิจโรงแรม |
| `income_tax_method` | text | NOT NULL | 'UNKNOWN'::text |  | วิธีคำนวณภาษีเงินได้ที่นักบัญชีเลือก |
| `accountant_name` | text |  |  |  | ชื่อนักบัญชีผู้ยืนยัน |
| `confirmed_by_accountant` | boolean | NOT NULL | false |  | นักบัญชียืนยันข้อมูลชุดนี้แล้ว |
| `confirmed_on` | date |  |  |  | วันที่ยืนยัน |
| `confirmation_note` | text |  |  |  | บันทึกการยืนยัน/เอกสารอ้างอิง |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_tax_profiles_audit`, `trg_tax_profiles_updated_at`, `trg_tax_profiles_validate`

### 36. `tax_rules`

สูตร/อัตราภาษีที่ผู้ใช้ตั้งค่าตามที่นักบัญชีแจ้ง (ระบบไม่กำหนดอัตราเอง)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `tax_type` | text | NOT NULL |  |  | ประเภทภาษี/ภาระ |
| `name_th` | text | NOT NULL |  |  | ชื่อที่แสดง |
| `calc_method` | text | NOT NULL |  |  | วิธีคำนวณ: % ของรายได้ / % ของกำไร / จำนวนคงที่ / ตารางขั้นบันได / กรอกเอง |
| `rate` | percent_rate (numeric(7,4)) |  |  |  | อัตรา (%) |
| `fixed_amount` | money_thb (numeric(14,2)) |  |  |  | จำนวนคงที่ |
| `brackets` | jsonb |  |  |  | ตารางขั้นบันได [{from, to, rate}] ตามที่นักบัญชีให้ |
| `filing_frequency` | text |  |  |  | ความถี่การยื่น |
| `filing_form` | text |  |  |  | แบบที่ใช้ยื่น (ข้อความตามที่นักบัญชีแจ้ง) |
| `due_rule_note` | text |  |  |  | กำหนดยื่น/ชำระ (ข้อความ) |
| `effective_from` | date | NOT NULL |  |  | มีผลตั้งแต่ |
| `effective_to` | date |  |  |  | มีผลถึง |
| `source_reference` | text | NOT NULL |  |  | ที่มาของสูตร (บังคับ) เช่น ชื่อนักบัญชี/เอกสารกรมสรรพากร |
| `confirmed_by_accountant` | boolean | NOT NULL | false |  | นักบัญชียืนยันแล้ว |
| `confirmed_on` | date |  |  |  | วันที่ยืนยัน |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_tax_rules_audit`, `trg_tax_rules_updated_at`

### 37. `tax_estimates`

ประมาณการภาษีที่ควรกันเงินไว้ — ไม่ใช่ยอดภาษีที่ต้องชำระจริงจนกว่าจะยื่นแบบ (FILED)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `tax_rule_id` | bigint |  |  | FK→tax_rules | สูตรที่ใช้ประมาณ |
| `tax_type` | text | NOT NULL |  |  | ประเภทภาษี |
| `period_start` | date | NOT NULL |  |  | งวดภาษี เริ่ม |
| `period_end` | date | NOT NULL |  |  | งวดภาษี สิ้นสุด |
| `basis_amount` | money_thb (numeric(14,2)) |  |  |  | ฐานที่ใช้คำนวณ (จากข้อมูลที่ยืนยันแล้วเท่านั้น) |
| `basis_description` | text | NOT NULL |  |  | คำอธิบายฐาน เช่น "รายได้ห้องพัก VERIFIED ม.ค.–มิ.ย. 2569" |
| `estimated_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | ยอดประมาณการที่ควรกันไว้ |
| `estimate_status` | text | NOT NULL | 'SYSTEM_ESTIMATE'::text |  | SYSTEM_ESTIMATE ระบบประมาณ / ACCOUNTANT_REVIEWED นักบัญชีตรวจ / FILED ยื่นแล้ว / PAID ชำระแล้ว / CANCELLED |
| `data_completeness` | text | NOT NULL |  |  | ความครบถ้วนของข้อมูลฐาน ณ ตอนประมาณ |
| `due_date` | date |  |  |  | กำหนดยื่น/ชำระ |
| `reviewed_by_accountant_name` | text |  |  |  | นักบัญชีผู้ตรวจ |
| `reviewed_on` | date |  |  |  | วันที่ตรวจ |
| `filed_amount` | money_thb (numeric(14,2)) |  |  |  | ยอดตามแบบที่ยื่นจริง |
| `filed_on` | date |  |  |  | วันที่ยื่น |
| `paid_amount` | money_thb (numeric(14,2)) |  |  |  | ยอดที่ชำระจริง |
| `payment_expense_id` | bigint |  |  | FK→expenses | รายการจ่ายที่ชำระภาษีนี้ |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้สร้าง |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_tax_estimates_audit`, `trg_tax_estimates_guard`, `trg_tax_estimates_updated_at`

### 38. `capital_goals`

เป้าหมายเงินทุน เช่น ทุนเปิดร้านสะดวกซื้อ 2,000,000 บาท (เก็บเป็นข้อมูล ไม่ฝังในโค้ด)

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสเป้าหมาย (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการที่เป็นแหล่งเงิน (รีสอร์ต) |
| `name` | text | NOT NULL |  |  | ชื่อเป้าหมาย |
| `target_amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | เงินทุนเป้าหมาย |
| `start_date` | date | NOT NULL |  |  | วันเริ่มเก็บ |
| `target_date` | date |  |  |  | วันที่อยากเปิดร้าน (ถ้ามี) |
| `status` | text | NOT NULL | 'ACTIVE'::text |  | ACTIVE / ACHIEVED / PAUSED / CANCELLED |
| `fund_bank_account_id` | bigint |  |  | FK→bank_accounts | บัญชีที่แยกเก็บกองทุน (แนะนำให้แยก) — NULL = กันไว้ในบัญชีรวม |
| `operating_reserve_months` | numeric(4,1) |  |  |  | นโยบายเงินสำรองรีสอร์ต: กี่เดือนของค่าใช้จ่ายดำเนินงานเฉลี่ย (เจ้าของกำหนด) |
| `operating_reserve_min_amount` | money_thb (numeric(14,2)) |  |  |  | เงินสำรองขั้นต่ำเป็นจำนวนเงิน |
| `obligation_horizon_months` | smallint | NOT NULL | 3 |  | จำนวนเดือนข้างหน้าของค่างวดหนี้ที่ต้องกันไว้ |
| `policy_confirmed_by_owner` | boolean | NOT NULL | false |  | เจ้าของยืนยันนโยบายเงินสำรองแล้ว |
| `created_by` | bigint |  |  | FK→users | ผู้สร้าง |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Triggers:** `trg_capital_goals_audit`, `trg_capital_goals_updated_at`

### 39. `capital_scenarios`

สมมติฐานการวางแผน (Conservative / Base / Optimistic) — ใช้เงินส่วนเกินหลังหักภาระ ไม่ใช้รายได้รวม

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัส (PK) |
| `goal_id` | bigint | NOT NULL |  | FK→capital_goals | เป้าหมาย |
| `scenario_type` | text | NOT NULL |  |  | ประเภทสถานการณ์ |
| `name` | text | NOT NULL |  |  | ชื่อที่แสดง |
| `lookback_months` | smallint | NOT NULL | 6 |  | ใช้ผลจริงย้อนหลังกี่เดือน (เฉพาะงวดที่ปิดแล้ว) เป็นฐาน |
| `revenue_change_pct` | numeric(6,2) | NOT NULL | 0 |  | ปรับรายได้จากฐานกี่ % (สมมติฐาน) |
| `expense_change_pct` | numeric(6,2) | NOT NULL | 0 |  | ปรับค่าใช้จ่ายจากฐานกี่ % (สมมติฐาน) |
| `allocation_pct` | percent_rate (numeric(7,4)) | NOT NULL |  |  | สัดส่วนของเงินส่วนเกินต่อเดือนที่จะโอนเข้ากองทุน (%) |
| `extra_monthly_obligations` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | ภาระรายเดือนอื่นที่ยังไม่มีในระบบ |
| `owner_monthly_drawings` | money_thb (numeric(14,2)) | NOT NULL | 0 |  | เงินที่เจ้าของถอนใช้ส่วนตัวต่อเดือน |
| `fallback_annual_revenue` | money_thb (numeric(14,2)) |  |  |  | รายได้ต่อปีที่เจ้าของประมาณ (เช่น 1,900,000) ใช้เฉพาะเมื่อยังไม่มีงวดที่ปิดแล้ว ผลลัพธ์ต้องติดป้าย "ยังไม่ตรวจสอบ" |
| `fallback_monthly_operating_expense` | money_thb (numeric(14,2)) |  |  |  | ค่าใช้จ่ายต่อเดือนที่เจ้าของประมาณ (ต้องกรอกคู่กับรายได้ประมาณการ) |
| `notes` | text |  |  |  | หมายเหตุสมมติฐาน |
| `created_by` | bigint |  |  | FK→users | ผู้สร้าง |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่สร้าง |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |

**Unique / ดัชนีกันซ้ำ:** `capital_scenarios_type_uq` (goal_id, scenario_type) WHERE (scenario_type <> 'CUSTOM'::text)

**Triggers:** `trg_capital_scenarios_audit`, `trg_capital_scenarios_updated_at`

### 40. `capital_fund_transactions`

ความเคลื่อนไหวของกองทุนเปิดร้าน — นับเป็น "เงินทุนสะสมแล้ว" เฉพาะรายการ VERIFIED

| คอลัมน์ | ชนิดข้อมูล | Null | ค่าเริ่มต้น | คีย์ | ความหมาย |
|---|---|---|---|---|---|
| `id` | bigint | NOT NULL | identity | PK | รหัสรายการ (PK) |
| `property_id` | bigint | NOT NULL |  | FK→properties | กิจการ |
| `goal_id` | bigint | NOT NULL |  | FK→capital_goals | เป้าหมาย |
| `txn_date` | date | NOT NULL |  |  | วันที่ |
| `txn_type` | text | NOT NULL |  |  | OPENING_BALANCE ยอดตั้งต้น / ALLOCATION กันเงินเพิ่ม / WITHDRAWAL ถอนออก / INTEREST ดอกเบี้ย / ADJUSTMENT ปรับปรุง (ต้องมีเหตุผล) |
| `amount` | money_thb (numeric(14,2)) | NOT NULL |  |  | จำนวนเงิน: บวก = เข้ากองทุน, ลบ = ออกจากกองทุน |
| `fund_transfer_id` | bigint |  |  | FK→fund_transfers | การโอนเงินจริงที่เป็นหลักฐาน (ถ้าแยกบัญชี) |
| `bank_transaction_id` | bigint |  |  | FK→bank_transactions | รายการธนาคารที่เป็นหลักฐาน |
| `status` | text | NOT NULL | 'DRAFT'::text |  | สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล |
| `notes` | text |  |  |  | หมายเหตุ |
| `created_by` | bigint |  |  | FK→users | ผู้บันทึก |
| `created_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่บันทึก |
| `updated_at` | timestamp with time zone | NOT NULL | now() |  | เวลาที่แก้ไขล่าสุด |
| `verified_by` | bigint |  |  | FK→users | ผู้ตรวจสอบ/ยืนยัน |
| `verified_at` | timestamp with time zone |  |  |  | เวลาที่ยืนยัน |
| `voided_by` | bigint |  |  | FK→users | ผู้ยกเลิก |
| `voided_at` | timestamp with time zone |  |  |  | เวลาที่ยกเลิก |
| `void_reason` | text |  |  |  | เหตุผลการยกเลิก (บังคับ) |

**Triggers:** `trg_capital_fund_transactions_audit`, `trg_capital_fund_transactions_guard`, `trg_capital_fund_transactions_period`, `trg_capital_fund_transactions_updated_at`, `trg_capital_fund_transactions_validate`


## Views (ตัวเลขที่คำนวณจากข้อมูลที่ยืนยันแล้ว)

- `v_bank_transaction_status` — สถานะจับคู่ของรายการธนาคาร: MATCHED / PARTIALLY_MATCHED / UNMATCHED / NEEDS_REVIEW / IGNORED
- `v_book_reconciliation_status` — สถานะจับคู่ฝั่งบัญชี (เงินรับ/การจ่าย ที่ยืนยันแล้ว) กับรายการธนาคาร — บัญชีเงินสดไม่ต้องจับคู่ (ใช้การนับเงินแทน)
- `v_capital_goal_progress` — เงินทุนสะสมแล้ว (เฉพาะรายการ VERIFIED) และจำนวนที่ยังขาดจากเป้าหมาย
- `v_expense_payment_status` — สถานะการจ่ายของรายจ่าย: UNPAID / PARTIALLY_PAID / PAID และยอดค้างจ่าย
- `v_folio_balances` — ยอดต่อ folio จากรายการที่ยืนยันแล้ว: balance > 0 ลูกหนี้ค้างชำระ, < 0 เงินรับล่วงหน้า, difference_vs_pms ผลต่างกับ PMS
- `v_payment_allocation_status` — เงินรับแต่ละรายการจัดสรรเข้า folio แล้วเท่าไร (ส่วนที่ยังไม่จัดสรร = เงินรับล่วงหน้า/รอระบุ)
- `v_period_completeness` — ความครบถ้วนของข้อมูลรายเดือนและป้าย FINAL / PRELIMINARY / INCOMPLETE ที่ทุกรายงานต้องแสดง
- `v_pnl_lines` — บรรทัดงบกำไรขาดทุนรายวันจากสมุดบัญชี (รายได้บวก ค่าใช้จ่ายบวก) — บัญชีงบดุลไม่อยู่ในนี้
- `v_pnl_monthly` — งบกำไรขาดทุนรายเดือน พร้อมป้ายความครบถ้วน — UI ต้องแสดง "กำไรสุทธิ (ยืนยันแล้ว)" เฉพาะ completeness = FINAL
- `v_verified_cash` — เงินสด/เงินฝากที่ตรวจสอบแล้วล่าสุดต่อบัญชี พร้อมยอดตามบัญชี ณ วันเดียวกันและผลต่าง
