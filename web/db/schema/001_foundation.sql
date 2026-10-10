-- =====================================================================================
-- Viphuanan Resort Accounting — 001 Foundation
-- Domains, shared trigger functions, properties, users / RBAC, audit log, evidence files.
--
-- Conventions (apply to every file in this folder):
--   * Money is NUMERIC(14,2) in THB (domain money_thb). Never REAL / DOUBLE PRECISION.
--   * Business dates are DATE (Gregorian, ISO). Event times are TIMESTAMPTZ.
--   * Status / type columns are TEXT + CHECK (easier to migrate than enum types).
--   * Accounting documents use status DRAFT -> VERIFIED -> VOIDED. Only DRAFT rows may be deleted.
--   * Business-rule errors use SQLSTATE class "AC" so the app can show a Thai message:
--       AC001 record is locked (verified / voided / append-only)
--       AC002 accounting period is closed
--       AC003 journal entry does not balance
--       AC004 amount over-applied (allocation / payment / reconciliation)
--       AC005 wrong ledger account type for this use
--       AC006 delete not allowed (void with a reason instead)
--       AC007 period cannot be closed: data incomplete
--       AC008 rows belong to different properties
--   * The application sets per-transaction context for the audit log:
--       SET LOCAL app.user_id = '<users.id>'; SET LOCAL app.reason = '...';
--       SET LOCAL app.request_id = '...'; SET LOCAL app.ip = '...';
-- =====================================================================================

CREATE DOMAIN money_thb AS numeric(14, 2);
COMMENT ON DOMAIN money_thb IS 'จำนวนเงินบาท ทศนิยม 2 ตำแหน่ง (ห้ามใช้ float)';

CREATE DOMAIN percent_rate AS numeric(7, 4) CHECK (VALUE >= 0 AND VALUE <= 100);
COMMENT ON DOMAIN percent_rate IS 'อัตราร้อยละ 0–100 ทศนิยม 4 ตำแหน่ง';

-- ------------------------------------------------------------------ helper functions

-- NULL-safe "has non-blank text". A CHECK that evaluates to NULL passes, so
-- "length(trim(x)) > 0" would let a missing reason through; always use has_text(x).
CREATE FUNCTION has_text(p text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(length(trim(p)), 0) > 0
$$;
COMMENT ON FUNCTION has_text(text) IS 'true เมื่อมีข้อความที่ไม่ว่าง (ใช้ใน CHECK แทน length(trim(x)) > 0 ที่ปล่อย NULL ผ่าน)';

CREATE FUNCTION app_user_id() RETURNS bigint
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.user_id', true), '')::bigint
$$;
COMMENT ON FUNCTION app_user_id() IS 'ผู้ใช้ที่แอปตั้งไว้ใน SET LOCAL app.user_id (NULL = ระบบ)';

CREATE FUNCTION fn_set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

-- Append-only tables (audit log, journal): no UPDATE / DELETE ever.
CREATE FUNCTION fn_forbid_update_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = 'AC001',
        MESSAGE = format('ตาราง %s บันทึกเพิ่มได้อย่างเดียว ห้ามแก้ไขหรือลบ (%s)', TG_TABLE_NAME, TG_OP);
END
$$;

-- Accounting documents: DRAFT may change or be deleted; VERIFIED may only become VOIDED; VOIDED is final.
CREATE FUNCTION fn_guard_document() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    c_mutable constant text[] := ARRAY['status', 'voided_by', 'voided_at', 'void_reason', 'updated_at'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION USING
                ERRCODE = 'AC006',
                MESSAGE = format('%s id=%s สถานะ %s ลบไม่ได้ ให้ยกเลิก (VOID) พร้อมเหตุผลแทน',
                                 TG_TABLE_NAME, OLD.id, OLD.status);
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status = 'VOIDED' THEN
        RAISE EXCEPTION USING
            ERRCODE = 'AC001',
            MESSAGE = format('%s id=%s ถูกยกเลิกแล้ว แก้ไขไม่ได้', TG_TABLE_NAME, OLD.id);
    END IF;

    IF OLD.status = 'VERIFIED' THEN
        IF NEW.status NOT IN ('VERIFIED', 'VOIDED') THEN
            RAISE EXCEPTION USING
                ERRCODE = 'AC001',
                MESSAGE = format('%s id=%s ยืนยันแล้ว เปลี่ยนกลับเป็น %s ไม่ได้ ให้ยกเลิกแล้วบันทึกใหม่',
                                 TG_TABLE_NAME, OLD.id, NEW.status);
        END IF;
        IF (to_jsonb(OLD) - c_mutable) IS DISTINCT FROM (to_jsonb(NEW) - c_mutable) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'AC001',
                MESSAGE = format('%s id=%s ยืนยันแล้ว แก้ไขข้อมูลไม่ได้ ให้ยกเลิกแล้วบันทึกใหม่',
                                 TG_TABLE_NAME, OLD.id);
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- Rows dated inside a CLOSED accounting period cannot be inserted, changed or deleted.
-- TG_ARGV[0] = name of the business-date column. The table must have property_id.
CREATE FUNCTION fn_guard_closed_period() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_row  jsonb;
    v_rows jsonb[] := ARRAY[]::jsonb[];
    v_date date;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN v_rows := v_rows || to_jsonb(OLD); END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN v_rows := v_rows || to_jsonb(NEW); END IF;
    FOREACH v_row IN ARRAY v_rows LOOP
        v_date := (v_row ->> TG_ARGV[0])::date;
        IF EXISTS (
            SELECT 1 FROM accounting_periods p
            WHERE p.property_id = (v_row ->> 'property_id')::bigint
              AND p.status = 'CLOSED'
              AND v_date BETWEEN p.period_start AND p.period_end
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'AC002',
                MESSAGE = format('งวดบัญชีของวันที่ %s ปิดแล้ว (%s) ให้บันทึกรายการปรับปรุงในงวดที่ยังเปิดอยู่',
                                 to_char(v_date, 'DD/MM/YYYY'), TG_TABLE_NAME);
        END IF;
    END LOOP;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END
$$;

-- Writes one audit_logs row per changed row. Secrets (password_hash) are never copied.
CREATE FUNCTION fn_audit_row() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_old     jsonb;
    v_new     jsonb;
    v_action  text;
    v_changed text[];
    v_row     jsonb;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN v_old := to_jsonb(OLD) - 'password_hash'; END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN v_new := to_jsonb(NEW) - 'password_hash'; END IF;
    v_row := COALESCE(v_new, v_old);

    v_action := CASE TG_OP WHEN 'INSERT' THEN 'INSERT' WHEN 'DELETE' THEN 'DELETE' ELSE 'UPDATE' END;
    IF TG_OP = 'UPDATE' THEN
        SELECT array_agg(k ORDER BY k) INTO v_changed
        FROM jsonb_object_keys(v_new) k
        WHERE v_new -> k IS DISTINCT FROM v_old -> k AND k <> 'updated_at';
        IF v_changed IS NULL THEN
            RETURN NULL; -- nothing but updated_at changed
        END IF;
        IF v_old ? 'status' AND v_old ->> 'status' IS DISTINCT FROM v_new ->> 'status' THEN
            v_action := CASE v_new ->> 'status'
                WHEN 'VERIFIED'  THEN 'VERIFY'
                WHEN 'VOIDED'    THEN 'VOID'
                WHEN 'CONFIRMED' THEN 'APPROVE'
                WHEN 'REJECTED'  THEN 'REJECT'
                WHEN 'REVERSED'  THEN 'VOID'
                WHEN 'CLOSED'    THEN 'PERIOD_CLOSE'
                WHEN 'COMMITTED' THEN 'COMMIT_IMPORT'
                ELSE 'UPDATE'
            END;
            IF TG_TABLE_NAME = 'accounting_periods' AND v_new ->> 'status' = 'OPEN' THEN
                v_action := 'PERIOD_REOPEN';
            END IF;
        END IF;
    END IF;

    INSERT INTO audit_logs (user_id, property_id, action, table_name, record_id,
                            old_data, new_data, changed_fields, reason, ip_address, request_id)
    VALUES (app_user_id(),
            CASE WHEN TG_TABLE_NAME = 'properties' THEN (v_row ->> 'id')::bigint
                 ELSE (v_row ->> 'property_id')::bigint END,
            v_action, TG_TABLE_NAME, (v_row ->> 'id')::bigint,
            v_old, v_new, v_changed,
            NULLIF(current_setting('app.reason', true), ''),
            NULLIF(current_setting('app.ip', true), '')::inet,
            NULLIF(current_setting('app.request_id', true), ''));
    RETURN NULL;
END
$$;

-- Attaches the standard audit trigger (and updated_at trigger when the column exists).
CREATE FUNCTION attach_audit(p_table regclass) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TRIGGER trg_%s_audit AFTER INSERT OR UPDATE OR DELETE ON %s '
                   'FOR EACH ROW EXECUTE FUNCTION fn_audit_row()', p_table::text, p_table);
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = p_table AND attname = 'updated_at' AND NOT attisdropped) THEN
        EXECUTE format('CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %s '
                       'FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at()', p_table::text, p_table);
    END IF;
END
$$;

-- Attaches the document guard (+ closed-period guard on p_date_column).
CREATE FUNCTION attach_document_guards(p_table regclass, p_date_column text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TRIGGER trg_%s_guard BEFORE UPDATE OR DELETE ON %s '
                   'FOR EACH ROW EXECUTE FUNCTION fn_guard_document()', p_table::text, p_table);
    EXECUTE format('CREATE TRIGGER trg_%s_period BEFORE INSERT OR UPDATE OR DELETE ON %s '
                   'FOR EACH ROW EXECUTE FUNCTION fn_guard_closed_period(%L)', p_table::text, p_table, p_date_column);
END
$$;

-- ------------------------------------------------------------------ properties

CREATE TABLE properties (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                    text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    name_th                 text NOT NULL CHECK (has_text(name_th)),
    name_en                 text,
    business_type           text NOT NULL DEFAULT 'RESORT'
                            CHECK (business_type IN ('RESORT', 'RETAIL_STORE', 'OTHER')),
    legal_entity_name       text,
    tax_id                  text CHECK (tax_id ~ '^[0-9]{13}$'),
    branch_code             text,
    address                 text,
    timezone                text NOT NULL DEFAULT 'Asia/Bangkok',
    pms_system              text NOT NULL DEFAULT 'NONE' CHECK (pms_system IN ('HOTELRUNNER', 'EZEE', 'NONE')),
    pms_property_ref        text,
    accounting_start_date   date NOT NULL,
    fiscal_year_start_month smallint NOT NULL DEFAULT 1 CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    is_sandbox              boolean NOT NULL DEFAULT false,
    is_active               boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE properties IS 'กิจการ/สถานประกอบการ (รีสอร์ต และในอนาคตร้านสะดวกซื้อ) ทุกตารางบัญชีผูกกับ property_id';
COMMENT ON COLUMN properties.id IS 'รหัสภายใน (PK)';
COMMENT ON COLUMN properties.code IS 'รหัสสั้น ตัวพิมพ์ใหญ่ เช่น VIPHUANAN';
COMMENT ON COLUMN properties.name_th IS 'ชื่อภาษาไทย เช่น วิภูอนันต์ รีสอร์ท';
COMMENT ON COLUMN properties.name_en IS 'ชื่อภาษาอังกฤษ';
COMMENT ON COLUMN properties.business_type IS 'RESORT / RETAIL_STORE / OTHER';
COMMENT ON COLUMN properties.legal_entity_name IS 'ชื่อผู้ประกอบการตามกฎหมาย (บุคคล/ห้างฯ/บริษัท) — รอยืนยัน';
COMMENT ON COLUMN properties.tax_id IS 'เลขประจำตัวผู้เสียภาษี 13 หลัก';
COMMENT ON COLUMN properties.branch_code IS 'รหัสสาขาตามทะเบียนภาษี (ถ้ามี)';
COMMENT ON COLUMN properties.address IS 'ที่อยู่สำหรับเอกสาร';
COMMENT ON COLUMN properties.timezone IS 'เขตเวลาที่ใช้ตัดวันทำการ (ค่าเริ่มต้น Asia/Bangkok)';
COMMENT ON COLUMN properties.pms_system IS 'ระบบ PMS ที่ใช้: HOTELRUNNER / EZEE / NONE';
COMMENT ON COLUMN properties.pms_property_ref IS 'รหัสโรงแรมในระบบ PMS';
COMMENT ON COLUMN properties.accounting_start_date IS 'วันแรกที่ระบบนี้เป็นสมุดบัญชีหลัก (ก่อนหน้านี้ไม่ตรวจความครบถ้วน)';
COMMENT ON COLUMN properties.fiscal_year_start_month IS 'เดือนเริ่มปีบัญชี (1 = มกราคม)';
COMMENT ON COLUMN properties.is_sandbox IS 'true = ข้อมูลทดสอบ แอปต้องแสดงป้าย "ข้อมูลทดสอบ" และห้ามใช้ใน Production';
COMMENT ON COLUMN properties.is_active IS 'ยังใช้งานอยู่';
COMMENT ON COLUMN properties.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN properties.updated_at IS 'เวลาที่แก้ไขล่าสุด';

-- ------------------------------------------------------------------ users and roles (RBAC)

CREATE TABLE users (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email              text NOT NULL CHECK (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    display_name       text NOT NULL CHECK (has_text(display_name)),
    password_hash      text,
    is_active          boolean NOT NULL DEFAULT true,
    mfa_enabled        boolean NOT NULL DEFAULT false,
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       timestamptz,
    last_login_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_uq ON users (lower(email));
COMMENT ON TABLE users IS 'ผู้ใช้งานระบบ (สิทธิ์อยู่ใน user_property_roles)';
COMMENT ON COLUMN users.id IS 'รหัสผู้ใช้ (PK)';
COMMENT ON COLUMN users.email IS 'อีเมลสำหรับเข้าสู่ระบบ (ไม่ซ้ำ ไม่สนตัวพิมพ์)';
COMMENT ON COLUMN users.display_name IS 'ชื่อที่แสดงในระบบและ Audit Log';
COMMENT ON COLUMN users.password_hash IS 'รหัสผ่านแบบ hash (argon2id) — NULL = ยังตั้งรหัสไม่ได้/เชิญอยู่; ไม่ถูกคัดลอกเข้า audit_logs';
COMMENT ON COLUMN users.is_active IS 'false = ปิดการใช้งาน (ห้ามลบผู้ใช้ที่เคยบันทึกรายการ)';
COMMENT ON COLUMN users.mfa_enabled IS 'เปิดยืนยันตัวตนสองชั้น (บังคับสำหรับ OWNER/ACCOUNTANT)';
COMMENT ON COLUMN users.failed_login_count IS 'จำนวนครั้งที่ล็อกอินผิดติดกัน';
COMMENT ON COLUMN users.locked_until IS 'ล็อกบัญชีชั่วคราวถึงเวลานี้';
COMMENT ON COLUMN users.last_login_at IS 'เข้าสู่ระบบครั้งล่าสุด';
COMMENT ON COLUMN users.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN users.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE roles (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    name_th     text NOT NULL,
    name_en     text NOT NULL,
    description text NOT NULL
);
COMMENT ON TABLE roles IS 'บทบาทผู้ใช้ (สิทธิ์ของแต่ละบทบาทกำหนดในโค้ดตามตาราง RBAC ในเอกสารสถาปัตยกรรม)';
COMMENT ON COLUMN roles.code IS 'รหัสบทบาท เช่น OWNER, ACCOUNTANT';
COMMENT ON COLUMN roles.name_th IS 'ชื่อบทบาทภาษาไทย';
COMMENT ON COLUMN roles.name_en IS 'ชื่อบทบาทภาษาอังกฤษ';
COMMENT ON COLUMN roles.description IS 'คำอธิบายหน้าที่';

CREATE TABLE user_property_roles (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       bigint NOT NULL REFERENCES users (id),
    property_id   bigint NOT NULL REFERENCES properties (id),
    role_code     text NOT NULL REFERENCES roles (code),
    granted_by    bigint REFERENCES users (id),
    granted_at    timestamptz NOT NULL DEFAULT now(),
    revoked_by    bigint REFERENCES users (id),
    revoked_at    timestamptz,
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX user_property_roles_active_uq
    ON user_property_roles (user_id, property_id, role_code) WHERE revoked_at IS NULL;
COMMENT ON TABLE user_property_roles IS 'การให้สิทธิ์ผู้ใช้ตามกิจการ (RBAC) — ไม่ลบ ใช้ revoked_at แทน';
COMMENT ON COLUMN user_property_roles.id IS 'รหัส (PK)';
COMMENT ON COLUMN user_property_roles.user_id IS 'ผู้ใช้';
COMMENT ON COLUMN user_property_roles.property_id IS 'กิจการที่ได้สิทธิ์';
COMMENT ON COLUMN user_property_roles.role_code IS 'บทบาท';
COMMENT ON COLUMN user_property_roles.granted_by IS 'ผู้ให้สิทธิ์';
COMMENT ON COLUMN user_property_roles.granted_at IS 'เวลาที่ให้สิทธิ์';
COMMENT ON COLUMN user_property_roles.revoked_by IS 'ผู้ถอนสิทธิ์';
COMMENT ON COLUMN user_property_roles.revoked_at IS 'เวลาที่ถอนสิทธิ์ (NULL = ยังมีสิทธิ์)';

-- ------------------------------------------------------------------ audit log

CREATE TABLE audit_logs (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    user_id        bigint REFERENCES users (id),
    property_id    bigint REFERENCES properties (id),
    action         text NOT NULL CHECK (action IN (
                       'INSERT', 'UPDATE', 'DELETE', 'VERIFY', 'VOID', 'APPROVE', 'REJECT',
                       'IMPORT', 'COMMIT_IMPORT', 'PERIOD_CLOSE', 'PERIOD_REOPEN',
                       'LOGIN', 'LOGIN_FAILED', 'LOGOUT', 'EXPORT', 'BACKUP', 'RESTORE', 'PERMISSION_CHANGE')),
    table_name     text,
    record_id      bigint,
    old_data       jsonb,
    new_data       jsonb,
    changed_fields text[],
    reason         text,
    ip_address     inet,
    request_id     text
);
CREATE INDEX audit_logs_record_idx ON audit_logs (table_name, record_id);
CREATE INDEX audit_logs_time_idx ON audit_logs (occurred_at);
CREATE INDEX audit_logs_user_idx ON audit_logs (user_id, occurred_at);
CREATE TRIGGER trg_audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION fn_forbid_update_delete();
CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION fn_forbid_update_delete();
COMMENT ON TABLE audit_logs IS 'ประวัติการสร้าง แก้ไข ยืนยัน ยกเลิก อนุมัติ นำเข้า และการเข้าระบบ — บันทึกเพิ่มได้อย่างเดียว';
COMMENT ON COLUMN audit_logs.id IS 'ลำดับ (PK)';
COMMENT ON COLUMN audit_logs.occurred_at IS 'เวลาที่เกิดเหตุการณ์';
COMMENT ON COLUMN audit_logs.user_id IS 'ผู้กระทำ (NULL = ระบบ/งานอัตโนมัติ)';
COMMENT ON COLUMN audit_logs.property_id IS 'กิจการที่เกี่ยวข้อง';
COMMENT ON COLUMN audit_logs.action IS 'ประเภทเหตุการณ์ เช่น INSERT, VERIFY, VOID, PERIOD_CLOSE, LOGIN';
COMMENT ON COLUMN audit_logs.table_name IS 'ตารางที่ถูกเปลี่ยน';
COMMENT ON COLUMN audit_logs.record_id IS 'id ของแถวที่ถูกเปลี่ยน';
COMMENT ON COLUMN audit_logs.old_data IS 'ข้อมูลก่อนเปลี่ยน (JSON)';
COMMENT ON COLUMN audit_logs.new_data IS 'ข้อมูลหลังเปลี่ยน (JSON)';
COMMENT ON COLUMN audit_logs.changed_fields IS 'ชื่อคอลัมน์ที่เปลี่ยน';
COMMENT ON COLUMN audit_logs.reason IS 'เหตุผลที่ผู้ใช้ระบุ (app.reason)';
COMMENT ON COLUMN audit_logs.ip_address IS 'IP ของผู้ใช้';
COMMENT ON COLUMN audit_logs.request_id IS 'รหัส request สำหรับไล่ปัญหา';

SELECT attach_audit('properties');
SELECT attach_audit('users');
SELECT attach_audit('user_property_roles');

-- ------------------------------------------------------------------ evidence files

CREATE TABLE attachments (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id       bigint NOT NULL REFERENCES properties (id),
    storage_key       text NOT NULL UNIQUE,
    original_filename text NOT NULL,
    mime_type         text NOT NULL CHECK (mime_type IN (
                          'application/pdf', 'image/jpeg', 'image/png', 'image/webp', 'image/heic',
                          'text/csv', 'application/vnd.ms-excel',
                          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')),
    size_bytes        bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 26214400),
    sha256            char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_by       bigint REFERENCES users (id),
    uploaded_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX attachments_same_file_uq ON attachments (property_id, sha256);
CREATE TRIGGER trg_attachments_append_only BEFORE UPDATE OR DELETE ON attachments
    FOR EACH ROW EXECUTE FUNCTION fn_forbid_update_delete();
COMMENT ON TABLE attachments IS 'ไฟล์หลักฐาน (สลิป ใบเสร็จ ใบกำกับภาษี) เก็บใน private storage — ห้ามลบ/แก้';
COMMENT ON COLUMN attachments.id IS 'รหัสไฟล์ (PK)';
COMMENT ON COLUMN attachments.property_id IS 'กิจการเจ้าของไฟล์';
COMMENT ON COLUMN attachments.storage_key IS 'ตำแหน่งไฟล์ใน private storage (ไม่ใช่ URL สาธารณะ เปิดได้ผ่าน endpoint ที่ตรวจสิทธิ์เท่านั้น)';
COMMENT ON COLUMN attachments.original_filename IS 'ชื่อไฟล์เดิมตอนอัปโหลด';
COMMENT ON COLUMN attachments.mime_type IS 'ชนิดไฟล์ที่อนุญาต (PDF รูปภาพ CSV Excel)';
COMMENT ON COLUMN attachments.size_bytes IS 'ขนาดไฟล์ (สูงสุด 25 MB)';
COMMENT ON COLUMN attachments.sha256 IS 'ลายนิ้วมือไฟล์ ใช้กันไฟล์เดียวกันถูกเก็บซ้ำ';
COMMENT ON COLUMN attachments.uploaded_by IS 'ผู้อัปโหลด';
COMMENT ON COLUMN attachments.uploaded_at IS 'เวลาที่อัปโหลด';

CREATE TABLE attachment_links (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attachment_id bigint NOT NULL REFERENCES attachments (id),
    entity_type   text NOT NULL CHECK (entity_type IN (
                      'REVENUE_ENTRY', 'PAYMENT', 'EXPENSE', 'EXPENSE_PAYMENT', 'FUND_TRANSFER',
                      'OTA_COMMISSION', 'BANK_TRANSACTION', 'RECONCILIATION', 'TAX_ESTIMATE',
                      'CAPITAL_FUND_TRANSACTION', 'FIXED_ASSET', 'LOAN', 'BALANCE_CONFIRMATION', 'JOURNAL_ENTRY')),
    entity_id     bigint NOT NULL,
    linked_by     bigint REFERENCES users (id),
    linked_at     timestamptz NOT NULL DEFAULT now(),
    unlinked_by   bigint REFERENCES users (id),
    unlinked_at   timestamptz,
    unlink_reason text,
    CHECK (unlinked_at IS NULL OR has_text(unlink_reason))
);
CREATE UNIQUE INDEX attachment_links_active_uq
    ON attachment_links (attachment_id, entity_type, entity_id) WHERE unlinked_at IS NULL;
CREATE INDEX attachment_links_entity_idx ON attachment_links (entity_type, entity_id);
SELECT attach_audit('attachment_links');
COMMENT ON TABLE attachment_links IS 'ผูกไฟล์หลักฐานกับรายการบัญชี (หนึ่งรายการมีได้หลายไฟล์) — ไม่ลบ ใช้ unlinked_at';
COMMENT ON COLUMN attachment_links.id IS 'รหัส (PK)';
COMMENT ON COLUMN attachment_links.attachment_id IS 'ไฟล์หลักฐาน';
COMMENT ON COLUMN attachment_links.entity_type IS 'ชนิดรายการที่ผูก';
COMMENT ON COLUMN attachment_links.entity_id IS 'id ของรายการที่ผูก';
COMMENT ON COLUMN attachment_links.linked_by IS 'ผู้ผูกไฟล์';
COMMENT ON COLUMN attachment_links.linked_at IS 'เวลาที่ผูก';
COMMENT ON COLUMN attachment_links.unlinked_by IS 'ผู้ยกเลิกการผูก';
COMMENT ON COLUMN attachment_links.unlinked_at IS 'เวลาที่ยกเลิกการผูก (NULL = ยังผูกอยู่)';
COMMENT ON COLUMN attachment_links.unlink_reason IS 'เหตุผลที่ยกเลิกการผูก (บังคับ)';

CREATE FUNCTION fn_guard_attachment_link() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.unlinked_at IS NOT NULL
       OR (to_jsonb(OLD) - ARRAY['unlinked_by', 'unlinked_at', 'unlink_reason'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['unlinked_by', 'unlinked_at', 'unlink_reason']) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001',
            MESSAGE = 'การผูกไฟล์หลักฐานลบหรือแก้ไม่ได้ ทำได้เพียงยกเลิกการผูกพร้อมเหตุผล';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_attachment_links_guard BEFORE UPDATE OR DELETE ON attachment_links
    FOR EACH ROW EXECUTE FUNCTION fn_guard_attachment_link();
