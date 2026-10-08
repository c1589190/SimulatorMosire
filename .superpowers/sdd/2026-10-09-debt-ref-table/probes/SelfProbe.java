package io.mosire.simos.app;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.migrate.DebtReferenceReconciler;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 2026-10-09 选项 A 的**自证探针**（/tmp，不进仓库；只在「改动后」classpath 上跑）。
 *
 * <pre>
 * rt    往返：apply(between(base,target),base).equals(target) + encode/decode 幂等（快照与变更集两条）
 * guard 守卫：悬空引用仍具名抛（可独立调用的守卫 + 构造期可达的标记位守卫 + 对账的孤儿合同守卫）
 * legacy 旧档：缺 householdDebtRefs 组件的旧变更集 ⇒ Unchanged，且引用表由合同表重建
 * </pre>
 */
public final class SelfProbe {

  private static final HouseholdId PEASANT = HouseholdIds.ofSeed(new HexCoord(0, 0), res("rural"), sc("poor_peasant"));
  private static final HouseholdId LANDLORD = HouseholdIds.ofSeed(new HexCoord(0, 0), res("rural"), sc("landlord"));
  private static final IndustryId FARM = new IndustryId("farm");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final DebtTerms TERMS = DebtTerms.legacyDefault();
  private static final DebtUnit UNIT = DebtUnit.commodity(GRAIN);

  private static int failures = 0;

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "rt";
    switch (mode) {
      case "rt" -> roundTrip();
      case "guard" -> guard();
      case "legacy" -> legacyChangeSet();
      default -> throw new IllegalArgumentException("未知 mode: " + mode);
    }
    System.out.println("\n== SelfProbe mode=" + mode + " 失败 " + failures + " 项 ==");
    if (failures > 0) {
      System.exit(1);
    }
  }

  // ── ① 往返 ────────────────────────────────────────────────────────────────────────────

  private static void roundTrip() throws Exception {
    section("1. 引用键自身的规范串往返");
    HouseholdDebtReference key = new HouseholdDebtReference(PEASANT, contractId(PEASANT, LANDLORD, 100L));
    check("toString", key.toString().equals(PEASANT.value() + "@" + contractId(PEASANT, LANDLORD, 100L).value()), key);
    check("parse(toString) == 原键", HouseholdDebtReference.parse(key.toString()).equals(key), key);

    section("2. 状态往返：apply(between(base,target),base).equals(target)");
    EconomyData base = world(Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)), Map.of());
    // target：① 两户的 cycleNaturalNeedMilli/naturalNeeds 变（"每天都会变"的那一维）；
    //         ② 新增一条合同 ⇒ 引用表多**恰好一对**。
    DebtContract contract = contract(PEASANT, LANDLORD, 100L);
    EconomyData target =
        base.withHouseholdEconomies(
                Map.of(
                    PEASANT, row(PEASANT, 6L),
                    LANDLORD, row(LANDLORD, 0L)))
            .withDebtContracts(Map.of(contract.id(), contract));
    EconomyChangeSet cs = EconomyChangeSet.between(base, target);
    check("classes 非 Unchanged（日变动那一维在变更集里）", cs.classes().changed(), cs.classes());
    check("householdDebtRefs 变了（新增一对）", cs.householdDebtRefs().changed(), cs.householdDebtRefs());
    check("引用差恰一对", refCount(cs) == 1, refCount(cs));
    check(
        "往返：apply(between,base).equals(target)",
        EconomyChangeSet.apply(cs, base).equals(target),
        "n/a");
    check(
        "target 的引用表 = 合同表派生（1 对）",
        target.householdDebtRefs().equals(Map.of(new HouseholdDebtReference(PEASANT, contract.id()), Boolean.TRUE)),
        target.householdDebtRefs());

    section("3. 只有日变动时：引用组件 Unchanged（这才是本次优化的目标形态）");
    EconomyData day2 = target.withHouseholdEconomies(Map.of(PEASANT, row(PEASANT, 12L), LANDLORD, row(LANDLORD, 0L)));
    EconomyChangeSet daily = EconomyChangeSet.between(target, day2);
    check("引用组件 Unchanged（0 字节引用陪跑）", !daily.householdDebtRefs().changed(), daily.householdDebtRefs());
    check("日变更集往返", EconomyChangeSet.apply(daily, target).equals(day2), "n/a");

    section("4. encode/decode 幂等（快照 + 变更集两条）");
    EconomyCodec codec = new EconomyCodec();
    SimosTimestamp ts = SimosTimestamp.of(7L);
    StateRef ref = new StateRef(new io.mosire.simos.util.state.BranchId("main"), new io.mosire.simos.util.state.RevisionId(7L));
    Snapshot snapshot = new EconomySnapshot(ref, ts, target);
    String snapshotJson = codec.encodeSnapshot(snapshot);
    Snapshot decoded = codec.decodeSnapshot(snapshotJson);
    check("快照 decode(encode(x)) 逐值相等", decoded.equals(snapshot), "n/a");
    check(
        "快照二次编码字节相同",
        codec.encodeSnapshot(decoded).equals(snapshotJson),
        "len=" + snapshotJson.length());
    check(
        "快照里出现新组件键",
        snapshotJson.contains("\"householdDebtRefs\""),
        "len=" + snapshotJson.length());

    String csJson = codec.encodeChangeSet(cs);
    EconomyChangeSet decodedCs = (EconomyChangeSet) codec.decodeChangeSet(csJson);
    check("变更集 decode(encode(x)) 可施加且相等", EconomyChangeSet.apply(decodedCs, base).equals(target), "n/a");
    check("变更集二次编码字节相同", codec.encodeChangeSet(decodedCs).equals(csJson), "len=" + csJson.length());

    section("5. 引用表键序（保序）稳定");
    EconomyData three =
        base.withHouseholdEconomies(
                Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)))
            .withDebtContracts(contractsOf(3));
    List<String> order = new ArrayList<>();
    three.householdDebtRefs().keySet().forEach(reference -> order.add(reference.toString()));
    List<String> expected = new ArrayList<>(three.householdDebtRefs().keySet().stream().map(Object::toString).toList());
    expected.sort(String::compareTo);
    List<String> byHouseholdThenId = new ArrayList<>();
    for (String household : List.of(PEASANT.value(), LANDLORD.value())) {
      List<String> mine = new ArrayList<>();
      for (String keyText : expected) {
        if (keyText.startsWith(household + "@")) {
          mine.add(keyText);
        }
      }
      byHouseholdThenId.addAll(mine);
    }
    check("键数 = 3", order.size() == 3, order);
    check("键序 = classes 键序 × 合同 id 升序", order.equals(byHouseholdThenId), order + " vs " + byHouseholdThenId);
    EconomyData again =
        base.withHouseholdEconomies(
                Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)))
            .withDebtContracts(contractsOf(3));
    check("同一份状态两次构造逐值相等（可重放）", three.equals(again), "n/a");
  }

  private static long refCount(EconomyChangeSet cs) {
    long n = 0;
    if (cs.householdDebtRefs() instanceof FieldDelta.Upsert<?> upsert) {
      n += upsert.entries().size();
    }
    if (cs.householdDebtRefs() instanceof FieldDelta.Patch<?> patch) {
      n += patch.upserts().entries().size();
    }
    return n;
  }

  // ── ② 守卫 ────────────────────────────────────────────────────────────────────────────

  private static void guard() throws Exception {
    section("1. 悬空引用 ⇒ 具名抛（守卫可独立调用：拆表不许把关卡拆松）");
    DebtContract contract = contract(PEASANT, LANDLORD, 100L);
    Map<DebtContractId, DebtContract> contracts = Map.of(contract.id(), contract);
    Map<HouseholdId, HouseholdEconomy> classes = Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L));
    DebtContractId ghost = new DebtContractId("debtc-deadbeef");
    Map<HouseholdDebtReference, Boolean> dangling =
        Map.of(new HouseholdDebtReference(PEASANT, ghost), Boolean.TRUE);
    IllegalArgumentException error = expectIae(
        () -> DebtReferenceReconciler.requireReferencesResolvable(contracts, classes, dangling),
        "householdDebtRefs 引用了不存在的债务合同");
    check("消息具名（含悬空引用两侧）", error.getMessage().contains(ghost.value()) && error.getMessage().contains(PEASANT.value()), error.getMessage());

    section("2. 对账的孤儿合同守卫（合同存在、债务人行不存在）⇒ 具名抛");
    HouseholdId ghostHouse = new HouseholdId("hh-ghost");
    DebtContractId orphanId = DebtContractId.idOf(ghostHouse, LANDLORD, UNIT, TERMS);
    Map<DebtContractId, DebtContract> orphans =
        Map.of(orphanId, contract(ghostHouse, LANDLORD, 50L));
    expectIae(
        () -> DebtReferenceReconciler.reconcile(orphans, classes, Map.of()),
        "债务引用对账失败");

    section("3. 构造期可达的守卫：标记位必须是 TRUE（false/null 同一条引用两种拼法 ⇒ 拒）");
    Map<HouseholdDebtReference, Boolean> falseMarker = new LinkedHashMap<>();
    falseMarker.put(new HouseholdDebtReference(PEASANT, contract.id()), Boolean.FALSE);
    IllegalArgumentException markerError =
        expectIae(
            () ->
                construct(
                    Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)),
                    Map.of(contract.id(), contract),
                    falseMarker),
            "householdDebtRefs 的值只允许标记位 TRUE");
    check("消息具名（含键与值）", markerError.getMessage().contains("TRUE"), markerError.getMessage());

    section("4. 对照：合法引用放行（否则上面可能是「一律拒」）");
    Map<HouseholdDebtReference, Boolean> good = new LinkedHashMap<>();
    good.put(new HouseholdDebtReference(PEASANT, contract.id()), Boolean.TRUE);
    EconomyData ok = construct(Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)), contracts, good);
    check("合法状态构造成功且引用保留", ok.householdDebtRefs().size() == 1, ok.householdDebtRefs());

    section("5. 对账修复语义（陈旧引用被清掉，不抛）——与改前逐条相同");
    Map<HouseholdDebtReference, Boolean> stale = new LinkedHashMap<>();
    stale.put(new HouseholdDebtReference(PEASANT, ghost), Boolean.TRUE);
    EconomyData repaired =
        construct(Map.of(PEASANT, row(PEASANT, 0L)), Map.of(), stale);
    check("合同表为空 ⇒ 陈旧引用被对账清掉（不再抛）", repaired.householdDebtRefs().isEmpty(), repaired.householdDebtRefs());
  }

  // ── ③ 旧档：缺 householdDebtRefs 组件的旧变更集 ─────────────────────────────────────────

  private static void legacyChangeSet() throws Exception {
    section("1. 旧变更集（无 householdDebtRefs 键）⇒ Unchanged，且 apply 后引用表由合同表重建");
    EconomyCodec codec = new EconomyCodec();
    EconomyData base = world(Map.of(PEASANT, row(PEASANT, 0L), LANDLORD, row(LANDLORD, 0L)), Map.of());
    DebtContract contract = contract(PEASANT, LANDLORD, 100L);
    EconomyData target =
        base.withDebtContracts(Map.of(contract.id(), contract))
            .withHouseholdEconomies(Map.of(PEASANT, row(PEASANT, 6L), LANDLORD, row(LANDLORD, 0L)));
    String json = codec.encodeChangeSet(EconomyChangeSet.between(base, target));
    // 模拟旧档：把新组件那一节整段去掉（旧字节里根本没有它）。
    String legacyJson = dropComponent(json, "householdDebtRefs");
    check("旧变更集确实不含该组件键", !legacyJson.contains("householdDebtRefs"), "len=" + legacyJson.length());
    EconomyChangeSet decoded = (EconomyChangeSet) codec.decodeChangeSet(legacyJson);
    check("缺键 ⇒ Unchanged（不是抛、也不是 NPE）", !decoded.householdDebtRefs().changed(), decoded.householdDebtRefs());
    EconomyData applied = EconomyChangeSet.apply(decoded, base);
    check("apply 后引用表由合同表重建（1 对）", applied.householdDebtRefs().size() == 1, applied.householdDebtRefs());

    section("2. 旧快照（无该组件键）⇒ 空表 ⇒ 构造期由合同表重建");
    String snapshotJson = codec.encodeSnapshot(new EconomySnapshot(ref(3), SimosTimestamp.of(3L), target));
    String legacySnapshot = dropComponent(snapshotJson, "householdDebtRefs");
    check("旧快照确实不含该组件键", !legacySnapshot.contains("householdDebtRefs"), "len=" + legacySnapshot.length());
    Snapshot decodedSnapshot = codec.decodeSnapshot(legacySnapshot);
    EconomyData legacyData = ((EconomySnapshot) decodedSnapshot).data();
    check("旧快照读回 ⇒ 引用表被重建（1 对）", legacyData.householdDebtRefs().size() == 1, legacyData.householdDebtRefs());
    check(
        "旧快照读回 ⇒ 逐值等于新档（除 ref 组件同形）",
        legacyData.householdDebtRefs().equals(target.householdDebtRefs()),
        "n/a");

    section("3. 旧档行内 classes[].debts 被摘掉（严格 mapper 不再炸）");
    String withRowDebts = snapshotJson.replaceFirst("\"cycleNaturalNeedMilli\"", "\"debts\":[],\"cycleNaturalNeedMilli\"");
    check("夹具确实注入了旧键 debts", withRowDebts.contains("\"debts\":[]"), "n/a");
    Snapshot legacyRow = codec.decodeSnapshot(withRowDebts);
    check(
        "旧键被摘掉后仍能读回，且引用表不变",
        ((EconomySnapshot) legacyRow).data().householdDebtRefs().equals(target.householdDebtRefs()),
        "n/a");
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────────

  private static EconomyData world(Map<HouseholdId, HouseholdEconomy> classes, Map<DebtContractId, DebtContract> contracts) {
    EconomyData base = EconomyData.empty().withHouseholdEconomies(classes);
    return contracts.isEmpty() ? base : base.withDebtContracts(contracts);
  }

  private static EconomyData base(Map<HouseholdId, HouseholdEconomy> classes) {
    return EconomyData.empty().withHouseholdEconomies(classes);
  }

  /** 直接走规范构造器（把引用表当参数传进去，用于打守卫）。 */
  private static EconomyData construct(
      Map<HouseholdId, HouseholdEconomy> classes,
      Map<DebtContractId, DebtContract> contracts,
      Map<HouseholdDebtReference, Boolean> refs) {
    EconomyData base = world(classes, contracts);
    return new EconomyData(
        base.meta(),
        base.industries(),
        base.classes(),
        base.debtContracts(),
        base.flows(),
        base.allocations(),
        base.relations(),
        base.markets(),
        base.shipments(),
        base.assetShares(),
        base.operatorConditions(),
        base.units(),
        base.demands(),
        base.candidates(),
        base.modes(),
        base.classStructures(),
        base.classPositions(),
        base.classStandings(),
        base.productionOrganizations(),
        base.assetRules(),
        base.governments(),
        base.moneyIssuances(),
        base.pledges(),
        base.liquidationPolicies(),
        base.crisisSignals(),
        base.modeTransitions(),
        base.classShares(),
        base.merchantFirms(),
        base.periodicAdjustments(),
        base.outputQuantityOverrides(),
        base.productionEfficiency(),
        base.currencies(),
        base.moneyInstruments(),
        base.marketZones(),
        refs);
  }

  private static HouseholdEconomy row(HouseholdId id, long cycleNaturalNeedMilli) {
    return new HouseholdEconomy(
        id,
        id.equals(PEASANT) ? key("0_0|rural|poor_peasant") : key("0_0|rural|landlord"),
        10L,
        100L,
        800,
        0L,
        Map.of(GRAIN, 30L),
        Map.of(GRAIN, 30L),
        cycleNaturalNeedMilli);
  }

  private static io.mosire.simos.economy.api.cohort.CohortKey key(String text) {
    return io.mosire.simos.economy.api.cohort.CohortKey.parse(text);
  }

  private static io.mosire.simos.economy.api.cohort.ResidenceKind res(String value) {
    return io.mosire.simos.economy.api.cohort.ResidenceKind.parse(value);
  }

  private static io.mosire.simos.economy.api.id.SocialClassId sc(String value) {
    return io.mosire.simos.economy.api.id.SocialClassId.parse(value);
  }

  private static DebtContractId contractId(HouseholdId debtor, HouseholdId creditor, long principal) {
    return DebtContractId.idOf(debtor, creditor, UNIT, TERMS);
  }

  private static DebtContract contract(HouseholdId debtor, HouseholdId creditor, long principal) {
    return new DebtContract(
        DebtContractId.idOf(debtor, creditor, UNIT, TERMS),
        debtor,
        creditor,
        UNIT,
        TERMS,
        principal,
        0L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  /** {@code count} 条不同的合同（同一四元组的 id 是纯函数 ⇒ 用不同利率造多条），返回表按键序。 */
  private static Map<DebtContractId, DebtContract> contractsOf(int count) {
    Map<DebtContractId, DebtContract> out = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      DebtTerms terms = DebtTerms.legacyDefault(100 + i);
      DebtContract other =
          new DebtContract(
              DebtContractId.idOf(PEASANT, LANDLORD, UNIT, terms),
              PEASANT,
              LANDLORD,
              UNIT,
              terms,
              20L + i,
              0L,
              OptionalLong.empty(),
              OptionalLong.empty(),
              DebtStatus.NORMAL);
      out.put(other.id(), other);
    }
    return out;
  }

  private static StateRef ref(long revision) {
    return new StateRef(new io.mosire.simos.util.state.BranchId("main"), new io.mosire.simos.util.state.RevisionId(revision));
  }

  /** 把某个顶层组件整段删掉（严格模拟"旧字节里没有这个键"）。 */
  private static String dropComponent(String json, String component) {
    String key = "\"" + component + "\":";
    int at = json.indexOf(key);
    if (at < 0) {
      return json;
    }
    int i = at + key.length();
    int depth = 0;
    boolean inString = false;
    boolean escape = false;
    int end = i;
    while (end < json.length()) {
      char ch = json.charAt(end);
      if (inString) {
        if (escape) {
          escape = false;
        } else if (ch == '\\') {
          escape = true;
        } else if (ch == '"') {
          inString = false;
        }
      } else if (ch == '"') {
        inString = true;
      } else if (ch == '{' || ch == '[') {
        depth++;
      } else if (ch == '}' || ch == ']') {
        depth--;
        if (depth == 0) {
          end++;
          break;
        }
      }
      end++;
    }
    String head = json.substring(0, at);
    String tail = json.substring(end);
    if (head.endsWith(",")) {
      head = head.substring(0, head.length() - 1);
    } else if (tail.startsWith(",")) {
      tail = tail.substring(1);
    }
    return head + tail;
  }

  // ── 小件 ──────────────────────────────────────────────────────────────────────────────

  private static IllegalArgumentException expectIae(Runnable action, String expectedFragment) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      System.out.println("   ✓ 具名抛: " + e.getMessage());
      if (!"n/a".equals(expectedFragment) && !e.getMessage().contains(expectedFragment)) {
        failures++;
        System.out.println("   ✗ 消息里没有期望片段: " + expectedFragment);
      }
      return e;
    } catch (RuntimeException e) {
      failures++;
      System.out.println("   ✗ 抛的不是 IllegalArgumentException: " + e);
      return new IllegalArgumentException(e);
    }
    failures++;
    System.out.println("   ✗ 没有抛（fail-closed 失效）");
    return new IllegalArgumentException("no-throw");
  }

  private static void check(String what, boolean ok, Object detail) {
    if (ok) {
      System.out.println("   ✓ " + what + (detail == null ? "" : " ⇒ " + brief(detail)));
    } else {
      failures++;
      System.out.println("   ✗ " + what + " ⇒ " + brief(detail));
    }
  }

  private static String brief(Object value) {
    String text = String.valueOf(value);
    return text.length() > 300 ? text.substring(0, 300) + "…" : text;
  }

  private static void section(String title) {
    System.out.println("\n── " + title + " ──");
  }

  private SelfProbe() {}
}
