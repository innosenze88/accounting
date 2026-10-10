-- =====================================================================================
-- 006 General ledger (double entry). Append-only: corrections are reversal entries.
-- Verified documents are posted here by the posting engine (Phase 3+); reports read the
-- ledger, so asset purchases, transfers, owner drawings and loan principal can never
-- show up as expenses: they post to balance-sheet accounts that have no pnl_section.
-- =====================================================================================

CREATE TABLE journal_entries (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id    bigint NOT NULL REFERENCES properties (id),
    entry_no       text NOT NULL,
    entry_date     date NOT NULL,
    description    text NOT NULL CHECK (has_text(description)),
    source_type    text NOT NULL CHECK (source_type IN (
                       'REVENUE_ENTRY', 'PAYMENT', 'PAYMENT_ALLOCATION', 'EXPENSE', 'EXPENSE_PAYMENT',
                       'OTA_COMMISSION', 'FUND_TRANSFER', 'DEPRECIATION', 'TAX_ESTIMATE',
                       'OPENING_BALANCE', 'MANUAL_ADJUSTMENT', 'REVERSAL', 'PERIOD_CLOSE')),
    source_id      bigint,
    reversal_of_id bigint UNIQUE REFERENCES journal_entries (id),
    posted_by      bigint REFERENCES users (id),
    posted_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, entry_no),
    CHECK ((source_type = 'REVERSAL') = (reversal_of_id IS NOT NULL)),
    CHECK (source_type IN ('OPENING_BALANCE', 'MANUAL_ADJUSTMENT', 'REVERSAL', 'PERIOD_CLOSE') OR source_id IS NOT NULL)
);
CREATE INDEX journal_entries_date_idx ON journal_entries (property_id, entry_date);
CREATE INDEX journal_entries_source_idx ON journal_entries (source_type, source_id);
SELECT attach_audit('journal_entries');
CREATE TRIGGER trg_journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION fn_forbid_update_delete();
CREATE TRIGGER trg_journal_entries_period BEFORE INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION fn_guard_closed_period('entry_date');
COMMENT ON TABLE journal_entries IS 'สมุดรายวันทั่วไป (บัญชีคู่) — บันทึกเพิ่มได้อย่างเดียว แก้ไขด้วยรายการกลับ (REVERSAL)';
COMMENT ON COLUMN journal_entries.id IS 'รหัสรายการ (PK)';
COMMENT ON COLUMN journal_entries.property_id IS 'กิจการ';
COMMENT ON COLUMN journal_entries.entry_no IS 'เลขที่รายการ เช่น JV-2026-10-0001 (ไม่ซ้ำในกิจการ)';
COMMENT ON COLUMN journal_entries.entry_date IS 'วันที่ลงบัญชี (ใช้จัดงวด)';
COMMENT ON COLUMN journal_entries.description IS 'คำอธิบายรายการ';
COMMENT ON COLUMN journal_entries.source_type IS 'เอกสารต้นทางที่สร้างรายการนี้';
COMMENT ON COLUMN journal_entries.source_id IS 'id ของเอกสารต้นทาง';
COMMENT ON COLUMN journal_entries.reversal_of_id IS 'รายการเดิมที่ถูกกลับรายการ (กลับได้ครั้งเดียว)';
COMMENT ON COLUMN journal_entries.posted_by IS 'ผู้ลงบัญชี (NULL = ระบบ)';
COMMENT ON COLUMN journal_entries.posted_at IS 'เวลาที่ลงบัญชี';

CREATE TABLE journal_lines (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id  bigint NOT NULL REFERENCES journal_entries (id),
    line_no           smallint NOT NULL CHECK (line_no > 0),
    ledger_account_id bigint NOT NULL REFERENCES ledger_accounts (id),
    debit             money_thb NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit            money_thb NOT NULL DEFAULT 0 CHECK (credit >= 0),
    memo              text,
    bank_account_id   bigint REFERENCES bank_accounts (id),
    folio_id          bigint REFERENCES folios (id),
    channel_id        bigint REFERENCES ota_channels (id),
    UNIQUE (journal_entry_id, line_no),
    CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);
CREATE INDEX journal_lines_account_idx ON journal_lines (ledger_account_id);
CREATE INDEX journal_lines_bank_idx ON journal_lines (bank_account_id) WHERE bank_account_id IS NOT NULL;
CREATE TRIGGER trg_journal_lines_append_only BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION fn_forbid_update_delete();
COMMENT ON TABLE journal_lines IS 'บรรทัดเดบิต/เครดิตของรายการบัญชี — ทุกรายการต้องมียอดเดบิตเท่ากับเครดิต';
COMMENT ON COLUMN journal_lines.id IS 'รหัสบรรทัด (PK)';
COMMENT ON COLUMN journal_lines.journal_entry_id IS 'รายการบัญชี';
COMMENT ON COLUMN journal_lines.line_no IS 'ลำดับบรรทัด';
COMMENT ON COLUMN journal_lines.ledger_account_id IS 'บัญชีแยกประเภท';
COMMENT ON COLUMN journal_lines.debit IS 'เดบิต';
COMMENT ON COLUMN journal_lines.credit IS 'เครดิต';
COMMENT ON COLUMN journal_lines.memo IS 'คำอธิบายบรรทัด';
COMMENT ON COLUMN journal_lines.bank_account_id IS 'บัญชีเงินที่เกี่ยวข้อง (บังคับเมื่อบัญชีเป็นเงินสด/ธนาคาร เพื่อหายอดคงเหลือรายบัญชี)';
COMMENT ON COLUMN journal_lines.folio_id IS 'มิติ folio (ลูกหนี้/เงินรับล่วงหน้า)';
COMMENT ON COLUMN journal_lines.channel_id IS 'มิติช่องทางขาย';

CREATE FUNCTION fn_validate_journal_line() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entry_property bigint;
    v_subtype        text;
BEGIN
    SELECT property_id INTO v_entry_property FROM journal_entries WHERE id = NEW.journal_entry_id;
    SELECT account_subtype INTO v_subtype FROM ledger_accounts WHERE id = NEW.ledger_account_id;
    IF v_subtype IN ('CASH', 'BANK', 'CLEARING') THEN
        IF NEW.bank_account_id IS NULL
           OR (SELECT ledger_account_id FROM bank_accounts WHERE id = NEW.bank_account_id) <> NEW.ledger_account_id THEN
            RAISE EXCEPTION USING ERRCODE = 'AC005',
                MESSAGE = 'บรรทัดที่ลงบัญชีเงินสด/ธนาคารต้องระบุบัญชีเงิน (bank_account_id) ที่ผูกกับบัญชีแยกประเภทนั้น';
        END IF;
    END IF;
    PERFORM assert_same_property('bank_accounts', NEW.bank_account_id, v_entry_property);
    PERFORM assert_same_property('folios', NEW.folio_id, v_entry_property);
    PERFORM assert_same_property('ota_channels', NEW.channel_id, v_entry_property);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_journal_lines_validate BEFORE INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION fn_validate_journal_line();

-- Checked at COMMIT: every entry has at least two lines and total debit = total credit.
CREATE FUNCTION fn_check_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entry  bigint := (to_jsonb(NEW) ->> CASE WHEN TG_TABLE_NAME = 'journal_entries' THEN 'id' ELSE 'journal_entry_id' END)::bigint;
    v_debit  numeric;
    v_credit numeric;
    v_lines  integer;
BEGIN
    SELECT COALESCE(sum(debit), 0), COALESCE(sum(credit), 0), count(*)
    INTO v_debit, v_credit, v_lines
    FROM journal_lines WHERE journal_entry_id = v_entry;
    IF v_lines < 2 OR v_debit <> v_credit THEN
        RAISE EXCEPTION USING ERRCODE = 'AC003',
            MESSAGE = format('รายการบัญชี id=%s ไม่สมดุล: เดบิต %s เครดิต %s (%s บรรทัด)', v_entry, v_debit, v_credit, v_lines);
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_check_journal_balanced();
CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_check_journal_balanced();
