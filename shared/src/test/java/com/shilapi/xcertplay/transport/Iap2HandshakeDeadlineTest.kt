package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class Iap2HandshakeDeadlineTest {
    @Test fun stalledStartupDoesNotInheritFiveMinuteOrDayLongControlBudget() {
        for (lifetime in listOf(300_000L, 86_400_000L, Long.MAX_VALUE)) {
            var now = 0L
            val budget = Iap2HandshakeDeadline(60_000, { false }, { now })
            assertEquals(60_000L, budget.bound(lifetime))
            now = 59_000_000_000L
            budget.stage = Iap2HandshakeStage.START_REQUESTED
            assertEquals(1_000L, budget.bound(lifetime))
            now = 60_000_000_000L
            assertEquals(Iap2HandshakeStage.START_REQUESTED,
                assertThrows(Iap2HandshakeTimeoutException::class.java) { budget.bound(lifetime) }.stage)
        }
    }
    @Test fun authenticationOrSendingWifiDetailsDoesNotProveAnActiveCarPlaySession() {
        for (stage in Iap2HandshakeStage.entries) {
            var now = 0L
            val budget = Iap2HandshakeDeadline(60_000, { false }, { now })
            budget.stage = stage
            now = 60_000_000_000L
            assertEquals(stage, assertThrows(Iap2HandshakeTimeoutException::class.java) { budget.bound(30_000) }.stage)
        }
    }
    @Test fun establishedSessionIsNotEndedByStartupDeadlineEvenAfterADay() {
        var now = 0L
        var active = false
        val budget = Iap2HandshakeDeadline(60_000, { active }, { now })
        assertEquals(1_000L, budget.bound(300_000, poll = true))
        active = true
        assertEquals(300_000L, budget.bound(300_000, poll = true))
        active = false
        now = 25 * 60 * 60 * 1_000_000_000L
        assertEquals(300_000L, budget.bound(300_000, poll = true))
    }
    @Test fun zeroKeepsLegacyTransportTiming() {
        val budget = Iap2HandshakeDeadline(0, { false })
        assertEquals(300_000L, budget.bound(300_000, poll = true))
    }
}
