-- =====================================================================================
-- 002 Chart of accounts, accounting periods, money accounts (bank / cash / e-wallet)
-- =====================================================================================

CREATE TABLE ledger_accounts (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            text NOT NULL UNIQUE CHECK (code ~ '^[0-9]{4,6}$'),
    name_th         text NOT NULL,
    name_en         text NOT NULL,
    account_type    text NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    account_subtype text NOT NULL,
    pnl_section     text CHECK (pnl_section IN (
                        'REVENUE', 'DISTRIBUTION_COST', 'OPERATING_EXPENSE', 'DEPRECIATION',
                        'FINANCE_COST', 'OTHER_INCOME', 'OTHER_EXPENSE', 'INCOME_TAX')),
    parent_id       bigint REFERENCES ledger_accounts (id),
    is_system       boolean NOT NULL DEFAULT false,
    is_active       boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (account_type = 'ASSET' AND account_subtype IN (
            'CASH', 'BANK', 'CLEARING', 'RECEIVABLE', 'TAX_RECEIVABLE', 'PREPAID',
            'FIXED_ASSET', 'ACCUMULATED_DEPRECIATION', 'OTHER_ASSET'))
        OR (account_type = 'LIABILITY' AND account_subtype IN (
            'PAYABLE', 'COMMISSION_PAYABLE', 'CUSTOMER_DEPOSIT', 'TAX_PAYABLE', 'LOAN', 'OTHER_LIABILITY'))
        OR (account_type = 'EQUITY' AND account_subtype IN ('OWNER_CAPITAL', 'OWNER_DRAWING', 'RETAINED_EARNINGS'))
        OR (account_type = 'REVENUE' AND account_subtype IN ('OPERATING_REVENUE', 'OTHER_REVENUE'))
        OR (account_type = 'EXPENSE' AND account_subtype = 'EXPENSE')
    ),
    -- Only revenue and expense accounts appear in the profit & loss statement.
    CHECK ((account_type IN ('REVENUE', 'EXPENSE')) = (pnl_section IS NOT NULL)),
    CHECK (account_type <> 'REVENUE' OR pnl_section IN ('REVENUE', 'OTHER_INCOME')),
    CHECK (account_type <> 'EXPENSE' OR pnl_section NOT IN ('REVENUE', 'OTHER_INCOME'))
);
SELECT attach_audit('ledger_accounts');
COMMENT ON TABLE ledger_accounts IS 'ผังบัญชี (Chart of Accounts) — ร่างเริ่มต้นต้องให้นักบัญชียืนยัน';
COMMENT ON COLUMN ledger_accounts.id IS 'รหัสบัญชี (PK)';
COMMENT ON COLUMN ledger_accounts.code IS 'เลขที่บัญชี 4–6 หลัก เช่น 4101';
COMMENT ON COLUMN ledger_accounts.name_th IS 'ชื่อบัญชีภาษาไทย';
COMMENT ON COLUMN ledger_accounts.name_en IS 'ชื่อบัญชีภาษาอังกฤษ';
COMMENT ON COLUMN ledger_accounts.account_type IS 'หมวด: ASSET สินทรัพย์ / LIABILITY หนี้สิน / EQUITY ส่วนของเจ้าของ / REVENUE รายได้ / EXPENSE ค่าใช้จ่าย';
COMMENT ON COLUMN ledger_accounts.account_subtype IS 'หมวดย่อยที่ระบบใช้ตรวจการใช้งาน เช่น BANK, RECEIVABLE, CUSTOMER_DEPOSIT, OWNER_DRAWING';
COMMENT ON COLUMN ledger_accounts.pnl_section IS 'ตำแหน่งในงบกำไรขาดทุน (เฉพาะบัญชีรายได้/ค่าใช้จ่าย; บัญชีงบดุลเป็น NULL จึงไม่ถูกนับเป็นค่าใช้จ่าย)';
COMMENT ON COLUMN ledger_accounts.parent_id IS 'บัญชีแม่ (สำหรับจัดกลุ่มรายงาน)';
COMMENT ON COLUMN ledger_accounts.is_system IS 'บัญชีที่ระบบใช้ลงรายการอัตโนมัติ (ห้ามปิด)';
COMMENT ON COLUMN ledger_accounts.is_active IS 'ยังใช้งานอยู่';
COMMENT ON COLUMN ledger_accounts.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN ledger_accounts.updated_at IS 'เวลาที่แก้ไขล่าสุด';

-- Raises AC005 unless the ledger account has one of the allowed subtypes.
CREATE FUNCTION assert_ledger_account(p_account_id bigint, p_allowed_subtypes text[], p_usage text)
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_subtype text;
    v_code    text;
BEGIN
    IF p_account_id IS NULL THEN RETURN; END IF;
    SELECT account_subtype, code INTO v_subtype, v_code FROM ledger_accounts WHERE id = p_account_id;
    IF v_subtype IS NULL OR NOT (v_subtype = ANY (p_allowed_subtypes)) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005',
            MESSAGE = format('บัญชี %s (%s) ใช้กับ %s ไม่ได้ ต้องเป็นประเภท %s',
                             v_code, v_subtype, p_usage, array_to_string(p_allowed_subtypes, ', '));
    END IF;
END
$$;

CREATE TABLE accounting_periods (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id    bigint NOT NULL REFERENCES properties (id),
    period_start   date NOT NULL CHECK (extract(day FROM period_start) = 1),
    period_end     date GENERATED ALWAYS AS ((period_start + interval '1 month' - interval '1 day')::date) STORED,
    status         text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    closed_by      bigint REFERENCES users (id),
    closed_at      timestamptz,
    close_note     text,
    reopened_by    bigint REFERENCES users (id),
    reopened_at    timestamptz,
    reopen_reason  text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, period_start),
    CHECK (status <> 'CLOSED' OR (closed_by IS NOT NULL AND closed_at IS NOT NULL)),
    CHECK (reopened_at IS NULL OR (reopened_by IS NOT NULL AND has_text(reopen_reason)))
);
SELECT attach_audit('accounting_periods');
COMMENT ON TABLE accounting_periods IS 'งวดบัญชีรายเดือน — งวดที่ CLOSED ล็อกรายการทุกประเภท และเป็นเงื่อนไขของคำว่า "ยืนยันแล้ว (FINAL)"';
COMMENT ON COLUMN accounting_periods.id IS 'รหัสงวด (PK)';
COMMENT ON COLUMN accounting_periods.property_id IS 'กิจการ';
COMMENT ON COLUMN accounting_periods.period_start IS 'วันแรกของเดือน';
COMMENT ON COLUMN accounting_periods.period_end IS 'วันสุดท้ายของเดือน (คำนวณอัตโนมัติ)';
COMMENT ON COLUMN accounting_periods.status IS 'OPEN เปิดอยู่ / CLOSED ปิดงวดแล้ว (ปิดได้เมื่อข้อมูลครบเท่านั้น)';
COMMENT ON COLUMN accounting_periods.closed_by IS 'ผู้ปิดงวด';
COMMENT ON COLUMN accounting_periods.closed_at IS 'เวลาที่ปิดงวด';
COMMENT ON COLUMN accounting_periods.close_note IS 'หมายเหตุการปิดงวด';
COMMENT ON COLUMN accounting_periods.reopened_by IS 'ผู้เปิดงวดใหม่ (เฉพาะ OWNER)';
COMMENT ON COLUMN accounting_periods.reopened_at IS 'เวลาที่เปิดงวดใหม่';
COMMENT ON COLUMN accounting_periods.reopen_reason IS 'เหตุผลที่เปิดงวดใหม่ (บังคับ)';
COMMENT ON COLUMN accounting_periods.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN accounting_periods.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE bank_accounts (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id       bigint NOT NULL REFERENCES properties (id),
    account_kind      text NOT NULL CHECK (account_kind IN ('BANK', 'CASH', 'EWALLET', 'CARD_CLEARING')),
    purpose           text NOT NULL DEFAULT 'OPERATING' CHECK (purpose IN (
                          'OPERATING', 'SAVINGS', 'CAPITAL_FUND', 'TAX_RESERVE', 'PETTY_CASH', 'OTHER')),
    bank_name         text,
    account_name      text NOT NULL,
    account_no_last4  text CHECK (account_no_last4 ~ '^[0-9]{4}$'),
    ledger_account_id bigint NOT NULL REFERENCES ledger_accounts (id),
    currency          char(3) NOT NULL DEFAULT 'THB' CHECK (currency = 'THB'),
    opened_on         date NOT NULL,
    closed_on         date,
    is_active         boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, account_name),
    CHECK (account_kind <> 'BANK' OR bank_name IS NOT NULL),
    CHECK (closed_on >= opened_on)
);
SELECT attach_audit('bank_accounts');
COMMENT ON TABLE bank_accounts IS 'บัญชีเงิน: บัญชีธนาคาร ลิ้นชักเงินสด e-wallet และบัญชีพักเงินบัตร (ยอดยกมาบันทึกเป็น journal OPENING_BALANCE)';
COMMENT ON COLUMN bank_accounts.id IS 'รหัสบัญชีเงิน (PK)';
COMMENT ON COLUMN bank_accounts.property_id IS 'กิจการ';
COMMENT ON COLUMN bank_accounts.account_kind IS 'BANK ธนาคาร / CASH เงินสด / EWALLET / CARD_CLEARING เงินบัตรระหว่างทาง';
COMMENT ON COLUMN bank_accounts.purpose IS 'วัตถุประสงค์ เช่น OPERATING ใช้จ่าย, CAPITAL_FUND กองทุนเปิดร้าน, TAX_RESERVE กันไว้จ่ายภาษี';
COMMENT ON COLUMN bank_accounts.bank_name IS 'ชื่อธนาคาร (บังคับเมื่อเป็น BANK)';
COMMENT ON COLUMN bank_accounts.account_name IS 'ชื่อที่ใช้เรียกในระบบ เช่น "KBank ออมทรัพย์ ใช้จ่ายรีสอร์ต"';
COMMENT ON COLUMN bank_accounts.account_no_last4 IS 'เลขบัญชี 4 หลักท้าย (ไม่เก็บเลขเต็มเพื่อความปลอดภัย)';
COMMENT ON COLUMN bank_accounts.ledger_account_id IS 'บัญชีแยกประเภทที่ผูก (ต้องเป็น CASH/BANK/CLEARING)';
COMMENT ON COLUMN bank_accounts.currency IS 'สกุลเงิน (รองรับเฉพาะ THB)';
COMMENT ON COLUMN bank_accounts.opened_on IS 'วันที่เริ่มใช้บัญชีนี้ในระบบ (ก่อนวันนี้ไม่ตรวจ statement)';
COMMENT ON COLUMN bank_accounts.closed_on IS 'วันที่ปิดบัญชี';
COMMENT ON COLUMN bank_accounts.is_active IS 'ยังใช้งานอยู่';
COMMENT ON COLUMN bank_accounts.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN bank_accounts.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_validate_bank_account() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_ledger_account(NEW.ledger_account_id, ARRAY['CASH', 'BANK', 'CLEARING'], 'บัญชีเงิน');
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_bank_accounts_validate BEFORE INSERT OR UPDATE ON bank_accounts
    FOR EACH ROW EXECUTE FUNCTION fn_validate_bank_account();

-- Raises AC008 when a referenced row belongs to another property.
CREATE FUNCTION assert_same_property(p_table text, p_id bigint, p_property_id bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_property bigint;
BEGIN
    IF p_id IS NULL THEN RETURN; END IF;
    EXECUTE format('SELECT property_id FROM %I WHERE id = $1', p_table) INTO v_property USING p_id;
    IF v_property IS DISTINCT FROM p_property_id THEN
        RAISE EXCEPTION USING ERRCODE = 'AC008',
            MESSAGE = format('%s id=%s เป็นของกิจการอื่น', p_table, p_id);
    END IF;
END
$$;
