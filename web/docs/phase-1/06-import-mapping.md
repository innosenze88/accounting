# 6. Import Mapping — รายงานจาก PMS

## 6.0 อ่านก่อน: ความน่าเชื่อถือของ mapping นี้

| แหล่งข้อมูล | ใช้อย่างไร | ความน่าเชื่อถือ |
|---|---|---|
| **โครงสร้างรายงาน eZee Absolute** ที่ตัวอ่านในแอป Android (`app/src/main/java/com/example/data/ezee/EzeeReportParser.kt`) อ่านได้ — สร้างจากไฟล์ PDF จริงที่รีสอร์ตได้รับทางอีเมล และมีเทสต์ (`EzeeReportParserTest.kt`) | เป็นฐานของ "ฟิลด์มาตรฐาน" และคอลัมน์ต้นทางด้านล่าง | ✅ ยืนยันจากไฟล์จริง (รูปแบบ PDF) |
| **HotelRunner** — เอกสารช่วยเหลือของ HotelRunner ระบุว่ารายงานปิดวัน (PMS › Night Audit › Archive) มีหมวด Overview (occupancy, revenue, ADR), Financial Statements (เงินสด บัตร ออนไลน์), Cashier and Reservation Transactions, Cancellation and No-Show และส่งออกเป็น PDF/Excel ได้; เมนู PMS › Reports มี Revenue Report, Payments Report ฯลฯ ที่เลือกฟิลด์ได้ | ใช้ตั้งสมมติฐานว่ารายงานไหนของ HotelRunner ทำหน้าที่ใด | ⚠️ **ยังไม่มีไฟล์ตัวอย่าง** — ชื่อคอลัมน์จริงของ HotelRunner **ยังไม่ทราบ** ทุกช่อง "HotelRunner" ด้านล่างเป็น "ต้องยืนยัน" |

ข้อสังเกตสำคัญ: ชื่อรายงานใน requirement (Night Audit, Monthly Statistics, Front Desk Activities, Receipt Detail, Expense Voucher) ตรงกับชื่อรายงานของ **eZee Absolute** ทุกชื่อ ระบบจึงออกแบบให้ **ประเภทรายงานกำหนดตามหน้าที่** (`report_type`) และผูกกับไฟล์จริงผ่าน `import_mapping_profiles` (แยกตาม `source_system` = `HOTELRUNNER`/`EZEE`) — เมื่อได้ไฟล์ตัวอย่างจะเพิ่มแม่แบบได้โดยไม่แก้โค้ด และแม่แบบจะเปลี่ยนสถานะเป็น `VERIFIED_WITH_SAMPLE` ก็ต่อเมื่อทดสอบกับไฟล์จริงแล้ว

**คำขอ requirement ระบุ "3 ประเภท"**: เอกสารนี้ทำ mapping ละเอียดของ 3 ประเภทแรก (Night Audit, Monthly Statistics, Front Desk Activities) และเพิ่ม Receipt Detail, Expense Voucher และ Bank Statement เพราะเป็นแหล่งเดียวของ "เงินรับจริง" และ "รายจ่าย" — ถ้า "3 ประเภท" หมายถึงชุดอื่น โปรดระบุ

### หน้าที่ของแต่ละรายงาน (กันนับซ้ำ)

| report_type | สร้างอะไรในบัญชี | ห้ามใช้เป็น |
|---|---|---|
| `NIGHT_AUDIT` | **รายได้** ต่อคืน (`revenue_entries`) + ยอดควบคุมรายวัน | เงินรับ |
| `MONTHLY_STATISTICS` | ไม่สร้างรายการบัญชี — สถิติ (`pms_daily_statistics`) และใช้เทียบยอด | รายได้/เงินรับ (จะนับซ้ำกับรายงานรายวัน และมีวันอนาคตที่เป็นยอดจองล่วงหน้า) |
| `FRONT_DESK_ACTIVITIES` | ไม่สร้างรายการบัญชี — สถานะการจอง + ยอด folio ตาม PMS เพื่อเทียบลูกหนี้ | รายได้/เงินรับ |
| `RECEIPT_DETAIL` | **เงินรับจริง** (`payments`) + การจัดสรรเข้า folio | รายได้ |
| `EXPENSE_VOUCHER` | **รายจ่าย** ที่จ่ายจากหน้าร้าน (`expenses` + `expense_payments`) | — (ระวังซ้ำกับใบเสร็จที่บันทึกเอง) |
| `BANK_STATEMENT` | รายการธนาคาร (`bank_transactions`) + ยอดปลายงวด | รายรับรายจ่าย (ใช้จับคู่เท่านั้น) |

## 6.1 กฎการอ่านค่าที่ใช้กับทุกรายงาน

| เรื่อง | กฎ |
|---|---|
| วันที่ | รองรับ `DD/MM/YYYY`, `D/M/YYYY`, `YYYY-MM-DD`, Excel serial date; ปี > 2400 = พ.ศ. → ลบ 543; วันที่ไม่มีจริง (31/02) = INVALID; วันที่อยู่นอกช่วงของรายงาน = INVALID |
| ตัวเลข | ตัด `,` และช่องว่าง; `(1,234.50)` = −1234.50; รองรับเลขไทย ๐–๙; เก็บเป็น Decimal 2 ตำแหน่ง — ถ้าต้นฉบับมีมากกว่า 2 ตำแหน่ง = INVALID (ไม่ปัดเงียบ) |
| ค่าว่าง/`-` | ช่องเงินที่ว่างหรือ `-` = ไม่มีค่า (NULL) ไม่ใช่ 0 — ถ้าเป็นช่องบังคับ = INVALID |
| ข้อความ | ตัดช่องว่างหัวท้าย, normalize Unicode (NFC) สำหรับภาษาไทย; ชื่อที่อ่านไม่ได้ (`???` จาก PDF ฟอนต์ไม่ครบ) = เตือน |
| CSV encoding | ตรวจ UTF-8 (มี/ไม่มี BOM), Windows-874/TIS-620 |
| Excel | อ่านค่าที่เก็บใน cell (ไม่ประเมินสูตร), ไม่เปิดไฟล์ที่มี macro; `raw_data` เก็บข้อความตามที่แสดง + ค่าดิบ |
| แถวสรุป/หัวตาราง | แถว Total/Grand Total ไม่เป็นรายการ แต่เก็บเป็น `source_control_totals` เพื่อเทียบ |
| ชื่อ Source/ช่องทาง | จับคู่กับ `ota_channels.pms_source_names` (เช่น `Booking.com`, `Booking.com-1`) — ไม่พบ = CONFLICT ให้ผู้ใช้เลือกช่องทาง |
| ข้อมูลส่วนบุคคล | ไม่นำเบอร์โทร/อีเมล/เลขบัตรเข้าตารางบัญชี (ยังอยู่ในไฟล์ต้นฉบับและ `raw_data` ซึ่งเข้าถึงได้เฉพาะผู้มีสิทธิ์) |
| natural_key | `{property_id}|{source_system}|{report_type}|{อ้างอิงของแถว}` — ระบุต่อรายงานด้านล่าง |
| row_hash | SHA-256 ของ `normalized_data` (เรียง key) — key เดิม hash เดิม = DUPLICATE, hash ต่าง = CONFLICT |

ตัวอย่าง `import_mapping_profiles.column_map` (รูปแบบ — คอลัมน์ HotelRunner ยังเป็นค่าสมมติจนกว่าจะได้ไฟล์):

```json
{
  "business_date": {"source": "@header:As On Date", "type": "date", "required": true},
  "room":          {"source": "Room",          "type": "text",  "required": true, "transform": "split_room_type"},
  "folio_no":      {"source": "Folio No.",     "type": "text",  "required": true},
  "offered_tariff":{"source": "Offered Tariff","type": "money", "required": true},
  "total_tax":     {"source": "Total Tax",     "type": "money", "required": true},
  "total_rent":    {"source": "Total Rent",    "type": "money", "required": true}
}
```

---

## 6.2 NIGHT_AUDIT — รายงานปิดวัน (หลัก: รายได้รายคืน)

- ความถี่: วันละ 1 ไฟล์ (ทุกวัน แม้ไม่มีแขก — ใช้ตรวจความครบถ้วนของเดือน)
- `report_date_from = report_date_to = business_date`
- โครงสร้าง eZee: 3 ส่วน — Room Charges (รายห้อง), Daily Sales (สรุปตามประเภทยอดขาย), Room Status (สถานะห้อง)
- HotelRunner (ต้องยืนยัน): Night Audit › Archive ของวันนั้น ส่งออก Excel — คาดว่าส่วน "Reservation Transactions"/"Overview" ทำหน้าที่ Room Charges/Daily Sales

### A. ส่วน Room Charges (1 แถว = 1 ห้อง-คืน)

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee (ยืนยันจากไฟล์จริง) | HotelRunner (ต้องยืนยัน) | ปลายทาง |
|---|---|---|---|---|---|
| `business_date` | date | ✔ | หัวรายงาน `As On Date` | วันที่ของ Archive | `revenue_entries.service_date` |
| `room_no` | text | ✔ | `11 - Deluxe` → ส่วนหน้าเครื่องหมาย `-` | Room | `rooms.room_no` → `room_id` |
| `room_type` | text | | `11 - Deluxe` → ส่วนหลัง | Room Type | ตรวจกับ `rooms.room_type` |
| `folio_no` | text | ✔ | คอลัมน์ที่ 2 (เลข folio) | Folio / Reservation No. | `folios.folio_no` (สร้างถ้ายังไม่มี) |
| `guest_name` | text | | คอลัมน์ที่ 3 | Guest | `folios.bill_to_name` |
| `source_name` | text | | คอลัมน์ Source (เช่น `Expedia`) — ว่างได้ | Channel / Source | `ota_channels` ผ่าน `pms_source_names` |
| `rate_type` | text | | `Room Only` | Rate Plan | `revenue_entries.description` |
| `normal_tariff` | money | | Normal Tariff | Rack rate | ข้อมูลประกอบ |
| `offered_tariff` | money | ✔ | Offered Tariff | Rate / Price | `amount_excl_tax` |
| `total_tax` | money | ✔ | Total Tax | Tax | `vat_amount` (ดูกฎ VAT) |
| `total_rent` | money | ✔ | Total Rent | Total | `total_amount` |
| `variance_pct` | number | | Variance % | — | ข้อมูลประกอบ (เช่น −32.5% บ่งชี้ราคาสุทธิ OTA) |
| `user` | text | | คอลัมน์สุดท้าย (เช่น `admin`) | User | `raw_data` เท่านั้น |

- **natural_key**: `{pid}|{src}|NIGHT_AUDIT|{business_date}|{folio_no}|{room_no}|ROOM`
- **ตรวจรายแถว**: `total_rent = offered_tariff + total_tax` (ต่างได้ 0.00); ยอด ≥ 0; folio ไม่ว่าง; ห้องมีอยู่และ active ในวันนั้น
- **กฎ VAT**: ถ้า `tax_profiles.vat_status ≠ REGISTERED` แต่ `total_tax > 0` → แถวเป็น CONFLICT "PMS คิดภาษีแต่กิจการยังไม่ยืนยันการจด VAT" ให้นักบัญชีตัดสิน
- **⚠️ ราคาสุทธิ OTA**: ตัวอย่างจากไฟล์ eZee — แถว Expedia มี Normal 1,510.00 แต่ Offered 1,019.26 (−32.5%) แสดงว่า PMS อาจบันทึกการจองแบบ OTA Collect เป็น **ราคาสุทธิหลังค่าคอม** ถ้าเป็นเช่นนั้น รายได้จะต่ำกว่าจริงและค่าคอมหายไปจากงบ — ต้องให้นักบัญชีเลือก (ไฟล์ 07 ข้อ A5): บันทึกยอดเต็ม + ค่าคอม (แนะนำ, R7) หรือยอดสุทธิ

### B. ส่วน Daily Sales (ยอดควบคุม)

| ฟิลด์ | eZee | ใช้ทำอะไร |
|---|---|---|
| `sales_type` | `Room Sales`, `No Show Sales`, `Cancellation Sales` | แยกประเภทรายได้ |
| `room_charges`, `extra_charges`, `room_tax`, `extra_tax`, `discount`, `adjustment`, `total_sales` | 7 คอลัมน์ตามลำดับ | `imported_files.source_control_totals` |

- **ตรวจ**: แต่ละแถว `room_charges + extra_charges + room_tax + extra_tax − discount + adjustment = total_sales`; ผลรวมแต่ละคอลัมน์ = แถว `Total (THB)`; `Room Sales.total_sales` = Σ `total_rent` ของ Room Charges (ไม่ตรง → NEEDS_REVIEW เช่น ห้อง day-use)
- **No Show / Cancellation**: Daily Sales มีแค่ยอดรวม ไม่มี folio — สร้าง `revenue_entries` ประเภท `NO_SHOW_FEE`/`CANCELLATION_FEE` เป็น DRAFT บน folio ประเภท `HOUSE` ชื่อ `NOSHOW-{date}` และขึ้นรายการให้ผู้ใช้ผูกกับการจองจริง (ต้องใช้รายงาน No Show/Cancellation หรือ Receipt Detail ประกอบ)
- ⚠️ **ห้ามนำเข้า Manager Report ของวันเดียวกันเป็นรายได้อีก** (Total Revenue with Tax ของ Manager Report = ยอดขายรวมของ Night Audit — ตัวอ่าน eZee เดิมเตือนเรื่องนี้)

### C. ส่วน Room Status (สถิติ)

| ฟิลด์ | eZee | ปลายทาง/ตรวจ |
|---|---|---|
| `total_rooms`, `occupied`, `due_out`, `vacant`, `departed`, `reserve`, `blocked` | ตาราง Room Status 7 ตัวเลข | `pms_daily_statistics`; `total_rooms` ≠ จำนวนห้อง active ใน `rooms` → เตือน (เช่น 15 vs 16) |

---

## 6.3 MONTHLY_STATISTICS — สถิติรายเดือน (ไม่สร้างรายการบัญชี)

- ความถี่: เดือนละครั้งหลังสิ้นเดือน (นำเข้าระหว่างเดือนได้ แต่ทุกวันหลังวันออกรายงานคือ `is_forecast = true`)
- `report_date_from` = วันที่ 1, `report_date_to` = วันสุดท้ายของเดือน (จากหัว `Month September,2026`)
- HotelRunner (ต้องยืนยัน): คาดว่าเป็น Revenue Report / Occupancy report รายวันในเดือน

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee | HotelRunner | ปลายทาง `pms_daily_statistics` |
|---|---|---|---|---|---|
| `stat_date` | date | ✔ | หัว `Month <ชื่อเดือน>,<ปี>` + เลขวันต้นแถว (`1 Tue`) | Date | `stat_date` |
| `available_rooms` | int | ✔ | คอลัมน์ 1 | Available | `rooms_available` |
| `nights_sold` | int | ✔ | คอลัมน์ 2 | Sold / Room nights | `rooms_sold` |
| `complimentary` | int | | คอลัมน์ 3 | Complimentary | `complimentary_rooms` |
| `occupancy_pct` | number | | คอลัมน์ 4 | Occupancy % | `occupancy_pct` |
| `adr` | money | | คอลัมน์ 5 | ADR | `adr` |
| `revpar` | money | | คอลัมน์ 6 | RevPAR | `revpar` |
| `pax` | int | | คอลัมน์ 7 | Guests | `guests` |
| `room_charges` | money | ✔ | คอลัมน์ 8 | Room Revenue | `room_charges` |
| `extra_charges` | money | | คอลัมน์ 9 | Extras | `extra_charges` |
| `tax` | money | | คอลัมน์ 10 | Tax | `tax_amount` |
| `receipt` | money | | คอลัมน์ 11 | Payments | `receipts` |
| `expense` | money | | คอลัมน์ 12 | Expenses | `expenses` |

- **natural_key**: `{pid}|{src}|MONTHLY_STATISTICS|{stat_date}|{sha256 ของไฟล์ 12 ตัวแรก}` (เก็บทุก snapshot แสดงฉบับล่าสุด)
- **ตรวจภายในไฟล์**: Σ แถว = แถว `Total` สำหรับ nights_sold, room_charges, tax, receipt, expense; `occupancy ≈ nights_sold ÷ available × 100` (±0.01); `adr ≈ room_charges ÷ nights_sold`; `revpar ≈ room_charges ÷ available`
- **เทียบข้ามรายงาน (แสดงเป็นรายการผลต่าง ไม่ลงบัญชี)**: `room_charges` ต่อวัน vs Σ `revenue_entries` ROOM ที่ VERIFIED วันนั้น; `receipt` vs Σ `payments` ของวันนั้น; `expense` vs Σ รายจ่ายจาก Expense Voucher
- ⚠️ รายงานรวมวันที่ยังไม่ถึง (ยอดจองล่วงหน้า) — ห้ามนับเป็นรายได้

---

## 6.4 FRONT_DESK_ACTIVITIES — สถานะแขกและยอด folio (ไม่สร้างรายการบัญชี)

- ความถี่: รายวัน (เช้า/ปิดวัน)
- โครงสร้าง eZee: หัว `Date dd/mm/yyyy`; หลายส่วน (เช่น `Departing Guests`, `Pending Check-outs`, Arrivals, In House) แต่ละแขก 2 บรรทัด: บรรทัดห้อง/แขก และบรรทัด `Folio No. | Total | Paid | Balance`
- HotelRunner (ต้องยืนยัน): Arrivals/Departures/In-house list หรือ Folio report

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee | HotelRunner | ปลายทาง |
|---|---|---|---|---|---|
| `report_date` | date | ✔ | หัว `Date` | วันที่รายงาน | `folios.pms_snapshot_date` |
| `section` | text | ✔ | ชื่อส่วน (`Departing Guests`, `Pending Check-outs`, ...) | Status | กำหนด `reservations.status` ตามตารางด้านล่าง |
| `room` | text | ✔ | `4 - Superior` | Room | `rooms` |
| `guest_name` | text | | ชื่อแขก (ไม่เก็บเบอร์ที่อยู่ติดกัน) | Guest | `reservations.guest_name` |
| `arrival` | date | ✔ | Arrival | Check-in | `reservations.arrival_date` |
| `departure` | date | ✔ | Departure | Check-out | `reservations.departure_date` |
| `nights` | int | | Nights | Nights | ตรวจ = departure − arrival |
| `pax` | text | | `2/0` (ผู้ใหญ่/เด็ก) | Adults/Children | `adults`, `children` |
| `rate_type` | text | | Rate Type | Rate plan | — |
| `source` | text | | Source (เช่น `Expedia`) | Channel | `reservations.channel_id` |
| `res_no` | text | ✔ | Res No. (เช่น `937`, `918-2`) | Reservation No. | `reservations.source_ref` |
| `voucher` | text | | Voucher | OTA confirmation | `reservations.ota_booking_ref` |
| `folio_no` | text | ✔ | Folio No. | Folio | `folios.folio_no` |
| `folio_total` | money | ✔ | Total | Total | `folios.pms_total` |
| `folio_paid` | money | ✔ | Paid | Paid | `folios.pms_paid` |
| `folio_balance` | money | ✔ | Balance | Balance | `folios.pms_balance` |

| ส่วนในรายงาน | สถานะที่ตั้ง |
|---|---|
| Arrivals / Expected Arrivals | `CONFIRMED` |
| In House | `IN_HOUSE` |
| Departing Guests / Departed | `CHECKED_OUT` (เมื่อรายงานยืนยันว่าออกแล้ว) |
| Pending Check-outs | `IN_HOUSE` + เตือน "ค้างเช็คเอาท์" |
| No Show / Cancelled | `NO_SHOW` / `CANCELLED` |

- **natural_key**: `{pid}|{src}|FRONT_DESK_ACTIVITIES|{report_date}|{res_no}|{folio_no}`
- **ตรวจ**: `balance = total − paid`; `nights = departure − arrival`; วันที่ arrival < departure
- **ผลลัพธ์ที่ใช้**: รายการ folio ที่ `v_folio_balances.balance ≠ pms_balance` (คอลัมน์ `difference_vs_pms`) ให้คนตรวจ — ใช้หาเงินรับ/รายได้ที่ยังไม่ได้นำเข้า
- ⚠️ PDF ของ eZee ฝังฟอนต์ไทยไม่ครบ ชื่อไทยกลายเป็น `???` — ไฟล์ Excel/CSV ไม่มีปัญหานี้

---

## 6.5 RECEIPT_DETAIL — เงินรับจริง

- HotelRunner (ต้องยืนยัน): Payments Report หรือส่วน Financial Statements/Cashier ของ Night Audit Archive
- eZee: ไม่มีตัวอ่าน Receipt Detail ในแอปเดิม (มีเฉพาะยอดรวมตามวิธีชำระใน Manager Report: `MOBILE BANKING`, `Cash`, `Total Payment`) — **ยังไม่มีโครงสร้างคอลัมน์ที่ยืนยันได้จากทั้งสองระบบ**

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | คอลัมน์ต้นทาง (ต้องยืนยัน) | ปลายทาง |
|---|---|---|---|---|
| `receipt_date` | date | ✔ | Date | `payments.received_date` |
| `receipt_no` | text | ✔ | Receipt No. | `payments.receipt_no`, `source_ref` |
| `folio_no` | text | (ควรมี) | Folio No. | `payment_allocations.folio_id` |
| `res_no` | text | | Reservation No. | ใช้หา folio |
| `payer_name` | text | | Guest / Paid by | `payments.payer_name` |
| `pay_method` | text | ✔ | Payment Type (เช่น `Cash`, `MOBILE BANKING`, `Credit Card`) | `payments.method` ผ่านตารางแปลงที่ตั้งค่าได้ |
| `amount` | money | ✔ | Amount (ติดลบ = คืนเงิน) | `amount` (บวกเสมอ) + `receipt_type` = `GUEST_PAYMENT` / `GUEST_REFUND` |
| `reference` | text | | Ref / Card approval | `external_ref` |
| `is_deposit` | bool | | Type = Advance/Deposit | ข้อมูลประกอบ (การเป็นเงินล่วงหน้าคำนวณจาก folio ไม่ใช่จาก flag) |
| `user` | text | | User | `raw_data` |

- **วิธีชำระ → บัญชีเงิน** (ตั้งค่า): `Cash` → ลิ้นชักเงินสด; `MOBILE BANKING`/`Transfer`/`PromptPay` → บัญชีธนาคารดำเนินงาน; `Credit Card` → บัญชีพักเงินบัตร (`CARD_CLEARING`) จนกว่าเงินเข้าธนาคาร
- **natural_key**: `{pid}|{src}|RECEIPT_DETAIL|{receipt_no}`; เอกสารซ้ำถูกกันซ้ำอีกชั้นด้วย `payments (property_id, source_system, source_ref)`
- **ตรวจ**: Σ amount = ยอดรวมรายงาน; Σ ตามวิธีชำระ = ยอดย่อยตามวิธีชำระ; วันที่อยู่ในช่วงรายงาน; folio ไม่พบ → เงินรับยังไม่จัดสรร (แสดงในรายการรอจัดสรร ไม่ใช่ error)
- ⚠️ เงินรับ ≠ เงินเข้าธนาคาร: บัตร/OTA virtual card เข้าบัญชีทีหลังพร้อมหักค่าธรรมเนียม, โอนกลางคืนเข้าวันถัดไป → จับคู่ใน Module D

---

## 6.6 EXPENSE_VOUCHER — รายจ่ายที่บันทึกใน PMS

- eZee (ยืนยันจากไฟล์จริง): หัว `Date From dd/mm/yyyy To dd/mm/yyyy`; ตาราง `Date | Bill To | Charge | Comment | Amount() | Pay Method | User`; แถว `Grand Total`; ตารางสรุป `Pay Method Wise Summary` และ `Charge wise Summary`
- HotelRunner: **ไม่ทราบว่ามีโมดูลรายจ่ายหรือไม่** (ไฟล์ 07 ข้อ A4) — ถ้าไม่มี รายจ่ายบันทึกในระบบนี้โดยตรง

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee | ปลายทาง |
|---|---|---|---|---|
| `voucher_date` | date | ✔ | Date | `expenses.expense_date`, `expense_payments.paid_date` |
| `voucher_no` | text | ✔ | คอลัมน์ถัดจากวันที่ (เลขใบสำคัญ) | `expenses.internal_voucher_no`, `source_ref` |
| `bill_to` | text | | Bill To (เช่น `PEA`) | `expenses.vendor_name` |
| `charge` | text | ✔ | Charge (เช่น `ค่าไฟฟ้า`) | `expense_categories` ผ่าน `pms_charge_names` — ไม่พบ = INVALID "หมวดยังไม่จับคู่" |
| `comment` | text | | Comment | `expenses.description` |
| `amount` | money | ✔ | Amount() | `total_amount`, `expense_payments.amount` |
| `pay_method` | text | ✔ | Pay Method (`Cash`, `Transfer`) | บัญชีเงินที่จ่าย |
| `user` | text | | User | `raw_data` |

- **natural_key**: `{pid}|{src}|EXPENSE_VOUCHER|{voucher_no}`
- **ตรวจ**: Σ = `Grand Total`; Σ ตาม Pay Method = ตารางสรุปวิธีจ่าย; Σ ตาม Charge = ตารางสรุปหมวด; ไฟล์ที่ `Grand Total = 0` = ไม่มีรายจ่าย (commit ได้ ไม่สร้างเอกสาร)
- **กันซ้ำกับรายการที่บันทึกเอง**: ถ้ามี expense อื่นที่ วันที่ + ยอด + ผู้ขาย ตรงกัน → CONFLICT ให้ผู้ใช้เลือก (ตัวอ่าน eZee ในแอปเดิมเตือนไว้แล้วว่าใบเสร็จที่สแกนกับ voucher ใน PMS อาจเป็นรายจ่ายเดียวกัน)

---

## 6.7 BANK_STATEMENT — รายการเดินบัญชี (Module D)

- ไฟล์จากธนาคาร (CSV/XLSX; PDF ภายหลัง) แยกแม่แบบตามธนาคาร (`variant` = `KBANK`, `SCB`, ...) — **ต้องได้ไฟล์ตัวอย่างของแต่ละธนาคาร/กระเป๋า MAKE**

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | ตัวอย่างหัวคอลัมน์ทั่วไป (ต้องยืนยัน) | ปลายทาง `bank_transactions` |
|---|---|---|---|---|
| `txn_date` | date | ✔ | วันที่ / Date | `txn_date` |
| `txn_time` | time | | เวลา / Time | `txn_time` |
| `description` | text | | รายการ / Description | `description` |
| `withdrawal` | money | ✱ | ถอน / Withdrawal | `amount` = −withdrawal |
| `deposit` | money | ✱ | ฝาก / Deposit | `amount` = +deposit |
| `balance` | money | ✔ | คงเหลือ / Balance | `balance_after` |
| `channel` | text | | ช่องทาง / Channel | `bank_channel` |
| `details` | text | | รายละเอียด / Details (ชื่อ/บัญชีคู่โอน) | `counterparty`, `bank_reference` |

✱ ต้องมีอย่างใดอย่างหนึ่งและไม่ใช่ทั้งคู่

- **dedupe_key**: SHA-256 ของ `bank_account_id|txn_date|txn_time|amount|balance_after|bank_reference|description|ลำดับในวัน` — statement ที่ทับช่วงเวลากันนำเข้าซ้ำได้โดยไม่เกิดรายการซ้ำ
- **ตรวจ**: ยอดยกมา + Σ amount = ยอดยกไป; ทุกแถว `balance_after(ก่อน) + amount = balance_after(นี้)`; ยอดยกมาต้องเท่ายอดยกไปของ statement ฉบับก่อน (ไม่มีช่วงขาด)
- **ผลลัพธ์**: `balance_confirmations` (method `BANK_STATEMENT`) ของวันสุดท้ายใน statement เป็น DRAFT ให้ผู้ตรวจ VERIFY

---

## 6.8 PDF (อนาคต)

โครงสร้างรองรับแล้ว: `import_mapping_profiles.file_format = 'PDF'` และ pipeline เดียวกัน (raw_data = ข้อความแต่ละบรรทัด/คอลัมน์) — วิธีอ่าน PDF แบบไม่ใช้ AI (แยกคอลัมน์ด้วยตำแหน่งตัวอักษร) มีต้นแบบในแอป Android (`PdfLineBuilder.kt`, `EzeeReportParser.kt`) ใช้เป็นกรณีทดสอบได้ — กฎเทียบยอดรวมเหมือนเดิม และ PDF ที่ยอดไม่ตรงจะ commit ไม่ได้เช่นกัน
