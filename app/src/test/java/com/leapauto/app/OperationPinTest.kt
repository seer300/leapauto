package com.leapauto.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationPinTest {
    @Test
    fun `accepts four ASCII digits including a leading zero`() {
        assertTrue(OperationPin.isValid("0123"))
    }

    @Test
    fun `rejects invalid lengths and characters`() {
        listOf("", "123", "12345", "12a4", "１２３４", "12 4").forEach {
            assertFalse(OperationPin.isValid(it))
        }
    }

    @Test
    fun `keeps the first four ASCII digits in pasted input`() {
        assertEquals("0123", OperationPin.filterInput(" 0a1-２2345"))
    }

    @Test
    fun `cloud confirmation is limited to the account vehicle session and request window`() {
        val request = ExternalControlRequest("lock", "VIN-A", "account", 7L, 100L)
        val oldAuth = OldAuth("account", "", "", "", 0L)
        val newAuth = NewAuth("account", "", "", "", 0L)
        val session = Session("device", oldAuth = oldAuth, newAuth = newAuth, selectedVin = "VIN-A", generation = 7L)

        assertTrue(request.isCurrent(session, 100L))
        assertTrue(request.isCurrent(session, 100L + ExternalControlRequest.EXPIRY_MS))
        assertFalse(request.isCurrent(session, 99L))
        assertFalse(request.isCurrent(session, 101L + ExternalControlRequest.EXPIRY_MS))
        assertFalse(request.copy(generation = 6L).isCurrent(session, 100L))
        assertFalse(request.copy(accountId = "other").isCurrent(session, 100L))
        assertFalse(request.copy(vin = "VIN-B").isCurrent(session, 100L))
        session.newAuth = null
        assertFalse(request.isCurrent(session, 100L))
    }

    @Test
    fun `scheduled linkage includes selected actions and prefers heating to ventilation`() {
        val plan = ScheduledAcPlan(
            closeWindows = true,
            closeSunshade = false,
            driverHeating = true,
            driverVent = true,
            passengerVent = true
        )
        assertEquals(
            listOf("windowClose", "driverSeatHeating_2", "passengerSeatVentilation_2"),
            ScheduledAcLinkageReceiver.linkageCommands(plan)
        )
        assertTrue(ScheduledAcLinkageReceiver.linkageCommands(
            ScheduledAcPlan(closeWindows = false, closeSunshade = false)
        ).isEmpty())
    }
}
