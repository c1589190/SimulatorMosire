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
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.MoneyOutput;
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
 * W5-fix 探针（**只读**，落 /tmp，不进仓、不进 src、不进 Maven 门禁）：
 *
 * <p>① 从 EconomyData / EconomyChangeSet 出发走遍"进快照/变更集的类型"闭包，用**裸 SimosObjectMapper**（=
 * Core 时间线那台 mapper 的同一份装配，模块私有 mixin 一概看不见）实测每个类型的**线格式属性集**，并对每个"bean 形状无参方法"
 * （{@code isEmpty} / {@code isXxx} / {@code getXxx} / 单名）给出**逐条**判定：写侧是否写出、读侧是否接受；
 * ② 复现 / 验收 MoneyOutput 往返（裸 mapper + 端到端 {@link EconomyCodec}）；③ 合成 record 钉死"哪些形状会被写出"；
 * ④ 旁证（只报不改）：SocialData / ActorData 闭包同款扫描。
 *
 * <p>判据（修好之后应满足）：SUMMARY 的 hazards=0、写侧写出≠组件的属性 0 个；PART B/C 逐值相等 = true、
 * 其它未知字段仍 fail-closed = true。
 */
public final class W5FixProbe {

  static final ObjectMapper BARE = SimosObjectMapper.create();
  static final Deque<Class<?>> QUEUE = new ArrayDeque<>();
  static final Set<Class<?>> SEEN = new LinkedHashSet<>();
  static final Set<Type> SEEN_TYPES = new LinkedHashSet<>();
  static final List<String> SHAPED = new ArrayList<>();
  static final List<String> TABLE = new ArrayList<>();
  static final List<String> HAZARDS = new ArrayList<>();
  static final List<String> MISMATCH = new ArrayList<>();
  static final List<String> RENAMED = new ArrayList<>();
  static final Set<String> SKIPPED = new TreeSet<>();
  static final Set<Class<?>> BREAKERS = new LinkedHashSet<>();
  static String scope = "A";
  static int typesAudited;
  static int plainNoArg;
  static int componentCount;
  static int shapedCount;

  public static void main(String[] args) throws Exception {
    System.out.println("=== jackson impl version = " + ObjectMapper.class.getPackage().getImplementationVersion());
    System.out.println("=== mapper = SimosObjectMapper.create()（裸 mapper；模块私有 mixin 一概看不见）");

    reset("SCOPE A：EconomyData + EconomyChangeSet 闭包（W5-fix 责任区）", "A");
    collect(EconomyData.class);
    collect(EconomyChangeSet.class);
    drain();
    summary();
    printTable();
    printShaped();
    printHazards();

    reset("SCOPE B：SocialData + ActorData 闭包（只读旁证，不改）", "B");
    for (String name :
        List.of("io.mosire.simos.social.SocialData", "io.mosire.simos.actor.ActorData")) {
      try {
        collect(Class.forName(name));
      } catch (Throwable t) {
        SKIPPED.add(name + " ← " + t.getClass().getSimpleName() + ": " + t.getMessage());
      }
    }
    drain();
    summary();
    printShaped();
    printHazards();

    reset("SCOPE C：其余快照根闭包（只读旁证，不改；W5 没碰这些模块）", "C");
    for (String name :
        List.of(
            "io.mosire.simos.map.MapSnapshot",
            "io.mosire.simos.unit.UnitSnapshot",
            "io.mosire.simos.social.SocialSnapshot",
            "io.mosire.simos.actor.ActorSnapshot",
            "io.mosire.simos.gov.GovSnapshot",
            "io.mosire.simos.army.ArmySnapshot",
            "io.mosire.simos.sd.state.SdSnapshot")) {
      try {
        collect(Class.forName(name));
      } catch (Throwable t) {
        SKIPPED.add(name + " <- " + t.getClass().getSimpleName() + ": " + t.getMessage());
      }
    }
    drain();
    summary();
    printTable();
    printHazards();

    partB();
    partC();
    partD();
    partE();
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
    TABLE.clear();
    HAZARDS.clear();
    MISMATCH.clear();
    RENAMED.clear();
    BREAKERS.clear();
    typesAudited = 0;
    plainNoArg = 0;
    componentCount = 0;
    shapedCount = 0;
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

  /** 逐类型：打印线格式属性集 + 每个"bean 形状无参方法"的实测判定（★ 判定用真 serializer/deserializer 内省）。 */
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
    try {
      JsonSerializer<Object> ser = BARE.getSerializerProviderInstance().findValueSerializer(c);
      if (ser instanceof BeanSerializer bs) {
        kind = "BeanSerializer";
        java.util.Iterator<PropertyWriter> it = bs.properties();
        while (it.hasNext()) {
          PropertyWriter w = it.next();
          props.add(w.getName());
          String memberName = w.getMember() == null ? "?" : w.getMember().getName();
          if (components.contains(w.getName())) {
            continue;
          }
          boolean memberIsComponent = components.contains(memberName);
          boolean accepted = readSideAccepts(c, w.getName());
          if (memberIsComponent) {
            RENAMED.add(
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
          } else if (!accepted) {
            BREAKERS.add(c);
            HAZARDS.add(
                "["
                    + scope
                    + "] "
                    + c.getSimpleName()
                    + " :: 写侧写出 prop=\""
                    + w.getName()
                    + "\" ← "
                    + memberName
                    + "()，实测读侧把它当**未知字段**（UnrecognizedPropertyException）⇒ 写出即读不回");
          } else {
            HAZARDS.add(
                "["
                    + scope
                    + "] "
                    + c.getSimpleName()
                    + " :: prop=\""
                    + w.getName()
                    + "\" ← "
                    + memberName
                    + "() 写出且读侧接受=@JsonIgnore 已登记（需人工判）");
          }
        }
      } else {
        kind = ser == null ? "null-serializer" : ser.getClass().getSimpleName();
      }
    } catch (Throwable t) {
      kind = "SER-FAIL:" + t.getClass().getSimpleName();
    }
    if (kind.equals("BeanSerializer") && !props.equals(components)) {
      MISMATCH.add("[" + scope + "] " + c.getSimpleName() + " components=" + components + " props=" + props);
    }

    System.out.println(
        "### TYPE "
            + c.getName()
            + " ["
            + kind
            + "] components="
            + components.size()
            + " props="
            + props.size()
            + (components.isEmpty() ? "" : " " + components)
            + (props.equals(components) ? "" : "  ★props≠components " + props));

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
      boolean componentAccessor = components.contains(name) && props.contains(name);
      boolean extraProp = props.contains(prop) && !componentAccessor;
      boolean shaped = isShaped(name, m);
      String verdict;
      String disposition;
      if (componentAccessor) {
        continue; // record 组件访问器：合法（属性名 = 组件名），不进报告，避免噪声。
      } else if (extraProp) {
        verdict = "SHAPED-SERIALIZED  " + name + "() → prop=\"" + prop + "\"  ★会被写出（非组件）";
        disposition = readSideAccepts(c, prop) ? "读侧已登记为可忽略（@JsonIgnore）" : "★需处理：读侧拒收 ⇒ 写出即读不回";
      } else if (shaped) {
        verdict = "shaped-not-written  " + name + "() → 候选属性名=\"" + prop + "\"  实测 props 里没有 ⇒ 不会写出";
        disposition = "无需处理（实测不写出）";
      } else {
        plainNoArg++;
        if (props.contains(name)) {
          verdict = "PLAIN-SERIALIZED  " + name + "() → prop=\"" + name + "\"  ★单名方法被写出";
          disposition = readSideAccepts(c, name) ? "读侧已登记为可忽略" : "★需处理：读侧拒收";
        } else {
          continue; // 普通无参方法（perUnitOf(...) 之类带参；这里只可能是无参纯方法）——实测不写出，不列。
        }
      }
      shapedCount++;
      String line = verdict;
      SHAPED.add(line);
      TABLE.add(
          "| "
              + c.getSimpleName()
              + " | `"
              + name
              + "()` | `"
              + prop
              + "` | "
              + (extraProp || props.contains(name) ? "**是**" : "否")
              + " | "
              + (extraProp || props.contains(name) ? (readSideAccepts(c, prop) ? "接受（@JsonIgnore）" : "**拒绝**") : "—")
              + " | "
              + disposition
              + " |");
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
            + " plainNoArg(不写出)="
            + plainNoArg
            + " shapedMethods="
            + shapedCount
            + " hazards="
            + HAZARDS.size()
            + " props≠components="
            + MISMATCH.size());
    for (String line : MISMATCH) {
      System.out.println("  MISMATCH " + line);
    }
    for (String line : RENAMED) {
      System.out.println("  RENAMED-COMPONENT " + line);
    }
    System.out.println("  --- 未做属性内省的类型（" + SKIPPED.size() + " 个）");
    for (String line : SKIPPED) {
      System.out.println("  SKIPPED " + line);
    }
  }

  static void printTable() {
    System.out.println("### 系统排查表（每个**非组件 bean 形状无参方法**一条）");
    System.out.println("| 类型 | 方法 | 隐式属性名 | 写侧写出 | 读侧 | 处置 |");
    System.out.println("|---|---|---|---|---|---|");
    if (TABLE.isEmpty()) {
      System.out.println("| （无） | | | | | |");
    }
    for (String line : TABLE) {
      System.out.println(line);
    }
  }

  static void printShaped() {
    System.out.println("### SHAPED（bean 形状的无参方法：isEmpty / isXxx / getXxx）与实测判定");
    if (SHAPED.isEmpty()) {
      System.out.println("  （无）");
    }
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

  // ───────────────────────── B：MoneyOutput 往返 ─────────────────────────

  static void partB() throws Exception {
    System.out.println();
    System.out.println("=== PART B: MoneyOutput 裸 mapper 往返（BLOCKED-1 的最小复现/验收）");
    MoneyOutput out = new MoneyOutput(new LinkedHashMap<>(Map.of(CurrencyId.parse("silver"), 500L)));
    String json = BARE.writeValueAsString(out);
    System.out.println("  JSON = " + json);
    System.out.println("  含 \"empty\" 属性 = " + json.contains("\"empty\""));
    try {
      MoneyOutput back = BARE.readValue(json, MoneyOutput.class);
      System.out.println("  读回成功；逐值相等 = " + out.equals(back));
    } catch (Throwable t) {
      System.out.println("  读回失败 = " + t.getClass().getName() + ": " + t.getMessage());
    }
    // B2：旧字节（含 "empty" 那一栏）还能不能读回。
    String withEmpty = json.substring(0, json.length() - 1) + ",\"empty\":false}";
    try {
      MoneyOutput legacy = BARE.readValue(withEmpty, MoneyOutput.class);
      System.out.println("  旧字节（含 \"empty\"）读回 = 成功；逐值相等 = " + out.equals(legacy));
    } catch (Throwable t) {
      System.out.println("  旧字节（含 \"empty\"）读回 = 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    // B3：**别的**未知字段仍必须 fail-closed（证明没有放宽 FAIL_ON_UNKNOWN_PROPERTIES）。
    String withBogus = json.substring(0, json.length() - 1) + ",\"bogus\":1}";
    try {
      BARE.readValue(withBogus, MoneyOutput.class);
      System.out.println("  其它未知字段仍 fail-closed = **false（★ 放宽了严格，不符合纪律）**");
    } catch (UnrecognizedPropertyException e) {
      System.out.println("  其它未知字段仍 fail-closed = true（bogus 被拒）");
    } catch (Throwable t) {
      System.out.println("  其它未知字段仍 fail-closed = 以别的异常拒绝: " + t.getClass().getSimpleName());
    }
    // B4：空表（NONE）也要能读回（"不铸币"是合法状态）。
    String noneJson = BARE.writeValueAsString(MoneyOutput.NONE);
    System.out.println("  NONE JSON = " + noneJson);
    try {
      System.out.println(
          "  NONE 读回逐值相等 = " + MoneyOutput.NONE.equals(BARE.readValue(noneJson, MoneyOutput.class)));
    } catch (Throwable t) {
      System.out.println("  NONE 读回失败 = " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  // ───────────────────────── C：EconomyCodec 快照往返（含铸币产业）─────────────────────────

  static void partC() {
    System.out.println();
    System.out.println("=== PART C: EconomyCodec.encodeSnapshot/decodeSnapshot 端到端往返（含 Industry.moneyOutputPerUnit）");
    try {
      Industry mint =
          new Industry(
              IndustryId.parse("ind-mint-probe"),
              "探针铸币作坊",
              io.mosire.simos.economy.api.id.RegimeId.parse("household_self"),
              30L,
              Map.of(io.mosire.simos.actor.api.asset.AssetKind.TOOL, 2000L),
              Map.of(),
              0L,
              0L,
              Map.of(CommodityId.parse("grain"), 1L),
              Map.of(),
              List.of(
                  new io.mosire.simos.economy.model.ClassSlot(
                      io.mosire.simos.economy.api.id.SocialClassId.parse("artisan"),
                      "探针阶层",
                      1000)),
              new io.mosire.simos.economy.model.AllocationRule.Split(500, 500),
              null,
              0L,
              Map.of(),
              0L,
              Map.of(),
              new MoneyOutput(new LinkedHashMap<>(Map.of(CurrencyId.parse("silver"), 500L))));
      EconomyData data = EconomyData.empty().withIndustries(Map.of(mint.id(), mint));
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
      System.out.println("  铸币产出率逐值相等 = " + backData.industries().get(mint.id()).moneyOutputPerUnit().equals(mint.moneyOutputPerUnit()));
    } catch (Throwable t) {
      System.out.println("  失败 = " + t.getClass().getName() + ": " + t.getMessage());
      Throwable cause = t.getCause();
      int depth = 0;
      while (cause != null && depth++ < 4) {
        System.out.println("    caused by " + cause.getClass().getName() + ": " + cause.getMessage());
        cause = cause.getCause();
      }
    }
    // C2：Government 也带上（W5 改了 Government 形状：mintScalePerCycle / issuable）
    try {
      EconomyData data = EconomyData.empty();
      EconomyCodec codec = new EconomyCodec();
      String json =
          codec.encodeSnapshot(
              new EconomySnapshot(
                  new StateRef(new BranchId("main"), new RevisionId(1L)), SimosTimestamp.of(0L), data));
      System.out.println("  空 EconomyData 往返成功 = " + codec.decodeSnapshot(json) != null);
    } catch (Throwable t) {
      System.out.println("  空 EconomyData 往返失败 = " + t.getClass().getName() + ": " + t.getMessage());
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

  // ───────────────────────── E：Government / Industry 单点往返 ─────────────────────────

  static void partE() throws Exception {
    System.out.println();
    System.out.println("=== PART E: Government 组件（W5 改了它的形状；属性集已由 SCOPE A 的表覆盖）");
    System.out.println(
        "  Government components = "
            + java.util.Arrays.toString(
                java.util.Arrays.stream(Government.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toArray()));
    System.out.println("  Government 含非组件 bean getter = " + hasExtraBeanGetter(Government.class));
  }

  /** 只读判定：该类型有没有"会写出去的非组件 bean 形状无参方法"。 */
  static boolean hasExtraBeanGetter(Class<?> c) {
    return BREAKERS.contains(c);
  }
}
