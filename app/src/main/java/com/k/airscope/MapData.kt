package com.k.airscope

import java.util.Locale

/**
 * Street-map data around the user from OpenStreetMap (roads, buildings, water, parks), stored in
 * local meters relative to [lat0]/[lon0] so it can be drawn fast in 2D or 3D.
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
class MapData(
    val lat0: Double,
    val lon0: Double,
    val roads: List<Road>,
    val buildings: List<Building>,
    val areas: List<Area>,
    val fetchedAt: Long,
) {
    /** cls: 0 highway/primary, 1 secondary/tertiary, 2 residential/local, 3 service, 4 foot/cycle path. */
    class Road(val cls: Int, val name: String?, val pts: FloatArray, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    class Building(val pts: FloatArray, val heightM: Float, val cx: Float, val cy: Float, val radius: Float)

    /** kind: 0 water, 1 park/green. */
    class Area(val kind: Int, val pts: FloatArray, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    val proj = LocalProj(lat0, lon0)

    companion object {
        const val ROAD_RADIUS = 1500
        const val DETAIL_RADIUS = 650
        const val REFETCH_M = 700.0

        fun query(lat: Double, lon: Double): String {
            fun around(r: Int) = String.format(Locale.US, "(around:%d,%.6f,%.6f)", r, lat, lon)
            return "[out:json][timeout:40];(" +
                "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
                "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]${around(ROAD_RADIUS)};" +
                "way[\"highway\"~\"^(service|footway|path|cycleway|pedestrian|track|steps)$\"]${around(DETAIL_RADIUS)};" +
                "way[\"building\"]${around(DETAIL_RADIUS)};" +
                "way[\"natural\"=\"water\"]${around(ROAD_RADIUS)};" +
                "way[\"leisure\"~\"^(park|pitch|golf_course|playground)$\"]${around(ROAD_RADIUS)};" +
                "way[\"landuse\"~\"^(grass|forest|recreation_ground|meadow|cemetery)$\"]${around(ROAD_RADIUS)};" +
                ");out geom qt;"
        }

        private fun roadClass(h: String): Int = when (h.removeSuffix("_link")) {
            "motorway", "trunk", "primary" -> 0
            "secondary", "tertiary" -> 1
            "unclassified", "residential", "living_street" -> 2
            "service" -> 3
            else -> 4
        }

        fun parse(json: String, lat0: Double, lon0: Double, now: Long): MapData {
            val p = LocalProj(lat0, lon0)
            val roads = ArrayList<Road>()
            val buildings = ArrayList<Building>()
            val areas = ArrayList<Area>()
            val root = MiniJson.parse(json) as? Map<*, *> ?: return MapData(lat0, lon0, roads, buildings, areas, now)
            for (e in root["elements"] as? List<*> ?: emptyList<Any>()) {
                val m = e as? Map<*, *> ?: continue
                val geom = m["geometry"] as? List<*> ?: continue
                val pts = FloatArray(geom.size * 2)
                var k = 0
                for (g in geom) {
                    val gm = g as? Map<*, *> ?: continue
                    val la = (gm["lat"] as? Number)?.toDouble() ?: continue
                    val lo = (gm["lon"] as? Number)?.toDouble() ?: continue
                    pts[k++] = p.x(lo).toFloat()
                    pts[k++] = p.y(la).toFloat()
                }
                if (k < 4) continue
                val xy = if (k == pts.size) pts else pts.copyOf(k)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in xy.indices step 2) {
                    minX = minOf(minX, xy[i]); maxX = maxOf(maxX, xy[i]); minY = minOf(minY, xy[i + 1]); maxY = maxOf(maxY, xy[i + 1])
                }
                val tags = m["tags"] as? Map<*, *> ?: emptyMap<String, Any>()
                val hw = tags["highway"] as? String
                when {
                    hw != null -> roads += Road(roadClass(hw), (tags["name"] ?: tags["ref"]) as? String, xy, minX, minY, maxX, maxY)
                    tags["building"] != null -> {
                        val levels = (tags["building:levels"] as? String)?.toFloatOrNull()
                        val h = (tags["height"] as? String)?.replace("m", "")?.trim()?.toFloatOrNull()
                            ?: levels?.let { it * 3.2f } ?: 6f
                        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
                        buildings += Building(xy, h.coerceIn(2.5f, 120f), cx, cy, maxOf(maxX - minX, maxY - minY) / 2)
                    }
                    tags["natural"] == "water" -> areas += Area(0, xy, minX, minY, maxX, maxY)
                    else -> areas += Area(1, xy, minX, minY, maxX, maxY)
                }
            }
            roads.sortBy { -it.cls } // draw minor roads first, highways on top
            return MapData(lat0, lon0, roads, buildings, areas, now)
        }
    }
}
