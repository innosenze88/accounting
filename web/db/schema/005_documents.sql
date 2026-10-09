-- =====================================================================================
-- 005 Accounting documents (the verified business records behind every report)
--   revenue_entries    รายได้ที่เกิดขึ้น (วันที่เกิดรายได้ = service_date)
--   payments           เงินรับจริง / คืนเงิน (วันที่รับเงิน = received_date)
--   payment_allocations จับคู่เงินรับกับ folio (หลาย folio ต่อเงินรับ และหลายเงินรับต่อ folio)
--   expenses           ค่าใช้จ่าย/รายการจ่าย (วันที่เกิด = expense_date)
--   expense_payments   การจ่ายเงินจริง (วันที่จ่าย = paid_date)
--   fund_transfers     โอนเงินระหว่างบัญชีของตัวเอง (ไม่ใช่รายได้/ค่าใช้จ่าย)
--   ota_commissions    ค่าคอมมิชชัน OTA
--   loans, fixed_assets, balance_confirmations
-- Every document: DRAFT -> VERIFIED -> VOIDED (fn_guard_document) and closed-period lock.
-- =====================================================================================

-- Comments for the standard document columns, so every table documents them the same way.
CREATE FUNCTION comment_standard_columns(p_table regclass) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT * FROM (VALUES
        ('id', 'รหัสรายการ (PK)'),
        ('property_id', 'กิจการ'),
        ('status', 'สถานะ: DRAFT ร่าง/รอตรวจ (ไม่นับในรายงาน), VERIFIED ยืนยันแล้ว (แก้ไม่ได้), VOIDED ยกเลิกพร้อมเหตุผล'),
        ('source_system', 'ที่มาของรายการ: HOTELRUNNER, EZEE, BANK, OTA, MANUAL, ANDROID_APP, SYSTEM'),
        ('source_ref', 'เลขอ้างอิงในระบบต้นทาง (กันบันทึกซ้ำร่วมกับ source_system)'),
        ('import_row_id', 'แถวข้อมูลดิบที่สร้างรายการนี้ (Audit Trail กลับไปหาไฟล์ต้นฉบับ)'),
        ('notes', 'หมายเหตุ'),
        ('created_by', 'ผู้บันทึก'),
        ('created_at', 'เวลาที่บันทึก'),
        ('updated_at', 'เวลาที่แก้ไขล่าสุด'),
        ('verified_by', 'ผู้ตรวจสอบ/ยืนยัน'),
        ('verified_at', 'เวลาที่ยืนยัน'),
        ('voided_by', 'ผู้ยกเลิก'),
        ('voided_at', 'เวลาที่ยกเลิก'),
        ('void_reason', 'เหตุผลการยกเลิก (บังคับ)')
    ) AS t(col, txt) LOOP
        IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = p_table AND attname = c.col AND NOT attisdropped) THEN
            EXECUTE format('COMMENT ON COLUMN %s.%I IS %L', p_table, c.col, c.txt);
        END IF;
    END LOOP;
END
$$;

-- ------------------------------------------------------------------ loans (needed by payments / expenses)

CREATE TABLE loans (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id          bigint NOT NULL REFERENCES properties (id),
    lender_name          text NOT NULL,
    loan_type            text NOT NULL CHECK (loan_type IN ('BANK_LOAN', 'HIRE_PURCHASE', 'FAMILY_LOAN', 'OTHER')),
    principal_amount     money_thb NOT NULL CHECK (principal_amount > 0),
    start_date           date NOT NULL,
    interest_rate_annual percent_rate,
    monthly_installment  money_thb CHECK (monthly_installment > 0),
    installment_day      smallint CHECK (installment_day BETWEEN 1 AND 31),
    maturity_date        date,
    ledger_account_id    bigint NOT NULL REFERENCES ledger_accounts (id),
    status               text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAID_OFF', 'CANCELLED')),
    notes                text,
    created_by           bigint REFERENCES users (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (maturity_date > start_date)
);
SELECT attach_audit('loans');
SELECT comment_standard_columns('loans');
COMMENT ON TABLE loans IS 'เงินกู้/ผ่อนชำระ — ใช้คำนวณภาระผูกพันที่ต้องกันเงินไว้';
COMMENT ON COLUMN loans.status IS 'ACTIVE ยังผ่อนอยู่ / PAID_OFF ปิดแล้ว / CANCELLED บันทึกผิด';
COMMENT ON COLUMN loans.lender_name IS 'ผู้ให้กู้';
COMMENT ON COLUMN loans.loan_type IS 'ประเภทหนี้';
COMMENT ON COLUMN loans.principal_amount IS 'เงินต้นตั้งต้น';
COMMENT ON COLUMN loans.start_date IS 'วันที่เริ่มกู้';
COMMENT ON COLUMN loans.interest_rate_annual IS 'ดอกเบี้ยต่อปี (%) ตามสัญญา';
COMMENT ON COLUMN loans.monthly_installment IS 'ค่างวดต่อเดือน';
COMMENT ON COLUMN loans.installment_day IS 'วันที่ครบกำหนดชำระของทุกเดือน';
COMMENT ON COLUMN loans.maturity_date IS 'วันครบกำหนดสัญญา';
COMMENT ON COLUMN loans.ledger_account_id IS 'บัญชีเงินกู้ (ต้องเป็น LOAN)';

CREATE FUNCTION fn_validate_loan() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_ledger_account(NEW.ledger_account_id, ARRAY['LOAN'], 'เงินกู้');
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_loans_validate BEFORE INSERT OR UPDATE ON loans
    FOR EACH ROW EXECUTE FUNCTION fn_validate_loan();

-- ------------------------------------------------------------------ revenue entries

CREATE TABLE revenue_entries (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id           bigint NOT NULL REFERENCES properties (id),
    folio_id              bigint NOT NULL REFERENCES folios (id),
    reservation_id        bigint REFERENCES reservations (id),
    room_id               bigint REFERENCES rooms (id),
    channel_id            bigint REFERENCES ota_channels (id),
    service_date          date NOT NULL,
    revenue_type          text NOT NULL CHECK (revenue_type IN (
                              'ROOM', 'EXTRA_BED', 'FOOD_BEVERAGE', 'LAUNDRY', 'TRANSPORT',
                              'CANCELLATION_FEE', 'NO_SHOW_FEE', 'OTHER', 'ADJUSTMENT')),
    description           text,
    document_no           text,
    discount_amount       money_thb NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    amount_excl_tax       money_thb NOT NULL,
    vat_amount            money_thb NOT NULL DEFAULT 0,
    service_charge_amount money_thb NOT NULL DEFAULT 0,
    total_amount          money_thb NOT NULL,
    ledger_account_id     bigint NOT NULL REFERENCES ledger_accounts (id),
    status                text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system         text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref            text,
    import_row_id         bigint REFERENCES import_rows (id),
    notes                 text,
    created_by            bigint REFERENCES users (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    verified_by           bigint REFERENCES users (id),
    verified_at           timestamptz,
    voided_by             bigint REFERENCES users (id),
    voided_at             timestamptz,
    void_reason           text,
    CHECK (total_amount = amount_excl_tax + vat_amount + service_charge_amount),
    CHECK (total_amount <> 0),
    CHECK (revenue_type = 'ADJUSTMENT' OR (amount_excl_tax >= 0 AND vat_amount >= 0 AND service_charge_amount >= 0)),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX revenue_entries_source_uq ON revenue_entries (property_id, source_system, source_ref)
    WHERE source_ref IS NOT NULL AND status <> 'VOIDED';
CREATE INDEX revenue_entries_date_idx ON revenue_entries (property_id, service_date);
CREATE INDEX revenue_entries_folio_idx ON revenue_entries (folio_id);
SELECT attach_audit('revenue_entries');
SELECT attach_document_guards('revenue_entries', 'service_date');
SELECT comment_standard_columns('revenue_entries');
COMMENT ON TABLE revenue_entries IS 'รายได้ที่เกิดขึ้นตามวันที่ให้บริการ (Accrual) — แยกจากเงินรับจริง ผูกกับ folio เสมอ';
COMMENT ON COLUMN revenue_entries.folio_id IS 'folio ที่รายได้นี้ถูกเรียกเก็บ';
COMMENT ON COLUMN revenue_entries.reservation_id IS 'การจองที่เกี่ยวข้อง';
COMMENT ON COLUMN revenue_entries.room_id IS 'ห้องพัก (สำหรับค่าห้องรายคืน)';
COMMENT ON COLUMN revenue_entries.channel_id IS 'ช่องทางขาย';
COMMENT ON COLUMN revenue_entries.service_date IS 'วันที่เกิดรายได้ (คืนที่เข้าพัก/วันที่ให้บริการ/วันที่ยกเลิก)';
COMMENT ON COLUMN revenue_entries.revenue_type IS 'ประเภทรายได้ (ADJUSTMENT เท่านั้นที่ติดลบได้)';
COMMENT ON COLUMN revenue_entries.description IS 'รายละเอียด';
COMMENT ON COLUMN revenue_entries.document_no IS 'เลขที่เอกสาร (ใบแจ้งหนี้/ใบเสร็จของเรา)';
COMMENT ON COLUMN revenue_entries.discount_amount IS 'ส่วนลดที่หักไปแล้ว (ข้อมูลประกอบ ยอดสุทธิอยู่ใน amount_excl_tax)';
COMMENT ON COLUMN revenue_entries.amount_excl_tax IS 'รายได้ก่อนภาษีหลังหักส่วนลด';
COMMENT ON COLUMN revenue_entries.vat_amount IS 'ภาษีมูลค่าเพิ่ม (0 ถ้ายังไม่ได้จด VAT ตาม tax_profiles)';
COMMENT ON COLUMN revenue_entries.service_charge_amount IS 'ค่าบริการ (Service charge) ถ้ามี';
COMMENT ON COLUMN revenue_entries.total_amount IS 'ยอดรวม = ก่อนภาษี + VAT + ค่าบริการ';
COMMENT ON COLUMN revenue_entries.ledger_account_id IS 'บัญชีรายได้ (ต้องเป็นหมวดรายได้)';

CREATE FUNCTION fn_validate_revenue_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_ledger_account(NEW.ledger_account_id, ARRAY['OPERATING_REVENUE', 'OTHER_REVENUE'], 'รายได้');
    PERFORM assert_same_property('folios', NEW.folio_id, NEW.property_id);
    PERFORM assert_same_property('reservations', NEW.reservation_id, NEW.property_id);
    PERFORM assert_same_property('rooms', NEW.room_id, NEW.property_id);
    PERFORM assert_same_property('ota_channels', NEW.channel_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_revenue_entries_validate BEFORE INSERT OR UPDATE ON revenue_entries
    FOR EACH ROW EXECUTE FUNCTION fn_validate_revenue_entry();

-- ------------------------------------------------------------------ payments (money received / refunded)

CREATE TABLE payments (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id      bigint NOT NULL REFERENCES properties (id),
    receipt_type     text NOT NULL CHECK (receipt_type IN (
                         'GUEST_PAYMENT', 'OTA_PAYOUT', 'OTHER_INCOME_RECEIPT', 'GUEST_REFUND',
                         'OWNER_CONTRIBUTION', 'LOAN_PROCEEDS', 'OTHER_RECEIPT')),
    direction        text GENERATED ALWAYS AS (CASE WHEN receipt_type = 'GUEST_REFUND' THEN 'OUT' ELSE 'IN' END) STORED,
    received_date    date NOT NULL,
    receipt_no       text,
    payer_name       text,
    channel_id       bigint REFERENCES ota_channels (id),
    loan_id          bigint REFERENCES loans (id),
    method           text NOT NULL CHECK (method IN (
                         'CASH', 'BANK_TRANSFER', 'PROMPTPAY', 'CREDIT_CARD', 'DEBIT_CARD',
                         'OTA_VIRTUAL_CARD', 'OTA_PAYOUT', 'CHEQUE', 'OTHER')),
    bank_account_id  bigint NOT NULL REFERENCES bank_accounts (id),
    amount           money_thb NOT NULL CHECK (amount > 0),
    fee_amount       money_thb NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    external_ref     text,
    status           text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system    text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref       text,
    import_row_id    bigint REFERENCES import_rows (id),
    notes            text,
    created_by       bigint REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    verified_by      bigint REFERENCES users (id),
    verified_at      timestamptz,
    voided_by        bigint REFERENCES users (id),
    voided_at        timestamptz,
    void_reason      text,
    CHECK (fee_amount < amount),
    CHECK (receipt_type <> 'LOAN_PROCEEDS' OR loan_id IS NOT NULL),
    CHECK (receipt_type <> 'OTA_PAYOUT' OR channel_id IS NOT NULL),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX payments_source_uq ON payments (property_id, source_system, source_ref)
    WHERE source_ref IS NOT NULL AND status <> 'VOIDED';
CREATE INDEX payments_date_idx ON payments (property_id, received_date);
CREATE INDEX payments_bank_idx ON payments (bank_account_id, received_date);
SELECT attach_audit('payments');
SELECT attach_document_guards('payments', 'received_date');
SELECT comment_standard_columns('payments');
COMMENT ON TABLE payments IS 'เงินรับจริง (และการคืนเงินลูกค้า) ตามวันที่รับเงิน — ไม่ถือว่าเท่ากับรายได้หรือยอดเข้าธนาคารโดยอัตโนมัติ';
COMMENT ON COLUMN payments.receipt_type IS 'GUEST_PAYMENT ลูกค้าจ่าย / OTA_PAYOUT OTA โอนให้ / OTHER_INCOME_RECEIPT รายได้อื่น / GUEST_REFUND คืนเงิน / OWNER_CONTRIBUTION เจ้าของนำเงินเข้า / LOAN_PROCEEDS รับเงินกู้ / OTHER_RECEIPT อื่นๆ';
COMMENT ON COLUMN payments.direction IS 'IN เงินเข้า / OUT เงินออก (คำนวณจาก receipt_type)';
COMMENT ON COLUMN payments.received_date IS 'วันที่รับเงินจริง (หรือวันที่คืนเงิน)';
COMMENT ON COLUMN payments.receipt_no IS 'เลขที่ใบเสร็จ (จาก PMS หรือของเรา)';
COMMENT ON COLUMN payments.payer_name IS 'ชื่อผู้จ่าย';
COMMENT ON COLUMN payments.channel_id IS 'ช่องทางขาย (บังคับเมื่อเป็น OTA_PAYOUT)';
COMMENT ON COLUMN payments.loan_id IS 'เงินกู้ที่รับ (บังคับเมื่อเป็น LOAN_PROCEEDS)';
COMMENT ON COLUMN payments.method IS 'ช่องทางชำระ: เงินสด โอน พร้อมเพย์ บัตร OTA ฯลฯ';
COMMENT ON COLUMN payments.bank_account_id IS 'บัญชีเงินที่เงินเข้า (ลิ้นชักเงินสดก็เป็นบัญชีเงินชนิด CASH)';
COMMENT ON COLUMN payments.amount IS 'จำนวนเงินที่ลูกค้าจ่าย (บวกเสมอ ทิศทางดูจาก direction)';
COMMENT ON COLUMN payments.fee_amount IS 'ค่าธรรมเนียมที่ถูกหัก (บัตร/OTA payout) — เงินเข้าบัญชีจริง = amount − fee_amount';
COMMENT ON COLUMN payments.external_ref IS 'เลขอ้างอิงธนาคาร/รหัสอนุมัติบัตร/รหัส payout';

CREATE FUNCTION fn_validate_payment() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('bank_accounts', NEW.bank_account_id, NEW.property_id);
    PERFORM assert_same_property('ota_channels', NEW.channel_id, NEW.property_id);
    PERFORM assert_same_property('loans', NEW.loan_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payments_validate BEFORE INSERT OR UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION fn_validate_payment();

CREATE TABLE payment_allocations (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id     bigint NOT NULL REFERENCES properties (id),
    payment_id      bigint NOT NULL REFERENCES payments (id),
    folio_id        bigint NOT NULL REFERENCES folios (id),
    allocation_date date NOT NULL,
    amount          money_thb NOT NULL CHECK (amount > 0),
    created_by      bigint REFERENCES users (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    voided_by       bigint REFERENCES users (id),
    voided_at       timestamptz,
    void_reason     text,
    CHECK (voided_at IS NULL OR (voided_by IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX payment_allocations_active_uq ON payment_allocations (payment_id, folio_id) WHERE voided_at IS NULL;
CREATE INDEX payment_allocations_folio_idx ON payment_allocations (folio_id);
SELECT attach_audit('payment_allocations');
SELECT comment_standard_columns('payment_allocations');
COMMENT ON TABLE payment_allocations IS 'การจับคู่เงินรับกับ folio: เงินรับ 1 รายการจ่ายได้หลาย folio และ folio 1 ใบรับเงินได้หลายรายการ';
COMMENT ON COLUMN payment_allocations.payment_id IS 'เงินรับ';
COMMENT ON COLUMN payment_allocations.folio_id IS 'folio ที่ได้รับชำระ';
COMMENT ON COLUMN payment_allocations.allocation_date IS 'วันที่จับคู่ (ใช้ล็อกงวด)';
COMMENT ON COLUMN payment_allocations.amount IS 'จำนวนเงินที่จัดสรร (รวมทุกแถวของเงินรับหนึ่งรายการต้องไม่เกินยอดเงินรับ)';

CREATE FUNCTION fn_guard_payment_allocation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_pay       payments%ROWTYPE;
    v_allocated numeric;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF (SELECT status FROM payments WHERE id = OLD.payment_id) <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = 'AC006',
                MESSAGE = 'การจัดสรรของเงินรับที่ยืนยันแล้วลบไม่ได้ ให้ยกเลิกพร้อมเหตุผล';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.voided_at IS NOT NULL
           OR (to_jsonb(OLD) - ARRAY['voided_by', 'voided_at', 'void_reason'])
              IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['voided_by', 'voided_at', 'void_reason']) THEN
            RAISE EXCEPTION USING ERRCODE = 'AC001',
                MESSAGE = 'การจัดสรรเงินแก้ไขไม่ได้ ทำได้เพียงยกเลิกแล้วจัดสรรใหม่';
        END IF;
        RETURN NEW;
    END IF;
    -- INSERT: lock the payment so two allocations cannot both pass the limit check.
    SELECT * INTO v_pay FROM payments WHERE id = NEW.payment_id FOR UPDATE;
    IF v_pay.status = 'VOIDED' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'เงินรับนี้ถูกยกเลิกแล้ว จัดสรรไม่ได้';
    END IF;
    IF v_pay.receipt_type NOT IN ('GUEST_PAYMENT', 'OTA_PAYOUT', 'OTHER_INCOME_RECEIPT', 'GUEST_REFUND') THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005',
            MESSAGE = format('เงินรับประเภท %s ไม่ใช่เงินจากลูกค้า จึงจัดสรรเข้า folio ไม่ได้', v_pay.receipt_type);
    END IF;
    IF v_pay.property_id <> NEW.property_id THEN
        RAISE EXCEPTION USING ERRCODE = 'AC008', MESSAGE = 'เงินรับเป็นของกิจการอื่น';
    END IF;
    PERFORM assert_same_property('folios', NEW.folio_id, NEW.property_id);
    SELECT COALESCE(sum(amount), 0) INTO v_allocated
    FROM payment_allocations WHERE payment_id = NEW.payment_id AND voided_at IS NULL;
    IF v_allocated + NEW.amount > v_pay.amount THEN
        RAISE EXCEPTION USING ERRCODE = 'AC004',
            MESSAGE = format('จัดสรรเกินยอดเงินรับ: จัดสรรแล้ว %s + %s > %s', v_allocated, NEW.amount, v_pay.amount);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payment_allocations_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_guard_payment_allocation();
CREATE TRIGGER trg_payment_allocations_period BEFORE INSERT OR UPDATE OR DELETE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_guard_closed_period('allocation_date');

-- ------------------------------------------------------------------ expense categories

CREATE TABLE expense_categories (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]+$'),
    name_th           text NOT NULL,
    name_en           text NOT NULL,
    treatment         text NOT NULL CHECK (treatment IN (
                          'OPERATING_EXPENSE', 'INTEREST_EXPENSE', 'INCOME_TAX_EXPENSE', 'CAPITAL_EXPENDITURE',
                          'PREPAYMENT', 'OWNER_DRAWING', 'LOAN_PRINCIPAL', 'TAX_REMITTANCE', 'OTA_COMMISSION_SETTLEMENT')),
    ledger_account_id bigint NOT NULL REFERENCES ledger_accounts (id),
    pms_charge_names  text[] NOT NULL DEFAULT '{}',
    needs_accountant_review boolean NOT NULL DEFAULT false,
    is_active         boolean NOT NULL DEFAULT true,
    sort_order        integer NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
SELECT attach_audit('expense_categories');
COMMENT ON TABLE expense_categories IS 'หมวดรายจ่าย — treatment กำหนดว่ารายการเข้างบกำไรขาดทุนหรือไม่ (ซื้อสินทรัพย์ ถอนเงินเจ้าของ ชำระหนี้ ไม่ใช่ค่าใช้จ่าย)';
COMMENT ON COLUMN expense_categories.id IS 'รหัสหมวด (PK)';
COMMENT ON COLUMN expense_categories.code IS 'รหัสหมวด เช่น SALARY, ELECTRICITY';
COMMENT ON COLUMN expense_categories.name_th IS 'ชื่อหมวดภาษาไทย';
COMMENT ON COLUMN expense_categories.name_en IS 'ชื่อหมวดภาษาอังกฤษ';
COMMENT ON COLUMN expense_categories.treatment IS 'วิธีบันทึกบัญชี: OPERATING_EXPENSE ค่าใช้จ่าย / CAPITAL_EXPENDITURE ซื้อสินทรัพย์ / OWNER_DRAWING ถอนใช้ส่วนตัว / LOAN_PRINCIPAL ชำระเงินต้น / TAX_REMITTANCE นำส่งภาษีที่เก็บไว้ / OTA_COMMISSION_SETTLEMENT จ่ายหนี้ค่าคอม OTA ฯลฯ';
COMMENT ON COLUMN expense_categories.ledger_account_id IS 'บัญชีแยกประเภทที่ลง (ต้องสอดคล้องกับ treatment)';
COMMENT ON COLUMN expense_categories.pms_charge_names IS 'ชื่อ Charge ใน Expense Voucher ของ PMS ที่จับคู่มาหมวดนี้';
COMMENT ON COLUMN expense_categories.needs_accountant_review IS 'หมวดที่ต้องให้นักบัญชียืนยันวิธีบันทึก (เช่น ภาษีเงินได้เจ้าของ)';
COMMENT ON COLUMN expense_categories.is_active IS 'ยังใช้งานอยู่';
COMMENT ON COLUMN expense_categories.sort_order IS 'ลำดับการแสดงผล';
COMMENT ON COLUMN expense_categories.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN expense_categories.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_validate_expense_category() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_acc ledger_accounts%ROWTYPE;
    v_ok  boolean;
BEGIN
    SELECT * INTO v_acc FROM ledger_accounts WHERE id = NEW.ledger_account_id;
    v_ok := CASE NEW.treatment
        WHEN 'OPERATING_EXPENSE'  THEN v_acc.account_type = 'EXPENSE'
                                       AND v_acc.pnl_section IN ('OPERATING_EXPENSE', 'DISTRIBUTION_COST', 'OTHER_EXPENSE')
        WHEN 'INTEREST_EXPENSE'   THEN v_acc.pnl_section = 'FINANCE_COST'
        WHEN 'INCOME_TAX_EXPENSE' THEN v_acc.pnl_section = 'INCOME_TAX'
        WHEN 'CAPITAL_EXPENDITURE' THEN v_acc.account_subtype = 'FIXED_ASSET'
        WHEN 'PREPAYMENT'         THEN v_acc.account_subtype = 'PREPAID'
        WHEN 'OWNER_DRAWING'      THEN v_acc.account_subtype = 'OWNER_DRAWING'
        WHEN 'LOAN_PRINCIPAL'     THEN v_acc.account_subtype = 'LOAN'
        WHEN 'TAX_REMITTANCE'     THEN v_acc.account_subtype = 'TAX_PAYABLE'
        WHEN 'OTA_COMMISSION_SETTLEMENT' THEN v_acc.account_subtype = 'COMMISSION_PAYABLE'
    END;
    -- System expense accounts (OTA commission, depreciation) are posted only by their own modules,
    -- so an expense voucher can never count them a second time.
    IF v_acc.account_type = 'EXPENSE' AND v_acc.is_system THEN
        v_ok := false;
    END IF;
    IF NOT COALESCE(v_ok, false) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005',
            MESSAGE = format('หมวด %s (%s) ใช้บัญชี %s ไม่ได้', NEW.code, NEW.treatment, v_acc.code);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_expense_categories_validate BEFORE INSERT OR UPDATE ON expense_categories
    FOR EACH ROW EXECUTE FUNCTION fn_validate_expense_category();

-- ------------------------------------------------------------------ expenses and expense payments

CREATE TABLE expenses (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id          bigint NOT NULL REFERENCES properties (id),
    expense_date         date NOT NULL,
    due_date             date,
    document_no          text,
    internal_voucher_no  text,
    vendor_name          text,
    vendor_tax_id        text CHECK (vendor_tax_id ~ '^[0-9]{13}$'),
    category_id          bigint NOT NULL REFERENCES expense_categories (id),
    description          text NOT NULL CHECK (has_text(description)),
    amount_excl_vat      money_thb NOT NULL CHECK (amount_excl_vat >= 0),
    vat_amount           money_thb NOT NULL DEFAULT 0 CHECK (vat_amount >= 0),
    total_amount         money_thb NOT NULL CHECK (total_amount > 0),
    wht_amount           money_thb NOT NULL DEFAULT 0 CHECK (wht_amount >= 0),
    vat_claimable        boolean NOT NULL DEFAULT false,
    service_period_start date,
    service_period_end   date,
    loan_id              bigint REFERENCES loans (id),
    status               text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system        text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref           text,
    import_row_id        bigint REFERENCES import_rows (id),
    notes                text,
    created_by           bigint REFERENCES users (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    verified_by          bigint REFERENCES users (id),
    verified_at          timestamptz,
    voided_by            bigint REFERENCES users (id),
    voided_at            timestamptz,
    void_reason          text,
    CHECK (total_amount = amount_excl_vat + vat_amount),
    CHECK (wht_amount < total_amount),
    CHECK (service_period_end >= service_period_start),
    CHECK (due_date >= expense_date),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX expenses_source_uq ON expenses (property_id, source_system, source_ref)
    WHERE source_ref IS NOT NULL AND status <> 'VOIDED';
CREATE UNIQUE INDEX expenses_voucher_no_uq ON expenses (property_id, internal_voucher_no)
    WHERE internal_voucher_no IS NOT NULL AND status <> 'VOIDED';
CREATE INDEX expenses_date_idx ON expenses (property_id, expense_date);
SELECT attach_audit('expenses');
SELECT attach_document_guards('expenses', 'expense_date');
SELECT comment_standard_columns('expenses');
COMMENT ON TABLE expenses IS 'รายการจ่าย/ค่าใช้จ่าย ตามวันที่เกิด (ใบแจ้งหนี้/ใบเสร็จ) — การจ่ายเงินจริงอยู่ที่ expense_payments';
COMMENT ON COLUMN expenses.expense_date IS 'วันที่เกิดค่าใช้จ่าย (วันที่ในเอกสารผู้ขาย)';
COMMENT ON COLUMN expenses.due_date IS 'วันครบกำหนดจ่าย';
COMMENT ON COLUMN expenses.document_no IS 'เลขที่ใบเสร็จ/ใบกำกับภาษีของผู้ขาย';
COMMENT ON COLUMN expenses.internal_voucher_no IS 'เลขที่ใบสำคัญจ่ายของเรา (ไม่ซ้ำ)';
COMMENT ON COLUMN expenses.vendor_name IS 'ชื่อผู้ขาย/ผู้รับเงิน';
COMMENT ON COLUMN expenses.vendor_tax_id IS 'เลขผู้เสียภาษีผู้ขาย 13 หลัก';
COMMENT ON COLUMN expenses.category_id IS 'หมวดรายจ่าย (กำหนดการลงบัญชี)';
COMMENT ON COLUMN expenses.description IS 'รายละเอียด';
COMMENT ON COLUMN expenses.amount_excl_vat IS 'ยอดก่อน VAT';
COMMENT ON COLUMN expenses.vat_amount IS 'VAT ตามใบกำกับ';
COMMENT ON COLUMN expenses.total_amount IS 'ยอดรวม = ก่อน VAT + VAT';
COMMENT ON COLUMN expenses.wht_amount IS 'ภาษีหัก ณ ที่จ่ายที่เราหักไว้ (จ่ายผู้ขายสุทธิ = total − wht)';
COMMENT ON COLUMN expenses.vat_claimable IS 'ขอคืนภาษีซื้อได้ (เฉพาะเมื่อจด VAT และมีใบกำกับเต็มรูป — นักบัญชียืนยัน)';
COMMENT ON COLUMN expenses.service_period_start IS 'ช่วงที่ใช้บริการ เริ่ม (เช่น รอบบิลค่าไฟ)';
COMMENT ON COLUMN expenses.service_period_end IS 'ช่วงที่ใช้บริการ สิ้นสุด';
COMMENT ON COLUMN expenses.loan_id IS 'เงินกู้ (บังคับเมื่อหมวดเป็น LOAN_PRINCIPAL/INTEREST_EXPENSE ของเงินกู้)';

CREATE FUNCTION fn_validate_expense() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_treatment text;
BEGIN
    SELECT treatment INTO v_treatment FROM expense_categories WHERE id = NEW.category_id;
    IF v_treatment = 'LOAN_PRINCIPAL' AND NEW.loan_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005', MESSAGE = 'การชำระเงินต้นต้องระบุเงินกู้ (loan_id)';
    END IF;
    PERFORM assert_same_property('loans', NEW.loan_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_expenses_validate BEFORE INSERT OR UPDATE ON expenses
    FOR EACH ROW EXECUTE FUNCTION fn_validate_expense();

CREATE TABLE expense_payments (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id     bigint NOT NULL REFERENCES properties (id),
    expense_id      bigint NOT NULL REFERENCES expenses (id),
    paid_date       date NOT NULL,
    bank_account_id bigint NOT NULL REFERENCES bank_accounts (id),
    method          text NOT NULL CHECK (method IN ('CASH', 'BANK_TRANSFER', 'PROMPTPAY', 'CREDIT_CARD', 'DEBIT_CARD', 'CHEQUE', 'AUTO_DEBIT', 'OTHER')),
    amount          money_thb NOT NULL CHECK (amount > 0),
    external_ref    text,
    status          text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system   text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref      text,
    import_row_id   bigint REFERENCES import_rows (id),
    notes           text,
    created_by      bigint REFERENCES users (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    verified_by     bigint REFERENCES users (id),
    verified_at     timestamptz,
    voided_by       bigint REFERENCES users (id),
    voided_at       timestamptz,
    void_reason     text,
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX expense_payments_source_uq ON expense_payments (property_id, source_system, source_ref)
    WHERE source_ref IS NOT NULL AND status <> 'VOIDED';
CREATE INDEX expense_payments_expense_idx ON expense_payments (expense_id);
CREATE INDEX expense_payments_date_idx ON expense_payments (property_id, paid_date);
SELECT attach_audit('expense_payments');
SELECT attach_document_guards('expense_payments', 'paid_date');
SELECT comment_standard_columns('expense_payments');
COMMENT ON TABLE expense_payments IS 'การจ่ายเงินจริงของรายจ่าย (จ่ายบางส่วน/หลายครั้งได้) — ยอดค้างจ่าย = total − wht − ที่จ่ายแล้ว';
COMMENT ON COLUMN expense_payments.expense_id IS 'รายจ่ายที่ชำระ';
COMMENT ON COLUMN expense_payments.paid_date IS 'วันที่จ่ายเงินจริง';
COMMENT ON COLUMN expense_payments.bank_account_id IS 'บัญชีเงินที่จ่ายออก';
COMMENT ON COLUMN expense_payments.method IS 'ช่องทางจ่าย';
COMMENT ON COLUMN expense_payments.amount IS 'จำนวนเงินที่จ่าย';
COMMENT ON COLUMN expense_payments.external_ref IS 'เลขอ้างอิงการโอน/เช็ค';

CREATE FUNCTION fn_validate_expense_payment() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_exp  expenses%ROWTYPE;
    v_paid numeric;
BEGIN
    IF NEW.status = 'VOIDED' THEN RETURN NEW; END IF;
    SELECT * INTO v_exp FROM expenses WHERE id = NEW.expense_id FOR UPDATE;
    IF v_exp.status = 'VOIDED' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'รายจ่ายนี้ถูกยกเลิกแล้ว บันทึกการจ่ายไม่ได้';
    END IF;
    IF v_exp.property_id <> NEW.property_id THEN
        RAISE EXCEPTION USING ERRCODE = 'AC008', MESSAGE = 'รายจ่ายเป็นของกิจการอื่น';
    END IF;
    PERFORM assert_same_property('bank_accounts', NEW.bank_account_id, NEW.property_id);
    SELECT COALESCE(sum(amount), 0) INTO v_paid
    FROM expense_payments
    WHERE expense_id = NEW.expense_id AND status <> 'VOIDED' AND id IS DISTINCT FROM NEW.id;
    IF v_paid + NEW.amount > v_exp.total_amount - v_exp.wht_amount THEN
        RAISE EXCEPTION USING ERRCODE = 'AC004',
            MESSAGE = format('จ่ายเกินยอดที่ต้องจ่าย: จ่ายแล้ว %s + %s > %s',
                             v_paid, NEW.amount, v_exp.total_amount - v_exp.wht_amount);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_expense_payments_validate BEFORE INSERT OR UPDATE ON expense_payments
    FOR EACH ROW EXECUTE FUNCTION fn_validate_expense_payment();

-- ------------------------------------------------------------------ transfers between own accounts

CREATE TABLE fund_transfers (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id          bigint NOT NULL REFERENCES properties (id),
    transfer_date        date NOT NULL,
    from_bank_account_id bigint NOT NULL REFERENCES bank_accounts (id),
    to_bank_account_id   bigint NOT NULL REFERENCES bank_accounts (id),
    amount               money_thb NOT NULL CHECK (amount > 0),
    fee_amount           money_thb NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    purpose              text NOT NULL CHECK (purpose IN (
                             'INTERNAL_TRANSFER', 'CASH_DEPOSIT_TO_BANK', 'CASH_WITHDRAWAL',
                             'CAPITAL_FUND_ALLOCATION', 'CAPITAL_FUND_RELEASE', 'TAX_RESERVE_ALLOCATION')),
    reference            text,
    status               text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system        text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref           text,
    import_row_id        bigint REFERENCES import_rows (id),
    notes                text,
    created_by           bigint REFERENCES users (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    verified_by          bigint REFERENCES users (id),
    verified_at          timestamptz,
    voided_by            bigint REFERENCES users (id),
    voided_at            timestamptz,
    void_reason          text,
    CHECK (from_bank_account_id <> to_bank_account_id),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE INDEX fund_transfers_date_idx ON fund_transfers (property_id, transfer_date);
SELECT attach_audit('fund_transfers');
SELECT attach_document_guards('fund_transfers', 'transfer_date');
SELECT comment_standard_columns('fund_transfers');
COMMENT ON TABLE fund_transfers IS 'โอนเงินระหว่างบัญชีของกิจการเอง (ฝากเงินสด กันเงินเข้ากองทุน) — ไม่ใช่รายได้หรือค่าใช้จ่าย';
COMMENT ON COLUMN fund_transfers.transfer_date IS 'วันที่โอน';
COMMENT ON COLUMN fund_transfers.from_bank_account_id IS 'บัญชีต้นทาง';
COMMENT ON COLUMN fund_transfers.to_bank_account_id IS 'บัญชีปลายทาง (ต้องไม่ใช่บัญชีเดียวกัน)';
COMMENT ON COLUMN fund_transfers.amount IS 'จำนวนเงินที่เข้าบัญชีปลายทาง';
COMMENT ON COLUMN fund_transfers.fee_amount IS 'ค่าธรรมเนียมโอน (ลงเป็นค่าธรรมเนียมธนาคาร)';
COMMENT ON COLUMN fund_transfers.purpose IS 'วัตถุประสงค์ของการโอน';
COMMENT ON COLUMN fund_transfers.reference IS 'เลขอ้างอิงการโอน';

CREATE FUNCTION fn_validate_fund_transfer() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('bank_accounts', NEW.from_bank_account_id, NEW.property_id);
    PERFORM assert_same_property('bank_accounts', NEW.to_bank_account_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_fund_transfers_validate BEFORE INSERT OR UPDATE ON fund_transfers
    FOR EACH ROW EXECUTE FUNCTION fn_validate_fund_transfer();

-- ------------------------------------------------------------------ OTA commissions

CREATE TABLE ota_commissions (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id           bigint NOT NULL REFERENCES properties (id),
    channel_id            bigint NOT NULL REFERENCES ota_channels (id),
    reservation_id        bigint REFERENCES reservations (id),
    folio_id              bigint REFERENCES folios (id),
    ota_booking_ref       text,
    recognition_date      date NOT NULL,
    commissionable_amount money_thb CHECK (commissionable_amount >= 0),
    commission_rate       percent_rate,
    commission_amount     money_thb NOT NULL CHECK (commission_amount > 0),
    vat_on_commission     money_thb NOT NULL DEFAULT 0 CHECK (vat_on_commission >= 0),
    basis                 text NOT NULL CHECK (basis IN ('ESTIMATED_FROM_RATE', 'OTA_STATEMENT', 'OTA_INVOICE')),
    settlement_method     text NOT NULL CHECK (settlement_method IN (
                              'DEDUCTED_FROM_PAYOUT', 'INVOICED_TO_HOTEL', 'CHARGED_TO_CARD', 'UNKNOWN')),
    payout_payment_id     bigint REFERENCES payments (id),
    settlement_expense_id bigint REFERENCES expenses (id),
    ota_invoice_no        text,
    status                text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    source_system         text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'BANK', 'OTA', 'MANUAL', 'ANDROID_APP', 'SYSTEM')),
    source_ref            text,
    import_row_id         bigint REFERENCES import_rows (id),
    notes                 text,
    created_by            bigint REFERENCES users (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    verified_by           bigint REFERENCES users (id),
    verified_at           timestamptz,
    voided_by             bigint REFERENCES users (id),
    voided_at             timestamptz,
    void_reason           text,
    CHECK (basis <> 'ESTIMATED_FROM_RATE' OR (commission_rate IS NOT NULL AND commissionable_amount IS NOT NULL)),
    CHECK (payout_payment_id IS NULL OR settlement_method = 'DEDUCTED_FROM_PAYOUT'),
    CHECK (settlement_expense_id IS NULL OR settlement_method = 'INVOICED_TO_HOTEL'),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX ota_commissions_source_uq ON ota_commissions (property_id, source_system, source_ref)
    WHERE source_ref IS NOT NULL AND status <> 'VOIDED';
CREATE INDEX ota_commissions_date_idx ON ota_commissions (property_id, recognition_date);
SELECT attach_audit('ota_commissions');
SELECT attach_document_guards('ota_commissions', 'recognition_date');
SELECT comment_standard_columns('ota_commissions');
COMMENT ON TABLE ota_commissions IS 'ค่าคอมมิชชัน OTA — เป็นค่าใช้จ่ายครั้งเดียวที่นี่ การจ่ายใบแจ้งหนี้ OTA เป็นการล้างหนี้ (OTA_COMMISSION_SETTLEMENT) ไม่ใช่ค่าใช้จ่ายซ้ำ';
COMMENT ON COLUMN ota_commissions.channel_id IS 'OTA';
COMMENT ON COLUMN ota_commissions.reservation_id IS 'การจองที่เกิดค่าคอม';
COMMENT ON COLUMN ota_commissions.folio_id IS 'folio ที่เกี่ยวข้อง (ถ้าถูกหักจาก payout จะลดยอดลูกหนี้ของ folio นี้)';
COMMENT ON COLUMN ota_commissions.ota_booking_ref IS 'เลขการจองของ OTA';
COMMENT ON COLUMN ota_commissions.recognition_date IS 'วันที่รับรู้ค่าใช้จ่าย (วันที่ของรายได้ที่เกี่ยวข้อง)';
COMMENT ON COLUMN ota_commissions.commissionable_amount IS 'ฐานที่ใช้คิดค่าคอม';
COMMENT ON COLUMN ota_commissions.commission_rate IS 'อัตราที่ใช้คิด (%)';
COMMENT ON COLUMN ota_commissions.commission_amount IS 'ค่าคอมมิชชัน';
COMMENT ON COLUMN ota_commissions.vat_on_commission IS 'VAT ของค่าคอม (ถ้า OTA เรียกเก็บ — นักบัญชียืนยันการบันทึก)';
COMMENT ON COLUMN ota_commissions.basis IS 'ESTIMATED_FROM_RATE ประมาณจากอัตรา (ทำให้งวดเป็น Preliminary) / OTA_STATEMENT / OTA_INVOICE ตัวเลขจริง';
COMMENT ON COLUMN ota_commissions.settlement_method IS 'วิธีชำระค่าคอม: หักจาก payout / OTA ส่งใบแจ้งหนี้ / ตัดบัตร / ยังไม่ทราบ';
COMMENT ON COLUMN ota_commissions.payout_payment_id IS 'payout ที่ถูกหักค่าคอมนี้';
COMMENT ON COLUMN ota_commissions.settlement_expense_id IS 'รายการจ่ายที่ล้างหนี้ค่าคอมนี้';
COMMENT ON COLUMN ota_commissions.ota_invoice_no IS 'เลขที่ใบแจ้งหนี้ของ OTA';

CREATE FUNCTION fn_validate_ota_commission() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('ota_channels', NEW.channel_id, NEW.property_id);
    PERFORM assert_same_property('reservations', NEW.reservation_id, NEW.property_id);
    PERFORM assert_same_property('folios', NEW.folio_id, NEW.property_id);
    PERFORM assert_same_property('payments', NEW.payout_payment_id, NEW.property_id);
    PERFORM assert_same_property('expenses', NEW.settlement_expense_id, NEW.property_id);
    IF NEW.payout_payment_id IS NOT NULL
       AND (SELECT receipt_type FROM payments WHERE id = NEW.payout_payment_id) <> 'OTA_PAYOUT' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005', MESSAGE = 'ค่าคอมที่หักจาก payout ต้องอ้างถึงเงินรับประเภท OTA_PAYOUT';
    END IF;
    IF NEW.settlement_expense_id IS NOT NULL
       AND (SELECT c.treatment FROM expenses e JOIN expense_categories c ON c.id = e.category_id
            WHERE e.id = NEW.settlement_expense_id) <> 'OTA_COMMISSION_SETTLEMENT' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005',
            MESSAGE = 'รายการจ่ายที่ล้างค่าคอมต้องอยู่หมวด OTA_COMMISSION_SETTLEMENT (ไม่ใช่ค่าใช้จ่ายซ้ำ)';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_ota_commissions_validate BEFORE INSERT OR UPDATE ON ota_commissions
    FOR EACH ROW EXECUTE FUNCTION fn_validate_ota_commission();

-- ------------------------------------------------------------------ fixed assets

CREATE TABLE fixed_assets (
    id                                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id                       bigint NOT NULL REFERENCES properties (id),
    asset_name                        text NOT NULL,
    asset_class                       text NOT NULL CHECK (asset_class IN (
                                          'BUILDING_IMPROVEMENT', 'FURNITURE_FIXTURE', 'EQUIPMENT', 'VEHICLE', 'COMPUTER', 'OTHER')),
    acquisition_date                  date NOT NULL,
    acquisition_cost                  money_thb NOT NULL CHECK (acquisition_cost > 0),
    salvage_value                     money_thb NOT NULL DEFAULT 0 CHECK (salvage_value >= 0),
    useful_life_months                integer CHECK (useful_life_months > 0),
    depreciation_start_date           date,
    source_expense_id                 bigint REFERENCES expenses (id),
    asset_account_id                  bigint NOT NULL REFERENCES ledger_accounts (id),
    accumulated_depreciation_account_id bigint NOT NULL REFERENCES ledger_accounts (id),
    depreciation_expense_account_id   bigint NOT NULL REFERENCES ledger_accounts (id),
    policy_confirmed                  boolean NOT NULL DEFAULT false,
    status                            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISPOSED', 'CANCELLED')),
    disposed_on                       date,
    disposal_note                     text,
    created_by                        bigint REFERENCES users (id),
    created_at                        timestamptz NOT NULL DEFAULT now(),
    updated_at                        timestamptz NOT NULL DEFAULT now(),
    CHECK (salvage_value < acquisition_cost),
    CHECK (depreciation_start_date >= acquisition_date),
    CHECK (NOT policy_confirmed OR (useful_life_months IS NOT NULL AND depreciation_start_date IS NOT NULL)),
    CHECK (status <> 'DISPOSED' OR disposed_on IS NOT NULL)
);
SELECT attach_audit('fixed_assets');
SELECT comment_standard_columns('fixed_assets');
COMMENT ON TABLE fixed_assets IS 'ทะเบียนทรัพย์สิน — คิดค่าเสื่อมราคาเฉพาะเมื่อนักบัญชียืนยันอายุการใช้งาน (policy_confirmed)';
COMMENT ON COLUMN fixed_assets.status IS 'ACTIVE ใช้งาน / DISPOSED ขายหรือทิ้งแล้ว / CANCELLED บันทึกผิด';
COMMENT ON COLUMN fixed_assets.asset_name IS 'ชื่อทรัพย์สิน';
COMMENT ON COLUMN fixed_assets.asset_class IS 'ประเภททรัพย์สิน';
COMMENT ON COLUMN fixed_assets.acquisition_date IS 'วันที่ได้มา';
COMMENT ON COLUMN fixed_assets.acquisition_cost IS 'ราคาทุน';
COMMENT ON COLUMN fixed_assets.salvage_value IS 'มูลค่าซาก';
COMMENT ON COLUMN fixed_assets.useful_life_months IS 'อายุการใช้งาน (เดือน) — NULL = ยังไม่กำหนด ไม่คิดค่าเสื่อม';
COMMENT ON COLUMN fixed_assets.depreciation_start_date IS 'วันเริ่มคิดค่าเสื่อม';
COMMENT ON COLUMN fixed_assets.source_expense_id IS 'รายการจ่ายที่ซื้อทรัพย์สินนี้ (หมวด CAPITAL_EXPENDITURE)';
COMMENT ON COLUMN fixed_assets.asset_account_id IS 'บัญชีสินทรัพย์ (FIXED_ASSET)';
COMMENT ON COLUMN fixed_assets.accumulated_depreciation_account_id IS 'บัญชีค่าเสื่อมราคาสะสม';
COMMENT ON COLUMN fixed_assets.depreciation_expense_account_id IS 'บัญชีค่าเสื่อมราคา (ค่าใช้จ่าย)';
COMMENT ON COLUMN fixed_assets.policy_confirmed IS 'นักบัญชียืนยันนโยบายค่าเสื่อมแล้ว';
COMMENT ON COLUMN fixed_assets.disposed_on IS 'วันที่จำหน่าย';
COMMENT ON COLUMN fixed_assets.disposal_note IS 'หมายเหตุการจำหน่าย';

CREATE FUNCTION fn_validate_fixed_asset() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_ledger_account(NEW.asset_account_id, ARRAY['FIXED_ASSET'], 'ทรัพย์สิน');
    PERFORM assert_ledger_account(NEW.accumulated_depreciation_account_id, ARRAY['ACCUMULATED_DEPRECIATION'], 'ค่าเสื่อมราคาสะสม');
    IF (SELECT pnl_section FROM ledger_accounts WHERE id = NEW.depreciation_expense_account_id) IS DISTINCT FROM 'DEPRECIATION' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005', MESSAGE = 'บัญชีค่าเสื่อมราคาต้องอยู่ในหมวด DEPRECIATION';
    END IF;
    PERFORM assert_same_property('expenses', NEW.source_expense_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_fixed_assets_validate BEFORE INSERT OR UPDATE ON fixed_assets
    FOR EACH ROW EXECUTE FUNCTION fn_validate_fixed_asset();

-- ------------------------------------------------------------------ verified balances (bank statement / cash count)

CREATE TABLE balance_confirmations (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id       bigint NOT NULL REFERENCES properties (id),
    bank_account_id   bigint NOT NULL REFERENCES bank_accounts (id),
    as_of_date        date NOT NULL,
    confirmed_balance money_thb NOT NULL,
    method            text NOT NULL CHECK (method IN ('BANK_STATEMENT', 'CASH_COUNT', 'BANK_PORTAL')),
    imported_file_id  bigint REFERENCES imported_files (id),
    counted_by        bigint REFERENCES users (id),
    witnessed_by      bigint REFERENCES users (id),
    status            text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    notes             text,
    created_by        bigint REFERENCES users (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    verified_by       bigint REFERENCES users (id),
    verified_at       timestamptz,
    voided_by         bigint REFERENCES users (id),
    voided_at         timestamptz,
    void_reason       text,
    CHECK (method <> 'BANK_STATEMENT' OR imported_file_id IS NOT NULL),
    CHECK (method <> 'CASH_COUNT' OR counted_by IS NOT NULL),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE UNIQUE INDEX balance_confirmations_day_uq ON balance_confirmations (bank_account_id, as_of_date) WHERE status <> 'VOIDED';
SELECT attach_audit('balance_confirmations');
SELECT attach_document_guards('balance_confirmations', 'as_of_date');
SELECT comment_standard_columns('balance_confirmations');
COMMENT ON TABLE balance_confirmations IS 'ยอดเงินที่ตรวจสอบแล้ว ณ วันที่ (ยอดปลาย statement หรือการนับเงินสด) — ใช้แสดง "เงินสดและเงินฝากที่ตรวจสอบแล้ว"';
COMMENT ON COLUMN balance_confirmations.bank_account_id IS 'บัญชีเงิน';
COMMENT ON COLUMN balance_confirmations.as_of_date IS 'ยอด ณ สิ้นวันที่';
COMMENT ON COLUMN balance_confirmations.confirmed_balance IS 'ยอดคงเหลือที่ยืนยัน (ตามธนาคาร/ตามที่นับได้)';
COMMENT ON COLUMN balance_confirmations.method IS 'BANK_STATEMENT จาก statement / CASH_COUNT นับเงินสด / BANK_PORTAL ดูจากแอปธนาคารพร้อมหลักฐาน';
COMMENT ON COLUMN balance_confirmations.imported_file_id IS 'statement ที่ใช้ยืนยัน';
COMMENT ON COLUMN balance_confirmations.counted_by IS 'ผู้นับเงินสด';
COMMENT ON COLUMN balance_confirmations.witnessed_by IS 'พยานการนับ';

CREATE FUNCTION fn_validate_balance_confirmation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('bank_accounts', NEW.bank_account_id, NEW.property_id);
    PERFORM assert_same_property('imported_files', NEW.imported_file_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_balance_confirmations_validate BEFORE INSERT OR UPDATE ON balance_confirmations
    FOR EACH ROW EXECUTE FUNCTION fn_validate_balance_confirmation();
