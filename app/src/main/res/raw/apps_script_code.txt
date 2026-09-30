/**
 * Viphuanan Accounting app -> Google Sheets
 *
 * วิธีติดตั้ง
 * 1) เปิด Google Sheet ที่ต้องการ -> ส่วนขยาย (Extensions) -> Apps Script
 * 2) ลบโค้ดเดิมทั้งหมด แล้ววางโค้ดนี้ -> กดบันทึก
 * 3) Project Settings -> Script Properties: เพิ่ม APP_TOKEN ให้ตรงกับรหัสในแอป
 *    ถ้าใช้ LINE ให้เก็บ LINE_CHANNEL_ACCESS_TOKEN และ LINE_BOT_USER_ID ใน Script Properties ด้วย
 * 4) ทำให้ใช้งานได้ (Deploy) -> การทำให้ใช้งานได้รายการใหม่ (New deployment) -> เลือกประเภท "เว็บแอป" (Web app)
 *      เรียกใช้ในฐานะ (Execute as): ฉัน (Me)
 *      ผู้ที่มีสิทธิ์เข้าถึง (Who has access): ทุกคน (Anyone)
 * 5) กดอนุญาตสิทธิ์ แล้วคัดลอก URL ที่ลงท้ายด้วย /exec ไปใส่ในแอป หน้า "ตั้งค่า"
 *
 * ถ้าแก้โค้ดภายหลัง: Deploy -> Manage deployments -> แก้ไข (ดินสอ) -> Version: New version -> Deploy
 *
 * แผ่นงานที่สร้างอัตโนมัติ
 *   Accounting      เอกสารบัญชีที่ยืนยันแล้ว (1 แถวต่อเอกสาร อัปเดตแถวเดิมถ้าส่งซ้ำ)
 *   Voided          เอกสารที่ยกเลิกในแอป (ย้ายออกจาก Accounting มาไว้ที่นี่ พร้อมเหตุผล)
 *   eZee_Reports    สรุปตัวเลขของรายงาน eZee แต่ละฉบับ
 *   eZee_<ประเภท>   ตารางรายละเอียดของรายงานแต่ละประเภท
 *   (ชื่อที่ตั้งเอง)   ข้อมูลจากไฟล์ CSV / Excel
 *   LineInbox       สลิป/ใบเสร็จที่ส่งเข้ากลุ่ม LINE (รอแอปดึงไปอ่าน)
 *
 * โฟลเดอร์ใน Google Drive ที่สร้างอัตโนมัติ
 *   Resort Accounting Backups   ไฟล์สำรองข้อมูลของแอป (เก็บ 7 ไฟล์ล่าสุด)
 *   LINE Slips                  รูปสลิปจากกลุ่ม LINE
 *
 * ครั้งแรกหลังวางโค้ดนี้: เลือกฟังก์ชัน authorize ด้านบน แล้วกด Run (เรียกใช้) หนึ่งครั้ง เพื่ออนุญาตให้ใช้ Google Drive
 *
 * ดึงสลิปจาก LINE (ไม่บังคับ): ดูวิธีตั้งค่าในไฟล์ LINE_SETUP.md ของโปรเจกต์
 */

// ตอบกลับในกลุ่มเมื่อได้รับรูป (true/false)
const LINE_REPLY = true;

// Secrets must be set in Project Settings -> Script Properties, never in this source file.
const APP_TOKEN_PROPERTY = 'APP_TOKEN';
const LINE_WEBHOOK_TOKEN_PROPERTY = 'LINE_WEBHOOK_TOKEN';

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    // LINE's signature header is not exposed in the Apps Script web-app event object.
    // Use a separate random bearer token in the webhook URL; this is not LINE signature verification.
    if (body.destination !== undefined && Array.isArray(body.events)) {
      const props = PropertiesService.getScriptProperties();
      const expectedWebhookToken = props.getProperty(LINE_WEBHOOK_TOKEN_PROPERTY);
      const suppliedWebhookToken = (e.parameter || {}).lineWebhookToken;
      if (!expectedWebhookToken || suppliedWebhookToken !== expectedWebhookToken) {
        return json_({ ok: false, error: 'LINE webhook token is missing or invalid' });
      }
      const webhookLock = LockService.getScriptLock();
      webhookLock.waitLock(30000);
      try {
        return json_(lineWebhook_(body));
      } finally {
        webhookLock.releaseLock();
      }
    }
    const expectedAppToken = PropertiesService.getScriptProperties().getProperty(APP_TOKEN_PROPERTY);
    if (!expectedAppToken) {
      return json_({ ok: false, error: 'APP_TOKEN is not configured in Script Properties' });
    }
    if (typeof body.token !== 'string' || body.token !== expectedAppToken) {
      return json_({ ok: false, error: 'รหัสลับ (Token) ไม่ตรงกับในแอป' });
    }
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
        case 'saveBackup':
          return json_(saveBackup_(body));
        case 'listLineInbox':
          return json_(listLineInbox_(body));
        case 'getLineFile':
          return json_(getLineFile_(body));
        case 'markLineImported':
          return json_(markLineImported_(body));
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

// Column names come from imported files and reports. A name starting with = + - @ would be run by Sheets
// as a formula when written into the header row, so it gets a leading "_".
function safeKey_(k) {
  const t = String(k === undefined || k === null ? '' : k);
  return /^[=+\-@]/.test(t) ? '_' + t : t;
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
  Object.keys(s).forEach(function (k) { summary[safeKey_(k)] = s[k]; });
  upsertRow_(sheet_('eZee_Reports'), summary, 'app_id');

  const rows = (r.rows || []).map(function (o) {
    const safe = {};
    Object.keys(o).forEach(function (k) { safe[safeKey_(k)] = o[k]; });
    return safe;
  });
  const sh = sheet_('eZee_' + (r.report_type || 'other'));
  // Always remove the previous detail rows, including when the new report is empty.
  deleteRowsWhere_(sh, 'app_id', r.app_id);
  if (rows.length) {
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

// Sheets the app writes itself. A CSV / Excel import must never write into them.
const RESERVED_SHEETS_ = ['accounting', 'voided', 'lineinbox'];
const MAX_TABLE_ROWS_ = 1000; // per chunk (the app sends 500)

// Compares the name the same way sheet_() cleans it, so "Accounting " or "eZee_x" are caught too.
function isReservedSheet_(name) {
  const n = String(name || 'Import').replace(/[\[\]\*\?\/\\:]/g, '_').substring(0, 90).trim().toLowerCase();
  return n.indexOf('ezee_') === 0 || RESERVED_SHEETS_.indexOf(n) !== -1;
}

// CSV / Excel rows. Chunks are ordered and idempotent per importId/chunkIndex.
function appendTable_(b) {
  if (!b || typeof b.importId !== 'string' || !b.importId || !Array.isArray(b.headers) || !Array.isArray(b.rows) || !Number.isInteger(b.chunkIndex) || b.chunkIndex < 0) {
    throw new Error('invalid table chunk');
  }
  if (b.rows.length > MAX_TABLE_ROWS_) throw new Error('too many rows in one chunk');
  if (isReservedSheet_(b.sheetName)) {
    throw new Error('ชื่อแผ่นงาน "' + b.sheetName + '" ใช้โดยแอปอยู่แล้ว ตั้งชื่ออื่น');
  }
  const cols = b.headers.map(safeKey_);
  const sh = sheet_(b.sheetName);
  const existingHeaders = ensureHeaders_(sh, ['import_id', 'file_name', 'chunk_index'].concat(cols));
  const idCol = existingHeaders.indexOf('import_id');
  const chunkCol = existingHeaders.indexOf('chunk_index');
  const last = sh.getLastRow();
  if (b.chunkIndex === 0) {
    deleteRowsWhere_(sh, 'import_id', b.importId);
  } else if (last > 1) {
    const ids = sh.getRange(2, idCol + 1, last - 1, 1).getValues();
    const chunks = sh.getRange(2, chunkCol + 1, last - 1, 1).getValues();
    let sawImport = false;
    let highestChunk = -1;
    for (let i = 0; i < ids.length; i++) {
      if (String(ids[i][0]) !== String(b.importId)) continue;
      sawImport = true;
      const existingChunk = Number(chunks[i][0]);
      if (existingChunk === b.chunkIndex) return { ok: true, rows: 0, duplicate: true };
      highestChunk = Math.max(highestChunk, existingChunk);
    }
    if (!sawImport || b.chunkIndex !== highestChunk + 1) throw new Error('chunk out of order');
  } else if (b.chunkIndex > 0) {
    throw new Error('chunk out of order');
  }
  const headers = ensureHeaders_(sh, ['import_id', 'file_name', 'chunk_index'].concat(cols));
  const idx = cols.map(function (h) { return headers.indexOf(h); });
  const fileCol = headers.indexOf('file_name');
  const values = b.rows.map(function (r) {
    const out = headers.map(function () { return ''; });
    out[idCol] = b.importId;
    out[fileCol] = b.fileName;
    out[chunkCol] = b.chunkIndex;
    r.forEach(function (v, i) { if (idx[i] >= 0) out[idx[i]] = cell_(v); });
    return out;
  });
  if (values.length) sh.getRange(sh.getLastRow() + 1, 1, values.length, headers.length).setValues(values);
  return { ok: true, rows: values.length };
}


function inboxHasId_(sh, headers, id) {
  const idCol = headers.indexOf('id');
  const last = sh.getLastRow();
  if (idCol < 0 || last < 2) return false;
  const values = sh.getRange(2, idCol + 1, last - 1, 1).getValues();
  return values.some(function (row) { return String(row[0]) === String(id); });
}

// ---------------------------------------------------------------- Google Drive

// เรียกครั้งเดียวจากหน้าแก้ไขโค้ด (เลือก authorize แล้วกด Run) เพื่ออนุญาตสิทธิ์ Drive / เชื่อมต่อภายนอก
function authorize() {
  DriveApp.getRootFolder();
  folder_('Resort Accounting Backups');
  folder_('LINE Slips');
  Logger.log('OK — อนุญาตสิทธิ์เรียบร้อย');
}

function folder_(name) {
  const it = DriveApp.getFoldersByName(name);
  return it.hasNext() ? it.next() : DriveApp.createFolder(name);
}

// ไฟล์สำรองจากแอป (base64) -> โฟลเดอร์ Resort Accounting Backups, เก็บไว้ keep ไฟล์ล่าสุด
function saveBackup_(b) {
  if (!/^resort-backup-[\w.-]+$/.test(String(b.name || ''))) return { ok: false, error: 'ชื่อไฟล์สำรองไม่ถูกต้อง' };
  const blob = Utilities.newBlob(Utilities.base64Decode(b.data), 'application/octet-stream', b.name);
  const folder = folder_('Resort Accounting Backups');
  const file = folder.createFile(blob);
  const keep = b.keep || 7;
  const files = [];
  const it = folder.getFiles();
  // Only the app's own backup files are counted and trashed — other files in the folder are never touched.
  while (it.hasNext()) { const f = it.next(); if (/^resort-backup-/.test(f.getName())) files.push(f); }
  files.sort(function (x, y) { return y.getDateCreated().getTime() - x.getDateCreated().getTime(); });
  files.slice(keep).forEach(function (f) { f.setTrashed(true); });
  return { ok: true, fileId: file.getId(), url: file.getUrl(), kept: Math.min(files.length, keep) };
}

// ---------------------------------------------------------------- LINE

const INBOX_HEADERS = ['id', 'received_at', 'source_type', 'group_id', 'user_id', 'sender_name',
  'message_type', 'file_id', 'file_name', 'mime', 'status', 'imported_at', 'doc_id', 'note'];

function inbox_() {
  const sh = sheet_('LineInbox');
  ensureHeaders_(sh, INBOX_HEADERS);
  return sh;
}

function lineApi_(url, method, payload) {
  const channelToken = PropertiesService.getScriptProperties().getProperty('LINE_CHANNEL_ACCESS_TOKEN') || '';
  const opt = { method: method || 'get', headers: { Authorization: 'Bearer ' + channelToken }, muteHttpExceptions: true };
  if (payload) { opt.contentType = 'application/json'; opt.payload = JSON.stringify(payload); }
  return UrlFetchApp.fetch(url, opt);
}

// Run once from the Apps Script editor, then copy the generated value from Script Properties
// into the LINE webhook URL as ?lineWebhookToken=<value>. Never log the value itself.
function initializeLineWebhookToken() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty(LINE_WEBHOOK_TOKEN_PROPERTY)) {
    const token = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    props.setProperty(LINE_WEBHOOK_TOKEN_PROPERTY, token);
  }
  Logger.log('LINE_WEBHOOK_TOKEN is ready. Copy it from Project Settings > Script Properties.');
}

function lineWebhook_(body) {
  const props = PropertiesService.getScriptProperties();
  const channelToken = props.getProperty('LINE_CHANNEL_ACCESS_TOKEN') || '';
  const botUserId = props.getProperty('LINE_BOT_USER_ID') || '';
  if (!channelToken || !botUserId) return { ok: false, error: 'LINE credentials are not configured in Script Properties' };
  if (body.destination !== botUserId) return { ok: false, error: 'wrong destination' };
  const sh = inbox_();
  const headers = ensureHeaders_(sh, INBOX_HEADERS);
  let saved = 0;
  (body.events || []).forEach(function (ev) {
    if (ev.type !== 'message' || !ev.message) return;
    const type = ev.message.type;
    if (type !== 'image' && type !== 'file') return;
    if (type === 'file' && !/\.pdf$/i.test(ev.message.fileName || '')) return;
    const inboxId = 'LINE-' + ev.message.id;
    if (inboxHasId_(sh, headers, inboxId)) return;
    const res = lineApi_('https://api-data.line.me/v2/bot/message/' + ev.message.id + '/content');
    if (res.getResponseCode() !== 200) return;
    const blob = res.getBlob();
    const src = ev.source || {};
    const when = Utilities.formatDate(new Date(ev.timestamp), 'Asia/Bangkok', 'yyyyMMdd-HHmmss');
    const name = type === 'file' ? ev.message.fileName : ('slip-' + when + '-' + ev.message.id + '.jpg');
    blob.setName(name);
    const file = folder_('LINE Slips').createFile(blob);
    let sender = '';
    try {
      const p = src.groupId
        ? lineApi_('https://api.line.me/v2/bot/group/' + src.groupId + '/member/' + src.userId)
        : lineApi_('https://api.line.me/v2/bot/profile/' + src.userId);
      if (p.getResponseCode() === 200) sender = JSON.parse(p.getContentText()).displayName || '';
    } catch (err) { /* profile is optional */ }
    const row = {
      id: inboxId, received_at: new Date(ev.timestamp), source_type: src.type || '',
      group_id: src.groupId || '', user_id: src.userId || '', sender_name: sender, message_type: type,
      file_id: file.getId(), file_name: name, mime: blob.getContentType(), status: 'NEW'
    };
    sh.appendRow(headers.map(function (h) { return row[h] === undefined ? '' : row[h]; }));
    saved++;
    if (LINE_REPLY && ev.replyToken) {
      lineApi_('https://api.line.me/v2/bot/message/reply', 'post', {
        replyToken: ev.replyToken, messages: [{ type: 'text', text: 'รับสลิปแล้ว ✓ รอบันทึกในแอปบัญชี' }]
      });
    }
  });
  return { ok: true, saved: saved };
}

// รายการสลิปจาก LINE ที่แอปยังไม่ได้ดึง
function listLineInbox_(b) {
  const sh = inbox_();
  const last = sh.getLastRow();
  if (last < 2) return { ok: true, items: [] };
  const values = sh.getRange(1, 1, last, sh.getLastColumn()).getValues();
  const headers = values[0].map(String);
  const items = [];
  for (let i = 1; i < values.length && items.length < (b.limit || 30); i++) {
    const o = {};
    headers.forEach(function (h, j) { o[h] = values[i][j] instanceof Date ? values[i][j].toISOString() : values[i][j]; });
    if (String(o.status) === 'NEW') items.push(o);
  }
  return { ok: true, items: items };
}

// ส่งได้เฉพาะไฟล์สลิปที่อยู่ในชีต LineInbox เท่านั้น (กันคนที่ได้ Token ไปใช้เปิดไฟล์อื่นใน Drive)
function isLineInboxFile_(fileId) {
  if (!fileId) return false;
  const sh = inbox_();
  const last = sh.getLastRow();
  if (last < 2) return false;
  const headers = ensureHeaders_(sh, INBOX_HEADERS);
  const ids = sh.getRange(2, headers.indexOf('file_id') + 1, last - 1, 1).getValues();
  for (let i = 0; i < ids.length; i++) if (String(ids[i][0]) === String(fileId)) return true;
  return false;
}

function getLineFile_(b) {
  if (!isLineInboxFile_(b.fileId)) return { ok: false, error: 'ไฟล์นี้ไม่ใช่สลิปจาก LINE' };
  const file = DriveApp.getFileById(b.fileId);
  const blob = file.getBlob();
  return { ok: true, name: file.getName(), mime: blob.getContentType(), data: Utilities.base64Encode(blob.getBytes()) };
}

// status: IMPORTED (อ่านเข้าแอปแล้ว) / DUPLICATE (ซ้ำ) / FAILED
function markLineImported_(b) {
  const sh = inbox_();
  const headers = ensureHeaders_(sh, INBOX_HEADERS);
  const last = sh.getLastRow();
  if (last < 2) return { ok: false, error: 'not found' };
  const ids = sh.getRange(2, headers.indexOf('id') + 1, last - 1, 1).getValues();
  for (let i = 0; i < ids.length; i++) {
    if (String(ids[i][0]) === String(b.id)) {
      const r = i + 2;
      sh.getRange(r, headers.indexOf('status') + 1).setValue(b.status || 'IMPORTED');
      sh.getRange(r, headers.indexOf('imported_at') + 1).setValue(new Date());
      sh.getRange(r, headers.indexOf('doc_id') + 1).setValue(b.docId || '');
      sh.getRange(r, headers.indexOf('note') + 1).setValue(b.note || '');
      return { ok: true };
    }
  }
  return { ok: false, error: 'not found' };
}
