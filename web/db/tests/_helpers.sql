-- Test helpers and a shared fixture. Loaded only into the throw-away test database by run-tests.sh.
-- All fixture data is labelled as test data (property code TEST_*, is_sandbox = true, emails @test.local).
\set ON_ERROR_STOP on

CREATE SCHEMA test;

CREATE FUNCTION test.ok(p_name text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    RAISE NOTICE 'ok - %', p_name;
END $$;

CREATE FUNCTION test.assert_eq(p_actual anyelement, p_expected anyelement, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_actual IS DISTINCT FROM p_expected THEN
        RAISE EXCEPTION 'not ok - %: expected [%] got [%]', p_name, p_expected, p_actual;
    END IF;
    PERFORM test.ok(p_name);
END $$;

CREATE FUNCTION test.assert_true(p_cond boolean, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_cond IS NOT TRUE THEN
        RAISE EXCEPTION 'not ok - %: condition is %', p_name, p_cond;
    END IF;
    PERFORM test.ok(p_name);
END $$;

-- Runs p_sql (and fires deferred constraints) in a sub-transaction; passes only if it fails with p_sqlstate.
CREATE FUNCTION test.assert_raises(p_sql text, p_sqlstate text, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        SET CONSTRAINTS ALL IMMEDIATE;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
    END;
    SET CONSTRAINTS ALL DEFERRED;
    IF v_state IS NULL THEN
        RAISE EXCEPTION 'not ok - %: expected error % but the statement succeeded', p_name, p_sqlstate;
    END IF;
    IF v_state <> p_sqlstate THEN
        RAISE EXCEPTION 'not ok - %: expected error % got % (%)', p_name, p_sqlstate, v_state, v_msg;
    END IF;
    PERFORM test.ok(p_name || ' [' || v_state || ']');
END $$;

-- Runs p_sql and fires deferred constraints; fails the test if anything raises.
CREATE FUNCTION test.assert_succeeds(p_sql text, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        SET CONSTRAINTS ALL IMMEDIATE;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        RAISE EXCEPTION 'not ok - %: unexpected error % (%)', p_name, v_state, v_msg;
    END;
    SET CONSTRAINTS ALL DEFERRED;
    PERFORM test.ok(p_name);
END $$;

-- ------------------------------------------------------------------ lookups

CREATE FUNCTION test.prop(p_code text DEFAULT 'TEST_RESORT') RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM properties WHERE code = p_code $$;
CREATE FUNCTION test.usr(p_name text) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM users WHERE email = p_name || '@test.local' $$;
CREATE FUNCTION test.acct(p_code text) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM ledger_accounts WHERE code = p_code $$;
CREATE FUNCTION test.bank(p_name text, p_property text DEFAULT 'TEST_RESORT') RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM bank_accounts WHERE account_name = p_name AND property_id = test.prop(p_property) $$;
CREATE FUNCTION test.channel(p_code text) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM ota_channels WHERE code = p_code AND property_id = test.prop() $$;
CREATE FUNCTION test.folio(p_no text, p_property text DEFAULT 'TEST_RESORT') RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM folios WHERE folio_no = p_no AND property_id = test.prop(p_property) $$;
CREATE FUNCTION test.category(p_code text) RETURNS bigint LANGUAGE sql STABLE AS
$$ SELECT id FROM expense_categories WHERE code = p_code $$;

-- ------------------------------------------------------------------ document builders (return id)

CREATE FUNCTION test.revenue(p_folio bigint, p_date date, p_amount numeric, p_status text DEFAULT 'VERIFIED',
                             p_ref text DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO revenue_entries (property_id, folio_id, service_date, revenue_type, amount_excl_tax, total_amount,
                                 ledger_account_id, status, source_system, source_ref, created_by, verified_by, verified_at)
    SELECT f.property_id, p_folio, p_date, 'ROOM', p_amount, p_amount, test.acct('4101'), p_status, 'HOTELRUNNER', p_ref,
           test.usr('frontdesk'),
           CASE WHEN p_status = 'VERIFIED' THEN test.usr('accountant') END,
           CASE WHEN p_status = 'VERIFIED' THEN now() END
    FROM folios f WHERE f.id = p_folio
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION test.payment(p_amount numeric, p_date date, p_status text DEFAULT 'VERIFIED',
                             p_type text DEFAULT 'GUEST_PAYMENT', p_bank text DEFAULT 'KBank operating',
                             p_ref text DEFAULT NULL, p_fee numeric DEFAULT 0, p_channel bigint DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO payments (property_id, receipt_type, received_date, method, bank_account_id, amount, fee_amount,
                          channel_id, status, source_system, source_ref, created_by, verified_by, verified_at)
    VALUES (test.prop(), p_type, p_date, CASE WHEN p_type = 'OTA_PAYOUT' THEN 'OTA_PAYOUT' ELSE 'BANK_TRANSFER' END,
            test.bank(p_bank), p_amount, p_fee, p_channel, p_status, 'HOTELRUNNER', p_ref, test.usr('frontdesk'),
            CASE WHEN p_status = 'VERIFIED' THEN test.usr('accountant') END,
            CASE WHEN p_status = 'VERIFIED' THEN now() END)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION test.allocate(p_payment bigint, p_folio bigint, p_amount numeric, p_date date DEFAULT '2026-03-15') RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO payment_allocations (property_id, payment_id, folio_id, allocation_date, amount, created_by)
    VALUES (test.prop(), p_payment, p_folio, p_date, p_amount, test.usr('frontdesk'))
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION test.expense(p_category text, p_total numeric, p_date date, p_status text DEFAULT 'VERIFIED',
                             p_wht numeric DEFAULT 0) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO expenses (property_id, expense_date, category_id, description, amount_excl_vat, total_amount, wht_amount,
                          status, source_system, created_by, verified_by, verified_at)
    VALUES (test.prop(), p_date, test.category(p_category), '[TEST] ' || p_category, p_total, p_total, p_wht,
            p_status, 'MANUAL', test.usr('frontdesk'),
            CASE WHEN p_status = 'VERIFIED' THEN test.usr('accountant') END,
            CASE WHEN p_status = 'VERIFIED' THEN now() END)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION test.expense_payment(p_expense bigint, p_amount numeric, p_date date, p_status text DEFAULT 'VERIFIED') RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO expense_payments (property_id, expense_id, paid_date, bank_account_id, method, amount, status,
                                  source_system, created_by, verified_by, verified_at)
    VALUES (test.prop(), p_expense, p_date, test.bank('KBank operating'), 'BANK_TRANSFER', p_amount, p_status,
            'MANUAL', test.usr('frontdesk'),
            CASE WHEN p_status = 'VERIFIED' THEN test.usr('accountant') END,
            CASE WHEN p_status = 'VERIFIED' THEN now() END)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION test.bank_txn(p_amount numeric, p_date date, p_key text, p_bank text DEFAULT 'KBank operating') RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO bank_transactions (property_id, bank_account_id, txn_date, description, amount, dedupe_key)
    VALUES (test.prop(), test.bank(p_bank), p_date, '[TEST] statement line', p_amount, p_key)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

-- Posts a two-line journal entry (debit account / credit account). Bank lines get their bank account automatically.
CREATE FUNCTION test.post(p_date date, p_debit text, p_credit text, p_amount numeric, p_no text,
                          p_debit_bank text DEFAULT NULL, p_credit_bank text DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v_id bigint;
BEGIN
    INSERT INTO journal_entries (property_id, entry_no, entry_date, description, source_type)
    VALUES (test.prop(), p_no, p_date, '[TEST] ' || p_no, 'MANUAL_ADJUSTMENT')
    RETURNING id INTO v_id;
    INSERT INTO journal_lines (journal_entry_id, line_no, ledger_account_id, debit, credit, bank_account_id)
    VALUES (v_id, 1, test.acct(p_debit), p_amount, 0, test.bank(p_debit_bank)),
           (v_id, 2, test.acct(p_credit), 0, p_amount, test.bank(p_credit_bank));
    RETURN v_id;
END $$;

-- ------------------------------------------------------------------ shared fixture (committed in the test DB)

INSERT INTO properties (code, name_th, name_en, pms_system, accounting_start_date, is_sandbox)
VALUES ('TEST_RESORT', '[ทดสอบ] รีสอร์ตตัวอย่าง', '[TEST] Demo resort', 'HOTELRUNNER', '2026-01-01', true),
       ('TEST_OTHER',  '[ทดสอบ] กิจการอื่น',      '[TEST] Other business', 'NONE', '2026-01-01', true);

INSERT INTO users (email, display_name) VALUES
    ('owner@test.local', '[TEST] Owner'),
    ('accountant@test.local', '[TEST] Accountant'),
    ('frontdesk@test.local', '[TEST] Front desk');

INSERT INTO user_property_roles (user_id, property_id, role_code)
VALUES (test.usr('owner'), test.prop(), 'OWNER'),
       (test.usr('accountant'), test.prop(), 'ACCOUNTANT'),
       (test.usr('frontdesk'), test.prop(), 'FRONT_DESK');

INSERT INTO bank_accounts (property_id, account_kind, purpose, bank_name, account_name, account_no_last4, ledger_account_id, opened_on)
VALUES (test.prop(), 'BANK', 'OPERATING', 'KBank', 'KBank operating', '1234', test.acct('1111'), '2026-01-01'),
       (test.prop(), 'BANK', 'CAPITAL_FUND', 'KBank', 'Capital fund', '5678', test.acct('1113'), '2026-01-01'),
       (test.prop(), 'CASH', 'OPERATING', NULL, 'Cash drawer', NULL, test.acct('1101'), '2026-01-01'),
       (test.prop('TEST_OTHER'), 'BANK', 'OPERATING', 'SCB', 'Other bank', '9999', test.acct('1111'), '2026-01-01');

INSERT INTO ota_channels (property_id, code, name, channel_type, collection_model, receivable_account_id)
VALUES (test.prop(), 'DIRECT', 'Direct', 'DIRECT', NULL, NULL),
       (test.prop(), 'BOOKING_COM', 'Booking.com', 'OTA', 'HOTEL_COLLECT', NULL),
       (test.prop(), 'EXPEDIA', 'Expedia', 'OTA', 'OTA_COLLECT', test.acct('1132'));

INSERT INTO rooms (property_id, room_no, room_type, active_from)
VALUES (test.prop(), '101', 'Deluxe', '2026-01-01'),
       (test.prop(), '102', 'Deluxe', '2026-01-01'),
       (test.prop(), '103', 'Superior', '2026-01-01');

INSERT INTO reservations (property_id, channel_id, source_system, source_ref, guest_name, arrival_date, departure_date, status)
VALUES (test.prop(), test.channel('DIRECT'), 'HOTELRUNNER', 'R-1001', '[TEST] Guest A', '2026-03-10', '2026-03-12', 'CHECKED_OUT'),
       (test.prop(), test.channel('BOOKING_COM'), 'HOTELRUNNER', 'R-1002', '[TEST] Guest B', '2026-03-10', '2026-03-11', 'CHECKED_OUT'),
       (test.prop(), test.channel('EXPEDIA'), 'HOTELRUNNER', 'R-1003', '[TEST] Guest C', '2026-03-20', '2026-03-21', 'CONFIRMED');

INSERT INTO folios (property_id, reservation_id, source_system, folio_no, bill_to_name, channel_id, room_id, opened_on, folio_type)
SELECT r.property_id, r.id, 'HOTELRUNNER', 'F-' || substr(r.source_ref, 3), r.guest_name, r.channel_id,
       (SELECT id FROM rooms WHERE room_no = '10' || substr(r.source_ref, 6) AND property_id = r.property_id),
       r.arrival_date, CASE WHEN r.source_ref = 'R-1003' THEN 'OTA_CITY_LEDGER' ELSE 'GUEST' END
FROM reservations r WHERE r.property_id = test.prop();

INSERT INTO folios (property_id, source_system, folio_no, bill_to_name, opened_on)
VALUES (test.prop('TEST_OTHER'), 'MANUAL', 'F-OTHER', '[TEST] other', '2026-03-01');
