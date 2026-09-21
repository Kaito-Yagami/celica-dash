/** RUN ME's one Java tool: `route <pbf> <dir>` for the graph, `search <pbf> <idx> <latMin> <latMax>` for the places. */
public class Tool {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { usage(); return; }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "route": Route.main(rest); break;
            case "search": Search.main(rest); break;
            default: usage();
        }
    }
    static void usage() {
        System.out.println("dash-import route <osm.pbf> <graph-dir>\ndash-import search <osm.pbf> <search.idx> <latMin> <latMax>");
        System.exit(2);
    }
}
