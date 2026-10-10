-- =====================================================================================
-- 003 Imports: mapping profiles, imported files (original kept read-only), raw rows (staging)
-- Raw import data is never the accounting record: rows become accounting documents only
-- through an explicit COMMIT, and every document keeps import_row_id back to its source row.
-- =====================================================================================

CREATE TABLE import_mapping_profiles (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_system         text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL_TEMPLATE')),
    report_type           text NOT NULL CHECK (report_type IN (
                              'NIGHT_AUDIT', 'MONTHLY_STATISTICS', 'FRONT_DESK_ACTIVITIES', 'RECEIPT_DETAIL',
                              'EXPENSE_VOUCHER', 'BANK_STATEMENT', 'OTA_STATEMENT')),
    file_format           text NOT NULL CHECK (file_format IN ('CSV', 'XLSX', 'PDF')),
    variant               text NOT NULL DEFAULT 'default',
    version               integer NOT NULL CHECK (version > 0),
    header_signature      text[] NOT NULL DEFAULT '{}',
    column_map            jsonb NOT NULL CHECK (jsonb_typeof(column_map) = 'object'),
    date_format           text,
    status                text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED_WITH_SAMPLE', 'RETIRED')),
    verified_sample_sha256 char(64) CHECK (verified_sample_sha256 ~ '^[0-9a-f]{64}$'),
    notes                 text,
    created_by            bigint REFERENCES users (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, report_type, file_format, variant, version),
    CHECK (status <> 'VERIFIED_WITH_SAMPLE' OR verified_sample_sha256 IS NOT NULL)
);
SELECT attach_audit('import_mapping_profiles');
COMMENT ON TABLE import_mapping_profiles IS 'แม่แบบจับคู่คอลัมน์ของไฟล์ต้นทางกับฟิลด์มาตรฐาน (แก้ได้โดยไม่ต้องแก้โค้ดเมื่อ PMS เปลี่ยนรูปแบบไฟล์)';
COMMENT ON COLUMN import_mapping_profiles.id IS 'รหัสแม่แบบ (PK)';
COMMENT ON COLUMN import_mapping_profiles.source_system IS 'ระบบต้นทาง: HOTELRUNNER, EZEE, BANK, OTA, MANUAL_TEMPLATE';
COMMENT ON COLUMN import_mapping_profiles.report_type IS 'ประเภทรายงานตามหน้าที่ (ไม่ใช่ชื่อเมนูของผู้ขาย)';
COMMENT ON COLUMN import_mapping_profiles.file_format IS 'CSV / XLSX / PDF (PDF รองรับในอนาคต)';
COMMENT ON COLUMN import_mapping_profiles.variant IS 'รูปแบบย่อย เช่น ชื่อธนาคาร KBANK หรือภาษาของรายงาน';
COMMENT ON COLUMN import_mapping_profiles.version IS 'เวอร์ชันของแม่แบบ (เพิ่มเมื่อหัวคอลัมน์เปลี่ยน)';
COMMENT ON COLUMN import_mapping_profiles.header_signature IS 'หัวคอลัมน์ที่คาดหวัง ใช้ตรวจจับชนิดไฟล์อัตโนมัติ';
COMMENT ON COLUMN import_mapping_profiles.column_map IS 'JSON: ฟิลด์มาตรฐาน -> {source, type, required, transform}';
COMMENT ON COLUMN import_mapping_profiles.date_format IS 'รูปแบบวันที่ในไฟล์ เช่น DD/MM/YYYY (รองรับปี พ.ศ.)';
COMMENT ON COLUMN import_mapping_profiles.status IS 'DRAFT ยังไม่ได้ทดสอบกับไฟล์จริง / VERIFIED_WITH_SAMPLE ทดสอบกับไฟล์จริงแล้ว / RETIRED เลิกใช้';
COMMENT ON COLUMN import_mapping_profiles.verified_sample_sha256 IS 'ลายนิ้วมือของไฟล์ตัวอย่างจริงที่ใช้ยืนยันแม่แบบ';
COMMENT ON COLUMN import_mapping_profiles.notes IS 'หมายเหตุ';
COMMENT ON COLUMN import_mapping_profiles.created_by IS 'ผู้สร้าง';
COMMENT ON COLUMN import_mapping_profiles.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN import_mapping_profiles.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE imported_files (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id           bigint NOT NULL REFERENCES properties (id),
    source_system         text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL_TEMPLATE')),
    report_type           text NOT NULL CHECK (report_type IN (
                              'NIGHT_AUDIT', 'MONTHLY_STATISTICS', 'FRONT_DESK_ACTIVITIES', 'RECEIPT_DETAIL',
                              'EXPENSE_VOUCHER', 'BANK_STATEMENT', 'OTA_STATEMENT')),
    mapping_profile_id    bigint REFERENCES import_mapping_profiles (id),
    bank_account_id       bigint REFERENCES bank_accounts (id),
    original_filename     text NOT NULL,
    mime_type             text NOT NULL,
    size_bytes            bigint NOT NULL CHECK (size_bytes > 0),
    sha256                char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key           text NOT NULL UNIQUE,
    report_date_from      date,
    report_date_to        date,
    status                text NOT NULL DEFAULT 'UPLOADED' CHECK (status IN (
                              'UPLOADED', 'PARSED', 'PARSE_FAILED', 'VALIDATED', 'COMMITTED', 'REJECTED')),
    total_rows            integer NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    valid_rows            integer NOT NULL DEFAULT 0 CHECK (valid_rows >= 0),
    invalid_rows          integer NOT NULL DEFAULT 0 CHECK (invalid_rows >= 0),
    duplicate_rows        integer NOT NULL DEFAULT 0 CHECK (duplicate_rows >= 0),
    conflict_rows         integer NOT NULL DEFAULT 0 CHECK (conflict_rows >= 0),
    committed_rows        integer NOT NULL DEFAULT 0 CHECK (committed_rows >= 0),
    source_control_totals jsonb,
    computed_totals       jsonb,
    totals_match          boolean,
    parse_error           text,
    uploaded_by           bigint REFERENCES users (id),
    uploaded_at           timestamptz NOT NULL DEFAULT now(),
    committed_by          bigint REFERENCES users (id),
    committed_at          timestamptz,
    rejected_by           bigint REFERENCES users (id),
    rejected_at           timestamptz,
    reject_reason         text,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK (report_date_to >= report_date_from),
    CHECK (report_type <> 'BANK_STATEMENT' OR bank_account_id IS NOT NULL),
    CHECK (status <> 'PARSE_FAILED' OR parse_error IS NOT NULL),
    -- A file whose parsed totals differ from the totals printed in the report can never be committed.
    CHECK (status <> 'COMMITTED' OR (totals_match IS DISTINCT FROM false AND committed_at IS NOT NULL
                                     AND report_date_from IS NOT NULL AND report_date_to IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR (rejected_at IS NOT NULL AND has_text(reject_reason)))
);
-- The very same file (same bytes) can be live only once per property; a rejected upload may be retried.
CREATE UNIQUE INDEX imported_files_same_file_uq ON imported_files (property_id, sha256) WHERE status <> 'REJECTED';
CREATE INDEX imported_files_period_idx ON imported_files (property_id, report_type, report_date_from, report_date_to);
SELECT attach_audit('imported_files');
COMMENT ON TABLE imported_files IS 'ไฟล์ที่นำเข้า (เก็บไฟล์ต้นฉบับแบบอ่านอย่างเดียว) พร้อมชื่อไฟล์ ต้นทาง วันที่นำเข้า ผลตรวจยอดรวม';
COMMENT ON COLUMN imported_files.id IS 'รหัสการนำเข้า (PK)';
COMMENT ON COLUMN imported_files.property_id IS 'กิจการ';
COMMENT ON COLUMN imported_files.source_system IS 'ระบบต้นทางของไฟล์';
COMMENT ON COLUMN imported_files.report_type IS 'ประเภทรายงาน';
COMMENT ON COLUMN imported_files.mapping_profile_id IS 'แม่แบบที่ใช้อ่านไฟล์';
COMMENT ON COLUMN imported_files.bank_account_id IS 'บัญชีธนาคารของ statement (บังคับเมื่อเป็น BANK_STATEMENT)';
COMMENT ON COLUMN imported_files.original_filename IS 'ชื่อไฟล์เดิม';
COMMENT ON COLUMN imported_files.mime_type IS 'ชนิดไฟล์';
COMMENT ON COLUMN imported_files.size_bytes IS 'ขนาดไฟล์';
COMMENT ON COLUMN imported_files.sha256 IS 'ลายนิ้วมือไฟล์ ไฟล์เดิมซ้ำจะถูกปฏิเสธ (ไม่สร้างรายการซ้ำ)';
COMMENT ON COLUMN imported_files.storage_key IS 'ตำแหน่งไฟล์ต้นฉบับใน private storage';
COMMENT ON COLUMN imported_files.report_date_from IS 'วันที่เริ่มของข้อมูลในรายงาน';
COMMENT ON COLUMN imported_files.report_date_to IS 'วันที่สิ้นสุดของข้อมูลในรายงาน';
COMMENT ON COLUMN imported_files.status IS 'UPLOADED -> PARSED/PARSE_FAILED -> VALIDATED -> COMMITTED หรือ REJECTED';
COMMENT ON COLUMN imported_files.total_rows IS 'จำนวนแถวที่อ่านได้';
COMMENT ON COLUMN imported_files.valid_rows IS 'แถวที่ผ่านการตรวจ';
COMMENT ON COLUMN imported_files.invalid_rows IS 'แถวที่ไม่ผ่าน (แสดงให้ผู้ใช้แก้)';
COMMENT ON COLUMN imported_files.duplicate_rows IS 'แถวที่เคยนำเข้าแล้ว (ข้าม)';
COMMENT ON COLUMN imported_files.conflict_rows IS 'แถวที่ key เดิมแต่ค่าต่างจากที่บันทึกไว้ (ต้องให้คนตรวจ)';
COMMENT ON COLUMN imported_files.committed_rows IS 'แถวที่บันทึกเป็นรายการบัญชีแล้ว';
COMMENT ON COLUMN imported_files.source_control_totals IS 'ยอดรวมที่พิมพ์อยู่ในรายงานต้นฉบับ (JSON)';
COMMENT ON COLUMN imported_files.computed_totals IS 'ยอดรวมที่ระบบคำนวณจากแถวที่อ่านได้ (JSON)';
COMMENT ON COLUMN imported_files.totals_match IS 'ยอดรวมตรงกับต้นฉบับหรือไม่ (false = ห้าม COMMIT, NULL = รายงานไม่มียอดรวมให้ตรวจ)';
COMMENT ON COLUMN imported_files.parse_error IS 'ข้อความผิดพลาดเมื่ออ่านไฟล์ไม่ได้';
COMMENT ON COLUMN imported_files.uploaded_by IS 'ผู้อัปโหลด';
COMMENT ON COLUMN imported_files.uploaded_at IS 'เวลาที่อัปโหลด';
COMMENT ON COLUMN imported_files.committed_by IS 'ผู้ยืนยันนำเข้า';
COMMENT ON COLUMN imported_files.committed_at IS 'เวลาที่ยืนยันนำเข้า';
COMMENT ON COLUMN imported_files.rejected_by IS 'ผู้ปฏิเสธไฟล์';
COMMENT ON COLUMN imported_files.rejected_at IS 'เวลาที่ปฏิเสธ';
COMMENT ON COLUMN imported_files.reject_reason IS 'เหตุผลที่ปฏิเสธ (บังคับ)';
COMMENT ON COLUMN imported_files.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_guard_imported_file() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    c_identity constant text[] := ARRAY['property_id', 'source_system', 'original_filename', 'mime_type',
                                        'size_bytes', 'sha256', 'storage_key', 'uploaded_by', 'uploaded_at'];
    k text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC006',
            MESSAGE = 'ประวัติการนำเข้าไฟล์ลบไม่ได้ (ใช้สถานะ REJECTED แทน)';
    END IF;
    FOREACH k IN ARRAY c_identity LOOP
        IF to_jsonb(OLD) -> k IS DISTINCT FROM to_jsonb(NEW) -> k THEN
            RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = format('แก้ %s ของไฟล์ที่นำเข้าแล้วไม่ได้', k);
        END IF;
    END LOOP;
    IF OLD.status IN ('COMMITTED', 'REJECTED') AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001',
            MESSAGE = format('ไฟล์สถานะ %s เปลี่ยนสถานะไม่ได้', OLD.status);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_imported_files_guard BEFORE UPDATE OR DELETE ON imported_files
    FOR EACH ROW EXECUTE FUNCTION fn_guard_imported_file();

CREATE TABLE import_rows (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    imported_file_id    bigint NOT NULL REFERENCES imported_files (id),
    row_number          integer NOT NULL CHECK (row_number > 0),
    section             text,
    raw_data            jsonb NOT NULL,
    normalized_data     jsonb,
    natural_key         text CHECK (natural_key ~ '^[0-9]+\|[A-Z_]+\|[A-Z_]+\|.+$'),
    row_hash            char(64) CHECK (row_hash ~ '^[0-9a-f]{64}$'),
    status              text NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                            'PENDING', 'VALID', 'INVALID', 'DUPLICATE', 'CONFLICT', 'COMMITTED', 'SKIPPED', 'SUPERSEDED')),
    errors              jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(errors) = 'array'),
    duplicate_of_row_id bigint REFERENCES import_rows (id),
    target_table        text,
    target_id           bigint,
    reviewed_by         bigint REFERENCES users (id),
    reviewed_at         timestamptz,
    review_note         text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (imported_file_id, row_number),
    CHECK (status <> 'INVALID' OR jsonb_array_length(errors) > 0),
    CHECK (status <> 'DUPLICATE' OR duplicate_of_row_id IS NOT NULL),
    CHECK (status NOT IN ('VALID', 'COMMITTED', 'DUPLICATE', 'CONFLICT') OR (natural_key IS NOT NULL AND row_hash IS NOT NULL)),
    CHECK (status <> 'COMMITTED' OR (target_table IS NOT NULL AND target_id IS NOT NULL)),
    CHECK (status <> 'SKIPPED' OR has_text(review_note))
);
-- Idempotency at the row level: one natural key can be committed only once.
CREATE UNIQUE INDEX import_rows_committed_key_uq ON import_rows (natural_key) WHERE status = 'COMMITTED';
CREATE INDEX import_rows_key_idx ON import_rows (natural_key);
CREATE INDEX import_rows_file_status_idx ON import_rows (imported_file_id, status);
COMMENT ON TABLE import_rows IS 'แถวข้อมูลดิบจากไฟล์นำเข้า (staging) — raw_data ไม่ถูกแก้ไข เพื่อไม่ให้ข้อมูลต้นฉบับสูญหาย';
COMMENT ON COLUMN import_rows.id IS 'รหัสแถว (PK)';
COMMENT ON COLUMN import_rows.imported_file_id IS 'ไฟล์ที่มาของแถวนี้';
COMMENT ON COLUMN import_rows.row_number IS 'ลำดับแถวในไฟล์ (นับรวมทุกส่วนของรายงาน)';
COMMENT ON COLUMN import_rows.section IS 'ส่วนของรายงาน/ชื่อชีต เช่น room_charges, daily_sales';
COMMENT ON COLUMN import_rows.raw_data IS 'ค่าทุกช่องตามต้นฉบับเป็นข้อความ (ห้ามแก้)';
COMMENT ON COLUMN import_rows.normalized_data IS 'ค่าที่แปลงชนิดแล้วตามฟิลด์มาตรฐาน';
COMMENT ON COLUMN import_rows.natural_key IS 'กุญแจกันซ้ำ รูปแบบ {property_id}|{source_system}|{report_type}|{ref...}';
COMMENT ON COLUMN import_rows.row_hash IS 'SHA-256 ของ normalized_data ใช้ตรวจว่า key เดิมแต่ค่าเปลี่ยนหรือไม่';
COMMENT ON COLUMN import_rows.status IS 'PENDING/VALID/INVALID/DUPLICATE/CONFLICT/COMMITTED/SKIPPED/SUPERSEDED';
COMMENT ON COLUMN import_rows.errors IS 'รายการข้อผิดพลาด [{field, code, message}]';
COMMENT ON COLUMN import_rows.duplicate_of_row_id IS 'แถวเดิมที่ซ้ำกัน';
COMMENT ON COLUMN import_rows.target_table IS 'ตารางบัญชีที่สร้างจากแถวนี้';
COMMENT ON COLUMN import_rows.target_id IS 'id ของรายการบัญชีที่สร้าง';
COMMENT ON COLUMN import_rows.reviewed_by IS 'ผู้ตรวจแถวที่มีปัญหา';
COMMENT ON COLUMN import_rows.reviewed_at IS 'เวลาที่ตรวจ';
COMMENT ON COLUMN import_rows.review_note IS 'บันทึกการตรวจ (บังคับเมื่อข้ามแถว)';
COMMENT ON COLUMN import_rows.created_at IS 'เวลาที่อ่านแถว';

CREATE FUNCTION fn_guard_import_row() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    c_identity constant text[] := ARRAY['imported_file_id', 'row_number', 'section', 'raw_data', 'natural_key', 'row_hash'];
    k text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC006', MESSAGE = 'แถวข้อมูลดิบที่นำเข้าลบไม่ได้';
    END IF;
    FOREACH k IN ARRAY c_identity LOOP
        IF to_jsonb(OLD) -> k IS DISTINCT FROM to_jsonb(NEW) -> k THEN
            RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = format('แก้ %s ของข้อมูลดิบไม่ได้', k);
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_import_rows_guard BEFORE UPDATE OR DELETE ON import_rows
    FOR EACH ROW EXECUTE FUNCTION fn_guard_import_row();
