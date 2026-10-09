-- =====================================================================================
-- 009 Derived figures. Every report reads these views/functions, which count VERIFIED
-- records only, and every period carries a completeness label:
--   FINAL        period CLOSED (closing is only allowed with zero issues)
--   PRELIMINARY  no open issues, but the period is not closed yet
--   INCOMPLETE   at least one issue (missing report day, draft, unmatched bank line, ...)
-- =====================================================================================

-- ------------------------------------------------------------------ receivables / deposits per folio

CREATE VIEW v_folio_balances AS
SELECT b.*,
       CASE WHEN b.balance > 0 THEN 'RECEIVABLE' WHEN b.balance < 0 THEN 'CREDIT' ELSE 'SETTLED' END AS balance_status,
       CASE WHEN b.pms_balance IS NULL THEN NULL ELSE b.balance - b.pms_balance END AS difference_vs_pms
FROM (
    SELECT f.id AS folio_id, f.property_id, f.folio_no, f.folio_type, f.bill_to_name, f.channel_id,
           f.status AS folio_status, f.opened_on, f.closed_on,
           COALESCE(r.charges, 0)        AS charges_total,
           COALESCE(a.paid_in, 0)        AS payments_applied,
           COALESCE(a.refunded, 0)       AS refunds_applied,
           COALESCE(c.commission, 0)     AS commission_deducted,
           COALESCE(r.charges, 0) - COALESCE(a.paid_in, 0) + COALESCE(a.refunded, 0) - COALESCE(c.commission, 0) AS balance,
           f.pms_balance, f.pms_snapshot_date
    FROM folios f
    LEFT JOIN LATERAL (
        SELECT sum(total_amount) AS charges FROM revenue_entries
        WHERE folio_id = f.id AND status = 'VERIFIED') r ON true
    LEFT JOIN LATERAL (
        SELECT sum(pa.amount) FILTER (WHERE p.direction = 'IN')  AS paid_in,
               sum(pa.amount) FILTER (WHERE p.direction = 'OUT') AS refunded
        FROM payment_allocations pa JOIN payments p ON p.id = pa.payment_id
        WHERE pa.folio_id = f.id AND pa.voided_at IS NULL AND p.status = 'VERIFIED') a ON true
    LEFT JOIN LATERAL (
        SELECT sum(commission_amount + vat_on_commission) AS commission FROM ota_commissions
        WHERE folio_id = f.id AND status = 'VERIFIED' AND settlement_method = 'DEDUCTED_FROM_PAYOUT') c ON true
) b;
COMMENT ON VIEW v_folio_balances IS 'ยอดต่อ folio จากรายการที่ยืนยันแล้ว: balance > 0 ลูกหนี้ค้างชำระ, < 0 เงินรับล่วงหน้า, difference_vs_pms ผลต่างกับ PMS';

CREATE VIEW v_payment_allocation_status AS
SELECT p.id AS payment_id, p.property_id, p.receipt_type, p.direction, p.received_date, p.amount, p.status,
       COALESCE(sum(pa.amount) FILTER (WHERE pa.voided_at IS NULL), 0) AS allocated,
       p.amount - COALESCE(sum(pa.amount) FILTER (WHERE pa.voided_at IS NULL), 0) AS unallocated,
       CASE
           WHEN p.receipt_type NOT IN ('GUEST_PAYMENT', 'OTA_PAYOUT', 'OTHER_INCOME_RECEIPT', 'GUEST_REFUND') THEN 'NOT_APPLICABLE'
           WHEN COALESCE(sum(pa.amount) FILTER (WHERE pa.voided_at IS NULL), 0) = 0 THEN 'UNALLOCATED'
           WHEN COALESCE(sum(pa.amount) FILTER (WHERE pa.voided_at IS NULL), 0) < p.amount THEN 'PARTIAL'
           ELSE 'FULL'
       END AS allocation_status
FROM payments p
LEFT JOIN payment_allocations pa ON pa.payment_id = p.id
GROUP BY p.id;
COMMENT ON VIEW v_payment_allocation_status IS 'เงินรับแต่ละรายการจัดสรรเข้า folio แล้วเท่าไร (ส่วนที่ยังไม่จัดสรร = เงินรับล่วงหน้า/รอระบุ)';

CREATE VIEW v_expense_payment_status AS
SELECT e.id AS expense_id, e.property_id, e.expense_date, e.due_date, e.category_id, e.vendor_name, e.status,
       e.total_amount, e.wht_amount, e.total_amount - e.wht_amount AS payable_amount,
       COALESCE(sum(ep.amount) FILTER (WHERE ep.status = 'VERIFIED'), 0) AS paid_amount,
       COALESCE(sum(ep.amount) FILTER (WHERE ep.status = 'DRAFT'), 0)    AS pending_payment_amount,
       e.total_amount - e.wht_amount - COALESCE(sum(ep.amount) FILTER (WHERE ep.status = 'VERIFIED'), 0) AS outstanding_amount,
       CASE
           WHEN COALESCE(sum(ep.amount) FILTER (WHERE ep.status = 'VERIFIED'), 0) = 0 THEN 'UNPAID'
           WHEN COALESCE(sum(ep.amount) FILTER (WHERE ep.status = 'VERIFIED'), 0) < e.total_amount - e.wht_amount THEN 'PARTIALLY_PAID'
           ELSE 'PAID'
       END AS payment_status
FROM expenses e
LEFT JOIN expense_payments ep ON ep.expense_id = e.id
GROUP BY e.id;
COMMENT ON VIEW v_expense_payment_status IS 'สถานะการจ่ายของรายจ่าย: UNPAID / PARTIALLY_PAID / PAID และยอดค้างจ่าย';

-- ------------------------------------------------------------------ reconciliation status

CREATE VIEW v_bank_transaction_status AS
SELECT bt.id AS bank_transaction_id, bt.property_id, bt.bank_account_id, bt.txn_date, bt.amount, bt.description,
       bt.review_status,
       COALESCE(x.confirmed, 0) AS matched_amount,
       abs(bt.amount) - COALESCE(x.confirmed, 0) AS unmatched_amount,
       CASE
           WHEN bt.review_status = 'IGNORED' THEN 'IGNORED'
           WHEN COALESCE(x.confirmed, 0) = abs(bt.amount) THEN 'MATCHED'
           WHEN bt.review_status = 'NEEDS_REVIEW' OR COALESCE(x.proposed, 0) > 0 THEN 'NEEDS_REVIEW'
           WHEN COALESCE(x.confirmed, 0) > 0 THEN 'PARTIALLY_MATCHED'
           ELSE 'UNMATCHED'
       END AS match_status
FROM bank_transactions bt
LEFT JOIN LATERAL (
    SELECT sum(i.amount) FILTER (WHERE r.status = 'CONFIRMED') AS confirmed,
           count(*) FILTER (WHERE r.status = 'PROPOSED')       AS proposed
    FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
    WHERE i.bank_transaction_id = bt.id) x ON true;
COMMENT ON VIEW v_bank_transaction_status IS 'สถานะจับคู่ของรายการธนาคาร: MATCHED / PARTIALLY_MATCHED / UNMATCHED / NEEDS_REVIEW / IGNORED';

CREATE VIEW v_book_reconciliation_status AS
WITH book AS (
    SELECT 'PAYMENT'::text AS item_type, p.id AS item_id, p.property_id, p.bank_account_id, p.received_date AS item_date,
           p.amount - p.fee_amount AS bank_amount
    FROM payments p WHERE p.status = 'VERIFIED'
    UNION ALL
    SELECT 'EXPENSE_PAYMENT', ep.id, ep.property_id, ep.bank_account_id, ep.paid_date, ep.amount
    FROM expense_payments ep WHERE ep.status = 'VERIFIED'
)
SELECT b.*, ba.account_kind,
       COALESCE(x.confirmed, 0) AS matched_amount,
       CASE
           WHEN ba.account_kind = 'CASH' THEN 'NOT_APPLICABLE'
           WHEN COALESCE(x.confirmed, 0) = b.bank_amount THEN 'MATCHED'
           WHEN COALESCE(x.proposed, 0) > 0 THEN 'NEEDS_REVIEW'
           WHEN COALESCE(x.confirmed, 0) > 0 THEN 'PARTIALLY_MATCHED'
           ELSE 'UNMATCHED'
       END AS match_status
FROM book b
JOIN bank_accounts ba ON ba.id = b.bank_account_id
LEFT JOIN LATERAL (
    SELECT sum(i.amount) FILTER (WHERE r.status = 'CONFIRMED') AS confirmed,
           count(*) FILTER (WHERE r.status = 'PROPOSED')       AS proposed
    FROM reconciliation_items i JOIN reconciliations r ON r.id = i.reconciliation_id
    WHERE (b.item_type = 'PAYMENT' AND i.payment_id = b.item_id)
       OR (b.item_type = 'EXPENSE_PAYMENT' AND i.expense_payment_id = b.item_id)) x ON true;
COMMENT ON VIEW v_book_reconciliation_status IS 'สถานะจับคู่ฝั่งบัญชี (เงินรับ/การจ่าย ที่ยืนยันแล้ว) กับรายการธนาคาร — บัญชีเงินสดไม่ต้องจับคู่ (ใช้การนับเงินแทน)';

-- ------------------------------------------------------------------ cash

CREATE FUNCTION book_balance(p_bank_account_id bigint, p_as_of date) RETURNS numeric
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(sum(jl.debit - jl.credit), 0)
    FROM journal_lines jl
    JOIN journal_entries je ON je.id = jl.journal_entry_id
    JOIN bank_accounts ba ON ba.id = jl.bank_account_id
    WHERE jl.bank_account_id = p_bank_account_id
      AND jl.ledger_account_id = ba.ledger_account_id
      AND je.entry_date <= p_as_of
$$;
COMMENT ON FUNCTION book_balance(bigint, date) IS 'ยอดคงเหลือตามบัญชี (ledger) ของบัญชีเงิน ณ สิ้นวันที่';

CREATE VIEW v_verified_cash AS
SELECT ba.id AS bank_account_id, ba.property_id, ba.account_name, ba.account_kind, ba.purpose,
       bc.as_of_date, bc.confirmed_balance, bc.method,
       CASE WHEN bc.id IS NULL THEN NULL ELSE book_balance(ba.id, bc.as_of_date) END AS book_balance_at_date,
       CASE WHEN bc.id IS NULL THEN NULL ELSE bc.confirmed_balance - book_balance(ba.id, bc.as_of_date) END AS difference
FROM bank_accounts ba
LEFT JOIN LATERAL (
    SELECT * FROM balance_confirmations
    WHERE bank_account_id = ba.id AND status = 'VERIFIED'
    ORDER BY as_of_date DESC LIMIT 1) bc ON true
WHERE ba.is_active;
COMMENT ON VIEW v_verified_cash IS 'เงินสด/เงินฝากที่ตรวจสอบแล้วล่าสุดต่อบัญชี พร้อมยอดตามบัญชี ณ วันเดียวกันและผลต่าง';

-- ------------------------------------------------------------------ period completeness

-- Days in [p_from, p_to] not covered by any COMMITTED import of the report type (per bank account for statements).
CREATE FUNCTION uncovered_days(p_property_id bigint, p_report_type text, p_bank_account_id bigint, p_from date, p_to date)
RETURNS date[]
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(array_agg(d::date ORDER BY d), '{}')
    FROM generate_series(p_from, p_to, interval '1 day') d
    WHERE p_from <= p_to
      AND NOT EXISTS (
        SELECT 1 FROM imported_files f
        WHERE f.property_id = p_property_id AND f.report_type = p_report_type AND f.status = 'COMMITTED'
          AND (p_bank_account_id IS NULL OR f.bank_account_id = p_bank_account_id)
          AND d::date BETWEEN f.report_date_from AND f.report_date_to)
$$;

CREATE VIEW v_period_completeness AS
WITH base AS (
    SELECT p.id AS period_id, p.property_id, p.period_start, p.period_end, p.status AS period_status,
           greatest(p.period_start, pr.accounting_start_date) AS check_from
    FROM accounting_periods p JOIN properties pr ON pr.id = p.property_id
),
accounts AS (
    SELECT b.period_id, ba.id AS bank_account_id, ba.account_kind,
           greatest(b.check_from, ba.opened_on) AS acc_from,
           least(b.period_end, COALESCE(ba.closed_on, b.period_end)) AS acc_to
    FROM base b JOIN bank_accounts ba ON ba.property_id = b.property_id
    WHERE ba.account_kind IN ('BANK', 'CASH', 'EWALLET')
      AND ba.opened_on <= b.period_end AND (ba.closed_on IS NULL OR ba.closed_on >= b.period_start)
),
counts AS (
    SELECT b.period_id,
        cardinality(uncovered_days(b.property_id, 'NIGHT_AUDIT', NULL, b.check_from, b.period_end)) AS missing_revenue_report_days,
        (SELECT count(*) FROM revenue_entries x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.service_date BETWEEN b.period_start AND b.period_end)
      + (SELECT count(*) FROM payments x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.received_date BETWEEN b.period_start AND b.period_end)
      + (SELECT count(*) FROM expenses x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.expense_date BETWEEN b.period_start AND b.period_end)
      + (SELECT count(*) FROM expense_payments x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.paid_date BETWEEN b.period_start AND b.period_end)
      + (SELECT count(*) FROM ota_commissions x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.recognition_date BETWEEN b.period_start AND b.period_end)
      + (SELECT count(*) FROM fund_transfers x WHERE x.property_id = b.property_id AND x.status = 'DRAFT' AND x.transfer_date BETWEEN b.period_start AND b.period_end)
            AS draft_documents,
        (SELECT count(*) FROM ota_commissions x WHERE x.property_id = b.property_id AND x.status = 'VERIFIED'
            AND x.basis = 'ESTIMATED_FROM_RATE' AND x.recognition_date BETWEEN b.period_start AND b.period_end) AS estimated_commissions,
        (SELECT count(*) FROM v_bank_transaction_status x WHERE x.property_id = b.property_id
            AND x.txn_date BETWEEN b.period_start AND b.period_end AND x.match_status NOT IN ('MATCHED', 'IGNORED')) AS unmatched_bank_transactions,
        (SELECT count(*) FROM v_book_reconciliation_status x WHERE x.property_id = b.property_id
            AND x.item_date BETWEEN b.period_start AND b.period_end AND x.match_status NOT IN ('MATCHED', 'NOT_APPLICABLE')) AS unmatched_book_items,
        (SELECT count(*) FROM accounts a WHERE a.period_id = b.period_id AND a.account_kind = 'BANK'
            AND cardinality(uncovered_days(b.property_id, 'BANK_STATEMENT', a.bank_account_id, a.acc_from, a.acc_to)) > 0) AS accounts_missing_statement,
        (SELECT count(*) FROM accounts a WHERE a.period_id = b.period_id AND NOT EXISTS (
            SELECT 1 FROM balance_confirmations bc WHERE bc.bank_account_id = a.bank_account_id
              AND bc.status = 'VERIFIED' AND bc.as_of_date = a.acc_to)) AS accounts_missing_month_end_balance,
        (SELECT count(*) FROM accounts a JOIN balance_confirmations bc ON bc.bank_account_id = a.bank_account_id
            AND bc.status = 'VERIFIED' AND bc.as_of_date = a.acc_to
            WHERE a.period_id = b.period_id AND bc.confirmed_balance <> book_balance(a.bank_account_id, a.acc_to)) AS accounts_with_balance_difference,
        (SELECT count(*) FROM imported_files f WHERE f.property_id = b.property_id
            AND f.status IN ('UPLOADED', 'PARSED', 'VALIDATED')
            AND f.report_date_from <= b.period_end AND f.report_date_to >= b.period_start) AS imports_pending_review
    FROM base b
)
SELECT b.period_id, b.property_id, b.period_start, b.period_end, b.period_status,
       uncovered_days(b.property_id, 'NIGHT_AUDIT', NULL, b.check_from, b.period_end) AS missing_revenue_report_dates,
       c.missing_revenue_report_days, c.draft_documents, c.estimated_commissions, c.unmatched_bank_transactions,
       c.unmatched_book_items, c.accounts_missing_statement, c.accounts_missing_month_end_balance,
       c.accounts_with_balance_difference, c.imports_pending_review,
       (c.missing_revenue_report_days + c.draft_documents + c.estimated_commissions + c.unmatched_bank_transactions
        + c.unmatched_book_items + c.accounts_missing_statement + c.accounts_missing_month_end_balance
        + c.accounts_with_balance_difference + c.imports_pending_review) AS issue_count,
       CASE
           WHEN b.period_status = 'CLOSED' THEN 'FINAL'
           WHEN (c.missing_revenue_report_days + c.draft_documents + c.estimated_commissions + c.unmatched_bank_transactions
                 + c.unmatched_book_items + c.accounts_missing_statement + c.accounts_missing_month_end_balance
                 + c.accounts_with_balance_difference + c.imports_pending_review) = 0 THEN 'PRELIMINARY'
           ELSE 'INCOMPLETE'
       END AS completeness
FROM base b JOIN counts c ON c.period_id = b.period_id;
COMMENT ON VIEW v_period_completeness IS 'ความครบถ้วนของข้อมูลรายเดือนและป้าย FINAL / PRELIMINARY / INCOMPLETE ที่ทุกรายงานต้องแสดง';

-- Closing a period requires zero issues; periods close and reopen in order.
CREATE FUNCTION fn_guard_period_close() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_issues integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = 'AC006', MESSAGE = 'งวดบัญชีลบไม่ได้';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION USING ERRCODE = 'AC007', MESSAGE = 'งวดใหม่ต้องเริ่มที่สถานะ OPEN แล้วปิดผ่านการตรวจความครบถ้วน';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'CLOSED' THEN
        IF EXISTS (SELECT 1 FROM accounting_periods WHERE property_id = NEW.property_id
                   AND period_start < NEW.period_start AND status = 'OPEN') THEN
            RAISE EXCEPTION USING ERRCODE = 'AC007', MESSAGE = 'ต้องปิดงวดก่อนหน้าให้ครบก่อน';
        END IF;
        SELECT issue_count INTO v_issues FROM v_period_completeness WHERE period_id = NEW.id;
        IF v_issues > 0 THEN
            RAISE EXCEPTION USING ERRCODE = 'AC007',
                MESSAGE = format('ปิดงวด %s ไม่ได้: ข้อมูลยังไม่ครบ %s รายการ (ดู v_period_completeness)',
                                 to_char(NEW.period_start, 'MM/YYYY'), v_issues);
        END IF;
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
        IF EXISTS (SELECT 1 FROM accounting_periods WHERE property_id = NEW.property_id
                   AND period_start > NEW.period_start AND status = 'CLOSED') THEN
            RAISE EXCEPTION USING ERRCODE = 'AC007', MESSAGE = 'ต้องเปิดงวดล่าสุดก่อน (เปิดย้อนทีละงวด)';
        END IF;
        IF NEW.reopened_at IS NOT DISTINCT FROM OLD.reopened_at THEN
            RAISE EXCEPTION USING ERRCODE = 'AC007', MESSAGE = 'การเปิดงวดใหม่ต้องระบุผู้เปิด เวลา และเหตุผล';
        END IF;
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status = 'CLOSED'
       AND (to_jsonb(OLD) - 'updated_at') IS DISTINCT FROM (to_jsonb(NEW) - 'updated_at') THEN
        RAISE EXCEPTION USING ERRCODE = 'AC001', MESSAGE = 'งวดที่ปิดแล้วแก้ไขไม่ได้';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_accounting_periods_guard BEFORE INSERT OR UPDATE OR DELETE ON accounting_periods
    FOR EACH ROW EXECUTE FUNCTION fn_guard_period_close();

-- ------------------------------------------------------------------ profit and loss (from the ledger)

CREATE VIEW v_pnl_lines AS
SELECT je.property_id, je.entry_date, date_trunc('month', je.entry_date)::date AS period_start,
       la.pnl_section, la.id AS ledger_account_id, la.code AS account_code, la.name_th AS account_name,
       CASE WHEN la.account_type = 'REVENUE' THEN jl.credit - jl.debit ELSE jl.debit - jl.credit END AS amount,
       je.source_type, je.source_id, je.id AS journal_entry_id
FROM journal_lines jl
JOIN journal_entries je ON je.id = jl.journal_entry_id
JOIN ledger_accounts la ON la.id = jl.ledger_account_id
WHERE la.pnl_section IS NOT NULL;
COMMENT ON VIEW v_pnl_lines IS 'บรรทัดงบกำไรขาดทุนรายวันจากสมุดบัญชี (รายได้บวก ค่าใช้จ่ายบวก) — บัญชีงบดุลไม่อยู่ในนี้';

CREATE VIEW v_pnl_monthly AS
SELECT s.*,
       s.revenue + s.other_income - s.distribution_cost - s.operating_expense - s.depreciation
           - s.finance_cost - s.other_expense AS profit_before_tax,
       s.revenue + s.other_income - s.distribution_cost - s.operating_expense - s.depreciation
           - s.finance_cost - s.other_expense - s.income_tax AS net_profit
FROM (
    SELECT p.property_id, p.period_start, p.period_end, c.completeness, c.issue_count,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'REVENUE'), 0)           AS revenue,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'OTHER_INCOME'), 0)      AS other_income,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'DISTRIBUTION_COST'), 0) AS distribution_cost,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'OPERATING_EXPENSE'), 0) AS operating_expense,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'DEPRECIATION'), 0)      AS depreciation,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'FINANCE_COST'), 0)      AS finance_cost,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'OTHER_EXPENSE'), 0)     AS other_expense,
           COALESCE(sum(l.amount) FILTER (WHERE l.pnl_section = 'INCOME_TAX'), 0)        AS income_tax
    FROM accounting_periods p
    JOIN v_period_completeness c ON c.period_id = p.id
    LEFT JOIN v_pnl_lines l ON l.property_id = p.property_id AND l.period_start = p.period_start
    GROUP BY p.property_id, p.period_start, p.period_end, c.completeness, c.issue_count
) s;
COMMENT ON VIEW v_pnl_monthly IS 'งบกำไรขาดทุนรายเดือน พร้อมป้ายความครบถ้วน — UI ต้องแสดง "กำไรสุทธิ (ยืนยันแล้ว)" เฉพาะ completeness = FINAL';

-- ------------------------------------------------------------------ capital goal progress

CREATE VIEW v_capital_goal_progress AS
SELECT s.*,
       greatest(s.target_amount - s.accumulated_verified, 0) AS remaining_to_target,
       round(least(greatest(s.accumulated_verified, 0) / s.target_amount * 100, 100), 2) AS progress_pct
FROM (
    SELECT g.id AS goal_id, g.property_id, g.name, g.target_amount, g.status,
           COALESCE(sum(t.amount) FILTER (WHERE t.status = 'VERIFIED'), 0) AS accumulated_verified,
           COALESCE(sum(t.amount) FILTER (WHERE t.status = 'DRAFT'), 0)    AS pending_draft
    FROM capital_goals g
    LEFT JOIN capital_fund_transactions t ON t.goal_id = g.id
    GROUP BY g.id
) s;
COMMENT ON VIEW v_capital_goal_progress IS 'เงินทุนสะสมแล้ว (เฉพาะรายการ VERIFIED) และจำนวนที่ยังขาดจากเป้าหมาย';
