/**
 * Viphuanan Accounting app -> Google Sheets
 *
 * วิธีติดตั้ง
 * 1) เปิด Google Sheet ที่ต้องการ -> ส่วนขยาย (Extensions) -> Apps Script
 * 2) ลบโค้ดเดิมทั้งหมด แล้ววางโค้ดนี้ -> กดบันทึก
 * 3) ทำให้ใช้งานได้ (Deploy) -> การทำให้ใช้งานได้รายการใหม่ (New deployment) -> เลือกประเภท "เว็บแอป" (Web app)
 *      เรียกใช้ในฐานะ (Execute as): ฉัน (Me)
 *      ผู้ที่มีสิทธิ์เข้าถึง (Who has access): ทุกคน (Anyone)
 * 4) กดอนุญาตสิทธิ์ แล้วคัดลอก URL ที่ลงท้ายด้วย /exec ไปใส่ในแอป หน้า "ตั้งค่า"
 *
 * ถ้าแก้โค้ดภายหลัง: Deploy -> Manage deployments -> แก้ไข (ดินสอ) -> Version: New version -> Deploy
 *
 * แผ่นงานที่สร้างอัตโนมัติ
 *   Accounting      เอกสารบัญชีที่ยืนยันแล้ว (1 แถวต่อเอกสาร อัปเดตแถวเดิมถ้าส่งซ้ำ)
 *   Voided          เอกสารที่ยกเลิกในแอป (ย้ายออกจาก Accounting มาไว้ที่นี่ พร้อมเหตุผล)
 *   eZee_Reports    สรุปตัวเลขของรายงาน eZee แต่ละฉบับ
 *   eZee_<ประเภท>   ตารางรายละเอียดของรายงานแต่ละประเภท
 *   (ชื่อที่ตั้งเอง)   ข้อมูลจากไฟล์ CSV / Excel
 */

// รหัสลับ ต้องตรงกับ "รหัสลับ (Token)" ในหน้าตั้งค่าของแอป
const TOKEN = 'PUT-THE-TOKEN-FROM-THE-APP-SETTINGS-HERE';

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.token !== TOKEN) return json_({ ok: false, error: 'รหัสลับ (Token) ไม่ตรงกับในแอป' });
    const lock = LockService.getScriptLock();
    lock.waitLock(30000);
    try {
      switch (body.action) {
        case 'ping':
          return json_({ ok: true, spreadsheet: SpreadsheetApp.getActive().getName() });
        case 'upsertDocument':
          return json_(upsertDocument_(body.document));
        case 'voidDocument':
          return json_(voidDocument_(body.document));
        case 'upsertReport':
          return json_(upsertReport_(body.report));
        case 'appendTable':
          return json_(appendTable_(body));
        default:
          return json_({ ok: false, error: 'unknown action: ' + body.action });
      }
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function doGet() {
  return json_({ ok: true, message: 'Accounting app endpoint is running. The app uses POST.' });
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function sheet_(name) {
  const ss = SpreadsheetApp.getActive();
  const clean = String(name || 'Import').replace(/[\[\]\*\?\/\\:]/g, '_').substring(0, 90);
  return ss.getSheetByName(clean) || ss.insertSheet(clean);
}

// Turns plain number text into numbers and stops text from being read as a formula.
function cell_(v) {
  if (v === undefined || v === null) return '';
  if (typeof v !== 'string') return v;
  const t = v.trim();
  if (/^-?(0|[1-9]\d*)(\.\d+)?$/.test(t)) return Number(t);
  if (/^[=+\-@]/.test(t)) return "'" + t;
  return v;
}

// Makes sure row 1 contains every key; returns the header list.
function ensureHeaders_(sh, keys) {
  const lastCol = sh.getLastColumn();
  let headers = lastCol > 0 ? sh.getRange(1, 1, 1, lastCol).getValues()[0].map(String) : [];
  const missing = keys.filter(function (k) { return headers.indexOf(k) === -1; });
  if (missing.length) {
    headers = headers.concat(missing);
    sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return headers;
}

// Writes obj as one row. If a row with the same idKey already exists it is replaced.
function upsertRow_(sh, obj, idKey) {
  const headers = ensureHeaders_(sh, Object.keys(obj));
  const row = headers.map(function (h) { return cell_(obj[h]); });
  const idCol = headers.indexOf(idKey) + 1;
  const last = sh.getLastRow();
  if (last > 1) {
    const ids = sh.getRange(2, idCol, last - 1, 1).getValues();
    for (let i = 0; i < ids.length; i++) {
      if (String(ids[i][0]) === String(obj[idKey])) {
        sh.getRange(i + 2, 1, 1, row.length).setValues([row]);
        return 'updated';
      }
    }
  }
  sh.appendRow(row);
  return 'added';
}

function deleteRowsWhere_(sh, key, value) {
  const lastCol = sh.getLastColumn();
  const last = sh.getLastRow();
  if (last < 2 || lastCol < 1) return;
  const headers = sh.getRange(1, 1, 1, lastCol).getValues()[0].map(String);
  const col = headers.indexOf(key) + 1;
  if (col < 1) return;
  const vals = sh.getRange(2, col, last - 1, 1).getValues();
  for (let i = vals.length - 1; i >= 0; i--) {
    if (String(vals[i][0]) === String(value)) sh.deleteRow(i + 2);
  }
}

function upsertDocument_(doc) {
  doc.synced_at = new Date();
  // A document sent again after being voided and re-counted must not stay in Voided.
  deleteRowsWhere_(sheet_('Voided'), 'app_id', doc.app_id);
  return { ok: true, result: upsertRow_(sheet_('Accounting'), doc, 'app_id') };
}

// A document cancelled in the app: removed from Accounting (so totals stay right) and kept in Voided.
function voidDocument_(doc) {
  doc.synced_at = new Date();
  upsertRow_(sheet_('Voided'), doc, 'app_id');
  deleteRowsWhere_(sheet_('Accounting'), 'app_id', doc.app_id);
  return { ok: true, result: 'voided' };
}

function upsertReport_(r) {
  const summary = {
    app_id: r.app_id,
    report_type: r.report_type,
    report_title: r.report_title,
    report_date: r.report_date,
    period_from: r.period_from,
    period_to: r.period_to,
    property_name: r.property_name,
    file_name: r.file_name,
    synced_at: new Date()
  };
  const s = r.summary || {};
  Object.keys(s).forEach(function (k) { summary[k] = s[k]; });
  upsertRow_(sheet_('eZee_Reports'), summary, 'app_id');

  const rows = r.rows || [];
  if (rows.length) {
    const sh = sheet_('eZee_' + (r.report_type || 'other'));
    deleteRowsWhere_(sh, 'app_id', r.app_id);
    const keys = ['app_id', 'report_date'];
    rows.forEach(function (o) {
      Object.keys(o).forEach(function (k) { if (keys.indexOf(k) === -1) keys.push(k); });
    });
    const headers = ensureHeaders_(sh, keys);
    const values = rows.map(function (o) {
      return headers.map(function (h) {
        if (h === 'app_id') return r.app_id;
        if (h === 'report_date') return r.report_date || '';
        return cell_(o[h]);
      });
    });
    sh.getRange(sh.getLastRow() + 1, 1, values.length, headers.length).setValues(values);
  }
  return { ok: true, rows: rows.length };
}

// CSV / Excel rows. Sent in chunks; chunk 0 first removes rows of an earlier send of the same file.
function appendTable_(b) {
  const sh = sheet_(b.sheetName);
  if (b.chunkIndex === 0) deleteRowsWhere_(sh, 'import_id', b.importId);
  const headers = ensureHeaders_(sh, ['import_id', 'file_name'].concat(b.headers));
  const idx = b.headers.map(function (h) { return headers.indexOf(h); });
  const idCol = headers.indexOf('import_id');
  const fileCol = headers.indexOf('file_name');
  const values = b.rows.map(function (r) {
    const out = headers.map(function () { return ''; });
    out[idCol] = b.importId;
    out[fileCol] = b.fileName;
    r.forEach(function (v, i) { if (idx[i] >= 0) out[idx[i]] = cell_(v); });
    return out;
  });
  if (values.length) sh.getRange(sh.getLastRow() + 1, 1, values.length, headers.length).setValues(values);
  return { ok: true, rows: values.length };
}
