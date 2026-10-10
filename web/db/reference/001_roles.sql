-- Reference data: user roles (production data, not test data).
-- Permissions per role are enforced in application code; see docs/phase-1/02-system-architecture.md (RBAC).
INSERT INTO roles (code, name_th, name_en, description) VALUES
    ('OWNER',      'เจ้าของกิจการ', 'Owner',
     'เห็นทุกอย่าง อนุมัติ/ยืนยันรายการ ปิดและเปิดงวด จัดการผู้ใช้ ตั้งค่า แผนเงินทุน'),
    ('ACCOUNTANT', 'นักบัญชี', 'Accountant',
     'ตรวจและยืนยันรายการ ยกเลิกรายการพร้อมเหตุผล กระทบยอด ปิดงวด ตั้งค่าภาษี (ไม่จัดการผู้ใช้)'),
    ('MANAGER',    'ผู้จัดการ', 'Manager',
     'บันทึกรายรับรายจ่าย นำเข้าไฟล์ เสนอการจับคู่ ดูรายงาน (ยืนยันรายการของตัวเองไม่ได้)'),
    ('FRONT_DESK', 'พนักงานต้อนรับ', 'Front desk',
     'อัปโหลดรายงาน PMS และหลักฐาน บันทึกเงินรับ/รายจ่ายเป็นร่าง ไม่เห็นกำไรและแผนเงินทุน'),
    ('VIEWER',     'ผู้ดูรายงาน', 'Viewer',
     'ดู Dashboard และรายงานอย่างเดียว');
