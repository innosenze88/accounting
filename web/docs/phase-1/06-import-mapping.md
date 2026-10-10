# 6. Import Mapping — รายงานจาก PMS

## 6.0 อ่านก่อน: ความน่าเชื่อถือของ mapping นี้

| แหล่งข้อมูล | ใช้อย่างไร | ความน่าเชื่อถือ |
|---|---|---|
| **PMS ที่ใช้จริง: eZee Absolute (Cloud)** — เจ้าของยืนยันเมื่อ 9 ต.ค. 2569 ว่าใช้ eZee และ **ไม่มีแผนย้าย PMS** (requirement เดิมระบุ HotelRunner) | ทุก mapping ในเอกสารนี้ใช้ `source_system = 'EZEE'` | ✅ ยืนยันแล้ว |
| **โครงสร้างรายงาน eZee Absolute** ที่ตัวอ่านในแอป Android (`app/src/main/java/com/example/data/ezee/EzeeReportParser.kt`) อ่านได้ — สร้างจากไฟล์ PDF จริงที่รีสอร์ตได้รับทางอีเมล และมีเทสต์ (`EzeeReportParserTest.kt`) | เป็นฐานของ "ฟิลด์มาตรฐาน" และคอลัมน์ต้นทางด้านล่าง | ✅ ยืนยันจากไฟล์จริง (รูปแบบ PDF) |
| **ไฟล์ Excel/CSV ที่ส่งออกจาก eZee** | ถ้าส่งออกได้ จะใช้เป็นช่องทางหลัก (อ่านง่ายกว่า PDF และชื่อภาษาไทยไม่เพี้ยน) | ⚠️ **ยังไม่เคยเห็นไฟล์** — หัวคอลัมน์ของ Excel/CSV อาจไม่ตรงกับ PDF ต้องยืนยันจากไฟล์ตัวอย่าง (ไฟล์ 07 ข้อ A2, A3) |
| **Receipt Detail ของ eZee** | แหล่งเงินรับจริง | ⚠️ แอปเดิมไม่มีตัวอ่าน — ยังไม่เคยเห็นโครงสร้าง ต้องได้ไฟล์ตัวอย่าง |
| **ไฟล์จริงที่เจ้าของส่งมา (10 ต.ค. 2569)** — PDF 5 ไฟล์ของวันทำการ 13/09/2569: Front Desk Activities, Monthly Statistics, Yearly Statistics, Monthly Occupancy, No Show Report | ตรวจโครงสร้างและยอดรวมกับ mapping นี้ (ผลในหัวข้อ 6.0.1) | ✅ Monthly Statistics และ Front Desk Activities ตรงกับ mapping; ยอดรวม Monthly/Yearly ตรงทุกคอลัมน์ — ⚠️ ยังไม่ได้ Night Audit, Receipt Detail, Expense Voucher |

ระบบกำหนด **ประเภทรายงานตามหน้าที่** (`report_type`) และผูกกับไฟล์จริงผ่าน `import_mapping_profiles` แยกตามรูปแบบไฟล์ (`file_format` = PDF / XLSX / CSV) — แม่แบบจะเปลี่ยนสถานะเป็น `VERIFIED_WITH_SAMPLE` ก็ต่อเมื่อทดสอบกับไฟล์จริงแล้ว

**รูปแบบไฟล์ที่จะนำเข้า (ต้องตัดสินใจเมื่อเห็นไฟล์)**: ปัจจุบันรีสอร์ตได้รับรายงาน eZee เป็น **PDF ทางอีเมลทุกคืน** (แอป Android อ่านไฟล์เหล่านี้อยู่) — ถ้า eZee Cloud ดาวน์โหลดรายงานเดียวกันเป็น Excel/CSV ได้ จะใช้ Excel/CSV เป็นหลักตาม requirement; ถ้าได้เฉพาะ PDF ตัวอ่าน PDF (ซึ่งมีต้นแบบและเทสต์ในแอป Android แล้ว) จะย้ายจาก "อนาคต" มาอยู่ใน Phase 2

**ขอบเขต "3 ประเภท"** (เจ้าของยืนยัน 10 ต.ค. 2569): Night Audit, Monthly Statistics, Front Desk Activities เป็น mapping หลัก — เอกสารนี้เพิ่ม Receipt Detail, Expense Voucher และ Bank Statement ไว้ด้วย เพราะเป็นแหล่งเดียวของ "เงินรับจริง" และ "รายจ่าย"

### 6.0.1 สิ่งที่พบจากไฟล์จริง (ไม่มีข้อมูลส่วนบุคคลของแขกในเอกสารนี้)

| # | สิ่งที่พบ | ผลต่อการออกแบบ |
|---|---|---|
| 1 | ไฟล์มาเป็นชุดอัตโนมัติทุกคืน ชื่อไฟล์ `<รายงาน>_<รหัส>_<YYYYMMDD วันทำการ>_60029.pdf` สร้างเวลา ~06:11 ของเช้าวันถัดไป | ใช้วันที่ในชื่อไฟล์เป็นวันทำการ; `60029` น่าจะเป็นรหัสโรงแรมใน eZee (ต้องยืนยัน) → `properties.pms_property_ref` |
| 2 | Front Desk Activities ของวันทำการ 13/09 พิมพ์ `Date 14/09/2026` (วันถัดไป) | `report_date` ของ FDA = วันทำการ + 1 — ต้องไม่ใช้เป็นวันที่ของรายได้ |
| 3 | PDF มาจาก 2 เครื่องมือ: TCPDF (Front Desk — ชื่อภาษาไทยเป็น `???`) และ Stimulsoft (Monthly/Yearly/No Show/Occupancy — ภาษาไทยอ่านได้) | ตัวอ่าน PDF ต้องรองรับทั้งสองแบบ; ชื่อแขกจาก FDA ใช้จับคู่ไม่ได้ ใช้เลข folio/การจองแทน |
| 4 | ห้องขายได้ **15 ห้อง/วัน** ทุกวัน แต่ใน FDA มีห้องเลข 16 | จำนวนห้อง = 15 ✅; เลขห้องไม่ต่อเนื่อง — ต้องได้รายชื่อห้องที่ขายจริง (ไฟล์ 07 ข้อ B1) |
| 5 | eZee คิด **Occ% = (ขาย + ห้องฟรี) ÷ ห้องที่มี** และ **ADR = ค่าห้อง ÷ (ขาย + ห้องฟรี)** — ตรวจกับ Yearly: (1,476.5 + 8) ÷ 4,950 = 29.99%, 1,454,314.83 ÷ 1,484.5 = 979.67 | ปรับสูตรตรวจในหัวข้อ 6.3 |
| 6 | ห้อง-คืนที่ขายมีทศนิยม (เมษายน 134.5) | เปลี่ยน `pms_daily_statistics.rooms_sold` เป็น NUMERIC แล้ว (มีเทสต์) |
| 7 | คอลัมน์ Tax เป็น 0 ทั้งปี ยกเว้นกันยายน 529.24 จาก 3 รายการ; รายการที่ตรวจได้: ค่า No-show 990.00 = 841.12 + 148.88 (17.7% = 1.10 × 1.07 ตรงกับ service charge 10% + VAT 7% แบบรวมในราคา — **สมมติฐาน ต้องให้นักบัญชียืนยัน**) | กฎ R12 ทำงานตามออกแบบ: ถ้ากิจการยังไม่ยืนยันการจด VAT รายการที่มีภาษีจะเป็น CONFLICT ให้นักบัญชีตัดสิน |
| 8 | การจอง **Expedia Collect**: Remark ระบุ `Expedia collects payment from traveler: Hotel invoices Expedia … Remittance Type: NET`; folio ยอด 1,019.26 จ่ายครบ 1,019.26 | **eZee บันทึกยอดสุทธิ (NET)** ของ Expedia Collect — หลักฐานสำหรับไฟล์ 07 ข้อ A5; Remark ใช้ระบุได้ว่าเป็น OTA Collect |
| 9 | Pending Check-outs: 9 folio ที่ต้องออกวันที่ 13/09 ยังเปิดอยู่ ทุกใบ Paid = 0.00 รวมค้าง 11,580 บาท (+ ห้องที่ออก 14/09 ค้าง 1,680) | ใน eZee ยังไม่มีการรับเงิน — ต้องรู้ว่าเงินของการจองเหล่านี้บันทึกที่อื่นหรือยังไม่ได้รับจริง (ไฟล์ 07 ข้อ A9) |
| 10 | Receipt ใน eZee ต่ำกว่าค่าห้องมากในบางช่วง (1–4 ก.ย. เต็ม 15 ห้องทุกคืน แต่ Receipt มีเพียง −1,400 วันที่ 3 ก.ย.) | ยืนยันว่าต้องแยกรายได้กับเงินรับ; Receipt ติดลบ = คืนเงิน → `GUEST_REFUND` |
| 11 | No Show Report: Booking.com ไม่มาเข้าพัก จ่ายแล้ว 2,320 แต่ไม่มีการคิดค่า No-show → ยอดคงเหลือ −2,320 (เงินค้างในระบบ); Expedia ไม่มาเข้าพัก คิดค่า 990.00 | เป็นกรณี "เงินรับล่วงหน้า" ที่ต้องตัดสินว่าเป็นรายได้หรือคืนเงิน; แนะนำนำเข้า No Show Report เพิ่ม (หัวข้อ 6.4.1) เพื่อผูกค่า No-show กับ folio |
| 12 | Yearly: มกราคม 2569 ห้องที่ขายได้ = 0 แต่ Expense 119,754.81; เริ่มขายกุมภาพันธ์ (360 = 24 วัน × 15 ห้อง); Expense ถูกบันทึกใน eZee ทุกเดือน ก.พ.–ส.ค. | Expense Voucher ของ eZee ถูกใช้งานจริง; วันเริ่มระบบและยอดยกมาต้องตกลงกับนักบัญชี |
| 13 | Monthly Occupancy เป็นกราฟ ไม่มีตารางตัวเลข | ไม่นำเข้า (ใช้ Monthly Statistics แทน) |

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
| natural_key | `{property_id}\|{source_system}\|{report_type}\|{อ้างอิงของแถว}` — ระบุต่อรายงานด้านล่าง |
| row_hash | SHA-256 ของ `normalized_data` (เรียง key) — key เดิม hash เดิม = DUPLICATE, hash ต่าง = CONFLICT |

ตัวอย่าง `import_mapping_profiles.column_map` (รูปแบบ — ชื่อคอลัมน์ตามรายงาน eZee ฉบับ PDF; ไฟล์ Excel/CSV ต้องยืนยันว่าหัวคอลัมน์ตรงกัน):

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

### A. ส่วน Room Charges (1 แถว = 1 ห้อง-คืน)

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee (ยืนยันจาก PDF จริง) | ปลายทาง |
|---|---|---|---|---|
| `business_date` | date | ✔ | หัวรายงาน `As On Date` | `revenue_entries.service_date` |
| `room_no` | text | ✔ | `11 - Deluxe` → ส่วนหน้าเครื่องหมาย `-` | `rooms.room_no` → `room_id` |
| `room_type` | text | | `11 - Deluxe` → ส่วนหลัง | ตรวจกับ `rooms.room_type` |
| `folio_no` | text | ✔ | คอลัมน์ที่ 2 (เลข folio) | `folios.folio_no` (สร้างถ้ายังไม่มี) |
| `guest_name` | text | | คอลัมน์ที่ 3 | `folios.bill_to_name` |
| `source_name` | text | | คอลัมน์ Source (เช่น `Expedia`) — ว่างได้ | `ota_channels` ผ่าน `pms_source_names` |
| `rate_type` | text | | `Room Only` | `revenue_entries.description` |
| `normal_tariff` | money | | Normal Tariff | ข้อมูลประกอบ |
| `offered_tariff` | money | ✔ | Offered Tariff | `amount_excl_tax` |
| `total_tax` | money | ✔ | Total Tax | `vat_amount` (ดูกฎ VAT) |
| `total_rent` | money | ✔ | Total Rent | `total_amount` |
| `variance_pct` | number | | Variance % | ข้อมูลประกอบ (เช่น −32.5% บ่งชี้ราคาสุทธิ OTA) |
| `user` | text | | คอลัมน์สุดท้าย (เช่น `admin`) | `raw_data` เท่านั้น |

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

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee | ปลายทาง `pms_daily_statistics` |
|---|---|---|---|---|
| `stat_date` | date | ✔ | หัว `Month <ชื่อเดือน>,<ปี>` + เลขวันต้นแถว (`1 Tue`) | `stat_date` |
| `available_rooms` | int | ✔ | คอลัมน์ 1 | `rooms_available` |
| `nights_sold` | int | ✔ | คอลัมน์ 2 | `rooms_sold` |
| `complimentary` | int | | คอลัมน์ 3 | `complimentary_rooms` |
| `occupancy_pct` | number | | คอลัมน์ 4 | `occupancy_pct` |
| `adr` | money | | คอลัมน์ 5 | `adr` |
| `revpar` | money | | คอลัมน์ 6 | `revpar` |
| `pax` | int | | คอลัมน์ 7 | `guests` |
| `room_charges` | money | ✔ | คอลัมน์ 8 | `room_charges` |
| `extra_charges` | money | | คอลัมน์ 9 | `extra_charges` |
| `tax` | money | | คอลัมน์ 10 | `tax_amount` |
| `receipt` | money | | คอลัมน์ 11 | `receipts` |
| `expense` | money | | คอลัมน์ 12 | `expenses` |

- **natural_key**: `{pid}|{src}|MONTHLY_STATISTICS|{stat_date}|{sha256 ของไฟล์ 12 ตัวแรก}` (เก็บทุก snapshot แสดงฉบับล่าสุด)
- **ตรวจภายในไฟล์**: Σ แถว = แถว `Total` สำหรับ available, nights_sold, complimentary, pax, room_charges, extra_charges, tax, receipt, expense; `occupancy ≈ (nights_sold + complimentary) ÷ available × 100` (±0.01); `adr ≈ room_charges ÷ (nights_sold + complimentary)`; `revpar ≈ room_charges ÷ available`
- ✅ **ทดสอบกับไฟล์จริงแล้ว** (กันยายน 2569 และ Yearly 2569): ทุกคอลัมน์ Σ แถว = Total ตรงทุกสตางค์; สูตร Occ/ADR/RevPAR ตรงกับที่พิมพ์
- `nights_sold`/`complimentary` มีทศนิยมได้ (0.5); `receipt` ติดลบได้ (วันที่มีคืนเงิน)
- **เทียบข้ามรายงาน (แสดงเป็นรายการผลต่าง ไม่ลงบัญชี)**: `room_charges` ต่อวัน vs Σ `revenue_entries` ROOM ที่ VERIFIED วันนั้น; `receipt` vs Σ `payments` ของวันนั้น; `expense` vs Σ รายจ่ายจาก Expense Voucher
- ⚠️ รายงานรวมวันที่ยังไม่ถึง (ยอดจองล่วงหน้า) — ห้ามนับเป็นรายได้

---

## 6.4 FRONT_DESK_ACTIVITIES — สถานะแขกและยอด folio (ไม่สร้างรายการบัญชี)

- ความถี่: รายวัน (เช้า/ปิดวัน)
- โครงสร้าง eZee: หัว `Date dd/mm/yyyy`; หลายส่วน (เช่น `Departing Guests`, `Pending Check-outs`, Arrivals, In House) แต่ละแขก 2 บรรทัด: บรรทัดห้อง/แขก และบรรทัด `Folio No. | Total | Paid | Balance`

| ฟิลด์มาตรฐาน | ชนิด | บังคับ | eZee | ปลายทาง |
|---|---|---|---|---|
| `report_date` | date | ✔ | หัว `Date` | `folios.pms_snapshot_date` |
| `section` | text | ✔ | ชื่อส่วน (`Departing Guests`, `Pending Check-outs`, ...) | กำหนด `reservations.status` ตามตารางด้านล่าง |
| `room` | text | ✔ | `4 - Superior` | `rooms` |
| `guest_name` | text | | ชื่อแขก (ไม่เก็บเบอร์ที่อยู่ติดกัน) | `reservations.guest_name` |
| `arrival` | date | ✔ | Arrival | `reservations.arrival_date` |
| `departure` | date | ✔ | Departure | `reservations.departure_date` |
| `nights` | int | | Nights | ตรวจ = departure − arrival |
| `pax` | text | | `2/0` (ผู้ใหญ่/เด็ก) | `adults`, `children` |
| `rate_type` | text | | Rate Type | — |
| `source` | text | | Source (เช่น `Expedia`) | `reservations.channel_id` |
| `res_no` | text | ✔ | Res No. (เช่น `937`, `918-2`) | `reservations.source_ref` |
| `voucher` | text | | Voucher | `reservations.ota_booking_ref` |
| `folio_no` | text | ✔ | Folio No. | `folios.folio_no` |
| `folio_total` | money | ✔ | Total | `folios.pms_total` |
| `folio_paid` | money | ✔ | Paid | `folios.pms_paid` |
| `folio_balance` | money | ✔ | Balance | `folios.pms_balance` |

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
- ✅ **ตรวจกับไฟล์จริงแล้ว** (ฉบับ 14/09/2569): ส่วน `Departing Guests` และ `Pending Check-outs` ตรงกับ mapping — สิ่งที่ตัวอ่านต้องรองรับเพิ่ม:
  - `Date` ในรายงาน = วันทำการ + 1
  - ชื่อประเภทห้องยาวหลายบรรทัด (เช่น `Triple Room With Queen Bed + Twin Bed + Sofa Bed`)
  - บล็อก `Remark` ใต้การจอง (เช่น `Expedia Collect Booking … Remittance Type: NET`) → เก็บใน `raw_data` และใช้ตั้งธงว่าเป็น OTA Collect
  - หน้า 2 ของ PDF ตัวอักษรเลื่อนตำแหน่ง (ย่อหน้าต่างจากหน้า 1)
  - คอลัมน์ Mobile มีเบอร์โทรแขก — ไม่นำเข้าตารางบัญชี
  - คอลัมน์ Voucher = เลขยืนยันของ OTA → `reservations.ota_booking_ref`

### 6.4.1 NO_SHOW_REPORT (แนะนำเพิ่ม — ไม่อยู่ใน 3 ประเภทหลัก)

- ได้ไฟล์จริงแล้ว (As On Date 13/09/2569): คอลัมน์ `Res. No | Guest Name | Arrival | Dept. | Folio No | No Show Revenue | Charges | Paid | Balance | Source | User/วันที่บันทึก` + `Remark` และแถวรวม `#(จำนวน)`
- ใช้ทำอะไร: ผูกรายได้ค่า No-show กับ folio จริง (แทนการพักไว้ที่ folio `HOUSE` ตามหัวข้อ 6.2 B) และหาการจองที่จ่ายแล้วแต่ไม่มาโดยยังไม่ได้คิดค่า (ยอดคงเหลือติดลบ = เงินรับล่วงหน้าที่ต้องตัดสิน)
- ⚠️ PDF ฉบับนี้คอลัมน์แคบจนตัวเลขและเลขการจองถูกตัดขึ้นบรรทัดใหม่ — ถ้าจะนำเข้า ควรใช้ Excel/CSV; ถ้าใช้ PDF ต้องอ่านด้วยตำแหน่งตัวอักษร
- ถ้าเจ้าของต้องการ จะเพิ่ม `report_type = 'NO_SHOW_REPORT'` ใน schema ตอน Phase 2

---

## 6.5 RECEIPT_DETAIL — เงินรับจริง

- eZee: แอปเดิมไม่มีตัวอ่าน Receipt Detail — **ยังไม่เคยเห็นโครงสร้างคอลัมน์** คอลัมน์ด้านล่างเป็นฟิลด์ที่ต้องการ ต้องยืนยันกับไฟล์ตัวอย่าง
- ยอดควบคุมที่มีอยู่แล้ว: Manager Report ของ eZee มียอดรวมตามวิธีชำระ (`MOBILE BANKING`, `Cash`, `Total Payment`) ของวันเดียวกัน — ใช้เทียบกับ Σ Receipt Detail ได้ (ไม่นำเข้าเป็นเงินรับซ้ำ)

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
- ต้องยืนยันว่าพนักงานบันทึกรายจ่ายใน Expense Voucher ของ eZee **ทุกรายการ** หรือเฉพาะบางส่วน (เช่น จ่ายเงินสดหน้าร้าน) — รายจ่ายที่ไม่ได้อยู่ใน eZee บันทึกในระบบนี้โดยตรง (ไฟล์ 07 ข้อ A4)

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

## 6.8 PDF

ถ้า eZee Cloud ส่งออกรายงานเป็น Excel/CSV ไม่ได้ PDF จะเป็นช่องทางหลักและทำใน Phase 2 — โครงสร้างรองรับแล้ว: `import_mapping_profiles.file_format = 'PDF'` และ pipeline เดียวกัน (raw_data = ข้อความแต่ละบรรทัด/คอลัมน์) — วิธีอ่าน PDF แบบไม่ใช้ AI (แยกคอลัมน์ด้วยตำแหน่งตัวอักษร) มีต้นแบบในแอป Android (`PdfLineBuilder.kt`, `EzeeReportParser.kt`) ใช้เป็นกรณีทดสอบได้ — กฎเทียบยอดรวมเหมือนเดิม และ PDF ที่ยอดไม่ตรงจะ commit ไม่ได้เช่นกัน
