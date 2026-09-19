import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public class OldArchiveProbe {
  public static void main(String[] args) throws Exception {
    String outer = Files.readString(Path.of(args[0]));
    JsonNode root = new ObjectMapper().readTree(outer);
    String payload = root.get("modules").get("map").asText();
    System.out.println("payload chars=" + payload.length());
    MapSnapshot snap = (MapSnapshot) new MapCodec().decodeSnapshot(payload);
    GameMap map = snap.map();
    TerrainBlocks.requirePartition(map.hexes(), map.terrainBlocks());
    Map<String, Integer> hist = new TreeMap<>();
    for (String t : map.terrainIndex().values()) hist.merge(t, 1, Integer::sum);
    System.out.println("partition=OK hexCount=" + map.hexes().size());
    System.out.println("blockCount=" + map.terrainBlocks().size());
    System.out.println("histogram=" + hist);
    HexCoord first = new HexCoord(-5, -59);
    System.out.println("firstHex terrain=" + map.terrainAt(first) + " height=" + map.hexes().get(first).height());
    System.out.println("sampleBlockId=" + map.terrainBlocks().keySet().iterator().next());
  }
}
