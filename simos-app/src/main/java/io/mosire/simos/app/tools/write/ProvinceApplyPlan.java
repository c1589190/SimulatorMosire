package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.gov.ProvinceDivider;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.province.apply} 的<b>纯推导</b>（P3 一键落盘模式）：从一份 {@link SimulationState} 与已解析参数算出
 * {@link Plan} 或具名门禁（{@link Gate}）——<b>不碰 {@code ToolContext}、不碰 {@code CoreSimos}</b>，preview 与
 * apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>为什么不重写划分算法</b>：省界来自 {@link ProvinceDivider#divide(GameMap, RegionId,
 * ProvinceDivider.Params)} 的同一份纯函数建议；本类不做第二次划分、不改算法、不动 {@code simos.province.divide} 的语义。{@code
 * suggestion.warnings()} 原样进入 preview。
 *
 * <p>★★ <b>首都圈（独立一件）</b>：{@code ProvinceDivider} 的建议里，首都圈只以 {@code capitalDistrict} 出现，<b>不再</b>混进
 * {@code provinces}。本工具先落每条建议省及其省 GOV/决策人，再在 {@code capitalDistrict} 非空时落独立首都区 Region {@code
 * sanitize(regionId) + "__CAP"}（名 = {@code namingPrefix + "·首都"}，由中央 GOV 管辖）。中央 GOV 的 jurisdiction
 * 只授 {@code __CAP}；没给 capitalHex（或首都圈为空）则中央不落 {@code unit.SetJurisdiction}（空管辖）。区域重叠是正常状态，由 GM 后续用
 * {@code unit.SetJurisdiction} / 清空工具调整。
 *
 * <p>★★ <b>pre-scan / 干净门（全部只读 base state；命中即零 revision、不部分覆盖）</b>：
 *
 * <ol>
 *   <li><b>相关结构门</b>（{@link Gate#NEEDS_CLEAR}）——四条任一命中：
 *       <ul>
 *         <li>目标 Region hex 集内存在带 {@link GovFormation} 的 Unit（按当刻 {@code effectivePosition}）；
 *         <li>存在 {@link Affiliation.Gov} 且其 {@code govUnit} 属于上述 Unit 的 DecisionMaker；
 *         <li>存在任何 Unit 的 {@code jurisdiction} 覆盖目标 Region 的 key，或覆盖一个 hex 集完全落在目标 Region hex 集内的
 *             Region；
 *         <li>存在 id 形如 {@code sanitize(regionId) + "__P" + 数字} 或 {@code sanitize(regionId) +
 *             "__CAP"} 的既有 Region。
 *       </ul>
 *   <li><b>重叠门</b>（{@link Gate#OVERLAP_OVERRIDE_REQUIRED}）——{@code overlappingRegions} 非空且 {@code
 *       overrideOverlaps=false}。区域重叠 ≠ 省籍：只列信息、要求显式 override；{@code overrideOverlaps=true} 时继续，
 *       overlaps 进 preview 的 warnings。
 * </ol>
 *
 * <p>★★ <b>固定批序（全部走现有 GM 命令；同 batchId/branch/expectedRevision ⇒ 一条 revision）</b>：
 *
 * <ol>
 *   <li>{@code map.CreateRegion} × 省（{@code suggestedRegionId/name/hexes}；meta tag={@code
 *       province}；不设 nation tag）；
 *   <li>{@code map.CreateRegion} × 首都区（仅首都圈非空；id={@code __CAP}，name={@code namingPrefix + "·首都"}）；
 *   <li>{@code unit.CreateUnit} × (N+1)：N = 建议省数（不含首都圈）；中央 {@code -gov-central} + 省 {@code
 *       -gov-P<建议序号>}；{@code member=0, equipment={}, speed=1, mobilityPerMille=500, position=对应
 *       center}、无 parent、status 走域层缺省 MOVING；
 *   <li>{@code unit.SetGovFormation} × (N+1)：中央 {@code {level:CENTRAL, staff, policy}}（不给
 *       superiorGov）；省 {@code {level:PROVINCE, superiorGov:中央, staff, policy}}；
 *   <li>{@code unit.SetJurisdiction}：中央在首都圈非空时授 {@code __CAP}、否则不落；每省授本省 Region；
 *   <li>{@code sd.CreateDecisionMaker} × (N+1)：id={@code <govUnitId>-dm}、affiliation={@code
 *       gov:<govUnitId>}；
 *   <li>{@code sd.SetDecisionMakerProvider} × (N+1)（仅 {@code providerId} 给了才落）；
 *   <li>{@code sd.PutInfo}：address=目标 Region canonical，key={@value #INFO_KEY}，value=JSON
 *       字符串，tick=base 世界当前日。
 * </ol>
 *
 * <p>★ <b>R2a 接缝</b>（2026-10-01 行政区划修复计划）：本工具只落 {@code Region} + {@code GOV} + 决策人， <b>不</b>改任何
 * {@code SocialCity.region}；落盘后建议调用 {@code simos.province.assignCities}，把 {@code at}
 * 落在新省/首都区的城市批量归省（R5 重建流程会显式调用）。
 *
 * <p>★ <b>确定性</b>：不碰墙钟（tick 是 base state 的函数）、不用随机量；省/unit/dm 顺序取建议顺序，扫描命中一律先按 id
 * 排序；staff/allowedTools 用 {@code LinkedHashMap}/{@code LinkedHashSet} 保序冻结（<b>不用</b> {@code
 * Map.copyOf}）。
 */
final class ProvinceApplyPlan {

  /** {@code map.CreateRegion} 的命令类型（与既有窄工具同源）。 */
  static final String CREATE_REGION_TYPE = MapCreateRegionTool.NAME;

  /** {@code unit.CreateUnit} 的命令类型。 */
  static final String CREATE_UNIT_TYPE = GovCreateOfficePlan.CREATE_UNIT_TYPE;

  /** {@code unit.SetGovFormation} 的命令类型。 */
  static final String SET_GOV_FORMATION_TYPE = GovCreateOfficePlan.SET_GOV_FORMATION_TYPE;

  /** {@code unit.SetJurisdiction} 的命令类型（中央无首都圈时缺席）。 */
  static final String SET_JURISDICTION_TYPE = GovCreateOfficePlan.SET_JURISDICTION_TYPE;

  /** {@code sd.CreateDecisionMaker} 的命令类型。 */
  static final String CREATE_DECISION_MAKER_TYPE = GovCreateOfficePlan.CREATE_DECISION_MAKER_TYPE;

  /** {@code sd.SetDecisionMakerProvider} 的命令类型（仅 providerId 给了才落）。 */
  static final String SET_DECISION_MAKER_PROVIDER_TYPE =
      GovCreateOfficePlan.SET_DECISION_MAKER_PROVIDER_TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = GovCreateOfficePlan.PUT_INFO_TYPE;

  /** 新 GOV 单位的固定速度（与 createOffice 既有口径同源）。 */
  static final int NEW_UNIT_SPEED = GovCreateOfficePlan.NEW_UNIT_SPEED;

  /** 新 GOV 单位的固定机动性（与 createOffice 既有口径同源）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = GovCreateOfficePlan.NEW_UNIT_MOBILITY_PER_MILLE;

  /** 决策人缺省决策周期（天）：域层合法下界 = 1。 */
  static final long DEFAULT_CADENCE = GovCreateOfficePlan.DEFAULT_CADENCE;

  /** 省 Region 的 meta tag（只标位，不设 nation tag）。 */
  static final String PROVINCE_TAG = "province";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 目标 Region canonical）。 */
  static final String INFO_KEY = "provinceApply";

  /** 相关结构命中时的指路文案。 */
  static final String NEEDS_CLEAR_HINT =
      "调用 simos.region.clearStructures 清空目标 Region 的既有生成器结构后重试"
          + "（需要连数据一起清时可先调用 simos.region.clearData）";

  /** 每类命中最多列几条示例（计数仍是全量）。 */
  private static final int MAX_HIT_SAMPLES = 5;

  /** 中央单位 id 后缀。 */
  private static final String CENTRAL_UNIT_SUFFIX = "-gov-central";

  /** 省单位 id 的中缀（后接与建议 Region 对齐的两位序号）。 */
  private static final String PROVINCE_UNIT_INFIX = "-gov-P";

  /** 决策人 id 后缀（绑到对应 GOV 单位 id 后）。 */
  private static final String DECISION_MAKER_SUFFIX = "-dm";

  /** 首都区 Region id 后缀。 */
  private static final String CAPITAL_REGION_SUFFIX = "__CAP";

  private ProvinceApplyPlan() {}

  /** 一次推导的门禁结局。 */
  enum Gate {
    /** 干净：{@link Derivation#plan()} 在场，可 preview / apply。 */
    OPEN,
    /** 相关结构命中：不 submit、零 revision，工具折 {@code NEEDS_CLEAR}。 */
    NEEDS_CLEAR,
    /** 相交 Region 未 override：不 submit、零 revision，工具折 {@code OVERLAP_OVERRIDE_REQUIRED}。 */
    OVERLAP_OVERRIDE_REQUIRED
  }

  /**
   * 一次调用的全部已解析参数（工具层完成 JSON 形状 / 词表 / 范围解析；本类型只做结构冻结）。
   *
   * @param regionId 目标 Region（必须在当前 map.regions() 里）
   * @param minHexPerProvince 每省 hex 下限（≥1）
   * @param maxHexPerProvince 每省 hex 上限（≥ min）
   * @param capitalHex 可选首都格（null = 不单列首都圈）
   * @param capitalDistrictRadius 首都圈半径（≥0；capitalHex 为 null 时只作占位）
   * @param namingPrefix 命名前缀（null = 取目标 Region 名；由 {@link #derive} 解析）
   * @param staff 每个 GOV 的初始编制（保序；同一套值给中央与省）
   * @param policy 每个 GOV 的编制政策（同一套值给中央与省）
   * @param allowedTools 每个决策人的窄工具白名单（保序；空 = P6a 口径"全局白名单"）
   * @param cadence 决策周期（天；≥1）
   * @param providerId 可选 LLM provider（给了才逐 DM 落 sd.SetDecisionMakerProvider）
   * @param overrideOverlaps 相交 Region 的显式 override 开关
   */
  record Params(
      String regionId,
      int minHexPerProvince,
      int maxHexPerProvince,
      HexCoord capitalHex,
      int capitalDistrictRadius,
      String namingPrefix,
      Map<StaffRole, Long> staff,
      OfficePolicy policy,
      Set<String> allowedTools,
      long cadence,
      Optional<String> providerId,
      boolean overrideOverlaps) {

    Params {
      requireNonBlank(regionId, "regionId");
      if (minHexPerProvince < 1) {
        throw new IllegalArgumentException("minHexPerProvince 必须 ≥1: " + minHexPerProvince);
      }
      if (maxHexPerProvince < minHexPerProvince) {
        throw new IllegalArgumentException(
            "maxHexPerProvince 必须 ≥ minHexPerProvince: max="
                + maxHexPerProvince
                + ", min="
                + minHexPerProvince);
      }
      if (capitalDistrictRadius < 0) {
        throw new IllegalArgumentException("capitalDistrictRadius 不能为负: " + capitalDistrictRadius);
      }
      if (namingPrefix != null && namingPrefix.isBlank()) {
        throw new IllegalArgumentException("namingPrefix 不得为空白（要么不给、要么给非空前缀）");
      }
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(providerId, "providerId");
      if (cadence < 1L) {
        throw new IllegalArgumentException("cadence 必须 ≥ 1（决策周期，单位：天）: " + cadence);
      }
      providerId.ifPresent(
          provider -> {
            if (provider.isBlank()) {
              throw new IllegalArgumentException("providerId 不得为空白（要么不给、要么给非空引用）");
            }
          });
      staff = freezeStaff(staff);
      allowedTools = freezeTools(allowedTools);
    }

    /** staff 的 JSON 视图（角色名 → 人数；保序）。 */
    Map<String, Object> staffView() {
      Map<String, Object> view = new LinkedHashMap<>();
      for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
        view.put(entry.getKey().name(), entry.getValue());
      }
      return view;
    }

    /** policy 的 JSON 视图（五个字段全给；staffCap 保序）。 */
    Map<String, Object> policyView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("grainPerStaffPerTick", policy.grainPerStaffPerTick());
      view.put("clothPerStaffPerCycle", policy.clothPerStaffPerCycle());
      view.put("moneyPerStaffPerTick", policy.moneyPerStaffPerTick());
      view.put("retirementPerStaff", policy.retirementPerStaff());
      Map<String, Object> caps = new LinkedHashMap<>();
      for (Map.Entry<StaffRole, Long> entry : policy.staffCap().entrySet()) {
        caps.put(entry.getKey().name(), entry.getValue());
      }
      view.put("staffCap", caps);
      return view;
    }

    /** allowedTools 的保序视图。 */
    List<String> allowedToolsView() {
      return List.copyOf(new ArrayList<>(allowedTools));
    }
  }

  /** 一条实际落盘的 Region + 对应 GOV 单位 + 决策人（省与首都区同形）。 */
  record RegionUnitEntry(
      String regionId,
      String name,
      HexCoord center,
      List<HexCoord> hexes,
      String unitId,
      String unitName,
      String decisionMakerId) {

    RegionUnitEntry {
      requireNonBlank(regionId, "regionId");
      requireNonBlank(name, "name");
      Objects.requireNonNull(center, "center");
      Objects.requireNonNull(hexes, "hexes");
      requireNonBlank(unitId, "unitId");
      requireNonBlank(unitName, "unitName");
      requireNonBlank(decisionMakerId, "decisionMakerId");
      if (hexes.isEmpty()) {
        throw new IllegalArgumentException("Region 的 hexes 不得为空: " + regionId);
      }
      hexes = List.copyOf(hexes);
    }

    int hexCount() {
      return hexes.size();
    }
  }

  /** 一条 GOV 单位计划（中央在首、省按建议顺序在后；jurisdiction 为空 = 不落该命令）。 */
  record GovEntry(
      String unitId,
      String unitName,
      HexCoord at,
      String decisionMakerId,
      GovLevel level,
      Optional<String> superiorGov,
      List<String> jurisdictionRegionIds) {

    GovEntry {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(unitName, "unitName");
      Objects.requireNonNull(at, "at");
      requireNonBlank(decisionMakerId, "decisionMakerId");
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(superiorGov, "superiorGov");
      Objects.requireNonNull(jurisdictionRegionIds, "jurisdictionRegionIds");
      for (String regionId : jurisdictionRegionIds) {
        requireNonBlank(regionId, "jurisdictionRegionIds 元素");
      }
      jurisdictionRegionIds = List.copyOf(jurisdictionRegionIds);
    }

    /** 是否要落 {@code unit.SetJurisdiction}（中央无首都圈时为空 ⇒ 保持创建缺省无管辖）。 */
    boolean hasJurisdictionCommand() {
      return !jurisdictionRegionIds.isEmpty();
    }
  }

  /** 一类结构命中：命中类别 / 全量计数 / 最多 {@value #MAX_HIT_SAMPLES} 条示例 id。 */
  record Hit(String kind, long count, List<String> samples) {

    Hit {
      requireNonBlank(kind, "kind");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥1: " + count);
      }
      Objects.requireNonNull(samples, "samples");
      samples = List.copyOf(samples);
    }
  }

  /** 相关结构门结果（{@code hits} 为空 = 干净）。 */
  record StructureGate(List<Hit> hits) {

    StructureGate {
      Objects.requireNonNull(hits, "hits");
      hits = List.copyOf(hits);
    }

    boolean clean() {
      return hits.isEmpty();
    }

    /** 线格式视图（preview 的 {@code cleanGate} 与 {@code NEEDS_CLEAR} 载荷共用一份形状）。 */
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("clean", clean());
      List<Map<String, Object>> hitViews = new ArrayList<>(hits.size());
      Map<String, Object> hitCounts = new LinkedHashMap<>();
      for (Hit hit : hits) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", hit.kind());
        row.put("count", hit.count());
        row.put("samples", hit.samples());
        hitViews.add(row);
        hitCounts.put(hit.kind(), hit.count());
      }
      view.put("hits", List.copyOf(hitViews));
      view.put("hitCounts", hitCounts);
      return view;
    }
  }

  /**
   * 推导结果：{@code OPEN} ⇒ {@code plan} 在场；门禁命中 ⇒ {@code plan} 缺席（工具折具名错误，零 revision）。{@code warnings}
   * 两种形态下都给（建议器 warnings 原样 + 本工具的 override/首都圈说明）。
   */
  record Derivation(
      Gate gate,
      Optional<Plan> plan,
      StructureGate structureGate,
      List<ProvinceDivider.Overlap> overlappingRegions,
      List<String> warnings) {

    Derivation {
      Objects.requireNonNull(gate, "gate");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(structureGate, "structureGate");
      Objects.requireNonNull(overlappingRegions, "overlappingRegions");
      overlappingRegions = List.copyOf(overlappingRegions);
      Objects.requireNonNull(warnings, "warnings");
      warnings = List.copyOf(warnings);
      if ((gate == Gate.OPEN) != plan.isPresent()) {
        throw new IllegalStateException("推演不自洽：gate=" + gate + " 但 plan 在场=" + plan.isPresent());
      }
    }
  }

  /**
   * 一份可执行的一键落盘计划（全部字段是 base state 与参数的纯函数；集合都在构造期冻结）。
   *
   * @param mapId 世界 map 称谓（进 sd.PutInfo 地址）
   * @param regionId 目标 Region id
   * @param regionName 目标 Region 名
   * @param tick base state 的世界当前日
   * @param namingPrefix 已解析的命名前缀（缺省 = 目标 Region 名）
   * @param centralAt 中央 GOV 落点（有首都圈 = 首都圈中心；否则目标 Region 自然序最小格）
   * @param provinces 建议省（含 ·首都 草案；顺序 = 建议顺序，id/序号与建议 Region 对齐）
   * @param capitalDistrict 独立首都区（可为 null）
   * @param params 本次调用参数（staff/policy/allowedTools/cadence/provider/override）
   * @param overlappingRegions 与目标 Region 相交的既有 Region（只作信息）
   * @param warnings preview 的 warnings（建议器 warnings 原样 + 本工具说明）
   */
  record Plan(
      String mapId,
      String regionId,
      String regionName,
      long tick,
      String namingPrefix,
      HexCoord centralAt,
      String centralUnitId,
      String centralUnitName,
      String centralDecisionMakerId,
      List<RegionUnitEntry> provinces,
      RegionUnitEntry capitalDistrict,
      Params params,
      List<ProvinceDivider.Overlap> overlappingRegions,
      List<String> warnings) {

    Plan {
      requireNonBlank(mapId, "mapId");
      requireNonBlank(regionId, "regionId");
      requireNonBlank(regionName, "regionName");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      requireNonBlank(namingPrefix, "namingPrefix");
      Objects.requireNonNull(centralAt, "centralAt");
      requireNonBlank(centralUnitId, "centralUnitId");
      requireNonBlank(centralUnitName, "centralUnitName");
      requireNonBlank(centralDecisionMakerId, "centralDecisionMakerId");
      Objects.requireNonNull(provinces, "provinces");
      Objects.requireNonNull(params, "params");
      Objects.requireNonNull(overlappingRegions, "overlappingRegions");
      Objects.requireNonNull(warnings, "warnings");
      provinces = List.copyOf(provinces);
      overlappingRegions = List.copyOf(overlappingRegions);
      warnings = List.copyOf(warnings);
    }

    /** 实际创建的 Region：先省（建议顺序）、后首都区。 */
    List<RegionUnitEntry> createdRegions() {
      List<RegionUnitEntry> out =
          new ArrayList<>(provinces.size() + (capitalDistrict == null ? 0 : 1));
      out.addAll(provinces);
      if (capitalDistrict != null) {
        out.add(capitalDistrict);
      }
      return List.copyOf(out);
    }

    /** GOV 单位计划：中央在首（省 superiorGov 指向它）、省按建议顺序在后。 */
    List<GovEntry> govs() {
      List<GovEntry> out = new ArrayList<>(provinces.size() + 1);
      out.add(
          new GovEntry(
              centralUnitId,
              centralUnitName,
              centralAt,
              centralDecisionMakerId,
              GovLevel.CENTRAL,
              Optional.empty(),
              capitalDistrict == null ? List.of() : List.of(capitalDistrict.regionId())));
      for (RegionUnitEntry province : provinces) {
        out.add(
            new GovEntry(
                province.unitId(),
                province.unitName(),
                province.center(),
                province.decisionMakerId(),
                GovLevel.PROVINCE,
                Optional.of(centralUnitId),
                List.of(province.regionId())));
      }
      return List.copyOf(out);
    }

    /** 命令类型 + 实际条数（固定批序；preview 与 apply 组批共用这一处）。 */
    List<Map<String, Object>> commandCounts() {
      List<GovEntry> govs = govs();
      Map<String, Integer> counts = new LinkedHashMap<>();
      counts.put(CREATE_REGION_TYPE, createdRegions().size());
      counts.put(CREATE_UNIT_TYPE, govs.size());
      counts.put(SET_GOV_FORMATION_TYPE, govs.size());
      int jurisdictions = 0;
      for (GovEntry gov : govs) {
        if (gov.hasJurisdictionCommand()) {
          jurisdictions++;
        }
      }
      counts.put(SET_JURISDICTION_TYPE, jurisdictions);
      counts.put(CREATE_DECISION_MAKER_TYPE, govs.size());
      if (params.providerId().isPresent()) {
        counts.put(SET_DECISION_MAKER_PROVIDER_TYPE, govs.size());
      }
      counts.put(PUT_INFO_TYPE, 1);
      List<Map<String, Object>> out = new ArrayList<>(counts.size());
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", entry.getKey());
        row.put("count", entry.getValue());
        out.add(row);
      }
      return List.copyOf(out);
    }

    /** preview 的 provinces（字段名与规格一致：suggestedRegionId/name/center/hexCount/unitId/dmId）。 */
    List<Map<String, Object>> provincesView() {
      List<Map<String, Object>> out = new ArrayList<>(provinces.size());
      for (RegionUnitEntry province : provinces) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("suggestedRegionId", province.regionId());
        row.put("name", province.name());
        row.put("center", ToolSupport.hexCoord(province.center()));
        row.put("hexCount", province.hexCount());
        row.put("unitId", province.unitId());
        row.put("dmId", province.decisionMakerId());
        out.add(row);
      }
      return List.copyOf(out);
    }

    /** preview 的 capitalDistrict（首都圈非空才调用）。 */
    Map<String, Object> capitalDistrictView() {
      if (capitalDistrict == null) {
        throw new IllegalStateException("没有首都圈却要组装 capitalDistrict 视图");
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("regionId", capitalDistrict.regionId());
      view.put("name", capitalDistrict.name());
      view.put("center", ToolSupport.hexCoord(capitalDistrict.center()));
      view.put("hexCount", capitalDistrict.hexCount());
      view.put("unitId", capitalDistrict.unitId());
      view.put("dmId", capitalDistrict.decisionMakerId());
      return view;
    }

    /** 相交 Region 的线格式视图（preview / 错误载荷 / PutInfo 共用一份）。 */
    List<Map<String, Object>> overlappingRegionsView() {
      return overlapViews(overlappingRegions);
    }

    /**
     * {@code sd.PutInfo.value}：JSON
     * <b>字符串</b>（regionId/省数/regionIds/unitIds/dmIds/overlaps/reason）。
     */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      List<String> regionIds = new ArrayList<>();
      for (RegionUnitEntry region : createdRegions()) {
        regionIds.add(region.regionId());
      }
      List<GovEntry> govs = govs();
      List<String> unitIds = new ArrayList<>(govs.size());
      List<String> dmIds = new ArrayList<>(govs.size());
      for (GovEntry gov : govs) {
        unitIds.add(gov.unitId());
        dmIds.add(gov.decisionMakerId());
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("regionId", regionId);
      value.put("provinceCount", provinces.size());
      value.put("regionIds", List.copyOf(regionIds));
      value.put("unitIds", List.copyOf(unitIds));
      value.put("dmIds", List.copyOf(dmIds));
      value.put("overlaps", overlappingRegionsView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 sd.PutInfo.note 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "省份一键落盘 region="
          + regionId
          + "：省 "
          + provinces.size()
          + " 个，Region "
          + createdRegions().size()
          + " 个（首都区="
          + (capitalDistrict == null ? "(无)" : capitalDistrict.regionId())
          + "），GOV/决策人 "
          + govs().size()
          + " 个（中央="
          + centralUnitId
          + "），相交 Region "
          + overlappingRegions.size()
          + " 个；reason="
          + reason;
    }

    // ── 批载荷（类型与字段都取自 Plan；工具只按固定批序取用） ────────────────────────────────

    /** {@code map.CreateRegion} 载荷；{@code provinceTag=true} 时 meta.tag=province，首都区不带 meta。 */
    String createRegionPayload(RegionUnitEntry region, boolean provinceTag) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("regionId", region.regionId());
      payload.put("name", region.name());
      List<Map<String, Object>> hexes = new ArrayList<>(region.hexes().size());
      for (HexCoord hex : region.hexes()) {
        hexes.add(ToolSupport.hexCoord(hex));
      }
      payload.put("hexes", List.copyOf(hexes));
      if (provinceTag) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tag", PROVINCE_TAG);
        payload.put("meta", meta);
      }
      return ToolSupport.json(payload);
    }

    /** {@code unit.CreateUnit} 载荷：新 GOV 单位参数逐值写死（与 createOffice 既有口径同源）。 */
    String createUnitPayload(GovEntry gov) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", gov.unitId());
      payload.put("name", gov.unitName());
      payload.put("position", ToolSupport.hexCoord(gov.at()));
      payload.put("member", 0);
      payload.put("equipment", new LinkedHashMap<String, Object>());
      payload.put("speed", NEW_UNIT_SPEED);
      payload.put("mobilityPerMille", NEW_UNIT_MOBILITY_PER_MILLE);
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetGovFormation} 载荷：{@code {unitId, level, superiorGov?, staff, policy}}。 */
    String setGovFormationPayload(GovEntry gov) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", gov.unitId());
      payload.put("level", gov.level().name());
      gov.superiorGov().ifPresent(superior -> payload.put("superiorGov", superior));
      payload.put("staff", params.staffView());
      payload.put("policy", params.policyView());
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetJurisdiction} 载荷（仅 {@link GovEntry#hasJurisdictionCommand()} 为真时调用）。 */
    String setJurisdictionPayload(GovEntry gov) {
      if (!gov.hasJurisdictionCommand()) {
        throw new IllegalStateException(
            "批不自洽：" + gov.unitId() + " 没有 jurisdiction 却要组装 unit.SetJurisdiction 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", gov.unitId());
      payload.put("regions", gov.jurisdictionRegionIds());
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.CreateDecisionMaker} 载荷：{@code {id, affiliation:{kind:gov,id:unitId}, allowedTools,
     * cadence}}。
     */
    String createDecisionMakerPayload(GovEntry gov) {
      Map<String, Object> affiliation = new LinkedHashMap<>();
      affiliation.put("kind", "gov");
      affiliation.put("id", gov.unitId());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", gov.decisionMakerId());
      payload.put("affiliation", affiliation);
      payload.put("allowedTools", params.allowedToolsView());
      payload.put("cadence", params.cadence());
      return ToolSupport.json(payload);
    }

    /** {@code sd.SetDecisionMakerProvider} 载荷（仅 providerId 给了才调用）。 */
    String setDecisionMakerProviderPayload(GovEntry gov) {
      Optional<String> providerId = params.providerId();
      if (providerId.isEmpty()) {
        throw new IllegalStateException("批不自洽：无 providerId 却要组装 sd.SetDecisionMakerProvider 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("decisionMakerId", gov.decisionMakerId());
      payload.put("providerId", providerId.get());
      return ToolSupport.json(payload);
    }
  }

  /**
   * ★★ preview / apply 共用的唯一推导入口：读 base state → {@link ProvinceDivider#divide} → 相关结构门 → 重叠门 →
   * {@link Plan}。任何前置不满足都抛 {@link IllegalArgumentException}（工具折 {@code BAD_REQUEST}，零 revision）；门禁
   * 命中走 {@link Gate}（工具折具名错误，同样零 revision）。
   *
   * @param state 只读 base state（preview = head 或给定 revision；apply = expectedRevision）
   * @param mapId 世界 map 称谓
   * @param params 已解析参数
   */
  static Derivation derive(SimulationState state, String mapId, Params params) {
    Objects.requireNonNull(state, "state");
    requireNonBlank(mapId, "mapId");
    Objects.requireNonNull(params, "params");
    GameMap map = ToolSupport.gameMap(state);
    Region region = map.regions().get(new RegionId(params.regionId()));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + params.regionId());
    }
    String namingPrefix = params.namingPrefix() == null ? region.name() : params.namingPrefix();
    ProvinceDivider.Params dividerParams =
        new ProvinceDivider.Params(
            params.minHexPerProvince(),
            params.maxHexPerProvince(),
            params.capitalHex(),
            params.capitalDistrictRadius(),
            namingPrefix);
    // ★ 唯一划分算法落点：只读建议器，本工具不重写、不做第二次划分。
    ProvinceDivider.Suggestion suggestion = ProvinceDivider.divide(map, region.id(), dividerParams);
    StructureGate structureGate = inspectStructures(state, map, region);
    List<ProvinceDivider.Overlap> overlaps = List.copyOf(suggestion.overlappingRegions());
    List<String> warnings = new ArrayList<>(suggestion.warnings());
    if (!structureGate.clean()) {
      return new Derivation(Gate.NEEDS_CLEAR, Optional.empty(), structureGate, overlaps, warnings);
    }
    if (!overlaps.isEmpty() && !params.overrideOverlaps()) {
      return new Derivation(
          Gate.OVERLAP_OVERRIDE_REQUIRED, Optional.empty(), structureGate, overlaps, warnings);
    }
    if (suggestion.provinces().isEmpty()) {
      throw new IllegalArgumentException(
          "目标 Region 没有可落盘的建议省（空建议）: "
              + params.regionId()
              + "；建议器 warnings="
              + suggestion.warnings());
    }
    if (!overlaps.isEmpty()) {
      warnings.add(
          "已显式 overrideOverlaps=true：目标 Region 与 "
              + overlaps.size()
              + " 个既有 Region 相交（区域重叠 ≠ 省籍），按调用方决定继续落盘");
    }
    Plan plan =
        buildPlan(mapId, state, region, suggestion, params, namingPrefix, overlaps, warnings);
    return new Derivation(Gate.OPEN, Optional.of(plan), structureGate, overlaps, plan.warnings());
  }

  // ── 相关结构门（只读 base state；四条命中任一即 NEEDS_CLEAR） ─────────────────────────────

  private static StructureGate inspectStructures(
      SimulationState state, GameMap map, Region target) {
    UnitState units = ToolSupport.unitState(state);
    SdState sd = ToolSupport.sdState(state);
    SimosTimestamp at = state.meta().timestamp();
    List<Hit> hits = new ArrayList<>();

    List<Unit> sortedUnits = new ArrayList<>(units.units().values());
    sortedUnits.sort(Comparator.comparing((Unit unit) -> unit.id().value()));

    // 1) 目标 hex 集内、带 GovFormation 的 Unit（按 effectivePosition）。
    Set<UnitId> govUnitsInTarget = new LinkedHashSet<>();
    List<String> govUnitSamples = new ArrayList<>();
    Set<HexCoord> targetHexes = target.hexes();
    for (Unit unit : sortedUnits) {
      if (!(unit.module().orElse(null) instanceof GovFormation)) {
        continue;
      }
      Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
      if (position.isPresent() && targetHexes.contains(position.get())) {
        govUnitsInTarget.add(unit.id());
        govUnitSamples.add(unit.id().value());
      }
    }
    addHit(hits, "unit.govFormationInRegion", govUnitSamples);

    // 2) Affiliation.Gov 且 govUnit 是上述 Unit 的 DecisionMaker。
    List<String> decisionMakerSamples = new ArrayList<>();
    List<DecisionMaker> sortedDecisionMakers = new ArrayList<>(sd.decisionMakers().values());
    sortedDecisionMakers.sort(
        Comparator.comparing((DecisionMaker decisionMaker) -> decisionMaker.id().value()));
    for (DecisionMaker decisionMaker : sortedDecisionMakers) {
      if (decisionMaker.affiliation() instanceof Affiliation.Gov gov
          && govUnitsInTarget.contains(gov.govUnit())) {
        decisionMakerSamples.add(decisionMaker.id().value());
      }
    }
    addHit(hits, "decisionMaker.govAffiliation", decisionMakerSamples);

    // 3) 任何 Unit 的 jurisdiction 覆盖目标 Region 的 key，或覆盖完全落在目标 hex 集内的 Region。
    List<String> jurisdictionSamples = new ArrayList<>();
    for (Unit unit : sortedUnits) {
      Optional<Jurisdiction> jurisdiction = unit.jurisdiction();
      if (jurisdiction.isEmpty()) {
        continue;
      }
      for (RegionId covered : jurisdiction.get().taxRatePerMilleByRegion().keySet()) {
        if (covered.equals(target.id())) {
          jurisdictionSamples.add(unit.id().value() + "->" + covered.value());
          continue;
        }
        Region coveredRegion = map.regions().get(covered);
        if (coveredRegion != null && targetHexes.containsAll(coveredRegion.hexes())) {
          jurisdictionSamples.add(unit.id().value() + "->" + covered.value());
        }
      }
    }
    addHit(hits, "unit.jurisdiction", jurisdictionSamples);

    // 4) 既有 id 形如 sanitize(regionId)+"__P"+数字 或 sanitize(regionId)+"__CAP" 的 Region。
    String prefix = ProvinceDivider.sanitize(target.id().value());
    String capitalRegionId = prefix + CAPITAL_REGION_SUFFIX;
    String provinceIdPrefix = prefix + "__P";
    List<String> regionSamples = new ArrayList<>();
    List<Region> sortedRegions = new ArrayList<>(map.regions().values());
    sortedRegions.sort(Comparator.comparing((Region region) -> region.id().value()));
    for (Region region : sortedRegions) {
      String id = region.id().value();
      if (id.equals(capitalRegionId) || isProvinceLikeId(provinceIdPrefix, id)) {
        regionSamples.add(id);
      }
    }
    addHit(hits, "region.provinceLikeId", regionSamples);

    return new StructureGate(hits);
  }

  /** 建议省 id 后缀：{@code prefix + "__P" + 一位以上 ASCII 数字}。 */
  private static boolean isProvinceLikeId(String provinceIdPrefix, String id) {
    if (!id.startsWith(provinceIdPrefix)) {
      return false;
    }
    String suffix = id.substring(provinceIdPrefix.length());
    if (suffix.isEmpty()) {
      return false;
    }
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** 收集一类命中（全量计数 + 前 {@value #MAX_HIT_SAMPLES} 条排序示例；空则不加）。 */
  private static void addHit(List<Hit> hits, String kind, List<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    List<String> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    List<String> samples = sorted.subList(0, Math.min(MAX_HIT_SAMPLES, sorted.size()));
    hits.add(new Hit(kind, sorted.size(), samples));
  }

  // ── Plan 组装 ────────────────────────────────────────────────────────────────────────

  private static Plan buildPlan(
      String mapId,
      SimulationState state,
      Region region,
      ProvinceDivider.Suggestion suggestion,
      Params params,
      String namingPrefix,
      List<ProvinceDivider.Overlap> overlaps,
      List<String> warnings) {
    String prefix = ProvinceDivider.sanitize(region.id().value());
    String centralUnitId = prefix + CENTRAL_UNIT_SUFFIX;
    String centralUnitName = namingPrefix + "·中央";
    String centralDecisionMakerId = centralUnitId + DECISION_MAKER_SUFFIX;

    List<RegionUnitEntry> provinces = new ArrayList<>();
    for (ProvinceDivider.Province province : suggestion.provinces()) {
      String suffix = provinceSuffix(prefix, province.suggestedRegionId());
      String unitId = prefix + PROVINCE_UNIT_INFIX + suffix;
      provinces.add(
          new RegionUnitEntry(
              province.suggestedRegionId(),
              province.name(),
              province.center(),
              province.hexes(),
              unitId,
              province.name() + "·政府",
              unitId + DECISION_MAKER_SUFFIX));
    }

    RegionUnitEntry capitalDistrict = null;
    if (suggestion.capitalDistrict() != null) {
      String capitalRegionId = prefix + CAPITAL_REGION_SUFFIX;
      capitalDistrict =
          new RegionUnitEntry(
              capitalRegionId,
              namingPrefix + "·首都",
              suggestion.capitalDistrict().center(),
              suggestion.capitalDistrict().hexes(),
              centralUnitId,
              centralUnitName,
              centralDecisionMakerId);
    }
    // ★ CreateUnit 的 position 必填（无 parent）：有首都圈取首都圈中心，否则取目标 Region 自然序最小格。
    HexCoord centralAt =
        capitalDistrict != null ? capitalDistrict.center() : Collections.min(region.hexes());
    return new Plan(
        mapId,
        region.id().value(),
        region.name(),
        state.meta().timestamp().tick(),
        namingPrefix,
        centralAt,
        centralUnitId,
        centralUnitName,
        centralDecisionMakerId,
        provinces,
        capitalDistrict,
        params,
        overlaps,
        warnings);
  }

  /** 从建议省 id 抽出与建议 Region 对齐的序号（{@code prefix + "__P" + 数字}）。 */
  private static String provinceSuffix(String prefix, String suggestedRegionId) {
    String expectedPrefix = prefix + "__P";
    if (!suggestedRegionId.startsWith(expectedPrefix)) {
      throw new IllegalStateException(
          "ProvinceDivider 输出不自洽：建议省 id 不符合 " + expectedPrefix + "<数字> 形制: " + suggestedRegionId);
    }
    String suffix = suggestedRegionId.substring(expectedPrefix.length());
    if (suffix.isEmpty()) {
      throw new IllegalStateException("ProvinceDivider 输出不自洽：建议省 id 没有序号: " + suggestedRegionId);
    }
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalStateException(
            "ProvinceDivider 输出不自洽：建议省 id 序号不是纯数字: " + suggestedRegionId);
      }
    }
    return suffix;
  }

  // ── 共享视图小件 ─────────────────────────────────────────────────────────────────────

  /** 相交 Region 的线格式（字段名与只读建议器一致：regionId/name/tag/overlapHexCount）。 */
  static List<Map<String, Object>> overlapViews(List<ProvinceDivider.Overlap> overlaps) {
    List<Map<String, Object>> out = new ArrayList<>(overlaps.size());
    for (ProvinceDivider.Overlap overlap : overlaps) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("regionId", overlap.regionId());
      row.put("name", overlap.name());
      row.put("tag", overlap.tag());
      row.put("overlapHexCount", overlap.overlapHexCount());
      out.add(row);
    }
    return List.copyOf(out);
  }

  // ── 冻结小件（保序不可变；不用 Map.copyOf / Set.copyOf） ──────────────────────────────

  private static Map<StaffRole, Long> freezeStaff(Map<StaffRole, Long> staff) {
    Objects.requireNonNull(staff, "staff");
    Map<StaffRole, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("staff 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "staff 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Set<String> freezeTools(Set<String> allowedTools) {
    Objects.requireNonNull(allowedTools, "allowedTools");
    Set<String> copy = new LinkedHashSet<>();
    for (String tool : allowedTools) {
      if (tool == null || tool.isBlank()) {
        throw new IllegalArgumentException("allowedTools 不得含空白元素");
      }
      copy.add(tool);
    }
    return Collections.unmodifiableSet(copy);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }
}
