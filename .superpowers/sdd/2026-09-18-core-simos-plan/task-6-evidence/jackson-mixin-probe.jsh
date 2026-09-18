import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.core.state.WorldChangeSet;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.LinkedHashMap;
import java.util.Map;

record ToyChangeSet(int v) implements ChangeSet {}

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
interface ChangeSetMixin {}

var plain = SimosObjectMapper.create();
System.out.println("P1 empty plain ser : " + plain.writeValueAsString(WorldChangeSet.empty()));
System.out.println("P2 toy   plain ser : " + plain.writeValueAsString(new WorldChangeSet(Map.of("toy", new ToyChangeSet(7)))));

SimpleModule mod = new SimpleModule("changeset-typing").setMixInAnnotation(ChangeSet.class, ChangeSetMixin.class);
ObjectMapper typed = SimosObjectMapper.create(mod);
System.out.println("P3 empty mixin ser : " + typed.writeValueAsString(WorldChangeSet.empty()));
String toyJson = typed.writeValueAsString(new WorldChangeSet(Map.of("toy", new ToyChangeSet(7))));
System.out.println("P4 toy   mixin ser : " + toyJson);
WorldChangeSet back = typed.readValue(toyJson, WorldChangeSet.class);
System.out.println("P5 mixin roundtrip equals : " + back.equals(new WorldChangeSet(Map.of("toy", new ToyChangeSet(7)))));
System.out.println("P6 mixin roundtrip as ChangeSet : " + typed.readValue(toyJson, ChangeSet.class));
try {
  plain.readValue(plain.writeValueAsString(new WorldChangeSet(Map.of("toy", new ToyChangeSet(7)))), WorldChangeSet.class);
  System.out.println("P7 plain roundtrip of NONEMPTY: OK (unexpected?)");
} catch (Exception e) {
  System.out.println("P7 plain roundtrip of NONEMPTY FAILS: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
}
WorldChangeSet emptyBack = plain.readValue(plain.writeValueAsString(WorldChangeSet.empty()), WorldChangeSet.class);
System.out.println("P8 plain roundtrip of EMPTY: " + emptyBack.equals(WorldChangeSet.empty()));
System.out.println("P9 mixin empty roundtrip equals: " + typed.readValue(typed.writeValueAsString(WorldChangeSet.empty()), WorldChangeSet.class).equals(WorldChangeSet.empty()));
LinkedHashMap<String, ChangeSet> ordered = new LinkedHashMap<>();
ordered.put("zulu", new ToyChangeSet(1));
ordered.put("mike", new ToyChangeSet(2));
ordered.put("alpha", new ToyChangeSet(3));
System.out.println("P10 mixin ser key order: " + typed.writeValueAsString(new WorldChangeSet(ordered)));
System.out.println("P11 mixin deserde of PLAIN nonempty bytes (no @class) dies?: ");
try {
  System.out.println("  -> " + typed.readValue(plain.writeValueAsString(new WorldChangeSet(Map.of("toy", new ToyChangeSet(7)))), WorldChangeSet.class));
} catch (Exception e) {
  System.out.println("  -> FAILS: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
}
/exit
