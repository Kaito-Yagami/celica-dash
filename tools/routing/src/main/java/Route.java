import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.util.EncodingManager;

/**
 * Imports an OSM extract into a GraphHopper 1.0 graph with contraction hierarchies for cars.
 * Usage: route <osm.pbf> <output-dir>. Takes a while for a country; a few GB of heap.
 */
public class Route {
    public static void main(String[] args) {
        GraphHopper h = new GraphHopperOSM().forDesktop();
        h.setDataReaderFile(args[0]);
        h.setGraphHopperLocation(args[1]);
        h.setEncodingManager(EncodingManager.create("car"));
        h.setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(false));
        h.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
        h.setMinNetworkSize(200);
        h.importOrLoad();
        h.close();
        System.out.println("done: " + args[1]);
    }
}
