-- =====================================================================================
-- 008 Tax planning (settings confirmed by the accountant, estimates clearly labelled)
--     and capital planning for the convenience-store fund.
-- No tax type or rate is assumed by the schema: everything comes from tax_profiles / tax_rules
-- that a person enters and the accountant confirms.
-- =====================================================================================

CREATE TABLE tax_profiles (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id             bigint NOT NULL REFERENCES properties (id),
    effective_from          date NOT NULL,
    effective_to            date,
    entity_type             text NOT NULL DEFAULT 'UNKNOWN' CHECK (entity_type IN (
                                'INDIVIDUAL', 'ORDINARY_PARTNERSHIP', 'REGISTERED_PARTNERSHIP', 'LIMITED_COMPANY', 'UNKNOWN')),
    vat_status              text NOT NULL DEFAULT 'UNKNOWN' CHECK (vat_status IN ('REGISTERED', 'NOT_REGISTERED', 'UNKNOWN')),
    vat_registered_on       date,
    room_prices_include_vat boolean,
    hotel_license_status    text NOT NULL DEFAULT 'UNKNOWN' CHECK (hotel_license_status IN ('LICENSED', 'NOT_LICENSED', 'IN_PROGRESS', 'UNKNOWN')),
    income_tax_method       text NOT NULL DEFAULT 'UNKNOWN' CHECK (income_tax_method IN (
                                'ACTUAL_EXPENSES', 'STANDARD_DEDUCTION', 'CORPORATE', 'UNKNOWN')),
    accountant_name         text,
    confirmed_by_accountant boolean NOT NULL DEFAULT false,
    confirmed_on            date,
    confirmation_note       text,
    created_by              bigint REFERENCES users (id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to >= effective_from),
    CHECK (vat_status <> 'REGISTERED' OR vat_registered_on IS NOT NULL),
    CHECK (NOT confirmed_by_accountant OR (accountant_name IS NOT NULL AND confirmed_on IS NOT NULL
           AND entity_type <> 'UNKNOWN' AND vat_status <> 'UNKNOWN'))
);
SELECT attach_audit('tax_profiles');
COMMENT ON TABLE tax_profiles IS 'สถานะทางภาษีของกิจการตามช่วงเวลา — ค่าเริ่มต้น UNKNOWN จนกว่านักบัญชียืนยัน';
COMMENT ON COLUMN tax_profiles.id IS 'รหัส (PK)';
COMMENT ON COLUMN tax_profiles.property_id IS 'กิจการ';
COMMENT ON COLUMN tax_profiles.effective_from IS 'มีผลตั้งแต่';
COMMENT ON COLUMN tax_profiles.effective_to IS 'มีผลถึง (NULL = ปัจจุบัน) ห้ามซ้อนกัน';
COMMENT ON COLUMN tax_profiles.entity_type IS 'รูปแบบกิจการ: บุคคลธรรมดา / ห้างหุ้นส่วนสามัญ / ห้างหุ้นส่วนจดทะเบียน / บริษัท / ยังไม่ทราบ';
COMMENT ON COLUMN tax_profiles.vat_status IS 'สถานะจดทะเบียนภาษีมูลค่าเพิ่ม';
COMMENT ON COLUMN tax_profiles.vat_registered_on IS 'วันที่จด VAT';
COMMENT ON COLUMN tax_profiles.room_prices_include_vat IS 'ราคาห้องรวม VAT แล้วหรือไม่';
COMMENT ON COLUMN tax_profiles.hotel_license_status IS 'สถานะใบอนุญาตประกอบธุรกิจโรงแรม';
COMMENT ON COLUMN tax_profiles.income_tax_method IS 'วิธีคำนวณภาษีเงินได้ที่นักบัญชีเลือก';
COMMENT ON COLUMN tax_profiles.accountant_name IS 'ชื่อนักบัญชีผู้ยืนยัน';
COMMENT ON COLUMN tax_profiles.confirmed_by_accountant IS 'นักบัญชียืนยันข้อมูลชุดนี้แล้ว';
COMMENT ON COLUMN tax_profiles.confirmed_on IS 'วันที่ยืนยัน';
COMMENT ON COLUMN tax_profiles.confirmation_note IS 'บันทึกการยืนยัน/เอกสารอ้างอิง';
COMMENT ON COLUMN tax_profiles.created_by IS 'ผู้บันทึก';
COMMENT ON COLUMN tax_profiles.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN tax_profiles.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_validate_tax_profile() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tax_profiles t
        WHERE t.property_id = NEW.property_id AND t.id <> NEW.id
          AND daterange(t.effective_from, t.effective_to, '[]') && daterange(NEW.effective_from, NEW.effective_to, '[]')
    ) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC005', MESSAGE = 'ช่วงเวลาของสถานะภาษีซ้อนกับชุดอื่น';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_tax_profiles_validate BEFORE INSERT OR UPDATE ON tax_profiles
    FOR EACH ROW EXECUTE FUNCTION fn_validate_tax_profile();

CREATE TABLE tax_rules (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id             bigint NOT NULL REFERENCES properties (id),
    tax_type                text NOT NULL CHECK (tax_type IN (
                                'VAT', 'PERSONAL_INCOME_TAX', 'CORPORATE_INCOME_TAX', 'WITHHOLDING_TAX',
                                'LAND_BUILDING_TAX', 'SIGNBOARD_TAX', 'HOTEL_FEE', 'SOCIAL_SECURITY', 'OTHER')),
    name_th                 text NOT NULL,
    calc_method             text NOT NULL CHECK (calc_method IN (
                                'PERCENT_OF_REVENUE', 'PERCENT_OF_PROFIT', 'FIXED_AMOUNT', 'PROGRESSIVE_TABLE', 'MANUAL')),
    rate                    percent_rate,
    fixed_amount            money_thb CHECK (fixed_amount >= 0),
    brackets                jsonb CHECK (jsonb_typeof(brackets) = 'array'),
    filing_frequency        text CHECK (filing_frequency IN ('MONTHLY', 'HALF_YEARLY', 'YEARLY', 'ONE_TIME')),
    filing_form             text,
    due_rule_note           text,
    effective_from          date NOT NULL,
    effective_to            date,
    source_reference        text NOT NULL,
    confirmed_by_accountant boolean NOT NULL DEFAULT false,
    confirmed_on            date,
    created_by              bigint REFERENCES users (id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to >= effective_from),
    CHECK (calc_method NOT IN ('PERCENT_OF_REVENUE', 'PERCENT_OF_PROFIT') OR rate IS NOT NULL),
    CHECK (calc_method <> 'FIXED_AMOUNT' OR fixed_amount IS NOT NULL),
    CHECK (calc_method <> 'PROGRESSIVE_TABLE' OR brackets IS NOT NULL),
    CHECK (NOT confirmed_by_accountant OR confirmed_on IS NOT NULL)
);
SELECT attach_audit('tax_rules');
COMMENT ON TABLE tax_rules IS 'สูตร/อัตราภาษีที่ผู้ใช้ตั้งค่าตามที่นักบัญชีแจ้ง (ระบบไม่กำหนดอัตราเอง)';
COMMENT ON COLUMN tax_rules.id IS 'รหัส (PK)';
COMMENT ON COLUMN tax_rules.property_id IS 'กิจการ';
COMMENT ON COLUMN tax_rules.tax_type IS 'ประเภทภาษี/ภาระ';
COMMENT ON COLUMN tax_rules.name_th IS 'ชื่อที่แสดง';
COMMENT ON COLUMN tax_rules.calc_method IS 'วิธีคำนวณ: % ของรายได้ / % ของกำไร / จำนวนคงที่ / ตารางขั้นบันได / กรอกเอง';
COMMENT ON COLUMN tax_rules.rate IS 'อัตรา (%)';
COMMENT ON COLUMN tax_rules.fixed_amount IS 'จำนวนคงที่';
COMMENT ON COLUMN tax_rules.brackets IS 'ตารางขั้นบันได [{from, to, rate}] ตามที่นักบัญชีให้';
COMMENT ON COLUMN tax_rules.filing_frequency IS 'ความถี่การยื่น';
COMMENT ON COLUMN tax_rules.filing_form IS 'แบบที่ใช้ยื่น (ข้อความตามที่นักบัญชีแจ้ง)';
COMMENT ON COLUMN tax_rules.due_rule_note IS 'กำหนดยื่น/ชำระ (ข้อความ)';
COMMENT ON COLUMN tax_rules.effective_from IS 'มีผลตั้งแต่';
COMMENT ON COLUMN tax_rules.effective_to IS 'มีผลถึง';
COMMENT ON COLUMN tax_rules.source_reference IS 'ที่มาของสูตร (บังคับ) เช่น ชื่อนักบัญชี/เอกสารกรมสรรพากร';
COMMENT ON COLUMN tax_rules.confirmed_by_accountant IS 'นักบัญชียืนยันแล้ว';
COMMENT ON COLUMN tax_rules.confirmed_on IS 'วันที่ยืนยัน';
COMMENT ON COLUMN tax_rules.created_by IS 'ผู้บันทึก';
COMMENT ON COLUMN tax_rules.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN tax_rules.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE tax_estimates (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id          bigint NOT NULL REFERENCES properties (id),
    tax_rule_id          bigint REFERENCES tax_rules (id),
    tax_type             text NOT NULL,
    period_start         date NOT NULL,
    period_end           date NOT NULL,
    basis_amount         money_thb,
    basis_description    text NOT NULL,
    estimated_amount     money_thb NOT NULL CHECK (estimated_amount >= 0),
    estimate_status      text NOT NULL DEFAULT 'SYSTEM_ESTIMATE' CHECK (estimate_status IN (
                             'SYSTEM_ESTIMATE', 'ACCOUNTANT_REVIEWED', 'FILED', 'PAID', 'CANCELLED')),
    data_completeness    text NOT NULL CHECK (data_completeness IN ('FINAL', 'PRELIMINARY', 'INCOMPLETE')),
    due_date             date,
    reviewed_by_accountant_name text,
    reviewed_on          date,
    filed_amount         money_thb CHECK (filed_amount >= 0),
    filed_on             date,
    paid_amount          money_thb CHECK (paid_amount >= 0),
    payment_expense_id   bigint REFERENCES expenses (id),
    notes                text,
    created_by           bigint REFERENCES users (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end >= period_start),
    CHECK (estimate_status <> 'ACCOUNTANT_REVIEWED' OR (reviewed_by_accountant_name IS NOT NULL AND reviewed_on IS NOT NULL)),
    CHECK (estimate_status NOT IN ('FILED', 'PAID') OR (filed_amount IS NOT NULL AND filed_on IS NOT NULL)),
    CHECK (estimate_status <> 'PAID' OR paid_amount IS NOT NULL)
);
CREATE INDEX tax_estimates_period_idx ON tax_estimates (property_id, period_start);
SELECT attach_audit('tax_estimates');
COMMENT ON TABLE tax_estimates IS 'ประมาณการภาษีที่ควรกันเงินไว้ — ไม่ใช่ยอดภาษีที่ต้องชำระจริงจนกว่าจะยื่นแบบ (FILED)';
COMMENT ON COLUMN tax_estimates.id IS 'รหัส (PK)';
COMMENT ON COLUMN tax_estimates.property_id IS 'กิจการ';
COMMENT ON COLUMN tax_estimates.tax_rule_id IS 'สูตรที่ใช้ประมาณ';
COMMENT ON COLUMN tax_estimates.tax_type IS 'ประเภทภาษี';
COMMENT ON COLUMN tax_estimates.period_start IS 'งวดภาษี เริ่ม';
COMMENT ON COLUMN tax_estimates.period_end IS 'งวดภาษี สิ้นสุด';
COMMENT ON COLUMN tax_estimates.basis_amount IS 'ฐานที่ใช้คำนวณ (จากข้อมูลที่ยืนยันแล้วเท่านั้น)';
COMMENT ON COLUMN tax_estimates.basis_description IS 'คำอธิบายฐาน เช่น "รายได้ห้องพัก VERIFIED ม.ค.–มิ.ย. 2569"';
COMMENT ON COLUMN tax_estimates.estimated_amount IS 'ยอดประมาณการที่ควรกันไว้';
COMMENT ON COLUMN tax_estimates.estimate_status IS 'SYSTEM_ESTIMATE ระบบประมาณ / ACCOUNTANT_REVIEWED นักบัญชีตรวจ / FILED ยื่นแล้ว / PAID ชำระแล้ว / CANCELLED';
COMMENT ON COLUMN tax_estimates.data_completeness IS 'ความครบถ้วนของข้อมูลฐาน ณ ตอนประมาณ';
COMMENT ON COLUMN tax_estimates.due_date IS 'กำหนดยื่น/ชำระ';
COMMENT ON COLUMN tax_estimates.reviewed_by_accountant_name IS 'นักบัญชีผู้ตรวจ';
COMMENT ON COLUMN tax_estimates.reviewed_on IS 'วันที่ตรวจ';
COMMENT ON COLUMN tax_estimates.filed_amount IS 'ยอดตามแบบที่ยื่นจริง';
COMMENT ON COLUMN tax_estimates.filed_on IS 'วันที่ยื่น';
COMMENT ON COLUMN tax_estimates.paid_amount IS 'ยอดที่ชำระจริง';
COMMENT ON COLUMN tax_estimates.payment_expense_id IS 'รายการจ่ายที่ชำระภาษีนี้';
COMMENT ON COLUMN tax_estimates.notes IS 'หมายเหตุ';
COMMENT ON COLUMN tax_estimates.created_by IS 'ผู้สร้าง';
COMMENT ON COLUMN tax_estimates.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN tax_estimates.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_guard_tax_estimate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.estimate_status <> 'SYSTEM_ESTIMATE' THEN
            RAISE EXCEPTION USING ERRCODE = 'AC006', MESSAGE = 'ประมาณการที่ตรวจ/ยื่นแล้วลบไม่ได้ ให้เปลี่ยนเป็น CANCELLED';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.estimate_status IN ('PAID', 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = format('ภาษีสถานะ %s แก้ไขไม่ได้', OLD.estimate_status);
    END IF;
    IF OLD.estimate_status = 'FILED' AND (
        NEW.estimate_status NOT IN ('FILED', 'PAID')
        OR (to_jsonb(OLD) - ARRAY['estimate_status', 'paid_amount', 'payment_expense_id', 'notes', 'updated_at'])
           IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['estimate_status', 'paid_amount', 'payment_expense_id', 'notes', 'updated_at'])) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'ภาษีที่ยื่นแล้วแก้ได้เฉพาะข้อมูลการชำระ';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_tax_estimates_guard BEFORE UPDATE OR DELETE ON tax_estimates
    FOR EACH ROW EXECUTE FUNCTION fn_guard_tax_estimate();

-- ------------------------------------------------------------------ capital planning

CREATE TABLE capital_goals (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id                 bigint NOT NULL REFERENCES properties (id),
    name                        text NOT NULL,
    target_amount               money_thb NOT NULL CHECK (target_amount > 0),
    start_date                  date NOT NULL,
    target_date                 date,
    status                      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ACHIEVED', 'PAUSED', 'CANCELLED')),
    fund_bank_account_id        bigint REFERENCES bank_accounts (id),
    operating_reserve_months    numeric(4, 1) CHECK (operating_reserve_months >= 0),
    operating_reserve_min_amount money_thb CHECK (operating_reserve_min_amount >= 0),
    obligation_horizon_months   smallint NOT NULL DEFAULT 3 CHECK (obligation_horizon_months BETWEEN 0 AND 24),
    policy_confirmed_by_owner   boolean NOT NULL DEFAULT false,
    created_by                  bigint REFERENCES users (id),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CHECK (target_date > start_date),
    CHECK (NOT policy_confirmed_by_owner OR operating_reserve_months IS NOT NULL)
);
SELECT attach_audit('capital_goals');
COMMENT ON TABLE capital_goals IS 'เป้าหมายเงินทุน เช่น ทุนเปิดร้านสะดวกซื้อ 2,000,000 บาท (เก็บเป็นข้อมูล ไม่ฝังในโค้ด)';
COMMENT ON COLUMN capital_goals.id IS 'รหัสเป้าหมาย (PK)';
COMMENT ON COLUMN capital_goals.property_id IS 'กิจการที่เป็นแหล่งเงิน (รีสอร์ต)';
COMMENT ON COLUMN capital_goals.name IS 'ชื่อเป้าหมาย';
COMMENT ON COLUMN capital_goals.target_amount IS 'เงินทุนเป้าหมาย';
COMMENT ON COLUMN capital_goals.start_date IS 'วันเริ่มเก็บ';
COMMENT ON COLUMN capital_goals.target_date IS 'วันที่อยากเปิดร้าน (ถ้ามี)';
COMMENT ON COLUMN capital_goals.status IS 'ACTIVE / ACHIEVED / PAUSED / CANCELLED';
COMMENT ON COLUMN capital_goals.fund_bank_account_id IS 'บัญชีที่แยกเก็บกองทุน (แนะนำให้แยก) — NULL = กันไว้ในบัญชีรวม';
COMMENT ON COLUMN capital_goals.operating_reserve_months IS 'นโยบายเงินสำรองรีสอร์ต: กี่เดือนของค่าใช้จ่ายดำเนินงานเฉลี่ย (เจ้าของกำหนด)';
COMMENT ON COLUMN capital_goals.operating_reserve_min_amount IS 'เงินสำรองขั้นต่ำเป็นจำนวนเงิน';
COMMENT ON COLUMN capital_goals.obligation_horizon_months IS 'จำนวนเดือนข้างหน้าของค่างวดหนี้ที่ต้องกันไว้';
COMMENT ON COLUMN capital_goals.policy_confirmed_by_owner IS 'เจ้าของยืนยันนโยบายเงินสำรองแล้ว';
COMMENT ON COLUMN capital_goals.created_by IS 'ผู้สร้าง';
COMMENT ON COLUMN capital_goals.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN capital_goals.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE capital_scenarios (
    id                                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    goal_id                           bigint NOT NULL REFERENCES capital_goals (id),
    scenario_type                     text NOT NULL CHECK (scenario_type IN ('CONSERVATIVE', 'BASE', 'OPTIMISTIC', 'CUSTOM')),
    name                              text NOT NULL,
    lookback_months                   smallint NOT NULL DEFAULT 6 CHECK (lookback_months BETWEEN 1 AND 36),
    revenue_change_pct                numeric(6, 2) NOT NULL DEFAULT 0 CHECK (revenue_change_pct BETWEEN -100 AND 100),
    expense_change_pct                numeric(6, 2) NOT NULL DEFAULT 0 CHECK (expense_change_pct BETWEEN -100 AND 300),
    allocation_pct                    percent_rate NOT NULL,
    extra_monthly_obligations         money_thb NOT NULL DEFAULT 0 CHECK (extra_monthly_obligations >= 0),
    owner_monthly_drawings            money_thb NOT NULL DEFAULT 0 CHECK (owner_monthly_drawings >= 0),
    fallback_annual_revenue           money_thb CHECK (fallback_annual_revenue > 0),
    fallback_monthly_operating_expense money_thb CHECK (fallback_monthly_operating_expense >= 0),
    notes                             text,
    created_by                        bigint REFERENCES users (id),
    created_at                        timestamptz NOT NULL DEFAULT now(),
    updated_at                        timestamptz NOT NULL DEFAULT now(),
    CHECK ((fallback_annual_revenue IS NULL) = (fallback_monthly_operating_expense IS NULL))
);
CREATE UNIQUE INDEX capital_scenarios_type_uq ON capital_scenarios (goal_id, scenario_type) WHERE scenario_type <> 'CUSTOM';
SELECT attach_audit('capital_scenarios');
COMMENT ON TABLE capital_scenarios IS 'สมมติฐานการวางแผน (Conservative / Base / Optimistic) — ใช้เงินส่วนเกินหลังหักภาระ ไม่ใช้รายได้รวม';
COMMENT ON COLUMN capital_scenarios.id IS 'รหัส (PK)';
COMMENT ON COLUMN capital_scenarios.goal_id IS 'เป้าหมาย';
COMMENT ON COLUMN capital_scenarios.scenario_type IS 'ประเภทสถานการณ์';
COMMENT ON COLUMN capital_scenarios.name IS 'ชื่อที่แสดง';
COMMENT ON COLUMN capital_scenarios.lookback_months IS 'ใช้ผลจริงย้อนหลังกี่เดือน (เฉพาะงวดที่ปิดแล้ว) เป็นฐาน';
COMMENT ON COLUMN capital_scenarios.revenue_change_pct IS 'ปรับรายได้จากฐานกี่ % (สมมติฐาน)';
COMMENT ON COLUMN capital_scenarios.expense_change_pct IS 'ปรับค่าใช้จ่ายจากฐานกี่ % (สมมติฐาน)';
COMMENT ON COLUMN capital_scenarios.allocation_pct IS 'สัดส่วนของเงินส่วนเกินต่อเดือนที่จะโอนเข้ากองทุน (%)';
COMMENT ON COLUMN capital_scenarios.extra_monthly_obligations IS 'ภาระรายเดือนอื่นที่ยังไม่มีในระบบ';
COMMENT ON COLUMN capital_scenarios.owner_monthly_drawings IS 'เงินที่เจ้าของถอนใช้ส่วนตัวต่อเดือน';
COMMENT ON COLUMN capital_scenarios.fallback_annual_revenue IS 'รายได้ต่อปีที่เจ้าของประมาณ (เช่น 1,900,000) ใช้เฉพาะเมื่อยังไม่มีงวดที่ปิดแล้ว ผลลัพธ์ต้องติดป้าย "ยังไม่ตรวจสอบ"';
COMMENT ON COLUMN capital_scenarios.fallback_monthly_operating_expense IS 'ค่าใช้จ่ายต่อเดือนที่เจ้าของประมาณ (ต้องกรอกคู่กับรายได้ประมาณการ)';
COMMENT ON COLUMN capital_scenarios.notes IS 'หมายเหตุสมมติฐาน';
COMMENT ON COLUMN capital_scenarios.created_by IS 'ผู้สร้าง';
COMMENT ON COLUMN capital_scenarios.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN capital_scenarios.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE capital_fund_transactions (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id         bigint NOT NULL REFERENCES properties (id),
    goal_id             bigint NOT NULL REFERENCES capital_goals (id),
    txn_date            date NOT NULL,
    txn_type            text NOT NULL CHECK (txn_type IN ('OPENING_BALANCE', 'ALLOCATION', 'WITHDRAWAL', 'INTEREST', 'ADJUSTMENT')),
    amount              money_thb NOT NULL CHECK (amount <> 0),
    fund_transfer_id    bigint REFERENCES fund_transfers (id),
    bank_transaction_id bigint REFERENCES bank_transactions (id),
    status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VERIFIED', 'VOIDED')),
    notes               text,
    created_by          bigint REFERENCES users (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    verified_by         bigint REFERENCES users (id),
    verified_at         timestamptz,
    voided_by           bigint REFERENCES users (id),
    voided_at           timestamptz,
    void_reason         text,
    CHECK (txn_type NOT IN ('OPENING_BALANCE', 'ALLOCATION', 'INTEREST') OR amount > 0),
    CHECK (txn_type <> 'WITHDRAWAL' OR amount < 0),
    CHECK (txn_type <> 'ADJUSTMENT' OR has_text(notes)),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (status <> 'VOIDED' OR (voided_by IS NOT NULL AND voided_at IS NOT NULL AND has_text(void_reason)))
);
CREATE INDEX capital_fund_transactions_goal_idx ON capital_fund_transactions (goal_id, txn_date);
SELECT attach_audit('capital_fund_transactions');
SELECT attach_document_guards('capital_fund_transactions', 'txn_date');
SELECT comment_standard_columns('capital_fund_transactions');
COMMENT ON TABLE capital_fund_transactions IS 'ความเคลื่อนไหวของกองทุนเปิดร้าน — นับเป็น "เงินทุนสะสมแล้ว" เฉพาะรายการ VERIFIED';
COMMENT ON COLUMN capital_fund_transactions.goal_id IS 'เป้าหมาย';
COMMENT ON COLUMN capital_fund_transactions.txn_date IS 'วันที่';
COMMENT ON COLUMN capital_fund_transactions.txn_type IS 'OPENING_BALANCE ยอดตั้งต้น / ALLOCATION กันเงินเพิ่ม / WITHDRAWAL ถอนออก / INTEREST ดอกเบี้ย / ADJUSTMENT ปรับปรุง (ต้องมีเหตุผล)';
COMMENT ON COLUMN capital_fund_transactions.amount IS 'จำนวนเงิน: บวก = เข้ากองทุน, ลบ = ออกจากกองทุน';
COMMENT ON COLUMN capital_fund_transactions.fund_transfer_id IS 'การโอนเงินจริงที่เป็นหลักฐาน (ถ้าแยกบัญชี)';
COMMENT ON COLUMN capital_fund_transactions.bank_transaction_id IS 'รายการธนาคารที่เป็นหลักฐาน';

CREATE FUNCTION fn_validate_capital_fund_transaction() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('capital_goals', NEW.goal_id, NEW.property_id);
    PERFORM assert_same_property('fund_transfers', NEW.fund_transfer_id, NEW.property_id);
    PERFORM assert_same_property('bank_transactions', NEW.bank_transaction_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_capital_fund_transactions_validate BEFORE INSERT OR UPDATE ON capital_fund_transactions
    FOR EACH ROW EXECUTE FUNCTION fn_validate_capital_fund_transaction();
