package com.celica.dash.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.celica.dash.data.DrivePoint
import com.celica.dash.data.Place
import com.celica.dash.search.PlaceSearch
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textLetterSpacing
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textTransform
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Sources and layers the app adds on top of the style: the trail, pins, a replay, fuel. */
object MapLayers {
    /** The style JSON with the tiles path filled in and its colours re-dressed in the dash's own palette. */
    fun styleJson(ctx: android.content.Context, tiles: java.io.File): String {
        var s = ctx.assets.open("map/style.json").bufferedReader().readText().replace("__TILES__", tiles.absolutePath)
        // the style is written in the ink look's literals; each maps to whatever the palette is now
        for ((from, to) in com.celica.dash.ui.theme.Ink.mapPalette()) if (from != to) s = s.replace(from, "\u0000" + to.drop(1))   // no # until the end, so a colour put in is never matched by a later key
        return s.replace("\u0000", "#")
    }

    const val TRAIL = "trail"
    const val PINS = "pins"
    const val REPLAY = "replay"
    const val LIFT = "lift"
    const val ROUTE = "route"
    const val GHOST = "ghost"
    const val GHOST_AT = "ghost-at"
    const val POIS = "pois"
    const val POI_LAYER = "pois-icons"
    const val PIN_LAYER = "pins-dot"
    const val PIN_LABEL = "pins-label"
    private const val ABOVE = "roads-labels"          // our lines go under the words

    /** What install() managed, for the diagnostics line: the unit has no other way of saying. */
    @Volatile
    var report: String = "not opened yet"

    fun install(st: Style) {
        try {
            installLayers(st)
        } catch (e: Throwable) {
            report = "failed: $e"
        }
    }

    private fun installLayers(st: Style) {
        // this drive so far, one red line
        st.addSource(GeoJsonSource(TRAIL))
        st.addLayerBelow(LineLayer("trail-line", TRAIL).withProperties(
            lineColor("#D8433C"), lineWidth(3f), lineOpacity(0.75f), lineCap("round"), lineJoin("round"),
        ), ABOVE)
        // a replayed drive: one segment per sample, coloured by how fast it was going, and only
        // the part already driven at full strength
        st.addSource(GeoJsonSource(REPLAY))
        st.addLayerBelow(LineLayer("replay-line", REPLAY).withProperties(
            lineColor(Expression.interpolate(Expression.linear(), get("s"),
                Expression.stop(0, Expression.color(0xFF6A6461.toInt())), Expression.stop(30, Expression.color(0xFF8E3C38.toInt())),
                Expression.stop(55, Expression.color(0xFFD8433C.toInt())), Expression.stop(85, Expression.color(0xFFFF6A5E.toInt())))),
            lineWidth(4f), lineOpacity(0.15f), lineCap("round"), lineJoin("round"),
        ), ABOVE)
        // the route ahead: a white line with a dark casing, above every road, under the words
        st.addSource(GeoJsonSource(ROUTE))
        st.addLayerBelow(LineLayer("route-casing", ROUTE).withProperties(
            lineColor("#0E0D0F"), lineWidth(9f), lineOpacity(0.9f), lineCap("round"), lineJoin("round"),
        ), ABOVE)
        st.addLayerBelow(LineLayer("route-line", ROUTE).withProperties(
            lineColor("#F1EDEA"), lineWidth(4.5f), lineOpacity(0.95f), lineCap("round"), lineJoin("round"),
        ), ABOVE)
        // the run being chased: its line laid down the road, and the ghost itself on it
        st.addSource(GeoJsonSource(GHOST))
        st.addSource(GeoJsonSource(GHOST_AT))
        st.addLayerBelow(LineLayer("ghost-line", GHOST).withProperties(
            lineColor("#8F8884"), lineWidth(2.5f), lineOpacity(0.45f), lineCap("round"), lineJoin("round"),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ), ABOVE)
        st.addLayer(CircleLayer("ghost-dot-halo", GHOST_AT).withProperties(
            circleRadius(11f), circleColor("#F1EDEA"), circleOpacity(0.16f),
        ))
        st.addLayer(CircleLayer("ghost-dot", GHOST_AT).withProperties(
            circleRadius(5.5f), circleColor("#0E0D0F"), circleStrokeColor("#F1EDEA"), circleStrokeWidth(2f), circleStrokeOpacity(0.85f),
        ))
        // where the engine went into lift on that drive
        st.addSource(GeoJsonSource(LIFT))
        st.addLayerBelow(CircleLayer("lift-dots", LIFT).withProperties(
            circleRadius(3.5f), circleColor("#FF6A5E"), circleStrokeColor("#0E0D0F"), circleStrokeWidth(1.5f), circleOpacity(0.15f), circleStrokeOpacity(0.15f),
        ), ABOVE)
        // pins: a white ring with a red heart, the name above in small caps
        st.addSource(GeoJsonSource(PINS))
        st.addLayer(CircleLayer(PIN_LAYER, PINS).withProperties(
            circleRadius(5f), circleColor("#D8433C"), circleStrokeColor("#F1EDEA"), circleStrokeWidth(1.6f),
        ))
        st.addLayer(SymbolLayer(PIN_LABEL, PINS).withProperties(
            textField(get("name")), textFont(arrayOf("Noto Sans Medium")), textSize(10.5f), textTransform("uppercase"), textLetterSpacing(0.12f),
            textOffset(arrayOf(0f, -1.3f)), textAnchor("bottom"), textColor("#F1EDEA"), textHaloColor("#0E0D0F"), textHaloWidth(1.6f), textOptional(false),
        ))
        // the stops the owner asked to see - petrol, food, parking and the rest. The basemap
        // thins its own POIs down to almost nothing, so these come from the search file instead.
        for ((cat, _) in PlaceSearch.CATEGORIES) st.addImage("poi-$cat", categoryIcon(cat))
        st.addSource(GeoJsonSource(POIS))
        st.addLayerBelow(SymbolLayer(POI_LAYER, POIS).withProperties(
            iconImage(get("icon")), iconSize(0.46f), iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(get("name")), textFont(arrayOf("Noto Sans Regular")), textSize(9.5f), textOffset(arrayOf(0f, 1.3f)), textAnchor("top"),
            textColor("#8F8884"), textHaloColor("#0E0D0F"), textHaloWidth(1.4f), textOptional(true),
        ).also { it.minZoom = 12.5f }, PIN_LAYER)
        val drawn = PlaceSearch.CATEGORIES.count { st.getImage("poi-" + it.first) != null }
        report = "installed · icons $drawn/${PlaceSearch.CATEGORIES.size} · ${st.layers.size} layers"
    }

    fun setTrail(st: Style, trail: List<DoubleArray>) {
        val src = st.getSourceAs<GeoJsonSource>(TRAIL) ?: return
        if (trail.size >= 2) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(trail.map { Point.fromLngLat(it[1], it[0]) })))
        else src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** The part of the route still to drive, from point `from` on; null clears it. */
    fun setRoute(st: Style, points: List<DoubleArray>?, from: Int) {
        val src = st.getSourceAs<GeoJsonSource>(ROUTE) ?: return
        if (points == null || points.size - from < 2) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points.subList(from.coerceIn(0, points.size - 2), points.size).map { Point.fromLngLat(it[1], it[0]) })))
    }

    /** What the map shows of its own accord: the stops in view, of whatever kinds were asked for. */
    fun setPois(st: Style, hits: List<PlaceSearch.Hit>) {
        st.getSourceAs<GeoJsonSource>(POIS)?.setGeoJson(FeatureCollection.fromFeatures(hits.mapNotNull { h ->
            val cat = PlaceSearch.categoryOf(h.kind) ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(h.lon, h.lat)).also {
                it.addStringProperty("icon", "poi-$cat"); it.addStringProperty("name", h.name); it.addStringProperty("kind", h.kind)
            }
        }))
    }

    /** The words and the blocks the basemap draws, which some prefer off. */
    fun setMapWords(st: Style, streets: Boolean, places: Boolean, buildings: Boolean) {
        val vis = { on: Boolean -> PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE) }
        st.getLayer("roads-labels")?.setProperties(vis(streets))
        st.getLayer("places")?.setProperties(vis(places))
        st.getLayer("buildings")?.setProperties(vis(buildings))
        st.getLayer("buildings-3d")?.setProperties(vis(buildings))
    }

    /** The line of the run being chased; null clears it. */
    fun setGhostRoute(st: Style, pts: List<DrivePoint>?) {
        val src = st.getSourceAs<GeoJsonSource>(GHOST) ?: return
        if (pts == null || pts.size < 2) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })))
    }

    /** Where the ghost has got to; null takes it off the map. */
    fun setGhostAt(st: Style, lat: Double?, lon: Double?) {
        val src = st.getSourceAs<GeoJsonSource>(GHOST_AT) ?: return
        if (lat == null || lon == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
    }

    fun setPins(st: Style, places: List<Place>) {
        st.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(FeatureCollection.fromFeatures(places.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also { it.addNumberProperty("pid", p.id); it.addStringProperty("name", p.name) }
        }))
    }

    /** Loads a drive into the replay layers; `liftRpm` decides which samples get a dot. */
    fun setReplay(st: Style, pts: List<DrivePoint>?, liftRpm: Int) {
        val rep = st.getSourceAs<GeoJsonSource>(REPLAY) ?: return
        val lift = st.getSourceAs<GeoJsonSource>(LIFT) ?: return
        if (pts == null || pts.size < 2) {
            rep.setGeoJson(FeatureCollection.fromFeatures(emptyList())); lift.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return
        }
        val segs = ArrayList<Feature>(pts.size)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            segs += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat)))).also {
                it.addNumberProperty("i", i); it.addNumberProperty("s", ((a.mph + b.mph) / 2f).toDouble())
            }
        }
        rep.setGeoJson(FeatureCollection.fromFeatures(segs))
        var wasLift = false
        val dots = ArrayList<Feature>()
        for ((i, p) in pts.withIndex()) {
            val on = p.rpm >= liftRpm
            if (on && !wasLift) dots += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also { it.addNumberProperty("i", i) }
            wasLift = on
        }
        lift.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    /** Brightens the replay up to sample `cursor`; the rest stays a ghost of where it is going. */
    fun setReplayCursor(st: Style, cursor: Int) {
        val done = Expression.lte(get("i"), literal(cursor))
        st.getLayer("replay-line")?.setProperties(lineOpacity(Expression.switchCase(done, literal(0.9f), literal(0.15f))))
        st.getLayer("lift-dots")?.setProperties(
            circleOpacity(Expression.switchCase(done, literal(1f), literal(0.15f))),
            circleStrokeOpacity(Expression.switchCase(done, literal(1f), literal(0.15f))),
        )
    }

    /**
     * The icon for one category, drawn at 64 px on a dark plate with a red edge and pinned to the
     * default density, so the unit scales it by the icon size and nothing else.
     */
    private fun categoryIcon(cat: String): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        b.density = android.util.DisplayMetrics.DENSITY_DEFAULT
        val c = Canvas(b)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20E0D0F.toInt() }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8433C.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1EDEA.toInt() }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1EDEA.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        val disc = RectF(4f, 4f, 60f, 60f)
        c.drawRoundRect(disc, 16f, 16f, plate)
        c.drawRoundRect(disc, 16f, 16f, edge)
        when (cat) {
            "FUEL" -> {                                              // a pump with its hose
                c.drawRoundRect(RectF(17f, 16f, 37f, 48f), 3f, 3f, white)
                c.drawRect(21f, 21f, 33f, 28f, plate)
                c.drawLine(37f, 24f, 44f, 24f, line)
                c.drawLine(44f, 24f, 44f, 40f, line)
                c.drawCircle(44f, 43f, 3.2f, white)
            }
            "FOOD" -> {                                              // a fork and a knife
                for (x in floatArrayOf(20f, 25f, 30f)) c.drawLine(x, 14f, x, 25f, line)
                c.drawLine(25f, 25f, 25f, 50f, line)
                c.drawRoundRect(RectF(38f, 14f, 46f, 34f), 4f, 4f, white)
                c.drawLine(42f, 34f, 42f, 50f, line)
            }
            "SHOP" -> {                                              // a carrier bag
                c.drawRoundRect(RectF(16f, 26f, 48f, 50f), 3f, 3f, white)
                c.drawArc(RectF(24f, 14f, 40f, 34f), 180f, 180f, false, line)
            }
            "PARK" -> letter(c, "P", white)
            "LOO" -> letter(c, "WC", white)
            "STAY" -> {                                              // a bed
                c.drawLine(16f, 24f, 16f, 46f, line)
                c.drawRoundRect(RectF(20f, 28f, 32f, 36f), 3f, 3f, white)
                c.drawRoundRect(RectF(16f, 36f, 48f, 44f), 3f, 3f, white)
                c.drawLine(48f, 38f, 48f, 46f, line)
            }
            "GARAGE" -> {                                            // a spanner
                c.drawLine(22f, 42f, 42f, 22f, Paint(line).apply { strokeWidth = 7f })
                c.drawCircle(19f, 45f, 8f, white)
                c.drawCircle(19f, 45f, 3.5f, plate)
                c.drawCircle(45f, 19f, 8f, white)
                c.drawCircle(45f, 19f, 3.5f, plate)
            }
            "GYM" -> {                                               // a dumbbell
                c.drawLine(22f, 32f, 42f, 32f, Paint(line).apply { strokeWidth = 5f })
                c.drawRoundRect(RectF(14f, 22f, 22f, 42f), 3f, 3f, white)
                c.drawRoundRect(RectF(42f, 22f, 50f, 42f), 3f, 3f, white)
            }
            else -> {                                                // care: a cross
                c.drawRoundRect(RectF(27f, 15f, 37f, 49f), 3f, 3f, white)
                c.drawRoundRect(RectF(15f, 27f, 49f, 37f), 3f, 3f, white)
            }
        }
        return b
    }

    /** A letter or two in the middle of the plate, for the ones a picture would only muddle. */
    private fun letter(c: Canvas, text: String, white: Paint) {
        val p = Paint(white).apply {
            textSize = if (text.length > 1) 26f else 38f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val m = p.fontMetrics
        c.drawText(text, 32f, 32f - (m.ascent + m.descent) / 2f, p)
    }
}
