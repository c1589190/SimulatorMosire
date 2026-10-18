package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyPayloadSupport.actorRef;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.assetCommodityMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.assetKind;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.assetMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.commodityMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.currencyMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalText;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 产业/生产单元/资产份额的机械解析 helper（P0.5-C2）。 */
final class EconomyProductionPayloads {

  // ── 实物资产份额（R3B.1）────────────────────────────────────────────────────────────

  /** 资产份额的必填地点：不做 entry/industry 反推，缺键/空白/非法一律 {@code asset-location-missing}。 */
  static ProductionLocation ownershipStakeLocation(JsonNode node) {
    JsonNode value = node.get("location");
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(
          "asset-location-missing: 资产份额缺少必填 location（规范 <q>_<r>）: " + node);
    }
    return OwnershipStake.requireSpecifiedLocation(ProductionLocation.parse(value.asText()));
  }

  /**
   * ★★ <b>一条实物资产份额载荷 → {@link OwnershipStake}</b>。
   *
   * <p>★ <b>新形状</b>：{@code {industry, owner:{kind,id}, operator:{kind,id}, asset, quantity, kind,
   * location}} —— {@code owner} 与 {@code operator} 是两件事，允许不等（租佃/委托）；{@code location} 必填且必须是规范
   * {@code <q>_<r>}（D-032 D4 R-15）。
   *
   * <p>★ <b>旧形状（{@code useRights} 数组）</b>：{@code {activity, holder, asset, quantity, kind,
   * location}} ⇒ 一对一翻译 {@code holder ⇒ owner=operator}、{@code activity ⇒ industry}；不拆地主/佃户/多
   * unit（R3B.1 边界）。地点同样必填， 不从 activity/holder 猜。
   *
   * <p>★ <b>id 一律不信任载荷、由确定性序号生成</b>：{@code LAND} 走 {@link OwnershipStake#landBlockIdOf(IndustryId,
   * ProductionLocation, long)}（id 只含 industry/asset/location/sequence， 不含
   * owner/operator/kind）；非土地资产走原 {@link OwnershipStake#idOf(IndustryId, AssetKind, ActorRef,
   * ActorRef, AssetRightKind, ProductionLocation, long)}。同一份载荷重放得到同一批 id，禁止随机数/时间戳；旧档里已落盘的 {@code
   * use-…} id 不走本方法（那条路在 {@code EconomyCodec} 里原样保留）。
   */
  static void addOwnershipStake(
      Map<AssetShareId, OwnershipStake> out,
      Map<String, Long> sequences,
      JsonNode node,
      boolean legacy) {
    IndustryId industry;
    ActorRef owner;
    ActorRef operator;
    if (legacy) {
      industry = IndustryId.parse(requireText(node, "activity"));
      JsonNode holderNode = optionalObject(node, "holder");
      if (holderNode == null) {
        throw new IllegalArgumentException("旧使用权（useRights）的字段 holder 必须是对象: " + node);
      }
      owner = actorRef(holderNode);
      operator = owner;
    } else {
      industry = IndustryId.parse(requireText(node, "industry"));
      JsonNode ownerNode = optionalObject(node, "owner");
      if (ownerNode == null) {
        throw new IllegalArgumentException("资产份额的字段 owner 必须是对象: " + node);
      }
      JsonNode operatorNode = optionalObject(node, "operator");
      if (operatorNode == null) {
        throw new IllegalArgumentException("资产份额的字段 operator 必须是对象: " + node);
      }
      owner = actorRef(ownerNode);
      operator = actorRef(operatorNode);
    }
    AssetKind asset = assetKind(requireText(node, "asset"), "asset");
    ProductionLocation location = ownershipStakeLocation(node);
    long quantity = requireLong(node, "quantity");
    AssetRightKind kind;
    try {
      kind = AssetRightKind.valueOf(requireText(node, "kind"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("资产份额的 kind 不是 OWNED/TENANCY/COMMUNAL: " + node, e);
    }
    String sequenceKey =
        asset == AssetKind.LAND
            ? industry + "|" + asset + "|" + location.value()
            : industry
                + "|"
                + asset
                + "|"
                + owner
                + "|"
                + operator
                + "|"
                + kind
                + "|"
                + location.value();
    long sequence = sequences.getOrDefault(sequenceKey, 0L);
    sequences.put(sequenceKey, sequence + 1L);
    AssetShareId shareId =
        asset == AssetKind.LAND
            ? OwnershipStake.landBlockIdOf(industry, location, sequence)
            : OwnershipStake.idOf(industry, asset, owner, operator, kind, location, sequence);
    OwnershipStake share =
        new OwnershipStake(shareId, industry, asset, owner, operator, quantity, kind, location);
    if (out.putIfAbsent(share.id(), share) != null) {
      throw new IllegalArgumentException("同一份载荷里资产份额重复: " + share.id());
    }
  }

  // ── 产业 / 阶层行 ────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>R0（S0.1）：entry 的格必须与 {@code industry.id} 里携带的格逐字一致</b>。
   *
   * <p>★★ <b>为什么必须在这里判死</b>：{@code entries[]} 是命令的<b>作用域</b>（{@code CommandTargets} 逐格判越权）， 而产业按
   * {@code id} 里的格参与结算/读口。两者不一致时，一条只被授权动 {@code (0,0)} 的播种命令会把 {@code farm@9_9} 的产业写进世界 ——
   * 命令目标与真实写入面错位（这正是 R0 要堵的旁路）。
   *
   * <p>★ <b>id 不带格键也拒</b>：那种 id 无法与任何 entry 对账（{@link IndustryHexKeys#hexKeyOf} 返回空），
   * 放行等于把这条守卫留成"只要不写 {@code @} 就能绕过"。★ 旧档（非播种载荷）不受影响：它们由 {@code EconomyCodec} 读入，不经本方法。
   */
  /** 该格 `plans[]` 里指名某产业 id 的方案节点（没有 ⇒ null）；它承载 unit 的 `relation` 声明。 */
  static JsonNode planNodeOf(List<JsonNode> planNodes, IndustryId id) {
    for (JsonNode node : planNodes) {
      if (id.value().equals(node.path("id").asText())) {
        return node;
      }
    }
    return null;
  }

  static void requireIndustryHexMatchesEntry(HexCoord entryHex, IndustryId id) {
    String entryKey = IndustryHexKeys.hexKey(entryHex.q(), entryHex.r());
    Optional<String> industryKey = IndustryHexKeys.hexKeyOf(id);
    if (industryKey.isEmpty()) {
      throw new IllegalArgumentException(
          "产业 id 必须携带格键（<kind>@<q>_<r>）才能与它所在的 entry 对账: industry.id="
              + id
              + "，entry 格="
              + entryKey);
    }
    if (!industryKey.get().equals(entryKey)) {
      throw new IllegalArgumentException(
          "产业 id 的格与它所在的 entry 格不一致（拒绝该播种载荷）: industry.id="
              + id
              + " ⇒ 格 "
              + industryKey.get()
              + "；entry=(q="
              + entryHex.q()
              + ",r="
              + entryHex.r()
              + ") ⇒ 格 "
              + entryKey);
    }
  }

  /**
   * ★★ <b>R3B.2 的 IndustryDefinitionView 载荷 = 纯技术模板</b>：只读 {@code
   * id/name/regime/cycleDays/capacityPerUnit/
   * dailyInputPerUnit/dailyLaborPerUnit/laborPerUnit/outputPerUnit/cycleInputPerUnit/allocation/slots}。
   *
   * <p>★★ <b>B5（§13.15.3 裁定十九）</b>：旧载荷的实例字段（{@code
   * operator/capacity/progressDays/cycleLaborMilli/cycleInputUsedMilli}）**不再被解析** —— `IndustrySpec`
   * 与旧形状合成路径已删（economy 载荷不再自带产业定义）。本方法只服务 codec 的旧档 reshape（保留， Alpha：旧档 fail-closed）。
   */
  static IndustryDefinitionView industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    long cycleDays = requireLong(node, "cycleDays");
    // ★★ B2c（裁定八）：方案定义新增的两组件（buildDays / acceptedRightKinds）—— 与 production 的
    //   `ProductionDefinitionPayloads.industry` 逐键同形、缺省同值（缺键 ⇒ 0 / 空集），免得同一份载荷节点
    //   在两侧读成两个形状（economy 侧这份只是阶段 2 的临时兼容组件，见 EconomyData 类注）。
    long buildDays = optionalLong(node, "buildDays", 0L);
    if (buildDays < 0L) {
      throw new IllegalArgumentException("字段 buildDays 不得为负: " + buildDays);
    }
    Set<AssetRightKind> acceptedRightKinds = new LinkedHashSet<>();
    JsonNode rightKindsNode = node.get("acceptedRightKinds");
    if (rightKindsNode != null && !rightKindsNode.isNull()) {
      if (!rightKindsNode.isArray()) {
        throw new IllegalArgumentException("字段 acceptedRightKinds 必须是数组: " + rightKindsNode);
      }
      for (JsonNode element : rightKindsNode) {
        if (!element.isTextual() || element.asText().isBlank()) {
          throw new IllegalArgumentException("字段 acceptedRightKinds 的元素必须是文本: " + element);
        }
        acceptedRightKinds.add(AssetRightKind.parse(element.asText()));
      }
    }
    // ★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    Map<AssetKind, Long> capacityPerUnit =
        assetMap(optionalObject(node, "capacityPerUnit"), "capacityPerUnit");
    long laborPerUnit = optionalLong(node, "laborPerUnit", 0L);
    Map<AssetKind, Map<CommodityId, Long>> dailyInput =
        assetCommodityMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    // ★★ W5：铸币产出率（可选；缺键/空表 = 本产业不铸币 ⇒ 逐值退回 W5 之前，I-C2）。
    Map<CurrencyId, Long> moneyOutput =
        currencyMap(optionalObject(node, "moneyOutputPerUnit"), "moneyOutputPerUnit");
    Map<AssetKind, Map<CommodityId, Long>> cycleInput =
        assetCommodityMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    List<ClassSlot> slots = new ArrayList<>();
    for (JsonNode slot : requireArray(node, "slots")) {
      slots.add(
          new ClassSlot(
              SocialClassId.parse(requireText(slot, "id")),
              requireText(slot, "name"),
              requireInt(slot, "laborParticipationPerMille")));
    }
    JsonNode allocation = node.get("allocation");
    if (allocation == null || !allocation.isObject()) {
      throw new IllegalArgumentException("字段 allocation 必须是对象: " + node);
    }
    AllocationRule rule;
    try {
      rule = EconomyPayloadSupport.mapper().convertValue(allocation, AllocationRule.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("allocation 形状不对（见 AllocationRule）: " + allocation, e);
    }
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（WageFirst 在播种期拒）。
    if (!(rule instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException("v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + rule);
    }
    return new IndustryDefinitionView(
        id,
        name,
        regime,
        Optional.empty(),
        cycleDays,
        buildDays,
        capacityPerUnit,
        dailyInput,
        dailyLabor,
        laborPerUnit,
        output,
        cycleInput,
        slots,
        rule,
        new MoneyOutput(moneyOutput),
        io.mosire.simos.production.contract.ProductionExperienceUse.NONE,
        List.of(),
        acceptedRightKinds);
  }

  /**
   * ★★ <b>Z1a：{@code economy.UpsertIndustry} 的产业节点解析唯一入口</b> —— 与 {@code economy.Seed} 的 {@code
   * industries[]} 节点<b>同一份字段解析</b>（委托 {@link #industry(JsonNode)}）。命令 handler 不另写第二套字段读取；
   * 构造期守卫（capacityPerUnit 非空且为正 / cycleDays / slots / 逐值非负 / allocation）仍由 {@link
   * IndustryDefinitionView} 与 上面的 {@link #industry(JsonNode)} 判。
   */
  static IndustryDefinitionView parseIndustryNode(JsonNode node) {
    return industry(node);
  }

  // ★★★ B5（§13.15.3 裁定十九）：原 `IndustrySpec` record / `industrySpec(JsonNode)` / `specOf(...)`
  //   三个成员**整段删除** —— 它们只服务"旧形状 industry 节点 ⇒ 合成 unit + 整额 OWNED 份额"那条已退役
  //   的路径（economy 载荷不再自带产业定义，旧档 fail-closed 是 Alpha 允许的）。
  /**
   * ★★ <b>R3B.2：一个 unit 载荷节点 → {@link ProductionProcess}</b>：{@code {id?, industry, operator?,
   * modeKey?, progressDays?, cycleInputUsedMilli?}}。 {@code id} 缺省 = {@link
   * ProductionUnitId#idOf}；{@code operator} 缺省 = 该产业 regime 的默认经营者； {@code modeKey} 缺省 = {@code
   * industry.id().value()}（旧档口径）。
   *
   * <p>★★ <b>机械修正（D-032 A 批续做）</b>：{@link ProductionProcess} 的"本周期劳动"已从<b>标量</b> {@code
   * cycleLaborMilli} 改成<b>逐家户台账</b> {@code Map<HouseholdId, CycleContribution>}（复核裁决 §5：
   * 整周期逐家户贡献及其当时生效效率必须可重建，标量表达不出来）。载荷里那份旧的 {@code cycleLaborMilli} 标量<b>无处安放</b>（它没有家户归属，编不出合法的
   * {@link io.mosire.simos.production.contract.CycleContribution}）。
   *
   * <p>★ 播种侧的实际写法：{@code EconomySeeder} 播的 {@code progressDays=0 / cycleLaborMilli=0}（周期刚起、 尚无出工）⇒
   * 逐家户台账恒为<b>空表</b>，"丢掉那个恒 0 的标量"与"读成空台账"逐值等价。 ★ 载荷键 {@code cycleLaborMilli}
   * 仍在（旧载荷/夹具写它不会炸），只是不再进状态；B 批把载荷面一并收口。 ★ 旧档兼容整体作废（Alpha 不兼容旧档）⇒ 不写"标量 →
   * 台账"的反解（那需要凭配额猜家户归属，是伪造进度）。
   */
  static ProductionProcess unit(JsonNode node, IndustryDefinitionView industry, ActorRef operator) {
    String idText = optionalText(node, "id").orElse(null);
    ProductionUnitId id =
        idText == null
            ? ProductionUnitId.idOf(industry.id(), operator)
            : ProductionUnitId.parse(idText);
    String modeKey = optionalText(node, "modeKey").orElse(industry.id().value());
    long progressDays = optionalLong(node, "progressDays", 0L);
    Map<CommodityId, Long> cycleInputUsed =
        commodityMap(optionalObject(node, "cycleInputUsedMilli"), "cycleInputUsedMilli");
    return new ProductionProcess(
        id,
        industry.id(),
        // ★★★ D-032 B3（§13.8 裁定二第 1/3/4 条）：载荷解析是**语义边界** ⇒ 解不出地点即具名拒绝
        //   （`location-unknown`），不再落到 UNSPECIFIED 占位（旧 `ofPlanOrUnspecified` 已删除）。
        //   ★ 经济载荷的 unit 节点按格登记（industry id 自带格段），且下面还要与 production 的权威实例逐值核对。
        io.mosire.simos.production.contract.ProductionLocation.ofPlan(industry.id())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "location-unknown: economy 载荷的 unit 指名的产业 id 不带格段，实例地点无从落定"
                            + "（不写 UNSPECIFIED 占位）: unit="
                            + id.value()
                            + " industry="
                            + industry.id().value())),
        operator,
        modeKey,
        progressDays,
        Map.of(),
        cycleInputUsed);
  }

  // ★★★ B5（§13.15.3 裁定十九）：原 `hasShareForIndustry(...)` / `synthesizeOwnedShares(...)` 两个助手
  //   **整段删除** —— 它们只服务已退役的旧形状合成路径（economy 载荷不再自带产业定义；旧档 fail-closed）。
}
