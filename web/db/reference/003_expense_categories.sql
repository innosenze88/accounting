-- Reference data: expense categories from the requirements (Module B), mapped to the draft chart of accounts.
-- treatment decides whether a payment is a P&L expense or a balance-sheet movement.
-- needs_accountant_review = the accounting treatment must be confirmed before go-live.
INSERT INTO expense_categories (code, name_th, name_en, treatment, ledger_account_id, needs_accountant_review, sort_order)
SELECT v.code, v.name_th, v.name_en, v.treatment, la.id, v.review, v.sort_order
FROM (VALUES
    ('SALARY',              'เงินเดือนและค่าจ้าง',            'Salaries & wages',            'OPERATING_EXPENSE',  '5201', false, 10),
    ('SOCIAL_SECURITY',     'ประกันสังคมส่วนนายจ้าง',          'Employer social security',    'OPERATING_EXPENSE',  '5202', false, 20),
    ('ELECTRICITY',         'ค่าไฟฟ้า',                     'Electricity',                 'OPERATING_EXPENSE',  '5211', false, 30),
    ('WATER',               'ค่าน้ำประปา',                   'Water',                       'OPERATING_EXPENSE',  '5212', false, 40),
    ('INTERNET_PHONE',      'ค่าอินเทอร์เน็ต/โทรศัพท์/เคเบิล',   'Internet, phone & TV',        'OPERATING_EXPENSE',  '5213', false, 50),
    ('REPAIR_MAINTENANCE',  'ค่าซ่อมบำรุง',                  'Repairs & maintenance',       'OPERATING_EXPENSE',  '5221', false, 60),
    ('ROOM_SUPPLIES',       'อุปกรณ์และของใช้ในห้องพัก',        'Guest room supplies',         'OPERATING_EXPENSE',  '5231', false, 70),
    ('LAUNDRY',             'ค่าซักรีด',                     'Laundry',                     'OPERATING_EXPENSE',  '5232', false, 80),
    ('CLEANING_SUPPLIES',   'วัสดุทำความสะอาด/สิ้นเปลือง',      'Cleaning & consumables',      'OPERATING_EXPENSE',  '5233', false, 90),
    ('SOFTWARE',            'ค่าซอฟต์แวร์ (eZee PMS ฯลฯ)',    'Software subscriptions',      'OPERATING_EXPENSE',  '5241', false, 100),
    ('PAYMENT_FEES',        'ค่าธรรมเนียมรับบัตร/QR',          'Payment processing fees',     'OPERATING_EXPENSE',  '5102', false, 110),
    ('LOCAL_TAX_FEES',      'ภาษีโรงเรือน/ป้าย/ค่าธรรมเนียมใบอนุญาต', 'Local taxes & licence fees', 'OPERATING_EXPENSE', '5251', true, 120),
    ('BANK_CHARGES',        'ค่าธรรมเนียมธนาคาร',             'Bank charges',                'OPERATING_EXPENSE',  '5261', false, 130),
    ('OTHER_EXPENSE',       'ค่าใช้จ่ายอื่น',                  'Other expenses',              'OPERATING_EXPENSE',  '5291', false, 140),
    ('INTEREST',            'ดอกเบี้ยจ่าย',                   'Interest expense',            'INTEREST_EXPENSE',   '5401', false, 150),
    ('OTA_COMMISSION_INVOICE', 'จ่ายใบแจ้งหนี้ค่าคอม OTA (ล้างหนี้)', 'OTA commission invoice payment', 'OTA_COMMISSION_SETTLEMENT', '2102', false, 160),
    ('VAT_REMITTANCE',      'นำส่งภาษีมูลค่าเพิ่ม',              'VAT remittance',              'TAX_REMITTANCE',     '2121', true, 170),
    ('WHT_REMITTANCE',      'นำส่งภาษีหัก ณ ที่จ่าย',            'Withholding tax remittance',  'TAX_REMITTANCE',     '2122', true, 180),
    ('CORPORATE_INCOME_TAX','ภาษีเงินได้นิติบุคคล',              'Corporate income tax',        'INCOME_TAX_EXPENSE', '5901', true, 190),
    ('OWNER_PERSONAL_TAX',  'ภาษีเงินได้บุคคลธรรมดาของเจ้าของ',    'Owner personal income tax',   'OWNER_DRAWING',      '3102', true, 200),
    ('ASSET_FURNITURE_EQUIPMENT', 'ซื้อเฟอร์นิเจอร์/อุปกรณ์ (สินทรัพย์)', 'Furniture & equipment purchase', 'CAPITAL_EXPENDITURE', '1202', true, 210),
    ('ASSET_BUILDING',      'ก่อสร้าง/ปรับปรุงอาคาร (สินทรัพย์)',  'Building improvement',        'CAPITAL_EXPENDITURE', '1201', true, 220),
    ('PREPAYMENT',          'จ่ายล่วงหน้า (เช่น ประกันรายปี)',      'Prepayment',                  'PREPAYMENT',         '1151', true, 230),
    ('OWNER_DRAWING',       'เจ้าของถอนใช้ส่วนตัว',             'Owner drawing',               'OWNER_DRAWING',      '3102', false, 240),
    ('LOAN_PRINCIPAL',      'ชำระเงินต้นเงินกู้/ค่างวด',          'Loan principal repayment',    'LOAN_PRINCIPAL',     '2201', false, 250)
) AS v(code, name_th, name_en, treatment, account_code, review, sort_order)
JOIN ledger_accounts la ON la.code = v.account_code;
