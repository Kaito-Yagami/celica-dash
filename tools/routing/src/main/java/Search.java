import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dash's search file - every place and stop in a region - from the OSM extract, the same
 * bytes tools/map/make_search.py wrote, so RUN ME needs a JRE and nothing else.
 *
 *     search <osm.pbf> <search.idx> <latMin> <latMax>
 *
 * The map tiles are drawn from a thinned basemap that keeps a couple of petrol stations per
 * town; the extract is the whole of OpenStreetMap for the region, so the search and the map's
 * icons come from that instead. Two passes: the first keeps every tagged node outright and
 * remembers the node ids of every tagged way; the second reads those nodes' positions, so a
 * way becomes the centre of its outline. The file:
 *
 *     "CSCH" 2  kindCount  [len,name] x kindCount
 *     count (u32)  lat0 e6 (i32)  step e6 (i32)  bands (u16)  bandStart (u32) x bands
 *     then, in latitude order: lat e6 (i32)  lon e6 (i32)  kind (u8)  nameLen (u8)  name (utf-8)
 *
 * Little-endian throughout. The unit scans the bytes - the file is the index - and the icons on
 * the map read only the latitude bands the screen covers, which is why the entries are sorted.
 */
public class Search {
    static final Map<String, Set<String>> BY_TAG = new LinkedHashMap<>();
    static {
        BY_TAG.put("amenity", set("fuel", "charging_station", "parking", "restaurant", "cafe", "fast_food", "pub", "bar",
            "ice_cream", "pharmacy", "hospital", "clinic", "doctors", "dentist", "veterinary",
            "police", "fire_station", "post_office", "bank", "atm", "bureau_de_change", "library",
            "townhall", "toilets", "drinking_water", "cinema", "theatre", "nightclub", "casino",
            "marketplace", "car_wash", "car_rental", "taxi", "bus_station", "ferry_terminal",
            "community_centre", "place_of_worship", "school", "college", "university", "kindergarten",
            "food_court", "biergarten"));
        BY_TAG.put("shop", set("supermarket", "convenience", "bakery", "butcher", "greengrocer", "mall", "department_store",
            "hardware", "doityourself", "garden_centre", "furniture", "electronics", "clothes", "shoes",
            "books", "florist", "hairdresser", "laundry", "optician", "jewelry", "gift", "toys", "pet",
            "car", "car_parts", "car_repair", "tyres", "bicycle", "alcohol", "beauty", "chemist",
            "mobile_phone", "sports", "stationery", "variety_store", "travel_agency", "deli", "farm",
            "hifi", "computer", "outdoor", "fishing", "music", "charity", "newsagent", "kiosk"));
        BY_TAG.put("leisure", set("fitness_centre", "sports_centre", "swimming_pool", "golf_course", "stadium", "park",
            "garden", "playground", "nature_reserve", "marina", "water_park", "ice_rink", "bowling_alley",
            "dog_park", "miniature_golf", "horse_riding"));
        BY_TAG.put("tourism", set("hotel", "motel", "guest_house", "hostel", "camp_site", "caravan_site", "attraction",
            "museum", "gallery", "zoo", "theme_park", "viewpoint", "picnic_site", "information", "artwork", "aquarium"));
        BY_TAG.put("historic", set("castle", "monument", "ruins", "memorial", "archaeological_site"));
        BY_TAG.put("healthcare", set("centre", "physiotherapist", "hospice"));
        BY_TAG.put("railway", set("station", "halt", "tram_stop"));
        BY_TAG.put("aeroway", set("aerodrome", "terminal"));
        BY_TAG.put("highway", set("services", "rest_area"));
        BY_TAG.put("man_made", set("lighthouse", "pier"));
        BY_TAG.put("place", set("city", "town", "village", "hamlet", "suburb", "borough", "quarter", "neighbourhood", "locality", "island"));
    }
    static final Map<String, String> RENAME = new HashMap<>();
    static { RENAME.put("healthcare/centre", "clinic"); RENAME.put("aeroway/terminal", "airport"); RENAME.put("aeroway/aerodrome", "airport"); }
    static final Set<String> NAMELESS = set("fuel", "charging_station", "toilets", "services", "viewpoint", "picnic_site");

    static Set<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    static String kindOf(ReaderElement e) {
        for (Map.Entry<String, Set<String>> t : BY_TAG.entrySet()) {
            Object v = e.getTag(t.getKey());
            if (v instanceof String && t.getValue().contains(v)) {
                String r = RENAME.get(t.getKey() + "/" + v);
                return r != null ? r : (String) v;
            }
        }
        return null;
    }

    static String nameOf(ReaderElement e) {
        for (String k : new String[] {"name", "name:en", "brand", "operator"}) {
            Object v = e.getTag(k);
            if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
        }
        return "";
    }

    /** One entry: the kind's number, the name, and where. */
    static final class Row { final String name; final int kind; final double lat, lon; Row(String n, int k, double la, double lo) { name = n; kind = k; lat = la; lon = lo; } }
    static final class Pending { final String name; final int kind; final long[] nodes; Pending(String n, int k, long[] nd) { name = n; kind = k; nodes = nd; } }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) { System.out.println("search <osm.pbf> <search.idx> <latMin> <latMax>"); System.exit(2); }
        File pbf = new File(args[0]);
        File out = new File(args[1]);
        double latMin = Double.parseDouble(args[2]), latMax = Double.parseDouble(args[3]);
        long t0 = System.currentTimeMillis();

        List<String> kinds = new ArrayList<>();
        Map<String, Integer> kindNo = new HashMap<>();
        Map<String, Row> seen = new HashMap<>();
        List<Pending> ways = new ArrayList<>();
        Set<Long> wanted = new HashSet<>();
        long done = 0;

        // pass one: nodes straight in, ways remembered with the nodes they need
        try (OSMInputFile in = new OSMInputFile(pbf).setWorkerThreads(2).open()) {
            ReaderElement e;
            while ((e = in.getNext()) != null) {
                if (++done % 5_000_000 == 0) System.out.println("  " + done / 1000 + "k objects, " + seen.size() + " entries, " + (System.currentTimeMillis() - t0) / 1000 + "s");
                if (!e.hasTags()) continue;
                String kind = kindOf(e);
                if (kind == null) continue;
                String name = nameOf(e);
                if (name.isEmpty() && !NAMELESS.contains(kind)) continue;
                Integer k = kindNo.get(kind);
                if (k == null) { k = kinds.size(); kindNo.put(kind, k); kinds.add(kind); }
                if (e.getType() == ReaderElement.NODE) {
                    ReaderNode n = (ReaderNode) e;
                    keep(seen, name, kind, k, n.getLat(), n.getLon());
                } else if (e.getType() == ReaderElement.WAY) {
                    ReaderWay w = (ReaderWay) e;
                    long[] ids = w.getNodes().toArray();
                    if (ids.length == 0) continue;
                    for (long id : ids) wanted.add(id);
                    ways.add(new Pending(name.isEmpty() ? title(kind) : name, k, ids));
                }
            }
        }
        System.out.println("  pass one: " + seen.size() + " nodes, " + ways.size() + " ways wanting " + wanted.size() + " positions");

        // pass two: the positions those ways need, then their centres
        Map<Long, double[]> pos = new HashMap<>(wanted.size() * 2);
        if (!ways.isEmpty()) {
            try (OSMInputFile in = new OSMInputFile(pbf).setWorkerThreads(2).open()) {
                ReaderElement e;
                while ((e = in.getNext()) != null) {
                    // read to the end rather than stop at the first way: the reader thread objects to being cut off
                    if (e.getType() != ReaderElement.NODE) continue;
                    if (wanted.contains(e.getId())) { ReaderNode n = (ReaderNode) e; pos.put(e.getId(), new double[] {n.getLat(), n.getLon()}); }
                }
            }
            for (Pending p : ways) {
                double la = 0, lo = 0; int n = 0;
                for (long id : p.nodes) { double[] q = pos.get(id); if (q != null) { la += q[0]; lo += q[1]; n++; } }
                if (n > 0) keep(seen, p.name, kinds.get(p.kind), p.kind, la / n, lo / n);
            }
        }

        List<Row> rows = new ArrayList<>(seen.values());
        rows.sort((a, b) -> Double.compare(a.lat, b.lat));
        System.out.println(done + " objects, " + rows.size() + " entries in " + (System.currentTimeMillis() - t0) / 1000 + "s");

        // one band of latitude every 0.05 degrees across the region, so the map reads only its strip
        int lat0 = (int) Math.floor(latMin * 1e6), step = 50_000;
        int bands = Math.max(1, (int) Math.ceil((latMax * 1e6 - lat0) / step));
        if (bands > 65535) { step = (int) Math.ceil((latMax * 1e6 - lat0) / 65535); bands = 65535; }
        int[] starts = new int[bands];
        ByteBuffer body = ByteBuffer.allocate(rows.size() * 100 + 16).order(ByteOrder.LITTLE_ENDIAN);
        int filled = -1;
        for (Row r : rows) {
            int e6 = (int) Math.round(r.lat * 1e6);
            int b = Math.min(bands - 1, Math.max(0, (e6 - lat0) / step));
            while (filled < b) starts[++filled] = body.position();
            byte[] nb = r.name.getBytes(StandardCharsets.UTF_8);
            if (nb.length > 255) nb = Arrays.copyOf(nb, 255);
            if (body.remaining() < 10 + nb.length) { ByteBuffer bigger = ByteBuffer.allocate(body.capacity() * 2).order(ByteOrder.LITTLE_ENDIAN); body.flip(); bigger.put(body); body = bigger; }
            body.putInt(e6).putInt((int) Math.round(r.lon * 1e6)).put((byte) r.kind).put((byte) nb.length).put(nb);
        }
        while (filled < bands - 1) starts[++filled] = body.position();

        try (DataOutputStream w = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
            w.write(new byte[] {'C', 'S', 'C', 'H', 2, (byte) kinds.size()});
            for (String k : kinds) { byte[] kb = k.getBytes(StandardCharsets.UTF_8); w.write(kb.length); w.write(kb); }
            ByteBuffer h = ByteBuffer.allocate(14 + 4 * bands).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(rows.size()).putInt(lat0).putInt(step).putShort((short) bands);
            for (int s : starts) h.putInt(s);
            w.write(h.array());
            w.write(body.array(), 0, body.position());
        }
        System.out.println("wrote " + out + " - " + out.length() / 1e6 + " MB, " + kinds.size() + " kinds, " + bands + " bands");
    }

    static void keep(Map<String, Row> seen, String name, String kind, int k, double lat, double lon) {
        String shown = name.isEmpty() ? title(kind) : (name.length() > 90 ? name.substring(0, 90) : name);
        String key = k + "|" + shown.toLowerCase() + "|" + Math.round(lat * 1e4) + "|" + Math.round(lon * 1e4);
        if (!seen.containsKey(key)) seen.put(key, new Row(shown, k, lat, lon));
    }

    static String title(String kind) {
        StringBuilder sb = new StringBuilder();
        for (String w : kind.split("_")) { if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return sb.toString();
    }
}
