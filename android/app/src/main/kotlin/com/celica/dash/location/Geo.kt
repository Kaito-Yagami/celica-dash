package com.celica.dash.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance in metres. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial bearing from one point to another, degrees clockwise from north. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

fun compass(deg: Float): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
}
