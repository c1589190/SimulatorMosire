import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * W1f 探针（只读，落 /tmp，不进仓）：
 *
 * <p>① 从 EconomyData / EconomyChangeSet 出发走遍"进快照的类型"闭包，用**裸 SimosObjectMapper**（= Core 时间线那台第四台
 * mapper 的同一份装配）实测每个类型的**线格式属性集**，并逐个"bean 形状无参方法"给出判定； ② 复现 MarketTable 往返缺陷（裸 mapper
 * 直连 + EconomyCodec 端到端）；③ 合成 record 钉死"哪些形状会被写出"的规则； ④ 责任区外：其余六个 Snapshot 根的同款扫描（只报不改）。
 */
public final class W1fProbe {

  static final ObjectMapper BARE = SimosObjectMapper.create();
  static final Deque<Class<?>> QUEUE = new ArrayDeque<>();
  static final Set<Class<?>> SEEN = new LinkedHashSet<>();
  static final Set<Type> SEEN_TYPES = new LinkedHashSet<>();
  static final List<String> SHAPED = new ArrayList<>();
  static final List<String> HAZARDS = new ArrayList<>();
  static final List<String> MISMATCH = new ArrayList<>();
  static final List<String> EXTRA_MEMBERS = new ArrayList<>();
  static final List<String> RENAMED = new ArrayList<>();
  static final Set<String> SKIPPED = new TreeSet<>();
  static String scope = "W1f";
  static int typesAudited;
  static int plainNoArgNotSerialized;
  static int componentCount;

  public static void main(String[] args) throws Exception {
    System.out.println("=== jackson impl version = " + ObjectMapper.class.getPackage().getImplementationVersion());
    System.out.println("=== mapper = SimosObjectMapper.create()（裸 mapper，无模块 mixin）");

    reset("W1f 责任区（EconomyData + EconomyChangeSet 闭包）", "W1f");
    collect(EconomyData.class);
    collect(EconomyChangeSet.class);
    drain();
    summary();
    printShaped();
    printHazards();
    partB();
    partC();
    partD();

    reset("责任区外观测（只报不改，六个其它 Snapshot 根）", "OUT-OF-SCOPE");
    for (String name :
        List.of(
            "io.mosire.simos.map.MapSnapshot",
            "io.mosire.simos.social.SocialSnapshot",
            "io.mosire.simos.unit.UnitSnapshot",
            "io.mosire.simos.actor.ActorSnapshot",
            "io.mosire.simos.gov.GovSnapshot",
            "io.mosire.simos.army.ArmySnapshot")) {
      try {
        collect(Class.forName(name));
      } catch (Throwable t) {
        SKIPPED.add(name + " ← " + t.getClass().getSimpleName());
      }
    }
    drain();
    summary();
    printHazards();
  }

  static void reset(String label, String scopeTag) {
    scope = scopeTag;
    SKIPPED.clear();
    System.out.println();
    System.out.println("########## " + label);
    QUEUE.clear();
    SEEN.clear();
    SEEN_TYPES.clear();
    SHAPED.clear();
    HAZARDS.clear();
    MISMATCH.clear();
    EXTRA_MEMBERS.clear();
    RENAMED.clear();
    typesAudited = 0;
    plainNoArgNotSerialized = 0;
    componentCount = 0;
  }

  // ───────────────────────── 闭包遍历 ─────────────────────────

  static void drain() {
    while (!QUEUE.isEmpty()) {
      Class<?> c = QUEUE.poll();
      if (!SEEN.add(c) || !c.getName().startsWith("io.mosire.")) {
        continue;
      }
      if (c.isEnum()) {
        SKIPPED.add("[enum，按名字序列化无属性] " + c.getName());
        continue;
      }
      if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
        SKIPPED.add(
            "[非具体类型，只作节点] "
                + c.getName()
                + (c.isSealed() ? "（sealed：实现已按许可子类入队）" : "（★ 非 sealed：须人工判多态装配）"));
        continue;
      }
      audit(c);
    }
  }

  static void collect(Type type) {
    for (Class<?> c : rawClasses(type)) {
      if (c.isArray()) {
        collect(c.getComponentType());
        continue;
      }
      String n = c.getName();
      if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.")) {
        continue;
      }
      if (n.startsWith("io.mosire.")) {
        QUEUE.add(c);
      }
    }
  }

  static Set<Class<?>> rawClasses(Type type) {
    Set<Class<?>> out = new LinkedHashSet<>();
    walk(type, out);
    return out;
  }

  static void walk(Type type, Set<Class<?>> out) {
    if (type == null || !SEEN_TYPES.add(type)) {
      return;
    }
    if (type instanceof Class<?> c) {
      out.add(c);
      if (c.getPermittedSubclasses() != null) {
        for (Class<?> sub : c.getPermittedSubclasses()) {
          out.add(sub);
        }
      }
    } else if (type instanceof ParameterizedType p) {
      walk(p.getRawType(), out);
      for (Type a : p.getActualTypeArguments()) {
        walk(a, out);
      }
    } else if (type instanceof GenericArrayType g) {
      walk(g.getGenericComponentType(), out);
    } else if (type instanceof WildcardType w) {
      for (Type b : w.getUpperBounds()) {
        walk(b, out);
      }
    } else if (type instanceof TypeVariable<?> v) {
      for (Type b : v.getBounds()) {
        walk(b, out);
      }
    }
  }

  /** 逐类型：打印线格式属性集 + 每个"bean 形状无参方法"的实测判定。 */
  static void audit(Class<?> c) {
    typesAudited++;
    Set<String> components = new TreeSet<>();
    if (c.isRecord()) {
      for (RecordComponent rc : c.getRecordComponents()) {
        components.add(rc.getName());
        collect(rc.getGenericType());
      }
    } else {
      for (Method m : c.getDeclaredMethods()) {
        if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())) {
          collect(m.getGenericReturnType());
        }
        for (Type t : m.getGenericParameterTypes()) {
          collect(t);
        }
      }
      for (var f : c.getDeclaredFields()) {
        collect(f.getGenericType());
      }
    }
    componentCount += components.size();

    Set<String> props = new TreeSet<>();
    String kind;
    List<String> renamedComponents = new ArrayList<>();
    try {
      JsonSerializer<Object> ser = BARE.getSerializerProviderInstance().findValueSerializer(c);
      if (ser instanceof BeanSerializer bs) {
        kind = "BeanSerializer";
        java.util.Iterator<PropertyWriter> it = bs.properties();
        while (it.hasNext()) {
          PropertyWriter w = it.next();
          props.add(w.getName());
          String memberName = w.getMember() == null ? "?" : w.getMember().getName();
          boolean nameIsComponent = components.contains(w.getName());
          boolean memberIsComponent = components.contains(memberName);
          if (nameIsComponent) {
            continue;
          }
          // 名字不是组件名 ⇒ 两类：① @JsonProperty 改名的组件（成员名仍是组件 ⇒ 合法，但要实测读侧同名）；
          // ② 无参 getter 派生出的**额外属性**（⇒ 读侧必不认）。
          boolean accepted = readSideAccepts(c, w.getName());
          if (memberIsComponent) {
            renamedComponents.add(
                c.getSimpleName()
                    + " :: prop=\""
                    + w.getName()
                    + "\" ← 组件 "
                    + memberName
                    + "（@JsonProperty 改名）；读侧实测接受 = "
                    + accepted);
            if (!accepted) {
              HAZARDS.add("[" + scope + "] " + c.getSimpleName() + " :: 改名组件读侧竟不接受 prop=\"" + w.getName() + "\"");
            }
          } else {
            EXTRA_MEMBERS.add(
                "[" + scope + "] " + c.getSimpleName() + " :: prop=\"" + w.getName() + "\" ← " + memberName + "（成员不是任何 record 组件）");
            if (!accepted) {
              HAZARDS.add(
                  "["
                      + scope
                      + "] "
                      + c.getSimpleName()
                      + " :: 写侧写出 prop=\""
                      + w.getName()
                      + "\" ← "
                      + memberName
                      + "，实测读侧把它当**未知字段**（UnrecognizedPropertyException）⇒ 写出即读不回");
            } else {
              HAZARDS.add("[" + scope + "] " + c.getSimpleName() + " :: prop=\"" + w.getName() + "\" 写出且读侧接受（需人工判）");
            }
          }
        }
      } else {
        kind = ser == null ? "null-serializer" : ser.getClass().getSimpleName();
      }
    } catch (Throwable t) {
      kind = "SER-FAIL:" + t.getClass().getSimpleName();
    }
    if (kind.equals("BeanSerializer") && !props.equals(components)) {
      MISMATCH.add(
          "[" + scope + "] " + c.getSimpleName() + " components=" + components + " props=" + props);
    }
    RENAMED.addAll(renamedComponents);

    System.out.println(
        "### TYPE "
            + c.getName()
            + " ["
            + kind
            + "] components="
            + components.size()
            + " props="
            + props.size()
            + (components.isEmpty() ? "" : " " + components));

    List<String> lines = new ArrayList<>();
    for (Method m : c.getMethods()) {
      if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
        continue;
      }
      if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
        continue;
      }
      String name = m.getName();
      if (name.equals("toString") || name.equals("hashCode") || name.equals("getClass")) {
        continue;
      }
      String prop = implicitPropertyOf(m);
      // ★ record 组件的访问器：Jackson 用**组件名**做属性名（即使方法叫 isXxx()，如 AssetRule.isCoreMeans）。
      boolean componentAccessor = components.contains(name) && props.contains(name);
      boolean extraProp = props.contains(prop) && !componentAccessor;
      boolean shaped = isShaped(name, m);
      if (extraProp) {
        lines.add("SHAPED-SERIALIZED  " + name + "() → prop=\"" + prop + "\"  ★会被写出（非组件）");
      } else if (componentAccessor) {
        // record 组件访问器：合法（属性名 = 组件名），不进报告，避免噪声。
      } else if (shaped) {
        lines.add("shaped-not-written  " + name + "() → 候选属性名=\"" + prop + "\"  实测 props 里没有 ⇒ 不会写出");
      } else {
        plainNoArgNotSerialized++;
        if (props.contains(name)) {
          lines.add("PLAIN-SERIALIZED  " + name + "() → prop=\"" + name + "\"  ★单名方法被写出");
        }
      }
    }
    SHAPED.addAll(lines);
    for (String line : lines) {
      System.out.println("    " + line);
    }
  }

  /** Jackson 的隐式属性名规则：getXxx → xxx；isXxx（boolean）→ xxx；其余单名方法 → 名字本身（实测判定）。 */
  static String implicitPropertyOf(Method m) {
    String name = m.getName();
    if (name.startsWith("get") && name.length() > 3 && !name.equals("getClass")) {
      return decap(name.substring(3));
    }
    if (name.startsWith("is")
        && name.length() > 2
        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
      return decap(name.substring(2));
    }
    return name;
  }

  static boolean isShaped(String name, Method m) {
    if (name.equals("isEmpty")) {
      return true;
    }
    if (name.startsWith("is")
        && name.length() > 2
        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
      return true;
    }
    return name.startsWith("get") && name.length() > 3 && !name.equals("getClass");
  }

  /**
   * ★ 决定性判据（真 DeserializationContext）：写出侧的属性名，读侧的 BeanDeserializer 认不认。
   *
   * <p>用 {@code {"prop":false}} 直接 readValue 会被"缺必需 creator 参数"抢先报错（Jackson 把未知字段留到绑定之后才报） ⇒
   * 必须走读侧内省。
   */
  static boolean readSideAccepts(Class<?> c, String prop) {
    try {
      DefaultDeserializationContext ctxt =
          ((DefaultDeserializationContext) BARE.getDeserializationContext())
              .createInstance(BARE.getDeserializationConfig(), null, null);
      JsonDeserializer<Object> de =
          ctxt.findRootValueDeserializer(BARE.getTypeFactory().constructType(c));
      if (de instanceof BeanDeserializerBase bdb) {
        return bdb.findProperty(prop) != null;
      }
      return true;
    } catch (Throwable t) {
      Throwable cur = t;
      while (cur != null) {
        if (cur instanceof UnrecognizedPropertyException) {
          return false;
        }
        cur = cur.getCause();
      }
      return true;
    }
  }

  static String decap(String s) {
    return s.substring(0, 1).toLowerCase() + s.substring(1);
  }

  static void summary() {
    System.out.println(
        "### SUMMARY typesAudited="
            + typesAudited
            + " components="
            + componentCount
            + " plainNoArgNotSerialized="
            + plainNoArgNotSerialized
            + " shapedMethods="
            + SHAPED.size()
            + " hazards="
            + HAZARDS.size()
            + " props≠components="
            + MISMATCH.size()
            + " extraMembers="
            + EXTRA_MEMBERS.size());
    for (String line : MISMATCH) {
      System.out.println("  MISMATCH " + line);
    }
    for (String line : RENAMED) {
      System.out.println("  RENAMED-COMPONENT " + line);
    }
    for (String line : EXTRA_MEMBERS) {
      System.out.println("  EXTRA-MEMBER " + line);
    }
    System.out.println("  --- 未做属性内省的类型（" + SKIPPED.size() + " 个）");
    for (String line : SKIPPED) {
      System.out.println("  SKIPPED " + line);
    }
  }

  static void printShaped() {
    System.out.println("### SHAPED（bean 形状的无参方法：isEmpty / isXxx / getXxx）与实测判定");
    for (String line : SHAPED) {
      System.out.println("  " + line);
    }
  }

  static void printHazards() {
    System.out.println("### HAZARDS（会被写出、且不是 record 组件的属性）");
    if (HAZARDS.isEmpty()) {
      System.out.println("  （无）");
    }
    for (String line : HAZARDS) {
      System.out.println("  " + line);
    }
  }

  // ───────────────────────── B：MarketTable 往返 ─────────────────────────

  static void partB() throws Exception {
    System.out.println();
    System.out.println("=== PART B: MarketTable 裸 mapper 往返");
    MarketTable table =
        new MarketTable(
            CurrencyId.parse("silver"),
            new LinkedHashSet<>(List.of(new HexCoord(0, 0), new HexCoord(1, 0))),
            Map.of(CommodityId.parse("grain"), 5L, CommodityId.parse("cloth"), 0L));
    String json = BARE.writeValueAsString(table);
    System.out.println("  JSON = " + json);
    System.out.println("  含 \"empty\" 属性 = " + json.contains("\"empty\""));
    try {
      MarketTable back = BARE.readValue(json, MarketTable.class);
      System.out.println("  读回成功；逐值相等 = " + table.equals(back));
    } catch (Throwable t) {
      System.out.println("  读回失败 = " + t.getClass().getName() + ": " + t.getMessage());
    }
    // B2：W1 期间写出的旧字节（带 "empty" 那一栏）还能不能读回（@JsonIgnore ⇒ 读侧登记为可忽略）。
    String withEmpty = json.substring(0, json.length() - 1) + ",\"empty\":false}";
    try {
      MarketTable legacyBack = BARE.readValue(withEmpty, MarketTable.class);
      System.out.println("  旧字节（含 \"empty\"）读回 = 成功；逐值相等 = " + table.equals(legacyBack));
    } catch (Throwable t) {
      System.out.println("  旧字节（含 \"empty\"）读回 = 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    // B3：**别的**未知字段仍必须 fail-closed（证明没有放宽 FAIL_ON_UNKNOWN_PROPERTIES）。
    String withBogus = json.substring(0, json.length() - 1) + ",\"bogus\":1}";
    try {
      BARE.readValue(withBogus, MarketTable.class);
      System.out.println("  其它未知字段仍 fail-closed = **false（★ 放宽了严格，不符合纪律）**");
    } catch (UnrecognizedPropertyException e) {
      System.out.println("  其它未知字段仍 fail-closed = true（bogus 被拒）");
    } catch (Throwable t) {
      System.out.println("  其它未知字段仍 fail-closed = 以别的异常拒绝: " + t.getClass().getSimpleName());
    }
  }

  // ───────────────────────── C：EconomyCodec 快照往返 ─────────────────────────

  static void partC() {
    System.out.println();
    System.out.println("=== PART C: EconomyCodec.encodeSnapshot/decodeSnapshot 端到端往返（含市场价表 + 统计簿）");
    try {
      MarketZoneId marketId = MarketZoneId.parse("m-0-0");
      MarketTable table =
          MarketTable.singleHex(
              CurrencyId.parse("silver"), new HexCoord(0, 0), Map.of(CommodityId.parse("grain"), 5L));
      CommodityRound round = new CommodityRound(100L, 80L, 60L, 5L, 40L);
      MarketRoundStats stats =
          MarketRoundStats.EMPTY.withRound(0L, Map.of(CommodityId.parse("grain"), round), 300L, false);
      EconomyData data =
          EconomyData.empty()
              .withMarketTables(Map.of(marketId, table))
              .withMarketRoundStats(Map.of(marketId, stats));
      EconomySnapshot snap =
          new EconomySnapshot(
              new StateRef(new BranchId("main"), new RevisionId(1L)), SimosTimestamp.of(0L), data);
      EconomyCodec codec = new EconomyCodec();
      String json = codec.encodeSnapshot(snap);
      System.out.println("  编码成功，字节数 = " + json.length());
      System.out.println("  含 \"empty\" 属性 = " + json.contains("\"empty\""));
      Snapshot back = codec.decodeSnapshot(json);
      EconomyData backData = ((EconomySnapshot) back).data();
      System.out.println("  解码成功；EconomyData 逐值相等 = " + backData.equals(data));
      System.out.println("  市场价表逐值相等 = " + backData.marketTables().equals(data.marketTables()));
      System.out.println("  市场统计簿逐值相等 = " + backData.marketRoundStats().equals(data.marketRoundStats()));
    } catch (Throwable t) {
      System.out.println("  失败 = " + t.getClass().getName() + ": " + t.getMessage());
      Throwable cause = t.getCause();
      int depth = 0;
      while (cause != null && depth++ < 4) {
        System.out.println("    caused by " + cause.getClass().getName() + ": " + cause.getMessage());
        cause = cause.getCause();
      }
    }
  }

  // ───────────────────────── D：合成 record 的规则钉死 ─────────────────────────

  /** 合成类型：把"哪些形状会被写出"钉成一条可复现的实测规则。 */
  public record SyntheticProbe(String value, Map<String, Long> rows) {
    public boolean isEmpty() {
      return rows.isEmpty();
    }

    public boolean hasRows() {
      return !rows.isEmpty();
    }

    public boolean isConfigured() {
      return true;
    }

    public String getLabel() {
      return "L";
    }

    public long currentCycleNumber() {
      return 1L;
    }

    public String describe() {
      return "d";
    }
  }

  static void partD() throws Exception {
    System.out.println();
    System.out.println("=== PART D: 合成 record 的写入规则（哪些形状被当属性写出去）");
    SyntheticProbe p = new SyntheticProbe("v", Map.of("k", 1L));
    System.out.println("  JSON = " + BARE.writeValueAsString(p));
  }
}
