package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RulesGuideContent(
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Rule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ขั้นตอนการทำงาน & กฎการสกัดข้อมูล",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "AI Accountant & OCR Extraction Workflow Constraints",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Rule 1
        RuleSectionCard(
            number = "1",
            title = "การจำแนกประเภทเอกสาร (Document Type)",
            icon = Icons.AutoMirrored.Filled.Assignment
        ) {
            ClassificationItem("RECEIPT", "ใบเสร็จรับเงิน / ใบเสร็จรับเงินออกให้ลูกค้า")
            ClassificationItem("PAYMENT_VOUCHER", "ใบสำคัญจ่าย / บิลจ่ายเงิน")
            ClassificationItem("UTILITY_BILL", "บิลค่าน้ำ / ค่าไฟฟ้า / ค่าโทรศัพท์ / อินเทอร์เน็ต")
            ClassificationItem("ADVANCE_DEPOSIT", "ใบเสร็จรับเงินมัดจำล่วงหน้า / เงินรับฝาก")
            ClassificationItem("OTHER", "เอกสารอื่นๆ ที่ไม่เข้าพวก")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Rule 2
        RuleSectionCard(
            number = "2",
            title = "ประเภททรานแซกชัน (Transaction Type)",
            icon = Icons.Default.FormatListNumbered
        ) {
            ClassificationItem("INCOME (รายรับ)", "เมื่อเอกสารแสดงว่าเราเป็นผู้รับเงิน (เช่น ใบเสร็จที่ออกให้ลูกค้า)")
            ClassificationItem("EXPENSE (รายจ่าย)", "เมื่อเอกสารแสดงว่าเราเป็นผู้จ่ายเงิน (เช่น บิลค่าน้ำไฟ, ใบสำคัญจ่าย, ซื้อของ)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Rule 3
        RuleSectionCard(
            number = "3",
            title = "Field Mapping Rules (การสกัดคอลัมน์มาตรฐาน)",
            icon = Icons.Default.Info
        ) {
            MappingRow("document_no", "เลขที่, No., Receipt No., Invoice No., Doc No., เลขที่ใบเสร็จ")
            MappingRow("date", "วันที่, Date, Issue Date -> รูปแบบ YYYY-MM-DD (พ.ศ. -> ค.ศ.)")
            MappingRow("seller_name", "ชื่อผู้ออกบิล / ผู้ขาย / ชื่อบริษัทบนหัวเอกสาร")
            MappingRow("seller_tax_id", "เลขประจำตัวผู้เสียภาษี 13 หลักของผู้ขาย")
            MappingRow("customer_name", "นาม, ลูกค้า, Customer, Sold To, ได้รับเงินจาก")
            MappingRow("customer_tax_id", "เลขประจำตัวผู้เสียภาษีของลูกค้า")
            MappingRow("subtotal", "มูลค่าสินค้า/บริการ, จำนวนเงิน, Subtotal, Before VAT")
            MappingRow("vat_amount", "ภาษีมูลค่าเพิ่ม 7%, VAT, VAT Amount")
            MappingRow("total_amount", "จำนวนเงินรวมทั้งสิ้น, รวมทั้งสิ้น, Grand Total, ยอดชำระ")
            MappingRow("deposit_amount", "เงินมัดจำ, หักมัดจำ, Deposit, Advance Payment")
            MappingRow("payment_method", "เงินสด, โอนเงิน, บัตรเครดิต, Cash, Transfer")
            MappingRow("line_items", "Array ของ [{ item_name, quantity, unit_price, total_price }]")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Rule 4 & 5
        RuleSectionCard(
            number = "4 & 5",
            title = "การทำความสะอาดและรูปแบบผลลัพธ์ (Clean & Output)",
            icon = Icons.Default.Code
        ) {
            CleaningRuleItem("ตัวเลขการเงิน (Numeric Fields)", "ต้องเป็นประเภท number (Float/Integer) เท่านั้น ห้ามใส่คอมมา เช่น \"1,250.00\" -> 1250.00")
            CleaningRuleItem("วันที่ (Date Fields)", "ต้องอยู่ในรูปแบบ YYYY-MM-DD เท่านั้น (แปลง พ.ศ. 2569 -> 2026)")
            CleaningRuleItem("ไม่พบข้อมูล", "กำหนดค่าเป็น null ห้ามเดาหรือหลอนข้อมูลขึ้นมาเอง")
            CleaningRuleItem("Output Format", "ตอบกลับเฉพาะ JSON Object ตาม Schema ห้ามมีข้อความเกริ่นหรือ Markdown นอกเหนือจาก JSON")
        }
    }
}

@Composable
private fun RuleSectionCard(
    number: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ClassificationItem(type: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "• $type: ",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = desc,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MappingRow(field: String, source: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = field,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(115.dp)
        )
        Text(
            text = source,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CleaningRuleItem(title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
