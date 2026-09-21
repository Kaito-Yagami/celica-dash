package com.celica.dash.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Parsing against replies as real adapters (and clones) actually produce them. */
class Elm327Test {
    @Test fun `spaced hex with echo off`() {
        val b = Elm327.parseReply(Elm327.clean("41 0C 1A F8\r\r>"), 0x01, 0x0C)
        assertArrayEquals(intArrayOf(0x1A, 0xF8), b)
        assertEquals(1726, Pids.rpm(b))
    }

    @Test fun `unspaced hex after ATS0`() {
        val b = Elm327.parseReply(Elm327.clean("410D46\r>"), 0x01, 0x0D)
        assertEquals(43.5f, Pids.speedMph(b), 0.1f)
    }

    @Test fun `searching chatter before first answer`() {
        val b = Elm327.parseReply(Elm327.clean("SEARCHING...\r41 05 7B\r\r>"), 0x01, 0x05)
        assertEquals(83, Pids.tempC(b))
    }

    @Test fun `no data throws`() {
        assertThrows(ObdException::class.java) { Elm327.parseReply(Elm327.clean("NO DATA\r>"), 0x01, 0x0F) }
    }

    @Test fun `wrong pid in reply throws`() {
        assertThrows(ObdException::class.java) { Elm327.parseReply(Elm327.clean("41 0D 46\r>"), 0x01, 0x0C) }
    }

    @Test fun `fault codes decode and padding is dropped`() {
        val b = Elm327.parseReply(Elm327.clean("43 01 33 04 20 00 00\r>"), 0x03, -1)
        assertEquals(listOf("P0133", "P0420"), Pids.dtcs(b))
    }

    @Test fun `code letters follow the top bits`() {
        assertEquals(listOf("C0300", "B1234", "U0100"), Pids.dtcs(intArrayOf(0x43, 0x00, 0x92, 0x34, 0xC1, 0x00)))
    }

    @Test fun `trims are signed percent`() {
        assertEquals(0f, Pids.trimPct(intArrayOf(128)), 0.01f)
        assertEquals(-100f, Pids.trimPct(intArrayOf(0)), 0.01f)
        assertEquals(99.2f, Pids.trimPct(intArrayOf(255)), 0.1f)
    }
}
