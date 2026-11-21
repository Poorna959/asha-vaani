package com.ashavaani.app.ui.history

import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.VisitRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFiltersTest {

    @Test
    fun `filters follow-ups accurately across All, PHC Urgent, Pending, and Completed`() {
        val sample = listOf(
            FollowUp("FU-1", "BEN-002", "Lakshmi Baby", dueDate = "2026-11-21", purpose = "PHC Referral", status = "ESCALATED"),
            FollowUp("FU-2", "BEN-001", "Sunita Devi", dueDate = "2026-12-19", purpose = "ANC Checkup", status = "PENDING"),
            FollowUp("FU-3", "BEN-004", "Meena Kumari", dueDate = "2026-11-18", purpose = "Postnatal", status = "COMPLETED"),
        )

        assertEquals(3, HistoryFilterHelpers.filterFollowUps(sample, FollowUpFilter.ALL).size)
        assertEquals(listOf("FU-1"), HistoryFilterHelpers.filterFollowUps(sample, FollowUpFilter.ESCALATED).map { it.id })
        assertEquals(listOf("FU-2"), HistoryFilterHelpers.filterFollowUps(sample, FollowUpFilter.PENDING).map { it.id })
        assertEquals(listOf("FU-3"), HistoryFilterHelpers.filterFollowUps(sample, FollowUpFilter.COMPLETED).map { it.id })
    }

    @Test
    fun `filters visits by PHC escalated danger flags and formats ISO dates`() {
        val visits = listOf(
            VisitRecord("VIS-1", "BEN-001", "Sunita Devi", "ANC", hasDangerSign = false),
            VisitRecord(
                "VIS-2",
                "BEN-002",
                "Lakshmi Baby",
                "NEWBORN",
                hasDangerSign = true,
                dangerSigns = listOf("DS_NB_HIGH_FEVER_COLD"),
            ),
        )

        assertEquals(2, HistoryFilterHelpers.filterVisits(visits, VisitFilter.ALL).size)
        assertEquals(listOf("VIS-2"), HistoryFilterHelpers.filterVisits(visits, VisitFilter.ESCALATED).map { it.id })
        assertEquals("2026-11-21", HistoryFilterHelpers.formatIsoDateShort("2026-11-21T10:15:00+00:00"))
        assertEquals("—", HistoryFilterHelpers.formatIsoDateShort(null))
    }
}
