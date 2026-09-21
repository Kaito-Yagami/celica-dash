package com.celica.dash.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    // a route north up a meridian for 2 km, one left turn half way, then 1 km west
    private val pts = (0..20).map { doubleArrayOf(52.0 + it * 0.0009, -1.0) } + (1..10).map { doubleArrayOf(52.018, -1.0 - it * 0.00146) }
    private val route = Route(
        pts,
        listOf(Turn(0, "High Street", 2000.0, 0, 0), Turn(-2, "Mill Lane", 1000.0, 20, 0), Turn(4, "", 0.0, pts.size - 1, 0)),
        3000.0, 240_000L,
    )

    @Test
    fun `first turn ahead is the left, with the distance along the road`() {
        val n = Navigator(route, "Home", pts.last()[0], pts.last()[1])
        val st = n.update(52.0045, -1.0)
        assertEquals(-2, st.turn?.sign)
        assertEquals(1500.0, st.toTurnM, 30.0)
        assertFalse(st.offRoute)
        assertFalse(st.arrived)
    }

    @Test
    fun `past the turn the next is the finish and remaining shrinks`() {
        val n = Navigator(route, "Home", pts.last()[0], pts.last()[1])
        n.update(52.0170, -1.0)
        val st = n.update(52.018, -1.005)
        assertEquals(4, st.turn?.sign)
        assertTrue(st.remainingM < 1000.0)
        assertTrue(st.remainingMs < 240_000L)
    }

    @Test
    fun `four fixes well off the line flag off route`() {
        val n = Navigator(route, "Home", pts.last()[0], pts.last()[1])
        repeat(3) { n.update(52.005, -1.003) }
        assertFalse(n.state.offRoute)
        assertTrue(n.update(52.005, -1.003).offRoute)
        // back on the line clears it
        assertFalse(n.update(52.006, -1.0).offRoute)
    }

    @Test
    fun `arriving at the destination`() {
        val n = Navigator(route, "Home", pts.last()[0], pts.last()[1])
        assertTrue(n.update(pts.last()[0] + 0.0001, pts.last()[1]).arrived)
    }

    @Test
    fun `distances read in yards then miles`() {
        assertEquals("150 YD", fmtDist(140.0))
        assertEquals("0.6 MI", fmtDist(1000.0))
        assertEquals("12 MI", fmtDist(19_000.0))
    }
}
