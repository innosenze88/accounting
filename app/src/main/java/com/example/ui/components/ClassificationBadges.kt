package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentType

@Composable
fun DocumentTypeBadge(
    docTypeCode: String?,
    modifier: Modifier = Modifier
) {
    val docType = DocumentType.fromCode(docTypeCode)

    val (bgColor, textColor, icon) = when (docType) {
        DocumentType.RECEIPT -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), Icons.Default.Receipt)
        DocumentType.PAYMENT_VOUCHER -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Default.Payments)
        DocumentType.UTILITY_BILL -> Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), Icons.Default.ElectricBolt)
        DocumentType.ADVANCE_DEPOSIT -> Triple(Color(0xFFF3E5F5), Color(0xFF6A1B9A), Icons.Default.AccountBalance)
        DocumentType.OTHER -> Triple(Color(0xFFECEFF1), Color(0xFF37474F), Icons.Default.Description)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("doc_type_badge_${docType.code}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = docType.titleTh,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${docType.titleTh} (${docType.code})",
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TransactionTypeBadge(
    typeCode: String?,
    modifier: Modifier = Modifier
) {
    val isIncome = typeCode?.equals("INCOME", ignoreCase = true) == true
    val bgColor = if (isIncome) Color(0xFFDCF8C6) else Color(0xFFFFE0B2)
    val textColor = if (isIncome) Color(0xFF2E7D32) else Color(0xFFE65100)
    val label = if (isIncome) "รายรับ (INCOME)" else "รายจ่าย (EXPENSE)"
    val icon: ImageVector = if (isIncome) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("transaction_type_badge_${if (isIncome) "income" else "expense"}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
