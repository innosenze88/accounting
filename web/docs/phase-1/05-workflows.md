# 5. Workflow Diagrams

## W1 นำเข้ารายงานจาก HotelRunner (Module C)

```mermaid
flowchart TD
    U[ผู้ใช้อัปโหลดไฟล์ CSV/XLSX<br/>เลือกประเภทรายงาน] --> H{SHA-256 ซ้ำกับไฟล์<br/>ที่ยังไม่ถูก REJECTED?}
    H -- ซ้ำ --> HX[แจ้ง: ไฟล์นี้นำเข้าแล้วเมื่อ ... โดย ...<br/>ไม่สร้างอะไรใหม่]
    H -- ไม่ซ้ำ --> S[เก็บไฟล์ต้นฉบับใน private storage<br/>imported_files = UPLOADED]
    S --> D{จับคู่หัวคอลัมน์กับ<br/>import_mapping_profiles}
    D -- ไม่พบแม่แบบ --> DX[PARSE_FAILED + ข้อความ<br/>ให้ผู้ดูแลสร้าง/ปรับแม่แบบ]
    D -- พบ --> P[อ่านทุกแถว → import_rows<br/>raw_data = ค่าเดิมทุกช่อง]
    P --> V[ตรวจแต่ละแถว: ชนิดข้อมูล วันที่ พ.ศ./ค.ศ.<br/>ช่องบังคับ ยอด = ส่วนประกอบ]
    V --> K[สร้าง natural_key + row_hash]
    K --> Q{key เคย COMMITTED?}
    Q -- ไม่เคย --> OK[VALID]
    Q -- เคย และ hash เท่าเดิม --> DUP[DUPLICATE<br/>ข้ามอัตโนมัติ]
    Q -- เคย แต่ค่าเปลี่ยน --> CF[CONFLICT<br/>ให้คนตัดสิน]
    V -- ไม่ผ่าน --> INV[INVALID + errors]
    OK & DUP & CF & INV --> T[เทียบยอดรวมที่คำนวณได้<br/>กับยอดรวมที่พิมพ์ในรายงาน]
    T --> PV[หน้า Preview:<br/>จำนวนแถวแต่ละสถานะ, แถวที่ไม่ผ่าน, ผลเทียบยอด]
    PV --> C{ผู้มีสิทธิ์กด Commit?}
    C -- ยอดไม่ตรง --> CX["Commit ไม่ได้ (DB บังคับ)<br/>แก้ไฟล์/แม่แบบ หรือ Reject พร้อมเหตุผล"]
    C -- ยอดตรง --> CM[สร้างเอกสาร DRAFT<br/>reservations / folios / revenue_entries / payments / expenses<br/>แต่ละเอกสารเก็บ import_row_id]
    CM --> AU[imported_files = COMMITTED<br/>Audit Log: COMMIT_IMPORT]
    AU --> RV[ผู้ตรวจ VERIFY เอกสาร<br/>→ posting engine ลงสมุดบัญชี]
```

## W2 วงจรชีวิตเอกสารบัญชี (Module B)

```mermaid
stateDiagram-v2
    [*] --> DRAFT: บันทึกเอง / จากการนำเข้า
    DRAFT --> DRAFT: แก้ไข (Audit UPDATE)
    DRAFT --> [*]: ลบ (Audit DELETE)
    DRAFT --> VERIFIED: ผู้มีสิทธิ์ตรวจ + แนบหลักฐาน (Audit VERIFY)
    VERIFIED --> VOIDED: ยกเลิกพร้อมเหตุผล (Audit VOID) + journal กลับรายการ
    VOIDED --> [*]
    note right of VERIFIED
        แก้ไม่ได้ ลบไม่ได้
        แก้ไขด้วยการ VOID แล้วบันทึกใหม่
        งวดปิดแล้ว: VOID ไม่ได้ ให้ปรับปรุงในงวดที่เปิด
    end note
```

## W3 รายได้ → เงินรับ → เงินเข้าธนาคาร (สามสิ่งที่ห้ามถือว่าเป็นอันเดียวกัน)

```mermaid
sequenceDiagram
    autonumber
    participant NA as Night Audit (รายวัน)
    participant RD as Receipt Detail
    participant SYS as ระบบบัญชี
    participant BK as Bank statement
    NA->>SYS: ค่าห้องคืนวันที่ 10, 11 ของ folio F-1001 (3,000)
    SYS->>SYS: revenue_entries ×2 (service_date 10, 11) → ลูกหนี้ 3,000
    RD->>SYS: ใบเสร็จ R-0001 มัดจำ 1,000 (วันที่ 1) / R-0002 2,000 (วันที่ 12)
    SYS->>SYS: payments ×2 (received_date) + payment_allocations → F-1001
    SYS->>SYS: v_folio_balances: 3,000 − 3,000 = 0 (SETTLED)
    BK->>SYS: เงินเข้า +1,000 (วันที่ 1), +2,000 (วันที่ 13)
    SYS->>SYS: เสนอจับคู่ (ยอดเท่ากัน วันที่ใกล้กัน) → PROPOSED
    Note over SYS: ผู้ใช้ยืนยัน → MATCHED<br/>ถ้าไม่มีรายการคู่: UNMATCHED ค้างให้คนตรวจ<br/>ระบบไม่สร้างเงินรับ/รายจ่ายขึ้นเอง
```

## W4 OTA สองแบบ

```mermaid
flowchart LR
    subgraph HC["Hotel Collect (ลูกค้าจ่ายที่รีสอร์ต)"]
        A1[รายได้ยอดเต็ม<br/>revenue_entries] --> A2[ลูกค้าจ่าย<br/>payments → folio]
        A1 --> A3[ค่าคอมประมาณ/ตามใบแจ้งหนี้<br/>ota_commissions<br/>Dr ค่าคอม Cr ค่าคอมค้างจ่าย]
        A3 --> A4[จ่ายใบแจ้งหนี้ OTA<br/>expenses หมวด OTA_COMMISSION_SETTLEMENT<br/>Dr ค่าคอมค้างจ่าย Cr ธนาคาร]
    end
    subgraph OC["OTA Collect (OTA เก็บเงินแล้วโอนให้)"]
        B1[รายได้ยอดเต็ม<br/>folio ประเภท OTA_CITY_LEDGER] --> B2[OTA payout สุทธิ<br/>payments OTA_PAYOUT → folio]
        B1 --> B3[ค่าคอมที่ถูกหัก<br/>ota_commissions DEDUCTED_FROM_PAYOUT<br/>ลดลูกหนี้ folio]
        B2 & B3 --> B4[folio = 0 เมื่อ payout + ค่าคอม = รายได้]
    end
```

## W5 กระทบยอดธนาคาร (Module D)

```mermaid
flowchart TD
    I[นำเข้า statement → bank_transactions<br/>dedupe_key กันซ้ำ, ตรวจยอดยกมา + รายการ = ยอดยกไป] --> BC[balance_confirmations<br/>ยอดปลาย statement]
    I --> AM[เสนอจับคู่อัตโนมัติ<br/>AUTO_EXACT: ยอด+วันที่+เลขอ้างอิง<br/>AUTO_RULE: ยอดเท่ากัน ±3 วัน, payout ตาม OTA]
    AM --> PR[reconciliations = PROPOSED<br/>สถานะรายการ: NEEDS_REVIEW]
    M[จับคู่เอง: เลือกรายการธนาคาร 1..n<br/>กับเงินรับ/การจ่าย/การโอน 1..n<br/>ใช้บางส่วนได้] --> PR
    PR --> CK{ฝั่งธนาคาร − ฝั่งบัญชี<br/>= ผลต่างที่ระบุ?<br/>ฝั่งบัญชี VERIFIED?}
    CK -- ไม่ --> FIX[ยืนยันไม่ได้: แก้การจับคู่<br/>หรือใส่ผลต่าง + เหตุผล]
    CK -- ใช่ --> CF[CONFIRMED<br/>MATCHED / PARTIALLY_MATCHED]
    PR -- ไม่ถูกต้อง --> RJ[REJECTED → กลับเป็น UNMATCHED]
    CF -- พบภายหลังว่าผิด --> RV[REVERSED + เหตุผล]
    U[รายการที่ไม่มีคู่] --> UN[UNMATCHED / NEEDS_REVIEW<br/>คนตรวจ: บันทึกเอกสารที่ขาดผ่าน Module B<br/>หรือ IGNORED พร้อมเหตุผล]
```

## W6 ปิดงวดรายเดือน (Module E — ทำให้กำไรเป็น "ยืนยันแล้ว")

```mermaid
flowchart TD
    S[สิ้นเดือน] --> C1[นำเข้า Night Audit ครบทุกวัน]
    C1 --> C2[นำเข้า statement ทุกบัญชีครบเดือน<br/>+ นับเงินสด ณ สิ้นเดือน]
    C2 --> C3[ยืนยันเอกสาร DRAFT ทั้งหมด หรือยกเลิก]
    C3 --> C4[จับคู่ธนาคารให้ครบ / IGNORED พร้อมเหตุผล]
    C4 --> C5[แทนค่าคอมประมาณด้วยใบแจ้งหนี้จริง]
    C5 --> V{v_period_completeness<br/>issue_count = 0?}
    V -- ไม่ --> L[แสดงรายการปัญหาแต่ละข้อ<br/>P&L แสดงป้าย INCOMPLETE]
    L --> C1
    V -- ใช่ --> PRE[P&L ป้าย PRELIMINARY<br/>นักบัญชีทบทวน]
    PRE --> CL[OWNER/ACCOUNTANT ปิดงวด<br/>DB ตรวจซ้ำ + งวดก่อนหน้าต้องปิดแล้ว]
    CL --> F[FINAL: กำไรสุทธิยืนยันแล้ว<br/>รายการทุกประเภทในเดือนนั้นถูกล็อก]
    F -. พบข้อผิดพลาด .-> RO[OWNER เปิดงวดใหม่พร้อมเหตุผล<br/>หรือบันทึกปรับปรุงในงวดปัจจุบัน]
```

## W7 รายจ่าย (Module B)

```mermaid
flowchart LR
    E1[ได้บิล/ใบเสร็จ<br/>ถ่ายรูป/อัปโหลด] --> E2[expenses DRAFT<br/>expense_date = วันที่ในเอกสาร<br/>เลือกหมวด → treatment]
    E2 --> E3[ตรวจซ้ำ: เลขเอกสาร/ยอด/ผู้ขาย/วันที่<br/>เทียบ Expense Voucher จาก PMS]
    E3 --> E4[VERIFY → ลงบัญชี<br/>ค่าใช้จ่าย หรือ สินทรัพย์/ถอนเจ้าของ/หนี้ ตามหมวด]
    E4 --> E5[จ่ายเงิน: expense_payments<br/>paid_date, บัญชีเงิน, จ่ายบางส่วนได้]
    E5 --> E6[จับคู่กับรายการธนาคาร<br/>W5]
    E4 -. ยังไม่จ่าย .-> AP[ยอดค้างจ่าย = ภาระที่ต้องกันเงิน]
```

## W8 ประมาณการภาษี (Module F)

```mermaid
flowchart TD
    T0[นักบัญชียืนยันรูปแบบกิจการ สถานะ VAT<br/>tax_profiles] --> T1[ตั้งสูตร/อัตราตามที่นักบัญชีแจ้ง<br/>tax_rules พร้อมที่มา]
    T1 --> T2[ระบบคำนวณจากข้อมูล VERIFIED<br/>tax_estimates = SYSTEM_ESTIMATE<br/>+ ป้ายความครบถ้วนของฐาน]
    T2 --> T3[แสดง: ประมาณการเงินที่ควรกันไว้<br/>+ รายการตรวจสอบ + เอกสารที่ต้องเตรียม]
    T3 --> T4[นักบัญชีตรวจ → ACCOUNTANT_REVIEWED]
    T4 --> T5["ยื่นแบบ → FILED (ยอดตามแบบ)"]
    T5 --> T6[ชำระ → PAID + ผูกรายการจ่าย]
    T0 -. ยังไม่ยืนยัน .-> TU[ทุกตัวเลขภาษีแสดงป้าย ยังไม่ได้ตรวจสอบ หลักเกณฑ์ยังไม่ยืนยัน]
```

## W9 วางแผนเงินทุน 2,000,000 บาท (Module G)

```mermaid
flowchart TD
    G0[capital_goals: เป้า 2,000,000<br/>นโยบายเงินสำรอง N เดือน — เจ้าของยืนยัน] --> G1
    G1[เงินสด/ฝากที่ตรวจสอบแล้ว<br/>v_verified_cash] --> G3
    G2[ภาระที่ต้องกัน: เงินรับล่วงหน้า, เจ้าหนี้, ค่าคอมค้างจ่าย,<br/>ภาษีประมาณการที่ยังไม่จ่าย, ค่างวด N เดือน, เงินสำรองรีสอร์ต] --> G3
    G3[เงินที่จัดสรรได้ตอนนี้ = max 0, เงินที่ตรวจแล้ว − กองทุนเดิม − ภาระ] --> G4[ผู้ใช้โอนเข้ากองทุน<br/>fund_transfers + capital_fund_transactions VERIFIED]
    G4 --> G5[v_capital_goal_progress:<br/>สะสมแล้ว / ยังขาด]
    H[งวดที่ปิดแล้วย้อนหลัง N เดือน<br/>เงินส่วนเกินหลังภาระต่อเดือน] --> S1[Conservative / Base / Optimistic<br/>ปรับรายได้/ค่าใช้จ่าย % × สัดส่วนจัดสรร]
    H -. ยังไม่มีงวดปิด .-> FB[ใช้ประมาณการเจ้าของ เช่น 1,900,000/ปี<br/>+ ค่าใช้จ่ายประมาณการ → ป้าย ยังไม่ตรวจสอบ]
    FB --> S1
    S1 --> S2[เดือนที่คาดว่าถึงเป้า = ceil ยังขาด ÷ ส่วนเกินที่จัดสรรได้ต่อเดือน<br/>≤ 0 → แจ้งว่าไม่ถึงเป้าภายใต้สมมติฐานนี้]
    G5 --> S2
```
