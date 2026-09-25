package com.example

import com.example.data.model.DocumentStatus
import com.example.data.model.DocumentStatusRules
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A counted document never leaves the totals without a reason (void), and is never counted twice. */
class DocumentStatusRulesTest {

    @Test
    fun verifiedDocumentCannotGoBackWithoutVoid() {
        assertNotNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.VERIFIED, DocumentStatus.PENDING))
        assertNotNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.VERIFIED, DocumentStatus.REJECTED))
        assertNotNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.VOIDED, DocumentStatus.PENDING))
        assertNotNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.PENDING, DocumentStatus.VOIDED))
    }

    @Test
    fun pendingAndRejectedCanMove() {
        assertNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.PENDING, DocumentStatus.REJECTED))
        assertNull(DocumentStatusRules.statusChangeProblem(DocumentStatus.REJECTED, DocumentStatus.PENDING))
    }

    @Test
    fun onlyUncountedDocumentsCanBeVerified() {
        assertNull(DocumentStatusRules.verifyProblem(DocumentStatus.PENDING))
        assertNull(DocumentStatusRules.verifyProblem(DocumentStatus.REJECTED))
        assertNotNull(DocumentStatusRules.verifyProblem(DocumentStatus.VERIFIED))
        assertNotNull(DocumentStatusRules.verifyProblem(DocumentStatus.VOIDED))
    }
}
