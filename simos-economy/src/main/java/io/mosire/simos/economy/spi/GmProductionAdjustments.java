package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyGmAdjustments.COMMAND;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Change;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Projection;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.UPSERT_CLASS_POSITION;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.UPSERT_PRODUCTION_RELATION;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.classPositionId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.classStructureId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.enumValue;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.hasValue;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.noOp;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.optionalEnumOrExisting;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.parseHouseholdList;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.parseOwnershipStakeList;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.parsePayeeList;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.productionEnterpriseId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.productionModeId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.productionProcessId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.requireExistingField;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.requireObjectNode;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** GM 调整拆分 helper（由 EconomyGmAdjustments 抽取；行为不变）。 */
final class GmProductionAdjustments {

  private GmProductionAdjustments() {}

  // ── P7：阶层结构 / 关系 / 资产规则 / 组织 的 GM 编辑（生产方式定义两个 kind 见下面的 B5 说明）──────────
  //
  // ★★ 全部只改源状态、只走既有 with* 写口（class 结构+位置两表需要同批落值时走
  //   EconomyData.withClassStructuresAndPositions 这个成对写口），最终都过 EconomyChangeSet.between；
  //   派生读数（flows/crisisSignals/classStandings.* 等）仍没有写口，白名单外一律 DERIVED_REJECTION。
  //   ★★★ B5（§13.15.2 裁定十八）：本段四个 kind 的"modeId 必须已存在"判据**读 production 当刻定义权威**
  //     （{@code definitions.mode(modeId)}），不再读已删的 economy `modes` 副本。

  // ★★★ B5（§13.15.2 裁定十八）：原 `upsertProductionMode(...)` 与 `deactivateProductionMode(...)`
  //   两个语义落点**整段删除**（连同它们读的 `EconomyData.modes` 副本、`copyModeReferences` 六类引用者
  //   遍历、以及 `VERSION_ABSENT` / 全部 mode 版本推进判据）。
  //   ★ B12（DEF-T6-4 #2）：`VERSION_ABSENT` 唯一的残留读者 `optionalVersion(...)` 也零调用点，
  //     同批删除 ⇒ 上面这句"连同 VERSION_ABSENT"在本批之后逐字成立（本批删的就是最后两个残留）。
  //   ★ 去核结论（账本 §B5-§2）：economy 的 mode 写腿**只有** `economy.GmAdjust` 这两个 kind，
  //     两者在 main/test/工具 schema/catalog **全是 0 调用点**；世界播种走 `production.Seed` 的
  //     `modes[]`。⇒ 按裁定十八第 ② 条连命令 kind 一起删；能力由 production 既有命令面承接
  //     （`production.UpsertProductionMode` / `production.DeactivateProductionMode`，
  //     唯一语义落点 `ProductionModeUpserts`，装配点 `Shell.java:925/:926`）。
  //   ★ 六类引用者清单没有丢：它是 production 停用命令的判据（`ProductionConsumerPort.modeReferences`
  //     → `AppProductionConsumerPort:123`，B4 已逐类平移）。

  /**
   * {@code upsertClassStructure}：{@code {id,modeId,positions?,defaultSharesPerMille?}}（至少给一项）。
   *
   * <p>语义：{@code positions} 逐项 upsert（同一位置 id 的全局表与<b>所有</b>含它的结构副本同值更新；不删除未列出的位置 —— 删除会牵动
   * classStandings/classShares/enterprises 的引用，本阶段不做）；{@code defaultSharesPerMille} 给到即整体替换（给
   * {@code {}} 可清空），未给保持原值。modeId 必须存在；既有结构的 modeId 不可改（换绑要删除重建）。新结构必须至少给一个 position。
   */
  static Projection upsertClassStructure(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_CLASS_STRUCTURE;
    ClassStructureId id =
        classStructureId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label
              + " 的 modeId 必须已存在（production 当刻定义权威）: "
              + modeId.value()
              + "；请先 production.UpsertProductionMode（其 classStructureId 必须已存在）");
    }
    ClassStructure before = base.classStructures().get(id);
    if (before != null && !before.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 不允许修改结构的 modeId：结构="
              + id.value()
              + "，现有 modeId="
              + before.modeId()
              + "，收到="
              + modeId);
    }
    boolean positionsGiven = hasValue(parameters, "positions");
    boolean sharesGiven =
        parameters.has("defaultSharesPerMille")
            && !parameters.get("defaultSharesPerMille").isNull();
    if (sharesGiven && !parameters.get("defaultSharesPerMille").isObject()) {
      throw new IllegalArgumentException(
          label + " 的 defaultSharesPerMille 必须是对象: " + parameters.get("defaultSharesPerMille"));
    }
    if (before == null && !positionsGiven) {
      throw new IllegalArgumentException(label + " 新建 classStructure 必须给非空 positions");
    }
    if (!positionsGiven && !sharesGiven) {
      throw new IllegalArgumentException(
          label + " 至少需要给 positions 或 defaultSharesPerMille（两者都给 = 结构+份额同批更新）");
    }
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>(base.classStructures());
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>(base.classPositions());
    Map<ClassPositionId, ProductionRole> targetPositions =
        before == null ? new LinkedHashMap<>() : new LinkedHashMap<>(before.positions());
    if (positionsGiven) {
      JsonNode positionNodes = parameters.get("positions");
      if (!positionNodes.isArray()) {
        throw new IllegalArgumentException(label + " 的 positions 必须是数组: " + positionNodes);
      }
      if (before == null && positionNodes.isEmpty()) {
        throw new IllegalArgumentException(label + " 新建 classStructure 必须至少给一个 position");
      }
      Set<ClassPositionId> seen = new LinkedHashSet<>();
      for (JsonNode positionNode : positionNodes) {
        if (!positionNode.isObject()) {
          throw new IllegalArgumentException(label + " 的 positions 每项必须是对象: " + positionNode);
        }
        ProductionRole position = EconomyPayloads.productionRole(positionNode, modeId.value());
        if (!seen.add(position.id())) {
          throw new IllegalArgumentException(
              label + " 的 positions 里位置 id 重复: " + position.id().value());
        }
        if (!position.modeId().equals(modeId)) {
          throw new IllegalArgumentException(
              label
                  + " 的位置 modeId 必须与结构 modeId 一致：位置="
                  + position.id().value()
                  + "，位置 modeId="
                  + position.modeId()
                  + "，结构 modeId="
                  + modeId);
        }
        ProductionRole globalBefore = positions.get(position.id());
        if (globalBefore != null && !globalBefore.modeId().equals(modeId)) {
          throw new IllegalArgumentException(
              label
                  + " 不允许把既有位置改属另一个 mode：位置="
                  + position.id().value()
                  + "，现有 modeId="
                  + globalBefore.modeId()
                  + "，收到="
                  + modeId);
        }
        targetPositions.put(position.id(), position);
        // ★ 同一位置 id 在全局表只有一份值：把含它的每个结构副本都同步到新值（含本结构）。
        List<ClassStructureId> structureIds = new ArrayList<>(structures.keySet());
        for (ClassStructureId structureId : structureIds) {
          ClassStructure structure = structures.get(structureId);
          ProductionRole copy = structure.positions().get(position.id());
          if (copy == null || copy.equals(position)) {
            continue;
          }
          Map<ClassPositionId, ProductionRole> updated = new LinkedHashMap<>(structure.positions());
          updated.put(position.id(), position);
          structures.put(
              structureId,
              new ClassStructure(
                  structure.id(), structure.modeId(), updated, structure.defaultSharesPerMille()));
        }
        if (globalBefore == null || !globalBefore.equals(position)) {
          positions.put(position.id(), position);
        }
      }
    }
    Map<ClassPositionId, Long> shares;
    if (sharesGiven) {
      shares =
          EconomyPayloads.productionRoleShareMap(
              parameters.get("defaultSharesPerMille"), label + ".defaultSharesPerMille");
      for (Map.Entry<ClassPositionId, Long> entry : shares.entrySet()) {
        if (!targetPositions.containsKey(entry.getKey())) {
          throw new IllegalArgumentException(
              label
                  + " 的 defaultSharesPerMille 键必须是 resulting positions 里已有的位置: "
                  + entry.getKey().value());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              label
                  + " 的 defaultSharesPerMille 不得为负："
                  + entry.getKey().value()
                  + " = "
                  + entry.getValue());
        }
      }
    } else {
      shares =
          before == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(before.defaultSharesPerMille());
    }
    ClassStructure after = new ClassStructure(id, modeId, targetPositions, shares);
    structures.put(id, after);
    EconomyData projected = base.withClassStructuresAndPositions(structures, positions);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes = new ArrayList<>();
    for (Map.Entry<ClassPositionId, ProductionRole> entry : positions.entrySet()) {
      ProductionRole old = base.classPositions().get(entry.getKey());
      if (!Objects.equals(old, entry.getValue())) {
        changes.add(new Change("classPositions", entry.getKey().value(), old, entry.getValue()));
      }
    }
    for (Map.Entry<ClassStructureId, ClassStructure> entry : structures.entrySet()) {
      ClassStructure old = base.classStructures().get(entry.getKey());
      if (!Objects.equals(old, entry.getValue())) {
        changes.add(new Change("classStructures", entry.getKey().value(), old, entry.getValue()));
      }
    }
    if (changes.isEmpty()) {
      return noOp(UPSERT_CLASS_STRUCTURE, reason, day, base);
    }
    return new Projection(UPSERT_CLASS_STRUCTURE, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code upsertClassPosition}：{@code {id,modeId,name?,relationToMeans?,laborRole?,surplusRole?,
   * ruleExtensions?,classStructureId?}}。
   *
   * <p>位置已属于某结构 ⇒ 同步更新全局表与<b>所有</b>结构内副本；位置未属于任何结构（或给了新的 classStructureId）⇒ 必须给 classStructureId
   * 以挂进该结构。modeId 必须存在，既有位置的 modeId 不可改。 未给的字段沿用既有值（新建时三个结构维与 name 必填，ruleExtensions 缺省空表）。
   */
  static Projection upsertClassPosition(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_CLASS_POSITION;
    ClassPositionId id =
        classPositionId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
    }
    ProductionRole before = base.classPositions().get(id);
    if (before != null && !before.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 不允许修改既有位置的 modeId：位置="
              + id.value()
              + "，现有 modeId="
              + before.modeId()
              + "，收到="
              + modeId);
    }
    String name =
        hasValue(parameters, "name")
            ? EconomyCommandPayloads.requireText(label, parameters, "name")
            : requireExistingField(label, "name", before == null ? null : before.name());
    ProductionRole.RelationToMeans relationToMeans =
        optionalEnumOrExisting(
            label,
            parameters,
            "relationToMeans",
            before == null ? null : before.relationToMeans(),
            ProductionRole.RelationToMeans.class);
    ProductionRole.LaborRole laborRole =
        optionalEnumOrExisting(
            label,
            parameters,
            "laborRole",
            before == null ? null : before.laborRole(),
            ProductionRole.LaborRole.class);
    ProductionRole.SurplusRole surplusRole =
        optionalEnumOrExisting(
            label,
            parameters,
            "surplusRole",
            before == null ? null : before.surplusRole(),
            ProductionRole.SurplusRole.class);
    Map<String, String> ruleExtensions;
    if (parameters.has("ruleExtensions")) {
      JsonNode node = parameters.get("ruleExtensions");
      if (node == null || node.isNull()) {
        ruleExtensions = Map.of();
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 ruleExtensions 必须是对象: " + node);
        }
        ruleExtensions = EconomyPayloads.stringMap(node, label + ".ruleExtensions");
      }
    } else {
      ruleExtensions = before == null ? Map.of() : before.ruleExtensions();
    }
    ProductionRole after =
        new ProductionRole(
            id, modeId, name, relationToMeans, laborRole, surplusRole, ruleExtensions);

    ClassStructureId targetStructureId = null;
    if (hasValue(parameters, "classStructureId")) {
      targetStructureId =
          classStructureId(
              label, EconomyCommandPayloads.requireText(label, parameters, "classStructureId"));
    }
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>(base.classStructures());
    List<ClassStructureId> containing = new ArrayList<>();
    for (ClassStructure structure : structures.values()) {
      if (structure.positions().containsKey(id)) {
        containing.add(structure.id());
      }
    }
    if (targetStructureId != null) {
      ClassStructure target = structures.get(targetStructureId);
      if (target == null) {
        throw new IllegalArgumentException(
            label + " 的 classStructureId 不存在: " + targetStructureId.value());
      }
      if (!target.modeId().equals(modeId)) {
        throw new IllegalArgumentException(
            label
                + " 的目标结构 modeId 与位置 modeId 不一致：结构="
                + targetStructureId.value()
                + "，结构 modeId="
                + target.modeId()
                + "，位置 modeId="
                + modeId);
      }
      if (!containing.contains(targetStructureId)) {
        containing.add(targetStructureId);
      }
    }
    if (containing.isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的位置不属于任何 ClassStructure，必须给 classStructureId 指明挂进哪个结构: " + id.value());
    }
    for (ClassStructureId structureId : containing) {
      ClassStructure structure = structures.get(structureId);
      Map<ClassPositionId, ProductionRole> updated = new LinkedHashMap<>(structure.positions());
      updated.put(id, after);
      structures.put(
          structureId,
          new ClassStructure(
              structure.id(), structure.modeId(), updated, structure.defaultSharesPerMille()));
    }
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>(base.classPositions());
    positions.put(id, after);
    EconomyData projected = base.withClassStructuresAndPositions(structures, positions);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes = new ArrayList<>();
    if (!Objects.equals(before, after)) {
      changes.add(new Change("classPositions", id.value(), before, after));
    }
    for (ClassStructureId structureId : containing) {
      ClassStructure old = base.classStructures().get(structureId);
      ClassStructure next = structures.get(structureId);
      if (!Objects.equals(old, next)) {
        changes.add(new Change("classStructures", structureId.value(), old, next));
      }
    }
    if (changes.isEmpty()) {
      return noOp(UPSERT_CLASS_POSITION, reason, day, base);
    }
    return new Projection(UPSERT_CLASS_POSITION, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code upsertProductionRelation}：{@code
   * {activity,operator?,inputSupplier?,rules?,residualOwner?, laborSource?}}。activity 必须对应既有
   * unit；operator（给了必须与 {@code unit.operator} 逐值相等，未给取 unit.operator ——
   * 那个值由守卫判死，是唯一合法值）。未给的可选字段沿用既有 relation。
   */
  static Projection upsertProductionRelation(
      EconomyData base,
      Map<ProductionUnitId, ProductionProcess> units,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_RELATION;
    ProductionUnitId activity =
        productionProcessId(
            label, EconomyCommandPayloads.requireText(label, parameters, "activity"));
    ProductionProcess unit = units.get(activity);
    if (unit == null) {
      throw new IllegalArgumentException(label + " 的 activity 必须对应已存在的 unit: " + activity.value());
    }
    ProductionRules before = base.relations().get(activity);
    ActorRef operator =
        hasValue(parameters, "operator")
            ? EconomyCommandPayloads.requireActor(label, parameters, "operator")
            : (before == null ? unit.operator() : before.operator());
    if (!operator.equals(unit.operator())) {
      throw new IllegalArgumentException(
          label
              + " 的 operator 必须与 unit.operator 一致：关系="
              + operator
              + "，unit="
              + unit.operator()
              + "（unit="
              + activity.value()
              + "）");
    }
    Payee inputSupplier;
    if (parameters.has("inputSupplier")) {
      JsonNode node = parameters.get("inputSupplier");
      if (node == null || node.isNull()) {
        inputSupplier = null; // ★ null/缺省语义交给 ProductionRules 构造期缺省（= operator）
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 inputSupplier 必须是对象: " + node);
        }
        inputSupplier = EconomyPayloads.recipient(node, "inputSupplier");
      }
    } else {
      inputSupplier = before == null ? null : before.inputSupplier();
    }
    List<CompensationRule> rules = new ArrayList<>();
    if (parameters.has("rules")) {
      JsonNode rulesNode = parameters.get("rules");
      if (rulesNode == null || !rulesNode.isArray()) {
        throw new IllegalArgumentException(label + " 的 rules 必须是数组: " + rulesNode);
      }
      for (JsonNode ruleNode : rulesNode) {
        if (!ruleNode.isObject()) {
          throw new IllegalArgumentException(label + " 的 rules 每项必须是对象: " + ruleNode);
        }
        rules.add(EconomyPayloads.compensationRule(ruleNode));
      }
    } else if (before != null) {
      rules.addAll(before.rules());
    }
    ActorRef residualOwner =
        hasValue(parameters, "residualOwner")
            ? EconomyCommandPayloads.requireActor(label, parameters, "residualOwner")
            : requireExistingField(
                label, "residualOwner", before == null ? null : before.residualOwner());
    LaborSource laborSource;
    if (parameters.has("laborSource")) {
      // 显式 null ⇒ null ⇒ ProductionRules 构造期缺省 SELF（清回"经营者自营"）。
      laborSource =
          EconomyCommandPayloads.optionalLaborSource(label, parameters, "laborSource", null);
    } else {
      laborSource = before == null ? null : before.laborSource();
    }
    ProductionRules after =
        new ProductionRules(activity, operator, inputSupplier, rules, residualOwner, laborSource);
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>(base.relations());
    relations.put(activity, after);
    EconomyData projected = base.withRelations(relations);
    // ★ EconomyData 会把旧档 ToCohort 受方按一一对应归一到 ToHousehold；幂等与审计读数必须取**投影里真正落下的**
    //   那一份，不能用归一前的 after（否则同一 payload 会被误报成变了）。
    ProductionRules stored = projected.relations().get(activity);
    if (stored.equals(before)) {
      return noOp(UPSERT_PRODUCTION_RELATION, reason, day, base);
    }
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_PRODUCTION_RELATION,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("relations", activity.value(), before, stored)));
  }

  /**
   * {@code upsertProductionOrganization}：{@code {id?,modeId,classPositionId,unitId?,organizer,
   * laborSources?,assetSources?,inputSources?,outputOwnership,relationTemplateRef?,status,
   * statusReason?}}。
   *
   * <p>id 缺省 ⇒ 走 {@link ProductionOrganizationId#idOf} 的既有拼写点，要求 organizer 是 HOUSEHOLD 且有 unitId
   * （格键从 unit 的 industry id 解出）；否则必须显式给 id。mode/position/unit 引用必须存在；资产份额与家户引用在对应表非空时
   * fail-closed；ACTIVE/EXITING 必须有 unitId，SHORTAGE 必须具名 reason。
   */
  static Projection upsertProductionOrganization(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionProcess> units,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_ORGANIZATION;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
    }
    ClassPositionId classPositionId =
        classPositionId(
            label, EconomyCommandPayloads.requireText(label, parameters, "classPositionId"));
    ProductionRole position = base.classPositions().get(classPositionId);
    if (position == null) {
      throw new IllegalArgumentException(
          label + " 的 classPositionId 必须已存在: " + classPositionId.value());
    }
    if (!position.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 的 classPositionId 必须属于 modeId：位置="
              + classPositionId.value()
              + "，位置 modeId="
              + position.modeId()
              + "，组织 modeId="
              + modeId);
    }
    ActorRef organizer = EconomyCommandPayloads.requireActor(label, parameters, "organizer");
    Optional<ProductionUnitId> unitId =
        hasValue(parameters, "unitId")
            ? Optional.of(
                productionProcessId(
                    label, EconomyCommandPayloads.requireText(label, parameters, "unitId")))
            : Optional.empty();
    ProductionOrganizationId id;
    if (hasValue(parameters, "id")) {
      id =
          productionEnterpriseId(
              label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    } else {
      if (organizer.kind() != io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
        throw new IllegalArgumentException(
            label
                + " 的 id 缺省派生要求 organizer.kind=HOUSEHOLD；"
                + "当前 kind="
                + organizer.kind()
                + "，请显式给 id");
      }
      if (unitId.isEmpty()) {
        throw new IllegalArgumentException(
            label + " 的 id 缺省派生要求给 unitId（格键从 unit 的 industry id 解出），请显式给 id");
      }
      ProductionProcess unitForHex = units.get(unitId.get());
      if (unitForHex == null) {
        throw new IllegalArgumentException(label + " 的 unitId 必须已存在: " + unitId.get().value());
      }
      String hexKey =
          IndustryHexKeys.hexKeyOf(unitForHex.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的 unit.industry id 里解不出格键，无法派生 id: "
                              + unitForHex.industry().value()));
      id =
          ProductionOrganizationId.idOf(
              modeId, classPositionId, HouseholdId.parse(organizer.id()), hexKey);
    }
    ProductionEnterprise before = base.productionOrganizations().get(id);
    if (before != null && !hasValue(parameters, "unitId")) {
      unitId = before.unitId();
    }
    if (unitId.isPresent()) {
      ProductionProcess unit = units.get(unitId.get());
      if (unit == null) {
        throw new IllegalArgumentException(label + " 的 unitId 必须已存在: " + unitId.get().value());
      }
      if (!unit.operator().equals(organizer)) {
        throw new IllegalArgumentException(
            label
                + " 的 organizer 必须与它指名 unit 的 operator 一致：organizer="
                + organizer
                + "，unit.operator="
                + unit.operator());
      }
    }
    List<HouseholdId> laborSources =
        hasValue(parameters, "laborSources")
            ? parseHouseholdList(base, label, parameters, "laborSources")
            : (before == null ? List.of() : before.laborSources());
    List<AssetShareId> assetSources =
        hasValue(parameters, "assetSources")
            ? parseOwnershipStakeList(base, label, parameters, "assetSources", organizer)
            : (before == null ? List.of() : before.assetSources());
    List<Payee> inputSources =
        hasValue(parameters, "inputSources")
            ? parsePayeeList(label, parameters, "inputSources")
            : (before == null ? List.of() : before.inputSources());
    Payee outputOwnership =
        hasValue(parameters, "outputOwnership")
            ? EconomyPayloads.recipient(
                requireObjectNode(label, parameters, "outputOwnership"), "outputOwnership")
            : requireExistingField(
                label, "outputOwnership", before == null ? null : before.outputOwnership());
    Optional<String> relationTemplateRef;
    if (parameters.has("relationTemplateRef")) {
      JsonNode node = parameters.get("relationTemplateRef");
      relationTemplateRef =
          node == null || node.isNull()
              ? Optional.empty()
              : Optional.of(
                  EconomyCommandPayloads.requireText(label, parameters, "relationTemplateRef"));
    } else {
      relationTemplateRef = before == null ? Optional.empty() : before.relationTemplateRef();
    }
    ProductionEnterprise.Status status =
        hasValue(parameters, "status")
            ? enumValue(
                label,
                "status",
                EconomyCommandPayloads.requireText(label, parameters, "status"),
                ProductionEnterprise.Status.class)
            : requireExistingField(label, "status", before == null ? null : before.status());
    String statusReason;
    if (parameters.has("statusReason")) {
      JsonNode node = parameters.get("statusReason");
      if (node == null || node.isNull()) {
        statusReason = "";
      } else {
        if (!node.isTextual()) {
          throw new IllegalArgumentException(label + " 的 statusReason 必须是文本: " + node);
        }
        statusReason = node.asText();
      }
    } else {
      statusReason = before == null ? "" : before.statusReason();
    }
    if ((status == ProductionEnterprise.Status.ACTIVE
            || status == ProductionEnterprise.Status.EXITING)
        && unitId.isEmpty()) {
      throw new IllegalArgumentException(label + " 的 " + status + " 必须有 unitId（没有 unit 的在产/退出说不通）");
    }
    if (status == ProductionEnterprise.Status.SHORTAGE && statusReason.isBlank()) {
      throw new IllegalArgumentException(label + " 的 SHORTAGE 必须带具名 statusReason");
    }
    if (status == ProductionEnterprise.Status.ACTIVE && !statusReason.isBlank()) {
      throw new IllegalArgumentException(
          label + " 的 ACTIVE 不携带缺口原因（要写原因请用 SHORTAGE）: " + statusReason);
    }
    ProductionEnterprise after =
        new ProductionEnterprise(
            id,
            modeId,
            classPositionId,
            unitId,
            organizer,
            laborSources,
            assetSources,
            inputSources,
            outputOwnership,
            relationTemplateRef,
            status,
            statusReason);
    if (after.equals(before)) {
      return noOp(UPSERT_PRODUCTION_ORGANIZATION, reason, day, base);
    }
    Map<ProductionOrganizationId, ProductionEnterprise> enterprises =
        new LinkedHashMap<>(base.productionOrganizations());
    enterprises.put(id, after);
    EconomyData projected = base.withProductionEnterprises(enterprises);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_PRODUCTION_ORGANIZATION,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("productionOrganizations", id.value(), before, after)));
  }
}
