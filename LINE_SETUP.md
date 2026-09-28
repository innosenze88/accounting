# ตั้งค่าดึงสลิปจาก LINE (ไม่บังคับ)

เมื่อลูกค้าหรือพนักงานส่ง **รูปสลิป / ใบเสร็จ / ไฟล์ PDF** เข้า LINE OA ของรีสอร์ท (หรือในกลุ่มที่มี LINE OA อยู่)
ไฟล์จะถูกเก็บไว้ใน Google Drive (โฟลเดอร์ **LINE Slips**) โดยอัตโนมัติ
จากนั้นในแอปกด **นำเข้า → ดึงสลิปจาก LINE** แอปจะอ่านด้วย AI และเก็บไว้เป็น "รอยืนยัน" เหมือนการอัปโหลดปกติ

> ต้องตั้งค่า Google Sheets (Apps Script) ในแอปให้ใช้งานได้ก่อน

## ขั้นที่ 1 สร้าง LINE OA + เปิด Messaging API

1. เข้า <https://manager.line.biz> → สร้างบัญชี LINE OA (ฟรี) เช่น "วิภูอนันต์ รีสอร์ท – บัญชี"
2. ในหน้า LINE OA Manager: **ตั้งค่า → Messaging API → เปิดใช้ Messaging API** (สร้าง Provider ใหม่ได้เลย)
3. **ตั้งค่า → การตอบกลับ**:
   - เปิด **Webhook**
   - ปิด **ข้อความตอบกลับอัตโนมัติ** (ไม่อย่างนั้นจะตอบซ้ำกับของแอป)
   - ถ้าจะใช้ในกลุ่มพนักงาน: **ตั้งค่าบัญชี → อนุญาตให้บัญชีเข้าร่วมแชทกลุ่ม**

## ขั้นที่ 2 เอา Channel access token

1. เข้า <https://developers.line.biz/console> → เลือก Provider → channel ของ OA
2. แท็บ **Messaging API** → เลื่อนลงล่างสุด **Channel access token (long-lived)** → กด **Issue**
3. คัดลอกเก็บไว้ (ห้ามส่งให้ใคร ห้ามใส่ใน GitHub)

## ขั้นที่ 3 ตั้งค่า secrets ใน Apps Script

1. เปิด Google Sheet ที่ใช้กับแอป → **ส่วนขยาย → Apps Script**
2. วางโค้ดเวอร์ชันใหม่ (แอป → ตั้งค่า → คัดลอกโค้ด Apps Script) ทับของเดิม แล้วบันทึก
3. เปิด **Project Settings → Script Properties** แล้วเพิ่ม:
   - `APP_TOKEN` = รหัส APP_TOKEN ในหน้าตั้งค่าแอป
   - `LINE_CHANNEL_ACCESS_TOKEN` = Channel access token จาก LINE Developers
   - `LINE_BOT_USER_ID` = User ID ของบอท (จำเป็นสำหรับตรวจ destination)
4. เลือกฟังก์ชัน **initializeLineWebhookToken** แล้วกด **Run** หนึ่งครั้ง จากนั้นกลับไปที่ Script Properties แล้วคัดลอก `LINE_WEBHOOK_TOKEN`
5. เลือกฟังก์ชัน **authorize** → กด **Run** → อนุญาตสิทธิ์ Google Drive (ทำครั้งเดียว)
6. **การทำให้ใช้งานได้ → จัดการการทำให้ใช้งานได้ → แก้ไข (รูปดินสอ) → เวอร์ชัน: เวอร์ชันใหม่ → ทำให้ใช้งานได้**
   (URL เดิมยังใช้ได้ ไม่ต้องแก้ในแอป)

## ขั้นที่ 4 ตั้ง Webhook URL

1. LINE Developers console → แท็บ **Messaging API** → **Webhook URL** → วาง URL ของ Apps Script ต่อท้ายด้วย `?lineWebhookToken=` และค่า `LINE_WEBHOOK_TOKEN` ที่คัดลอกจาก Script Properties
   ตัวอย่างรูปแบบ: `https://script.google.com/macros/s/.../exec?lineWebhookToken=<secret>`
2. เปิด **Use webhook**
3. ปุ่ม **Verify** อาจขึ้น error (302) — เป็นเรื่องปกติของ Apps Script ไม่ต้องกังวล
   ให้ทดสอบจริงโดยส่งรูปสลิปเข้า LINE OA แล้วดูชีต **LineInbox** ใน Google Sheet ว่ามีแถวใหม่

## ใช้งาน

- ส่งรูป/PDF เข้า LINE OA หรือในกลุ่มที่มี OA → ไฟล์ไปอยู่ใน Drive โฟลเดอร์ **LINE Slips** + ชีต **LineInbox** (สถานะ NEW)
- ในแอป: **นำเข้า → ดึงสลิปจาก LINE** → แอปอ่านทีละไฟล์ แล้วเปลี่ยนสถานะเป็น
  - `IMPORTED` อ่านเข้าแอปแล้ว (รอยืนยันในแท็บประวัติ)
  - `DUPLICATE` ไฟล์นี้มีในแอปอยู่แล้ว
  - `FAILED` อ่านไม่ได้ (ไฟล์ยังอยู่ใน Drive เพิ่มเองได้)
- ข้อความตัวอักษรในแชทจะไม่ถูกเก็บ เก็บเฉพาะรูปและ PDF

## ความปลอดภัย

- `APP_TOKEN`, `LINE_CHANNEL_ACCESS_TOKEN` และ `LINE_WEBHOOK_TOKEN` เก็บใน Script Properties ไม่อยู่ในไฟล์โค้ดหรือ GitHub. จำกัดผู้แก้ไข Apps Script ให้ผู้ดูแลที่ไว้ใจได้
- Web App ต้องเปิดให้ผู้เรียกทั่วไปเข้าถึง จึงตรวจสิทธิ์ด้วย bearer `APP_TOKEN`; ผู้ที่ได้ทั้ง URL และ token สามารถสั่งงาน Sheet/Drive ได้ เปลี่ยน `APP_TOKEN` ใน Script Properties และแอปทันทีหากสงสัยว่ารั่ว
- Apps Script web-app event object ไม่เปิด request headers ให้ `doPost`; จึงตรวจ `X-Line-Signature` ของ LINE ไม่ได้ที่นี่. `LINE_WEBHOOK_TOKEN` ใน query string เป็นการป้องกันแบบ bearer เพิ่มเติม ไม่ใช่การตรวจลายเซ็นจาก LINE. หากต้องการยืนยัน webhook ด้วยลายเซ็นจริง ให้ปิด LINE webhook นี้และย้ายปลายทางไป proxy ที่อ่าน headers ได้
- หาก Channel access token หลุด ให้กด **Reissue** ใน LINE Developers และอัปเดต `LINE_CHANNEL_ACCESS_TOKEN` ใน Script Properties
