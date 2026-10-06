package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.model.ProductionRole.LaborRole;
import io.mosire.simos.economy.model.ProductionRole.RelationToMeans;
import io.mosire.simos.economy.model.ProductionRole.SurplusRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>默认生产方式目录（P1）—— 新运行时“生产方式 → 阶层结构 → 阶层位置”的唯一静态权威。</b>
 *
 * <p>★★ <b>它解决什么问题</b>：探针已经把佃农制（定额实物租/分成租/货币租）、雇农制与手工业验证成可运行的经济形态，
 * 但正式世界还没有它们的权威状态。本类把探针口径翻成确定、可复现、可审计的静态出厂数据：每个生产方式一件 {@link ClassStructure} 与一组 {@link
 * ProductionRole}；{@code EconomySeeder} 的 {@code production-runtime} profile 直接以本目录为唯一拼写点发 {@code
 * modes} / {@code classStructures} / {@code classPositions} / {@code classStandings} 四个顶层键。
 *
 * <p>★★ <b>八个 mode 与位置角色（P1 的六个 + P6 追加的商人 mode + P10.1 追加的流民 mode；本表是唯一裁决表）</b>：
 *
 * <pre>
 * mode                   结构 id                        位置 role → relationToMeans / laborRole / surplusRole
 * tenancy_fixed_kind     tenancy-fixed-kind-structure   landlord        OWNER          NONE        SURPLUS_RECEIVER
 *                                                       tenant_operator OPERATOR       BOTH        SURPLUS_RECEIVER
 * tenancy_share          tenancy-share-structure        landlord        OWNER          NONE        SURPLUS_RECEIVER
 *                                                       tenant_operator OPERATOR       BOTH        SURPLUS_RECEIVER
 * tenancy_cash           tenancy-cash-structure         landlord        OWNER          NONE        SURPLUS_RECEIVER
 *                                                       tenant_operator OPERATOR       BOTH        SURPLUS_RECEIVER
 * wage_farm              wage-farm-structure            landlord_operator OWNER        ORGANIZER   SURPLUS_RECEIVER
 *                                                       wage_laborer    DIRECT_LABORER PROVIDER    WAGE_EARNER
 * handicraft_workshop    handicraft-workshop-structure  workshop_owner  OWNER          ORGANIZER   SURPLUS_RECEIVER
 *                                                       artisan         DIRECT_LABORER PROVIDER    WAGE_EARNER
 * family_farm            family-farm-structure          family_farmer   MIXED          BOTH        SELF_SUBSISTENCE
 * merchant               merchant-structure             principal       OWNER          ORGANIZER   SURPLUS_RECEIVER
 *                                                       porter          DIRECT_LABORER PROVIDER    WAGE_EARNER
 *                                                       self_employed   MIXED          BOTH        SELF_SUBSISTENCE
 * displaced              displaced-structure             laborer        DIRECT_LABORER PROVIDER    WAGE_EARNER
 *                                                       destitute       DIRECT_LABORER NONE        DEPENDENT
 * </pre>
 *
 * <p>★★ <b>为什么有 {@code family_farm} 这个桥接 mode</b>：旧社会阶层的 {@code middle_peasant} / {@code
 * rich_peasant} 家户在 P1 还没有正式的自耕农 mode；{@code production-runtime} 的 {@code classStandings}
 * 需要一个稳定位置可指。{@code family_farm} 只承载“家户自耕农”的结构位置，不是探针五模式之一； 它的角色三档与 {@code LegacyClassStructure}
 * 的中农/富农位置一致（MIXED / BOTH / SELF_SUBSISTENCE）， 不发明新的分配规则。
 *
 * <p>★★ <b>P6 的 {@code merchant} mode</b>：把探针 {@code TransportTeam}/{@code MerchantTier} 的三档
 * （脚夫/个体户/老板）落成正式结构位置；层次、城市折扣、农村累积成本与运力自增长的政策值在 {@code MerchantPolicy}。 ★ P6 只让目录/结构/位置能进 {@code
 * EconomyData} 并通过构造期守卫，<b>不</b>生成商人组织（组织生成是 P6 后续/P9）。
 *
 * <p>★★ <b>P10.1 的 {@code displaced} mode</b>：失产/失业家户的一等 mode（架构 §3.3），位置 {@code
 * displaced-laborer}（DIRECT_LABORER/PROVIDER/WAGE_EARNER）与 {@code displaced-destitute}
 * （DIRECT_LABORER/NONE/DEPENDENT）。本批只落目录/结构/位置并随 {@code EconomySeeder} 的逐目录迭代进新档；
 * 进入/离开规则与人口迁移留给后续批次，不在这里接线。
 *
 * <p>★★ <b>ID 三条硬约束</b>：
 *
 * <ol>
 *   <li><b>mode id 是规范词表值</b>（{@code tenancy_fixed_kind} 等，下划线），结构 id 与位置 id 一律
 *       <b>kebab-case</b>：结构 = mode id 的下划线换连字符 + {@code -structure}；位置 = mode id 的下划线换连字符 + {@code
 *       -} + role（下划线换连字符）。全部不含 {@code "."}（地址在第一个点处切段，含点即被 ID 构造期拒绝）。
 *   <li><b>每个 mode 独立结构 + 独立位置 id</b>：{@link ProductionRole#modeId()} 在构造期判“同一个位置 id 不能在两个 mode
 *       下有两种形状”，故 {@code landlord} / {@code tenant_operator} 这类同形角色也按 mode 前缀各拿一个 id，不跨 mode 复用。
 *   <li><b>不留孤儿位置</b>：{@code classPositions()} 里的每个位置都恰属于本目录的一个 {@code ClassStructure}。 P1 曾因为没有
 *       {@code merchant} mode 而不定义商人位置；P6 追加 {@code merchant} mode 后，{@code
 *       merchant-principal}/{@code merchant-porter}/{@code merchant-self-employed} 由该 mode 的结构引用 ⇒
 *       仍无孤儿位置，不触发 {@code EconomyData} 的“位置必须至少属于一个 ClassStructure”守卫。
 * </ol>
 *
 * <p>★★ <b>{@code defaultSharesPerMille}：全零，P1 不携带人口份额意见。</b>真人口份额是 {@code EconomySeeder} 的创世参数（由实际
 * {@code classes[]} 行给出），在目录里再写一份就是第二处真相；且 P1 不接线结算，份额不驱动任何行为。 零值的语义 = “本默认结构不携带份额意见”，与 {@code
 * LegacyClassStructure} 的保守口径同款。
 *
 * <p>★★ <b>确定性与纯度</b>：全部是静态常量与纯函数；静态表按本类声明的 mode 顺序用 {@link LinkedHashMap} 保序构建； 无随机、无时钟、无
 * UUID、无文件系统；同一个查询在任意进程/任意时刻得到同一个值。{@code modes()} / {@code classStructures()} / {@code
 * classPositions()} 返回<b>防御性副本 + 不可变包装</b>，调用方拿不到也改不动类内常量表。
 *
 * <p>★ <b>P1 的行为边界</b>：本类<b>不被</b>任何生产/结算路径自动调用；它只提供默认目录与稳定查询。 运行期结算（P3+）必须读 {@code EconomyData}
 * 里的状态，而不是回头读本静态表；GM 编辑（P7）改的也是状态。
 */
public final class DefaultProductionModes {

  /** ★ 佃农制（定额实物租）：探针 {@code Tenancy.rentForm=FIXED_KIND} 的正式 mode。 */
  public static final ProductionModeId TENANCY_FIXED_KIND =
      new ProductionModeId("tenancy_fixed_kind");

  /** ★ 佃农制（分成租）：探针 {@code Tenancy.rentForm=SHARE} 的正式 mode。 */
  public static final ProductionModeId TENANCY_SHARE = new ProductionModeId("tenancy_share");

  /** ★ 佃农制（货币租）：探针 {@code Tenancy.rentForm=FIXED_CASH} 的正式 mode。 */
  public static final ProductionModeId TENANCY_CASH = new ProductionModeId("tenancy_cash");

  /** ★ 雇农制：探针 {@code WageFarm} 的正式 mode。 */
  public static final ProductionModeId WAGE_FARM = new ProductionModeId("wage_farm");

  /** ★ 手工业作坊：探针 {@code HandicraftProbeTest} 的正式 mode。 */
  public static final ProductionModeId HANDICRAFT_WORKSHOP =
      new ProductionModeId("handicraft_workshop");

  /** ★ 桥接 mode：中农/富农家户在 P1 的默认“家户自耕”位置（不是探针五模式之一）。 */
  public static final ProductionModeId FAMILY_FARM = new ProductionModeId("family_farm");

  /** ★ P6 商人：探针 {@code TransportTeam}/{@code MerchantTier} 的正式 mode（脚夫/个体户/老板三档）。 */
  public static final ProductionModeId MERCHANT = new ProductionModeId("merchant");

  /** ★★ P10.1 流民：失产/失业家户的一等 mode（架构 §3.3；本批只落目录与位置，不接线迁移）。 */
  public static final ProductionModeId DISPLACED = new ProductionModeId("displaced");

  /** ★ 位置角色名：地主（佃农制三个 mode 共用角色名，但各自有独立位置 id）。 */
  public static final String ROLE_LANDLORD = "landlord";

  /** ★ 位置角色名：佃农经营者。 */
  public static final String ROLE_TENANT_OPERATOR = "tenant_operator";

  /** ★ 位置角色名：农场经营者（雇农制里既占有又经营的主体）。 */
  public static final String ROLE_LANDLORD_OPERATOR = "landlord_operator";

  /** ★ 位置角色名：雇农（工资劳动者）。 */
  public static final String ROLE_WAGE_LABORER = "wage_laborer";

  /** ★ 位置角色名：作坊主。 */
  public static final String ROLE_WORKSHOP_OWNER = "workshop_owner";

  /** ★ 位置角色名：工匠（作坊里的直接劳动者）。 */
  public static final String ROLE_ARTISAN = "artisan";

  /** ★ 位置角色名：家户自耕农（{@code family_farm} 桥接模式的位置）。 */
  public static final String ROLE_FAMILY_FARMER = "family_farmer";

  /** ★ 位置角色名：商人本金主（P6；位置 id = {@code merchant-principal}）。 */
  public static final String ROLE_MERCHANT_PRINCIPAL = "principal";

  /** ★ {@link #ROLE_MERCHANT_PRINCIPAL} 的角色词表别名（同一个值，便于按角色名引用；不是第二处拼写点）。 */
  public static final String ROLE_PRINCIPAL = ROLE_MERCHANT_PRINCIPAL;

  /** ★ 位置角色名：脚夫（P6；位置 id = {@code merchant-porter}）。 */
  public static final String ROLE_PORTER = "porter";

  /** ★ 位置角色名：个体商户（P6；位置 id = {@code merchant-self-employed}）。 */
  public static final String ROLE_SELF_EMPLOYED = "self_employed";

  /** ★★ 位置角色名：流民劳力（P10.1；位置 id = {@code displaced-laborer}）。 */
  public static final String ROLE_DISPLACED_LABORER = "laborer";

  /** ★★ 位置角色名：流民依附者（P10.1；位置 id = {@code displaced-destitute}）。 */
  public static final String ROLE_DISPLACED_DESTITUTE = "destitute";

  /** ★ {@link #ROLE_DISPLACED_LABORER} 的短名别名（同一个值，不是第二处拼写点）。 */
  public static final String ROLE_LABORER = ROLE_DISPLACED_LABORER;

  /** ★ {@link #ROLE_DISPLACED_DESTITUTE} 的短名别名（同一个值，不是第二处拼写点）。 */
  public static final String ROLE_DESTITUTE = ROLE_DISPLACED_DESTITUTE;

  /** ★ 目录的稳定顺序（声明序 = mode 列表序 = 结构列表序 = 位置在本结构内的声明序）。 */
  private static final List<ModeSpec> MODE_SPECS = buildModeSpecs();

  /** ★ mode 表（键 = 值内 id；保序不可变）。 */
  private static final Map<ProductionModeId, ProductionMode> MODES = indexModes(MODE_SPECS);

  /** ★ 阶层结构表（键 = 值内 id；保序不可变）。 */
  private static final Map<ClassStructureId, ClassStructure> CLASS_STRUCTURES =
      indexStructures(MODE_SPECS);

  /** ★ 全局位置表（键 = 值内 id；保序不可变；每个位置恰属于一个结构）。 */
  private static final Map<ClassPositionId, ProductionRole> CLASS_POSITIONS =
      indexPositions(MODE_SPECS);

  /** ★ (mode, role) → 位置 的稳定查询索引；role 是本类的 {@code ROLE_*} 常量。 */
  private static final Map<ProductionModeId, Map<String, ProductionRole>> POSITIONS_BY_MODE_ROLE =
      indexPositionRoles(MODE_SPECS);

  private DefaultProductionModes() {}

  /**
   * ★ 默认生产方式目录（键 = {@link ProductionMode#id()}；保序）。
   *
   * <p>返回每次新建的防御性副本（仍是不可变包装）；顺序 = 本类声明的 mode 顺序（佃农制三形态 → 雇农制 → 手工业 → 桥接 {@code family_farm} → P6
   * {@code merchant} → P10.1 {@code displaced}）。
   */
  public static Map<ProductionModeId, ProductionMode> modes() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(MODES));
  }

  /** ★ 默认阶层结构目录（键 = {@link ClassStructure#id()}；保序；防御性副本）。 */
  public static Map<ClassStructureId, ClassStructure> classStructures() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(CLASS_STRUCTURES));
  }

  /** ★ 默认阶层位置目录（键 = {@link ProductionRole#id()}；保序；防御性副本）。 */
  public static Map<ClassPositionId, ProductionRole> classPositions() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(CLASS_POSITIONS));
  }

  /** ★ 按 id 查默认 mode；不在目录里 ⇒ 空（不抛：这是一个“是不是我的 mode”的判断）。 */
  public static Optional<ProductionMode> mode(ProductionModeId modeId) {
    Objects.requireNonNull(modeId, "modeId");
    return Optional.ofNullable(MODES.get(modeId));
  }

  /** ★ 按 id 查默认阶层结构；不在目录里 ⇒ 空。 */
  public static Optional<ClassStructure> classStructure(ClassStructureId structureId) {
    Objects.requireNonNull(structureId, "structureId");
    return Optional.ofNullable(CLASS_STRUCTURES.get(structureId));
  }

  /** ★ 按 id 查默认阶层位置；不在目录里 ⇒ 空。 */
  public static Optional<ProductionRole> position(ClassPositionId positionId) {
    Objects.requireNonNull(positionId, "positionId");
    return Optional.ofNullable(CLASS_POSITIONS.get(positionId));
  }

  /**
   * ★★ <b>按 (mode, role) 查默认位置 —— 本目录的稳定查询口</b>（供 seeder / 迁移器 / 后续 P6 用）。
   *
   * <p>role 是本类的 {@code ROLE_*} 常量（如 {@link #ROLE_LANDLORD}、{@link #ROLE_WAGE_LABORER}）； 未知
   * mode、未知/空白 role ⇒ 空。哪个社会阶层映射到哪个 (mode, role) 由调用方（{@code EconomySeeder}）决定， 本类不内建“贫农 → 雇农”一类判断。
   */
  public static Optional<ProductionRole> position(ProductionModeId modeId, String role) {
    Objects.requireNonNull(modeId, "modeId");
    if (role == null || role.isBlank()) {
      return Optional.empty();
    }
    Map<String, ProductionRole> byRole = POSITIONS_BY_MODE_ROLE.get(modeId);
    if (byRole == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byRole.get(role));
  }

  /** ★ {@link #position(ProductionModeId, String)} 的 id 形态（位置 id 不是调用方拼出来的第二处真相）。 */
  public static Optional<ClassPositionId> positionId(ProductionModeId modeId, String role) {
    return position(modeId, role).map(ProductionRole::id);
  }

  /**
   * 目录的稳定声明序：P1 五模式（计划序）+ 桥接 family_farm + P6 merchant + P10.1 displaced（均追加在末尾）。每项自带 mode / 结构 /
   * 位置。
   */
  private static List<ModeSpec> buildModeSpecs() {
    List<ModeSpec> specs = new ArrayList<>();
    specs.add(
        modeSpec(
            TENANCY_FIXED_KIND,
            "定额实物租佃",
            positionSpec(
                ROLE_LANDLORD,
                "地主",
                RelationToMeans.OWNER,
                LaborRole.NONE,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_TENANT_OPERATOR,
                "佃农经营者",
                RelationToMeans.OPERATOR,
                LaborRole.BOTH,
                SurplusRole.SURPLUS_RECEIVER)));
    specs.add(
        modeSpec(
            TENANCY_SHARE,
            "分成租佃",
            positionSpec(
                ROLE_LANDLORD,
                "地主",
                RelationToMeans.OWNER,
                LaborRole.NONE,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_TENANT_OPERATOR,
                "佃农经营者",
                RelationToMeans.OPERATOR,
                LaborRole.BOTH,
                SurplusRole.SURPLUS_RECEIVER)));
    specs.add(
        modeSpec(
            TENANCY_CASH,
            "货币租佃",
            positionSpec(
                ROLE_LANDLORD,
                "地主",
                RelationToMeans.OWNER,
                LaborRole.NONE,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_TENANT_OPERATOR,
                "佃农经营者",
                RelationToMeans.OPERATOR,
                LaborRole.BOTH,
                SurplusRole.SURPLUS_RECEIVER)));
    specs.add(
        modeSpec(
            WAGE_FARM,
            "雇农制农场",
            positionSpec(
                ROLE_LANDLORD_OPERATOR,
                "农场经营者",
                RelationToMeans.OWNER,
                LaborRole.ORGANIZER,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_WAGE_LABORER,
                "雇农",
                RelationToMeans.DIRECT_LABORER,
                LaborRole.PROVIDER,
                SurplusRole.WAGE_EARNER)));
    specs.add(
        modeSpec(
            HANDICRAFT_WORKSHOP,
            "手工业作坊",
            positionSpec(
                ROLE_WORKSHOP_OWNER,
                "作坊主",
                RelationToMeans.OWNER,
                LaborRole.ORGANIZER,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_ARTISAN,
                "工匠",
                RelationToMeans.DIRECT_LABORER,
                LaborRole.PROVIDER,
                SurplusRole.WAGE_EARNER)));
    specs.add(
        modeSpec(
            FAMILY_FARM,
            "家户自耕（桥接模式）",
            positionSpec(
                ROLE_FAMILY_FARMER,
                "家户自耕农",
                RelationToMeans.MIXED,
                LaborRole.BOTH,
                SurplusRole.SELF_SUBSISTENCE)));
    // ★★ P6：merchant 追加在**末尾**，P1 的六个 mode / 结构 / 位置声明序逐项不变（旧 payload 是
    //   前缀不变的，新条目只落在尾部）。
    specs.add(
        modeSpec(
            MERCHANT,
            "商人承运",
            positionSpec(
                ROLE_MERCHANT_PRINCIPAL,
                "商人本金主",
                RelationToMeans.OWNER,
                LaborRole.ORGANIZER,
                SurplusRole.SURPLUS_RECEIVER),
            positionSpec(
                ROLE_PORTER,
                "脚夫",
                RelationToMeans.DIRECT_LABORER,
                LaborRole.PROVIDER,
                SurplusRole.WAGE_EARNER),
            positionSpec(
                ROLE_SELF_EMPLOYED,
                "个体商户",
                RelationToMeans.MIXED,
                LaborRole.BOTH,
                SurplusRole.SELF_SUBSISTENCE)));
    // ★★ P10.1：displaced 追加在**末尾**，P1/P6 的七个 mode / 结构 / 位置声明序逐项不变（旧 payload 前缀不变）。
    //   角色照架构 §3.3：displaced_laborer DIRECT_LABORER / PROVIDER / WAGE_EARNER；
    //   displaced_destitute DIRECT_LABORER / NONE / DEPENDENT。位置 id 按本目录既有规则生成 =
    //   displaced-laborer / displaced-destitute（role 下划线换连字符）。
    specs.add(
        modeSpec(
            DISPLACED,
            "流民（失产失业）",
            positionSpec(
                ROLE_DISPLACED_LABORER,
                "流民劳力",
                RelationToMeans.DIRECT_LABORER,
                LaborRole.PROVIDER,
                SurplusRole.WAGE_EARNER),
            positionSpec(
                ROLE_DISPLACED_DESTITUTE,
                "流民依附者",
                RelationToMeans.DIRECT_LABORER,
                LaborRole.NONE,
                SurplusRole.DEPENDENT)));
    return Collections.unmodifiableList(specs);
  }

  /** 一个位置的出厂规格（把五个参数收成具名 record，避免在 mode 声明里重复写 new）。 */
  private static PositionSpec positionSpec(
      String role,
      String name,
      RelationToMeans relationToMeans,
      LaborRole laborRole,
      SurplusRole surplusRole) {
    return new PositionSpec(role, name, relationToMeans, laborRole, surplusRole);
  }

  /** 一个 mode 的完整出厂形状：mode + 结构 + 有序位置 + (role → 位置) 查询表。 */
  private static ModeSpec modeSpec(
      ProductionModeId modeId, String name, PositionSpec... positionSpecs) {
    ClassStructureId structureId = structureIdOf(modeId);
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>();
    Map<String, ProductionRole> byRole = new LinkedHashMap<>();
    for (PositionSpec spec : positionSpecs) {
      ProductionRole position =
          new ProductionRole(
              positionIdOf(modeId, spec.role()),
              modeId,
              spec.name(),
              spec.relationToMeans(),
              spec.laborRole(),
              spec.surplusRole());
      if (positions.putIfAbsent(position.id(), position) != null) {
        throw new IllegalStateException("默认生产方式目录内位置 id 重复: " + position.id());
      }
      if (byRole.putIfAbsent(spec.role(), position) != null) {
        throw new IllegalStateException("默认生产方式目录内位置 role 重复: " + modeId + " / " + spec.role());
      }
    }
    Map<ClassPositionId, Long> defaultShares = new LinkedHashMap<>();
    for (ClassPositionId positionId : positions.keySet()) {
      defaultShares.put(positionId, 0L);
    }
    ClassStructure structure = new ClassStructure(structureId, modeId, positions, defaultShares);
    ProductionMode mode = new ProductionMode(modeId, name, 1, structureId);
    return new ModeSpec(
        mode,
        structure,
        Collections.unmodifiableList(new ArrayList<>(byRole.values())),
        Collections.unmodifiableMap(byRole));
  }

  /** mode id → 结构 id：下划线换连字符 + {@code -structure}（唯一的拼写点）。 */
  private static ClassStructureId structureIdOf(ProductionModeId modeId) {
    return new ClassStructureId(kebabCase(modeId.value()) + "-structure");
  }

  /** (mode id, role) → 位置 id：mode 前缀保证跨 mode 唯一；角色下划线同样换成连字符（唯一的拼写点）。 */
  private static ClassPositionId positionIdOf(ProductionModeId modeId, String role) {
    return new ClassPositionId(kebabCase(modeId.value()) + "-" + kebabCase(role));
  }

  /** 规范串里的下划线换连字符；不改变其余字符（ID 构造期已保证无 {@code "."}）。 */
  private static String kebabCase(String value) {
    return value.replace('_', '-');
  }

  /** 稳定索引 mode 表；id 重复 ⇒ fail-closed（常量表写坏不许静默覆盖）。 */
  private static Map<ProductionModeId, ProductionMode> indexModes(List<ModeSpec> specs) {
    Map<ProductionModeId, ProductionMode> modes = new LinkedHashMap<>();
    for (ModeSpec spec : specs) {
      if (modes.putIfAbsent(spec.mode().id(), spec.mode()) != null) {
        throw new IllegalStateException("默认生产方式目录内 mode id 重复: " + spec.mode().id());
      }
    }
    return Collections.unmodifiableMap(modes);
  }

  /** 稳定索引结构表；id 重复 ⇒ fail-closed。 */
  private static Map<ClassStructureId, ClassStructure> indexStructures(List<ModeSpec> specs) {
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>();
    for (ModeSpec spec : specs) {
      if (structures.putIfAbsent(spec.structure().id(), spec.structure()) != null) {
        throw new IllegalStateException("默认生产方式目录内结构 id 重复: " + spec.structure().id());
      }
    }
    return Collections.unmodifiableMap(structures);
  }

  /** 稳定索引全局位置表；id 重复 ⇒ fail-closed。 */
  private static Map<ClassPositionId, ProductionRole> indexPositions(List<ModeSpec> specs) {
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>();
    for (ModeSpec spec : specs) {
      for (ProductionRole position : spec.orderedPositions()) {
        if (positions.putIfAbsent(position.id(), position) != null) {
          throw new IllegalStateException("默认生产方式目录内全局位置 id 重复: " + position.id());
        }
      }
    }
    return Collections.unmodifiableMap(positions);
  }

  /** 稳定索引 (mode, role) 查询表；同名 role 在同一 mode 下重复 ⇒ fail-closed。 */
  private static Map<ProductionModeId, Map<String, ProductionRole>> indexPositionRoles(
      List<ModeSpec> specs) {
    Map<ProductionModeId, Map<String, ProductionRole>> index = new LinkedHashMap<>();
    for (ModeSpec spec : specs) {
      if (index.putIfAbsent(spec.mode().id(), spec.positionsByRole()) != null) {
        throw new IllegalStateException("默认生产方式目录内 mode 查询索引重复: " + spec.mode().id());
      }
    }
    return Collections.unmodifiableMap(index);
  }

  /** 一个位置出厂规格（role 是 {@code ROLE_*} 常量；displayName 只用于展示）。 */
  private record PositionSpec(
      String role,
      String name,
      RelationToMeans relationToMeans,
      LaborRole laborRole,
      SurplusRole surplusRole) {}

  /** 一个 mode 的完整出厂规格；mode/结构已由本类构建，外部只读取。 */
  private record ModeSpec(
      ProductionMode mode,
      ClassStructure structure,
      List<ProductionRole> orderedPositions,
      Map<String, ProductionRole> positionsByRole) {

    /** ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP2 只看构造器；与 {@code ClassStructure} 同款）。 */
    private ModeSpec {
      orderedPositions = Collections.unmodifiableList(new ArrayList<>(orderedPositions));
      positionsByRole = Collections.unmodifiableMap(new LinkedHashMap<>(positionsByRole));
    }
  }
}
