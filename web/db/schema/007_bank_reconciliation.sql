-- =====================================================================================
-- 007 Bank statement lines and reconciliation (many-to-many, partial matches allowed).
-- Reconciliation only LINKS existing records: it never creates a receipt or an expense.
-- Match status (Matched / Partially Matched / Unmatched / Needs Review) is derived in views.
-- =====================================================================================

CREATE TABLE bank_transactions (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id      bigint NOT NULL REFERENCES properties (id),
    bank_account_id  bigint NOT NULL REFERENCES bank_accounts (id),
    txn_date         date NOT NULL,
    txn_time         time,
    value_date       date,
    description      text,
    bank_channel     text,
    counterparty     text,
    bank_reference   text,
    amount           money_thb NOT NULL CHECK (amount <> 0),
    balance_after    money_thb,
    dedupe_key       text NOT NULL,
    imported_file_id bigint REFERENCES imported_files (id),
    import_row_id    bigint REFERENCES import_rows (id),
    review_status    text NOT NULL DEFAULT 'OPEN' CHECK (review_status IN ('OPEN', 'NEEDS_REVIEW', 'IGNORED')),
    review_note      text,
    reviewed_by      bigint REFERENCES users (id),
    reviewed_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bank_account_id, dedupe_key),
    CHECK (review_status = 'OPEN' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK (review_status <> 'IGNORED' OR has_text(review_note))
);
CREATE INDEX bank_transactions_date_idx ON bank_transactions (property_id, txn_date);
CREATE INDEX bank_transactions_amount_idx ON bank_transactions (bank_account_id, amount, txn_date);
SELECT attach_audit('bank_transactions');
CREATE TRIGGER trg_bank_transactions_period BEFORE INSERT OR UPDATE OR DELETE ON bank_transactions
    FOR EACH ROW EXECUTE FUNCTION fn_guard_closed_period('txn_date');
COMMENT ON TABLE bank_transactions IS 'รายการเดินบัญชีจาก statement ธนาคาร (ข้อมูลของธนาคาร แก้ไม่ได้ ยกเว้นสถานะการตรวจ)';
COMMENT ON COLUMN bank_transactions.id IS 'รหัสรายการ (PK)';
COMMENT ON COLUMN bank_transactions.property_id IS 'กิจการ';
COMMENT ON COLUMN bank_transactions.bank_account_id IS 'บัญชีธนาคาร';
COMMENT ON COLUMN bank_transactions.txn_date IS 'วันที่ทำรายการตาม statement';
COMMENT ON COLUMN bank_transactions.txn_time IS 'เวลาทำรายการ (ถ้ามี)';
COMMENT ON COLUMN bank_transactions.value_date IS 'วันที่มีผล (ถ้าต่างจากวันทำรายการ)';
COMMENT ON COLUMN bank_transactions.description IS 'คำอธิบายจากธนาคาร';
COMMENT ON COLUMN bank_transactions.bank_channel IS 'ช่องทาง เช่น K PLUS, ATM, EDC';
COMMENT ON COLUMN bank_transactions.counterparty IS 'ชื่อ/บัญชีคู่โอน (ถ้ามี)';
COMMENT ON COLUMN bank_transactions.bank_reference IS 'เลขอ้างอิงของธนาคาร';
COMMENT ON COLUMN bank_transactions.amount IS 'จำนวนเงิน: บวก = เงินเข้า, ลบ = เงินออก';
COMMENT ON COLUMN bank_transactions.balance_after IS 'ยอดคงเหลือหลังรายการตาม statement (ใช้ตรวจความต่อเนื่อง)';
COMMENT ON COLUMN bank_transactions.dedupe_key IS 'กุญแจกันซ้ำที่ importer คำนวณ (วันที่ เวลา ยอด ยอดคงเหลือ อ้างอิง ลำดับในวัน)';
COMMENT ON COLUMN bank_transactions.imported_file_id IS 'statement ที่นำเข้า';
COMMENT ON COLUMN bank_transactions.import_row_id IS 'แถวข้อมูลดิบที่มา';
COMMENT ON COLUMN bank_transactions.review_status IS 'OPEN ปกติ / NEEDS_REVIEW ต้องให้คนดู / IGNORED ไม่ต้องจับคู่ (ต้องมีเหตุผล เช่น ดอกเบี้ยที่บันทึกรวมแล้ว)';
COMMENT ON COLUMN bank_transactions.review_note IS 'บันทึกการตรวจ';
COMMENT ON COLUMN bank_transactions.reviewed_by IS 'ผู้ตรวจ';
COMMENT ON COLUMN bank_transactions.reviewed_at IS 'เวลาที่ตรวจ';
COMMENT ON COLUMN bank_transactions.created_at IS 'เวลาที่นำเข้า';

CREATE FUNCTION fn_guard_bank_transaction() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    c_mutable constant text[] := ARRAY['review_status', 'review_note', 'reviewed_by', 'reviewed_at'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC006', MESSAGE = 'รายการเดินบัญชีธนาคารลบไม่ได้';
    END IF;
    IF TG_OP = 'UPDATE' AND (to_jsonb(OLD) - c_mutable) IS DISTINCT FROM (to_jsonb(NEW) - c_mutable) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'ข้อมูลจากธนาคารแก้ไขไม่ได้ แก้ได้เฉพาะสถานะการตรวจ';
    END IF;
    PERFORM assert_same_property('bank_accounts', NEW.bank_account_id, NEW.property_id);
    PERFORM assert_same_property('imported_files', NEW.imported_file_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_bank_transactions_guard BEFORE INSERT OR UPDATE OR DELETE ON bank_transactions
    FOR EACH ROW EXECUTE FUNCTION fn_guard_bank_transaction();

CREATE TABLE reconciliations (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id       bigint NOT NULL REFERENCES properties (id),
    match_method      text NOT NULL CHECK (match_method IN ('AUTO_EXACT', 'AUTO_RULE', 'MANUAL')),
    rule_code         text,
    confidence        numeric(5, 4) CHECK (confidence BETWEEN 0 AND 1),
    status            text NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'REVERSED')),
    difference_amount money_thb NOT NULL DEFAULT 0,
    difference_reason text,
    notes             text,
    created_by        bigint REFERENCES users (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    confirmed_by      bigint REFERENCES users (id),
    confirmed_at      timestamptz,
    rejected_by       bigint REFERENCES users (id),
    rejected_at       timestamptz,
    reversed_by       bigint REFERENCES users (id),
    reversed_at       timestamptz,
    reverse_reason    text,
    CHECK (match_method = 'MANUAL' OR rule_code IS NOT NULL),
    CHECK (status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL)),
    CHECK (status <> 'REVERSED' OR (reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND has_text(reverse_reason))),
    CHECK (difference_amount = 0 OR has_text(difference_reason))
);
SELECT attach_audit('reconciliations');
COMMENT ON TABLE reconciliations IS 'ชุดการจับคู่ระหว่างรายการธนาคารกับรายการในระบบ (ระบบเสนอได้ แต่คนต้องยืนยัน)';
COMMENT ON COLUMN reconciliations.id IS 'รหัสชุดจับคู่ (PK)';
COMMENT ON COLUMN reconciliations.property_id IS 'กิจการ';
COMMENT ON COLUMN reconciliations.match_method IS 'AUTO_EXACT ยอด+วันที่+อ้างอิงตรง / AUTO_RULE ตามกฎ / MANUAL คนจับคู่เอง';
COMMENT ON COLUMN reconciliations.rule_code IS 'กฎที่ใช้เสนอ (บังคับเมื่อเป็น AUTO)';
COMMENT ON COLUMN reconciliations.confidence IS 'ความมั่นใจของการเสนอ 0–1';
COMMENT ON COLUMN reconciliations.status IS 'PROPOSED เสนอ / CONFIRMED ยืนยัน / REJECTED ปฏิเสธ / REVERSED ยกเลิกหลังยืนยัน';
COMMENT ON COLUMN reconciliations.difference_amount IS 'ผลต่าง = ยอดฝั่งธนาคาร − ยอดฝั่งบัญชี (ไม่ใช่ 0 ต้องมีเหตุผล ระบบไม่สร้างรายการปรับให้เอง)';
COMMENT ON COLUMN reconciliations.difference_reason IS 'เหตุผลของผลต่าง';
COMMENT ON COLUMN reconciliations.notes IS 'หมายเหตุ';
COMMENT ON COLUMN reconciliations.created_by IS 'ผู้สร้าง (NULL = ระบบเสนอ)';
COMMENT ON COLUMN reconciliations.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN reconciliations.updated_at IS 'เวลาที่แก้ไขล่าสุด';
COMMENT ON COLUMN reconciliations.confirmed_by IS 'ผู้ยืนยัน';
COMMENT ON COLUMN reconciliations.confirmed_at IS 'เวลาที่ยืนยัน';
COMMENT ON COLUMN reconciliations.rejected_by IS 'ผู้ปฏิเสธ';
COMMENT ON COLUMN reconciliations.rejected_at IS 'เวลาที่ปฏิเสธ';
COMMENT ON COLUMN reconciliations.reversed_by IS 'ผู้ยกเลิกการจับคู่';
COMMENT ON COLUMN reconciliations.reversed_at IS 'เวลาที่ยกเลิกการจับคู่';
COMMENT ON COLUMN reconciliations.reverse_reason IS 'เหตุผลที่ยกเลิกการจับคู่ (บังคับ)';

CREATE TABLE reconciliation_items (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reconciliation_id  bigint NOT NULL REFERENCES reconciliations (id),
    side               text NOT NULL CHECK (side IN ('BANK', 'BOOK')),
    bank_transaction_id bigint REFERENCES bank_transactions (id),
    payment_id         bigint REFERENCES payments (id),
    expense_payment_id bigint REFERENCES expense_payments (id),
    fund_transfer_id   bigint REFERENCES fund_transfers (id),
    transfer_leg       text CHECK (transfer_leg IN ('OUT', 'IN')),
    amount             money_thb NOT NULL CHECK (amount > 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(bank_transaction_id, payment_id, expense_payment_id, fund_transfer_id) = 1),
    CHECK ((side = 'BANK') = (bank_transaction_id IS NOT NULL)),
    CHECK ((fund_transfer_id IS NOT NULL) = (transfer_leg IS NOT NULL))
);
CREATE INDEX reconciliation_items_rec_idx ON reconciliation_items (reconciliation_id);
CREATE INDEX reconciliation_items_bank_idx ON reconciliation_items (bank_transaction_id) WHERE bank_transaction_id IS NOT NULL;
CREATE INDEX reconciliation_items_payment_idx ON reconciliation_items (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX reconciliation_items_exp_pay_idx ON reconciliation_items (expense_payment_id) WHERE expense_payment_id IS NOT NULL;
SELECT attach_audit('reconciliation_items');
COMMENT ON TABLE reconciliation_items IS 'รายการในชุดจับคู่ (ฝั่งธนาคาร/ฝั่งบัญชี) และยอดที่ใช้ — รายการเดียวถูกจับคู่บางส่วนได้หลายชุด แต่รวมแล้วต้องไม่เกินยอดจริง';
COMMENT ON COLUMN reconciliation_items.id IS 'รหัส (PK)';
COMMENT ON COLUMN reconciliation_items.reconciliation_id IS 'ชุดจับคู่';
COMMENT ON COLUMN reconciliation_items.side IS 'BANK รายการธนาคาร / BOOK รายการในระบบ';
COMMENT ON COLUMN reconciliation_items.bank_transaction_id IS 'รายการเดินบัญชี (ฝั่ง BANK)';
COMMENT ON COLUMN reconciliation_items.payment_id IS 'เงินรับ/คืนเงิน (ฝั่ง BOOK)';
COMMENT ON COLUMN reconciliation_items.expense_payment_id IS 'การจ่ายเงิน (ฝั่ง BOOK)';
COMMENT ON COLUMN reconciliation_items.fund_transfer_id IS 'การโอนระหว่างบัญชี (ฝั่ง BOOK)';
COMMENT ON COLUMN reconciliation_items.transfer_leg IS 'ขาโอนออก/โอนเข้า (บังคับเมื่อเป็น fund_transfer)';
COMMENT ON COLUMN reconciliation_items.amount IS 'ยอดที่ใช้ในชุดนี้ (บวกเสมอ)';
COMMENT ON COLUMN reconciliation_items.created_at IS 'เวลาที่เพิ่ม';

-- Signed effect of one item on its bank account: + money in, − money out.
CREATE FUNCTION reconciliation_item_signed(p reconciliation_items) RETURNS numeric
LANGUAGE sql STABLE AS $$
    SELECT p.amount * CASE
        WHEN p.bank_transaction_id IS NOT NULL THEN sign((SELECT amount FROM bank_transactions WHERE id = p.bank_transaction_id))
        WHEN p.payment_id IS NOT NULL THEN CASE (SELECT direction FROM payments WHERE id = p.payment_id) WHEN 'IN' THEN 1 ELSE -1 END
        WHEN p.expense_payment_id IS NOT NULL THEN -1
        WHEN p.fund_transfer_id IS NOT NULL THEN CASE p.transfer_leg WHEN 'IN' THEN 1 ELSE -1 END
    END
$$;

CREATE FUNCTION fn_guard_reconciliation_item() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_rec      reconciliations%ROWTYPE;
    v_capacity numeric;
    v_used     numeric;
    v_property bigint;
    v_status   text;
BEGIN
    SELECT * INTO v_rec FROM reconciliations
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.reconciliation_id ELSE NEW.reconciliation_id END;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'รายการในชุดจับคู่แก้ไขไม่ได้ ให้ลบ (ขณะยังเสนอ) หรือยกเลิกทั้งชุด';
    END IF;
    IF v_rec.status <> 'PROPOSED' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001',
            MESSAGE = format('ชุดจับคู่สถานะ %s เพิ่ม/ลบรายการไม่ได้', v_rec.status);
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;

    -- Lock the matched record, then check it is not applied beyond its real amount.
    IF NEW.bank_transaction_id IS NOT NULL THEN
        SELECT abs(amount), property_id INTO v_capacity, v_property FROM bank_transactions WHERE id = NEW.bank_transaction_id FOR UPDATE;
        SELECT COALESCE(sum(i.amount), 0) INTO v_used FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
        WHERE i.bank_transaction_id = NEW.bank_transaction_id AND r.status IN ('PROPOSED', 'CONFIRMED');
    ELSIF NEW.payment_id IS NOT NULL THEN
        SELECT amount - fee_amount, property_id, status INTO v_capacity, v_property, v_status FROM payments WHERE id = NEW.payment_id FOR UPDATE;
        SELECT COALESCE(sum(i.amount), 0) INTO v_used FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
        WHERE i.payment_id = NEW.payment_id AND r.status IN ('PROPOSED', 'CONFIRMED');
    ELSIF NEW.expense_payment_id IS NOT NULL THEN
        SELECT amount, property_id, status INTO v_capacity, v_property, v_status FROM expense_payments WHERE id = NEW.expense_payment_id FOR UPDATE;
        SELECT COALESCE(sum(i.amount), 0) INTO v_used FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
        WHERE i.expense_payment_id = NEW.expense_payment_id AND r.status IN ('PROPOSED', 'CONFIRMED');
    ELSE
        SELECT amount + CASE WHEN NEW.transfer_leg = 'OUT' THEN fee_amount ELSE 0 END, property_id, status
        INTO v_capacity, v_property, v_status FROM fund_transfers WHERE id = NEW.fund_transfer_id FOR UPDATE;
        SELECT COALESCE(sum(i.amount), 0) INTO v_used FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
        WHERE i.fund_transfer_id = NEW.fund_transfer_id AND i.transfer_leg = NEW.transfer_leg AND r.status IN ('PROPOSED', 'CONFIRMED');
    END IF;

    IF v_property <> v_rec.property_id THEN
        RAISE EXCEPTION USING ERRCODE = 'AC008', MESSAGE = 'รายการที่จับคู่เป็นของกิจการอื่น';
    END IF;
    IF v_status = 'VOIDED' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'รายการที่ยกเลิกแล้วนำมาจับคู่ไม่ได้';
    END IF;
    IF v_used + NEW.amount > v_capacity THEN
        RAISE EXCEPTION USING ERRCODE = 'AC004',
            MESSAGE = format('จับคู่เกินยอดจริง: ใช้ไปแล้ว %s + %s > %s', v_used, NEW.amount, v_capacity);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_reconciliation_items_guard BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_items
    FOR EACH ROW EXECUTE FUNCTION fn_guard_reconciliation_item();

CREATE FUNCTION fn_guard_reconciliation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    c_mutable constant text[] := ARRAY['status', 'confirmed_by', 'confirmed_at', 'rejected_by', 'rejected_at',
                                       'reversed_by', 'reversed_at', 'reverse_reason', 'difference_amount',
                                       'difference_reason', 'notes', 'updated_at'];
    v_bank   numeric;
    v_book   numeric;
    v_banks  integer;
    v_books  integer;
    v_drafts integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC006', MESSAGE = 'ชุดจับคู่ลบไม่ได้ ให้ปฏิเสธหรือยกเลิกพร้อมเหตุผล';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' THEN
            RAISE EXCEPTION USING ERRCODE = 'AC004', MESSAGE = 'ชุดจับคู่ใหม่ต้องเริ่มที่ PROPOSED แล้วยืนยันหลังใส่รายการครบ';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(OLD) - c_mutable) IS DISTINCT FROM (to_jsonb(NEW) - c_mutable) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'แก้ไขข้อมูลหลักของชุดจับคู่ไม่ได้';
    END IF;
    -- Allowed: PROPOSED -> CONFIRMED | REJECTED, CONFIRMED -> REVERSED. REJECTED / REVERSED are final.
    IF OLD.status IN ('REJECTED', 'REVERSED')
       OR (OLD.status = 'PROPOSED' AND NEW.status NOT IN ('PROPOSED', 'CONFIRMED', 'REJECTED'))
       OR (OLD.status = 'CONFIRMED' AND NEW.status NOT IN ('CONFIRMED', 'REVERSED')) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001',
            MESSAGE = format('ชุดจับคู่สถานะ %s เปลี่ยนเป็น %s ไม่ได้', OLD.status, NEW.status);
    END IF;
    IF OLD.status = 'CONFIRMED'
       AND (OLD.difference_amount, OLD.difference_reason) IS DISTINCT FROM (NEW.difference_amount, NEW.difference_reason) THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'ชุดจับคู่ที่ยืนยันแล้วแก้ผลต่างไม่ได้';
    END IF;

    IF NEW.status = 'CONFIRMED' AND OLD.status = 'PROPOSED' THEN
        SELECT COALESCE(sum(reconciliation_item_signed(i)) FILTER (WHERE i.side = 'BANK'), 0),
               COALESCE(sum(reconciliation_item_signed(i)) FILTER (WHERE i.side = 'BOOK'), 0),
               count(*) FILTER (WHERE i.side = 'BANK'),
               count(*) FILTER (WHERE i.side = 'BOOK')
        INTO v_bank, v_book, v_banks, v_books
        FROM reconciliation_items i WHERE i.reconciliation_id = NEW.id;
        IF v_banks = 0 OR v_books = 0 THEN
            RAISE EXCEPTION USING ERRCODE = 'AC004', MESSAGE = 'ชุดจับคู่ต้องมีทั้งรายการธนาคารและรายการในระบบ';
        END IF;
        IF v_bank - v_book <> NEW.difference_amount THEN
            RAISE EXCEPTION USING ERRCODE = 'AC004',
                MESSAGE = format('ยอดไม่ตรง: ธนาคาร %s − ระบบ %s ≠ ผลต่างที่ระบุ %s', v_bank, v_book, NEW.difference_amount);
        END IF;
        SELECT count(*) INTO v_drafts FROM reconciliation_items i
        LEFT JOIN payments p ON p.id = i.payment_id
        LEFT JOIN expense_payments e ON e.id = i.expense_payment_id
        LEFT JOIN fund_transfers f ON f.id = i.fund_transfer_id
        WHERE i.reconciliation_id = NEW.id AND i.side = 'BOOK'
          AND COALESCE(p.status, e.status, f.status) <> 'VERIFIED';
        IF v_drafts > 0 THEN
            RAISE EXCEPTION USING ERRCODE = 'AC004', MESSAGE = 'ยืนยันการจับคู่ได้เฉพาะรายการในระบบที่ยืนยันแล้ว (VERIFIED)';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_reconciliations_guard BEFORE INSERT OR UPDATE OR DELETE ON reconciliations
    FOR EACH ROW EXECUTE FUNCTION fn_guard_reconciliation();
