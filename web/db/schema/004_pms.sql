-- =====================================================================================
-- 004 Hotel operations data from the PMS: rooms, sales channels, reservations, folios,
--     daily statistics. These are reference / operational records, not ledger postings.
-- =====================================================================================

CREATE TABLE rooms (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id   bigint NOT NULL REFERENCES properties (id),
    room_no       text NOT NULL CHECK (has_text(room_no)),
    room_type     text,
    pms_room_ref  text,
    max_occupancy smallint CHECK (max_occupancy > 0),
    is_sellable   boolean NOT NULL DEFAULT true,
    active_from   date NOT NULL,
    active_to     date,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, room_no),
    CHECK (active_to >= active_from)
);
SELECT attach_audit('rooms');
COMMENT ON TABLE rooms IS 'ห้องพัก (ปัจจุบัน 15 ห้อง เพิ่มห้องได้โดยกำหนด active_from) ใช้คำนวณจำนวนห้องที่ขายได้ต่อวัน';
COMMENT ON COLUMN rooms.id IS 'รหัสห้อง (PK)';
COMMENT ON COLUMN rooms.property_id IS 'กิจการ';
COMMENT ON COLUMN rooms.room_no IS 'เลขห้องตามที่ใช้ใน PMS';
COMMENT ON COLUMN rooms.room_type IS 'ประเภทห้อง เช่น Deluxe, Superior';
COMMENT ON COLUMN rooms.pms_room_ref IS 'รหัสห้องใน PMS (ถ้าต่างจากเลขห้อง)';
COMMENT ON COLUMN rooms.max_occupancy IS 'จำนวนผู้เข้าพักสูงสุด';
COMMENT ON COLUMN rooms.is_sellable IS 'false = ไม่นับเป็นห้องที่ขายได้ (เช่น ห้องพนักงาน)';
COMMENT ON COLUMN rooms.active_from IS 'วันที่เริ่มขายห้องนี้';
COMMENT ON COLUMN rooms.active_to IS 'วันสุดท้ายที่ขาย (NULL = ยังขายอยู่)';
COMMENT ON COLUMN rooms.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN rooms.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE ota_channels (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id           bigint NOT NULL REFERENCES properties (id),
    code                  text NOT NULL CHECK (code ~ '^[A-Z0-9_]+$'),
    name                  text NOT NULL,
    channel_type          text NOT NULL CHECK (channel_type IN ('OTA', 'DIRECT', 'WALK_IN', 'AGENT', 'CORPORATE', 'OTHER')),
    collection_model      text CHECK (collection_model IN ('HOTEL_COLLECT', 'OTA_COLLECT', 'MIXED')),
    commission_rate       percent_rate,
    commission_basis      text CHECK (commission_basis IN ('GROSS_INCL_TAX', 'NET_EXCL_TAX')),
    terms_confirmed       boolean NOT NULL DEFAULT false,
    receivable_account_id bigint REFERENCES ledger_accounts (id),
    pms_source_names      text[] NOT NULL DEFAULT '{}',
    is_active             boolean NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, code),
    CHECK (NOT terms_confirmed OR channel_type <> 'OTA'
           OR (collection_model IS NOT NULL AND commission_rate IS NOT NULL AND commission_basis IS NOT NULL))
);
SELECT attach_audit('ota_channels');
COMMENT ON TABLE ota_channels IS 'ช่องทางขาย (OTA, จองตรง, Walk-in) และเงื่อนไขค่าคอมมิชชัน — ค่าว่าง = ยังไม่ทราบ ห้ามเดา';
COMMENT ON COLUMN ota_channels.id IS 'รหัสช่องทาง (PK)';
COMMENT ON COLUMN ota_channels.property_id IS 'กิจการ';
COMMENT ON COLUMN ota_channels.code IS 'รหัส เช่น BOOKING_COM, AGODA, EXPEDIA, DIRECT, WALK_IN';
COMMENT ON COLUMN ota_channels.name IS 'ชื่อที่แสดง';
COMMENT ON COLUMN ota_channels.channel_type IS 'ประเภทช่องทาง';
COMMENT ON COLUMN ota_channels.collection_model IS 'ใครเก็บเงินลูกค้า: HOTEL_COLLECT เราเก็บเอง / OTA_COLLECT OTA เก็บแล้วโอนให้ / MIXED';
COMMENT ON COLUMN ota_channels.commission_rate IS 'อัตราค่าคอมมิชชันตามสัญญา (%) — NULL = ยังไม่ยืนยัน';
COMMENT ON COLUMN ota_channels.commission_basis IS 'ฐานคิดค่าคอม: รวมภาษี หรือ ก่อนภาษี';
COMMENT ON COLUMN ota_channels.terms_confirmed IS 'เจ้าของ/นักบัญชียืนยันเงื่อนไขกับสัญญาแล้ว';
COMMENT ON COLUMN ota_channels.receivable_account_id IS 'บัญชีลูกหนี้ของช่องทางนี้ (กรณี OTA เก็บเงินแทน)';
COMMENT ON COLUMN ota_channels.pms_source_names IS 'ชื่อ Source ที่ปรากฏในรายงาน PMS ที่ให้จับคู่มาช่องทางนี้ เช่น {"Booking.com","Booking.com-1"}';
COMMENT ON COLUMN ota_channels.is_active IS 'ยังใช้งานอยู่';
COMMENT ON COLUMN ota_channels.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN ota_channels.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_validate_ota_channel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_ledger_account(NEW.receivable_account_id, ARRAY['RECEIVABLE'], 'ลูกหนี้ช่องทางขาย');
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_ota_channels_validate BEFORE INSERT OR UPDATE ON ota_channels
    FOR EACH ROW EXECUTE FUNCTION fn_validate_ota_channel();

CREATE TABLE reservations (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id      bigint NOT NULL REFERENCES properties (id),
    channel_id       bigint REFERENCES ota_channels (id),
    source_system    text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'MANUAL', 'ANDROID_APP')),
    source_ref       text,
    ota_booking_ref  text,
    guest_name       text,
    adults           smallint CHECK (adults >= 0),
    children         smallint CHECK (children >= 0),
    room_count       smallint NOT NULL DEFAULT 1 CHECK (room_count > 0),
    arrival_date     date NOT NULL,
    departure_date   date NOT NULL,
    nights           integer GENERATED ALWAYS AS (departure_date - arrival_date) STORED,
    status           text NOT NULL CHECK (status IN ('CONFIRMED', 'IN_HOUSE', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
    booked_at        timestamptz,
    cancelled_at     timestamptz,
    pms_total_amount money_thb,
    import_row_id    bigint REFERENCES import_rows (id),
    created_by       bigint REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (departure_date > arrival_date)
);
CREATE UNIQUE INDEX reservations_source_uq ON reservations (property_id, source_system, source_ref) WHERE source_ref IS NOT NULL;
CREATE INDEX reservations_stay_idx ON reservations (property_id, arrival_date, departure_date);
SELECT attach_audit('reservations');
COMMENT ON TABLE reservations IS 'การจองจาก PMS (ข้อมูลอ้างอิง ไม่ใช่รายได้) — เก็บข้อมูลส่วนบุคคลของแขกเท่าที่จำเป็น';
COMMENT ON COLUMN reservations.id IS 'รหัสการจอง (PK)';
COMMENT ON COLUMN reservations.property_id IS 'กิจการ';
COMMENT ON COLUMN reservations.channel_id IS 'ช่องทางขาย';
COMMENT ON COLUMN reservations.source_system IS 'ระบบที่สร้างการจอง';
COMMENT ON COLUMN reservations.source_ref IS 'เลขที่การจองใน PMS (Res No.) ใช้กันซ้ำ';
COMMENT ON COLUMN reservations.ota_booking_ref IS 'เลขยืนยันของ OTA';
COMMENT ON COLUMN reservations.guest_name IS 'ชื่อแขก (ไม่เก็บเบอร์โทร/เลขบัตร)';
COMMENT ON COLUMN reservations.adults IS 'จำนวนผู้ใหญ่';
COMMENT ON COLUMN reservations.children IS 'จำนวนเด็ก';
COMMENT ON COLUMN reservations.room_count IS 'จำนวนห้องในการจอง';
COMMENT ON COLUMN reservations.arrival_date IS 'วันเช็คอิน';
COMMENT ON COLUMN reservations.departure_date IS 'วันเช็คเอาท์';
COMMENT ON COLUMN reservations.nights IS 'จำนวนคืน (คำนวณ)';
COMMENT ON COLUMN reservations.status IS 'CONFIRMED / IN_HOUSE / CHECKED_OUT / CANCELLED / NO_SHOW';
COMMENT ON COLUMN reservations.booked_at IS 'เวลาที่จอง';
COMMENT ON COLUMN reservations.cancelled_at IS 'เวลาที่ยกเลิก';
COMMENT ON COLUMN reservations.pms_total_amount IS 'ยอดรวมตาม PMS (ใช้เทียบเท่านั้น ไม่ใช่ยอดบัญชี)';
COMMENT ON COLUMN reservations.import_row_id IS 'แถวข้อมูลดิบที่มา';
COMMENT ON COLUMN reservations.created_by IS 'ผู้บันทึก';
COMMENT ON COLUMN reservations.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN reservations.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE TABLE folios (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id        bigint NOT NULL REFERENCES properties (id),
    reservation_id     bigint REFERENCES reservations (id),
    folio_type         text NOT NULL DEFAULT 'GUEST' CHECK (folio_type IN ('GUEST', 'OTA_CITY_LEDGER', 'HOUSE', 'OTHER_INCOME')),
    source_system      text NOT NULL CHECK (source_system IN ('HOTELRUNNER', 'EZEE', 'MANUAL', 'ANDROID_APP')),
    folio_no           text NOT NULL,
    bill_to_name       text,
    channel_id         bigint REFERENCES ota_channels (id),
    room_id            bigint REFERENCES rooms (id),
    opened_on          date NOT NULL,
    closed_on          date,
    status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'VOID')),
    pms_total          money_thb,
    pms_paid           money_thb,
    pms_balance        money_thb,
    pms_snapshot_date  date,
    import_row_id      bigint REFERENCES import_rows (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, source_system, folio_no),
    CHECK (closed_on >= opened_on),
    CHECK ((pms_snapshot_date IS NULL) = (pms_total IS NULL AND pms_paid IS NULL AND pms_balance IS NULL))
);
CREATE INDEX folios_reservation_idx ON folios (reservation_id);
SELECT attach_audit('folios');
COMMENT ON TABLE folios IS 'บัญชีค่าใช้จ่ายของแขก (Folio) — ศูนย์กลางเชื่อมรายได้กับเงินรับ ยอดค้าง = รายได้ − เงินที่จัดสรรมา';
COMMENT ON COLUMN folios.id IS 'รหัส folio (PK)';
COMMENT ON COLUMN folios.property_id IS 'กิจการ';
COMMENT ON COLUMN folios.reservation_id IS 'การจองที่เกี่ยวข้อง';
COMMENT ON COLUMN folios.folio_type IS 'GUEST แขก / OTA_CITY_LEDGER ลูกหนี้ OTA / HOUSE ภายใน / OTHER_INCOME รายได้อื่นที่ไม่มีการจอง';
COMMENT ON COLUMN folios.source_system IS 'ระบบที่สร้าง folio';
COMMENT ON COLUMN folios.folio_no IS 'เลขที่ folio ใน PMS (กันซ้ำร่วมกับ source_system)';
COMMENT ON COLUMN folios.bill_to_name IS 'ชื่อผู้ถูกเรียกเก็บ';
COMMENT ON COLUMN folios.channel_id IS 'ช่องทางขาย';
COMMENT ON COLUMN folios.room_id IS 'ห้องพัก';
COMMENT ON COLUMN folios.opened_on IS 'วันที่เปิด folio';
COMMENT ON COLUMN folios.closed_on IS 'วันที่ปิด folio';
COMMENT ON COLUMN folios.status IS 'OPEN / CLOSED / VOID';
COMMENT ON COLUMN folios.pms_total IS 'ยอดรวมตาม PMS ล่าสุด (ใช้เทียบ ไม่ใช่ยอดบัญชี)';
COMMENT ON COLUMN folios.pms_paid IS 'ยอดชำระตาม PMS ล่าสุด';
COMMENT ON COLUMN folios.pms_balance IS 'ยอดค้างตาม PMS ล่าสุด';
COMMENT ON COLUMN folios.pms_snapshot_date IS 'วันที่ของตัวเลข PMS ข้างต้น';
COMMENT ON COLUMN folios.import_row_id IS 'แถวข้อมูลดิบที่มา';
COMMENT ON COLUMN folios.created_at IS 'เวลาที่สร้าง';
COMMENT ON COLUMN folios.updated_at IS 'เวลาที่แก้ไขล่าสุด';

CREATE FUNCTION fn_validate_folio() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_same_property('reservations', NEW.reservation_id, NEW.property_id);
    PERFORM assert_same_property('ota_channels', NEW.channel_id, NEW.property_id);
    PERFORM assert_same_property('rooms', NEW.room_id, NEW.property_id);
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_folios_validate BEFORE INSERT OR UPDATE ON folios
    FOR EACH ROW EXECUTE FUNCTION fn_validate_folio();

CREATE TABLE pms_daily_statistics (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id         bigint NOT NULL REFERENCES properties (id),
    stat_date           date NOT NULL,
    is_forecast         boolean NOT NULL,
    rooms_available     integer CHECK (rooms_available >= 0),
    rooms_sold          integer CHECK (rooms_sold >= 0),
    complimentary_rooms integer CHECK (complimentary_rooms >= 0),
    occupancy_pct       numeric(6, 2) CHECK (occupancy_pct BETWEEN 0 AND 100),
    adr                 money_thb,
    revpar              money_thb,
    guests              integer CHECK (guests >= 0),
    room_charges        money_thb,
    extra_charges       money_thb,
    tax_amount          money_thb,
    receipts            money_thb,
    expenses            money_thb,
    imported_file_id    bigint NOT NULL REFERENCES imported_files (id),
    import_row_id       bigint REFERENCES import_rows (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, stat_date, imported_file_id),
    CHECK (rooms_sold <= rooms_available)
);
COMMENT ON TABLE pms_daily_statistics IS 'สถิติรายวันจาก Monthly Statistics (อัตราเข้าพัก ADR RevPAR) — ใช้แสดงผลและตรวจทานเท่านั้น ไม่นับเป็นรายการบัญชี';
COMMENT ON COLUMN pms_daily_statistics.id IS 'รหัส (PK)';
COMMENT ON COLUMN pms_daily_statistics.property_id IS 'กิจการ';
COMMENT ON COLUMN pms_daily_statistics.stat_date IS 'วันที่ของสถิติ';
COMMENT ON COLUMN pms_daily_statistics.is_forecast IS 'true = วันที่ยังไม่ถึงตอนออกรายงาน (ยอดจองล่วงหน้า ไม่ใช่ยอดจริง)';
COMMENT ON COLUMN pms_daily_statistics.rooms_available IS 'ห้องที่ขายได้';
COMMENT ON COLUMN pms_daily_statistics.rooms_sold IS 'ห้องที่ขายแล้ว';
COMMENT ON COLUMN pms_daily_statistics.complimentary_rooms IS 'ห้องฟรี';
COMMENT ON COLUMN pms_daily_statistics.occupancy_pct IS 'อัตราเข้าพัก (%) ตาม PMS';
COMMENT ON COLUMN pms_daily_statistics.adr IS 'ราคาห้องเฉลี่ยต่อห้องที่ขาย (ADR)';
COMMENT ON COLUMN pms_daily_statistics.revpar IS 'รายได้ต่อห้องที่มี (RevPAR)';
COMMENT ON COLUMN pms_daily_statistics.guests IS 'จำนวนแขก';
COMMENT ON COLUMN pms_daily_statistics.room_charges IS 'ค่าห้องตาม PMS';
COMMENT ON COLUMN pms_daily_statistics.extra_charges IS 'ค่าบริการเสริมตาม PMS';
COMMENT ON COLUMN pms_daily_statistics.tax_amount IS 'ภาษีตาม PMS';
COMMENT ON COLUMN pms_daily_statistics.receipts IS 'เงินรับตาม PMS (ใช้เทียบกับ Receipt Detail)';
COMMENT ON COLUMN pms_daily_statistics.expenses IS 'รายจ่ายตาม PMS (ใช้เทียบกับ Expense Voucher)';
COMMENT ON COLUMN pms_daily_statistics.imported_file_id IS 'ไฟล์ที่มา (ไฟล์ใหม่กว่าของวันเดียวกันแทนที่ในการแสดงผล)';
COMMENT ON COLUMN pms_daily_statistics.import_row_id IS 'แถวข้อมูลดิบที่มา';
COMMENT ON COLUMN pms_daily_statistics.created_at IS 'เวลาที่สร้าง';
