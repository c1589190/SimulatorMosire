package io.mosire.simos.economy.migrate;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassPosition.LaborRole;
import io.mosire.simos.economy.model.ClassPosition.RelationToMeans;
import io.mosire.simos.economy.model.ClassPosition.SurplusRole;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.ProductionMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>旧档默认阶层结构（E1b）—— "旧档默认 mode / 阶层结构 / 阶层位置"的唯一来源。</b>
 *
 * <p>★★ <b>它解决什么问题</b>：E1a 已把 {@code ProductionMode} / {@code ClassStructure} / {@code
 * ClassPosition} / {@code HouseholdClassMembership} 四张新表落进 {@code EconomyData}，但旧档只有 {@code
 * HouseholdEconomy.view.stratum}（{@link SocialClassId}）这一件事实。本类把"旧档的 7
 * 个社会阶层"翻译成一套<b>确定、可复现、可审计</b>的默认阶层结构：一个默认 {@link ProductionMode}、一个默认 {@link ClassStructure}、7 个
 * {@link ClassPosition}。迁移器（{@code ClassPositionResolver.seedLegacyClassMemberships}）与只读解析器都以本类为唯一拼写点
 * —— 其它任何地方不得再写第二套默认映射。
 *
 * <p>★★ <b>ID 三条硬约束</b>（E1b 判据）：
 *
 * <ol>
 *   <li><b>稳定且无 {@code "."}</b>：默认 mode = {@code legacy}，默认结构 = {@code legacy-class-structure}， 位置
 *       = {@link #POSITION_ID_PREFIX} + {@link SocialClassId#value()}（如 {@code
 *       legacy-poor_peasant}）。
 *   <li><b>位置 id 含社会阶层值</b>：后缀就是 {@link SocialClassId} 的规范词表值 ⇒ 人和机器都能从位置 id 直接读出它对应哪个社会阶层（{@link
 *       #socialClassOf(ClassPositionId)} 是这条规则的逆，解析失败返回空）。
 *   <li><b>全局唯一</b>：{@code EconomyData.classPositions} 是按位置 id 索引的<b>全局</b>表；前缀把旧档位置 限定在 {@code
 *       legacy-} 命名空间内，将来 E6 的新 mode 造自己的位置时不会与旧档名字撞车（同一个 {@code ClassPositionId} 不能在两个 mode
 *       下有两种形状，见 {@code ClassPosition.modeId} 的构造期判据）。
 * </ol>
 *
 * <p>★★ <b>7 个位置的 role 映射（唯一的权威表；改动只许改这里）</b>：
 *
 * <pre>
 * SocialClassId      位置 id                   relationToMeans  laborRole  surplusRole
 * poor_peasant       legacy-poor_peasant        MIXED            BOTH       SELF_SUBSISTENCE
 * middle_peasant     legacy-middle_peasant      MIXED            BOTH       SELF_SUBSISTENCE
 * rich_peasant       legacy-rich_peasant        MIXED            BOTH       SELF_SUBSISTENCE
 * landlord           legacy-landlord            OWNER            NONE       SURPLUS_RECEIVER
 * landless_laborer   legacy-landless_laborer    DIRECT_LABORER   PROVIDER   WAGE_EARNER
 * artisan            legacy-artisan             MIXED            BOTH       WAGE_EARNER
 * official           legacy-official            MIXED            NONE       SURPLUS_RECEIVER
 * </pre>
 *
 * <p>★ <b>逐组理由（不是随手填的）</b>：
 *
 * <ul>
 *   <li>{@code landlord}：占有生产资料（{@code OWNER}）、自己不承担劳动角色（{@code NONE}）、以地租/分成形式 索取剩余（{@code
 *       SURPLUS_RECEIVER}）。这是"固定经济位置"，不是"收入永远不变"；
 *   <li>{@code poor/middle/rich_peasant}：家庭经营 + 家庭劳动是它们共同的结构底色（{@code MIXED}：既非纯 所有者、也非纯雇工；{@code
 *       BOTH}：经营与劳动都参与；{@code SELF_SUBSISTENCE}：产出归本户）。三者之间的 差别（自有地多少、是否净雇工、债务压力）是<b>运行期可观察量</b>，由旧
 *       {@code HouseholdClassRule} / 将来的生产关系表达，<b>不</b>在 position 上多写一个硬阈值 —— 富农可能净雇工，但那是 relation
 *       层的事实， 不是"富农位置"的结构定义；
 *   <li>{@code landless_laborer}：不占有生产资料、以提供劳动参与（{@code DIRECT_LABORER} + {@code
 *       PROVIDER}）、靠工资维生（{@code WAGE_EARNER}）；
 *   <li>{@code artisan}：自有/自营工具与作坊（{@code MIXED}）、既经营又劳动（{@code BOTH}），但在本模型的分配 关系里更接近计件/工资/分成报酬，故选
 *       {@code WAGE_EARNER} 而非 {@code SELF_SUBSISTENCE} —— 若选后者， 它与三个 peasant 位置在结构上只剩名字差别，E6 迁移时
 *       "谁是手工业者" 就没有结构维可读。E2 若需要区分 "独立匠户"与"受雇匠人"，应由更细的位置/关系表达，而不是把这一档改成自给自足；
 *   <li>{@code official}：{@code MIXED + NONE + SURPLUS_RECEIVER}。<b>它仅作旧档兼容占位，不改变旧行为</b> ——
 *       官署依附/公职在旧档里没有对应的生产关系模板，E1b 只保证它有一个可解析的位置，不把任何分配规则画进 position。
 * </ul>
 *
 * <p>★★ <b>{@code defaultSharesPerMille}：全零，仅兼容占位，不驱动生产。</b>7 个键都在、值全为 0 —— 这是刻意的保守选择：
 *
 * <ol>
 *   <li>真实的旧档初始人口份额（贫农 450 / 中农 350 / 富农 150 / 地主 50）是 <b>app 侧 {@code EconomySeeder}
 *       的创世参数</b>，且随版本变化；在 economy 里复制一份数字就是第二处真相，两边一旦漂开，"默认结构" 会 给出一个谁也没在用的份额；
 *   <li>E1b 不接线生产/结算；本结构只回答"旧档的每个 stratum 该解析成哪个位置"，<b>不</b>回答"新世界人口怎么分"；
 *   <li>将来由显式命令/创世器按自己的版本写入真实份额。零值在这里的语义 = "本兼容结构不携带份额意见"。
 * </ol>
 *
 * <p>★★ <b>确定性与幂等</b>：本类全部是静态常量与纯函数；位置按 {@link SocialClassId#all()} 的稳定顺序生成， {@code LinkedHashMap}
 * 保序；无随机、无时钟、无 UUID、无文件系统。同一个 {@link SocialClassId} 在任意进程/任意 时刻都得到同一个 {@link ClassPosition}（值相等）。
 *
 * <p>★ <b>E1b 的行为边界</b>：本类<b>不</b>被任何生产路径自动调用 —— 不接着 {@code EconomyData} 构造器、不进 {@code
 * EconomySeedHandler} / {@code EconomyStateBuilder} / {@code settle*}；只有未来的显式迁移器或读口调用 {@link
 * ClassPositionResolver#seedLegacyClassMemberships(EconomyData)} 时，这些常量才会被物化进状态。
 */
public final class LegacyClassStructure {

  /** ★ 默认生产方式 id：{@code legacy}（稳定规范串、不含 {@code "."}）。 */
  public static final ProductionModeId DEFAULT_MODE_ID = new ProductionModeId("legacy");

  /** ★ 默认阶层结构 id：{@code legacy-class-structure}（稳定规范串、不含 {@code "."}）。 */
  public static final ClassStructureId DEFAULT_CLASS_STRUCTURE_ID =
      new ClassStructureId("legacy-class-structure");

  /**
   * ★ 默认阶层位置 id 的前缀（{@code "legacy-"}）：位置 id = 前缀 + {@link SocialClassId#value()}。
   *
   * <p>它同时服务两件事：① 把旧档位置限定在 {@code legacy-} 命名空间，避免与将来新 mode 的位置 id 撞车； ② 让 {@link
   * #socialClassOf(ClassPositionId)} 能用同一处前缀做逆解析（不是第二套映射表）。
   */
  public static final String POSITION_ID_PREFIX = "legacy-";

  /**
   * ★ 旧档种子写入 {@code HouseholdClassMembership.reason} 的具名值：{@code legacyDefault:seedClassStanding}。
   *
   * <p>命名风格与结算关账的 reason 一致（{@code retainedCurrentView:noObservableEvidence} 那一类）：
   * 冒号前是来源、冒号后是动作。它是状态字段的可审计文本，不是显示给玩家的文本。
   */
  public static final String SEED_REASON = "legacyDefault:seedClassStanding";

  /** ★ 默认生产方式本体：version = 1，绑定 {@link #DEFAULT_CLASS_STRUCTURE_ID}。 */
  private static final ProductionMode DEFAULT_MODE =
      new ProductionMode(DEFAULT_MODE_ID, "旧档默认生产方式", 1, DEFAULT_CLASS_STRUCTURE_ID);

  /** ★ 7 个默认位置（键 = 值内 id；保序不可变）。由 {@link #buildDefaultPositions()} 一次生成。 */
  private static final Map<ClassPositionId, ClassPosition> DEFAULT_POSITIONS =
      buildDefaultPositions();

  /** ★ 默认阶层结构：包含全部 7 个位置 + 全零默认份额（份额口径见类注释）。 */
  private static final ClassStructure DEFAULT_CLASS_STRUCTURE =
      new ClassStructure(
          DEFAULT_CLASS_STRUCTURE_ID,
          DEFAULT_MODE_ID,
          DEFAULT_POSITIONS,
          zeroDefaultShares(DEFAULT_POSITIONS));

  private LegacyClassStructure() {}

  /** ★ 默认 {@link ProductionModeId}（只读查询）。 */
  public static ProductionModeId defaultModeId() {
    return DEFAULT_MODE_ID;
  }

  /** ★ 默认 {@link ClassStructureId}（只读查询）。 */
  public static ClassStructureId defaultClassStructureId() {
    return DEFAULT_CLASS_STRUCTURE_ID;
  }

  /** ★ 默认生产方式本体（只读查询；record 不可变，可直接共享）。 */
  public static ProductionMode defaultMode() {
    return DEFAULT_MODE;
  }

  /** ★ 默认阶层结构本体（只读查询；其 positions/shares 已由 {@link ClassStructure} 冻结）。 */
  public static ClassStructure defaultClassStructure() {
    return DEFAULT_CLASS_STRUCTURE;
  }

  /**
   * ★ 7 个默认位置（键 = {@link ClassPositionId}；保序）。
   *
   * <p>返回的是每次新建的防御性副本（仍是不可变包装）：调用方拿不到也改不动类内的常量表；顺序与 {@link SocialClassId#all()} 相同，因而同一输入恒得逐项相同的序列。
   */
  public static Map<ClassPositionId, ClassPosition> defaultClassPositions() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULT_POSITIONS)); // ★ 防御性副本
  }

  /**
   * ★★ <b>社会阶层 → 默认阶层位置 id 的唯一映射</b>：{@code legacy-} + {@link SocialClassId#value()}。
   *
   * <p>它是纯函数、无副作用；对 7 个词表值全部有定义（见类注释的表）。调用方不需要知道前缀怎么拼 —— 前缀只在本类里读。
   *
   * @param socialClass 旧档社会阶层；不得为 null
   * @return 该社会阶层在本默认结构里的位置 id；同一输入恒得同一输出
   */
  public static ClassPositionId positionIdOf(SocialClassId socialClass) {
    Objects.requireNonNull(socialClass, "socialClass");
    return new ClassPositionId(POSITION_ID_PREFIX + socialClass.value());
  }

  /**
   * ★ <b>社会阶层 → 默认位置本体</b>（只读便利查询；角色三档从返回值读，不需要调用方自己再拼映射）。
   *
   * @param socialClass 旧档社会阶层；不得为 null
   * @return 该社会阶层的默认 {@link ClassPosition}
   * @throws IllegalStateException 默认结构缺少该位置（意味着常量表与词表漂开，fail-closed 不猜）
   */
  public static ClassPosition defaultClassPosition(SocialClassId socialClass) {
    Objects.requireNonNull(socialClass, "socialClass");
    ClassPosition position = DEFAULT_POSITIONS.get(positionIdOf(socialClass));
    if (position == null) {
      throw new IllegalStateException("旧档默认阶层结构缺少社会阶层的位置：" + socialClass);
    }
    return position;
  }

  /**
   * ★ <b>默认位置 id → 社会阶层</b>：{@link #positionIdOf(SocialClassId)} 的逆（只读查询）。
   *
   * <p>口径：不是 {@code legacy-} 前缀、或后缀不在 {@link SocialClassId} 词表内 ⇒ {@link Optional#empty()}；
   * <b>不抛</b> —— 这是一个"是不是我的位置"的判断，坏输入不是状态错误。{@code null} 同样返回空（便于在断言里 直接调用而不先判空）。
   *
   * @param positionId 待判的位置 id（可为 null）
   * @return 对应的社会阶层；不是本默认结构的位置 ⇒ 空
   */
  public static Optional<SocialClassId> socialClassOf(ClassPositionId positionId) {
    if (positionId == null) {
      return Optional.empty();
    }
    String value = positionId.value();
    if (!value.startsWith(POSITION_ID_PREFIX)) {
      return Optional.empty();
    }
    String socialClassValue = value.substring(POSITION_ID_PREFIX.length());
    try {
      return Optional.of(SocialClassId.parse(socialClassValue));
    } catch (IllegalArgumentException notADefaultPosition) {
      return Optional.empty();
    }
  }

  /** ★ 该 {@link ProductionModeId} 是不是旧档默认 mode（只读查询；null ⇒ false）。 */
  public static boolean isLegacyDefault(ProductionModeId modeId) {
    return DEFAULT_MODE_ID.equals(modeId);
  }

  /** ★ 该 {@link ClassStructureId} 是不是旧档默认结构（只读查询；null ⇒ false）。 */
  public static boolean isLegacyDefault(ClassStructureId structureId) {
    return DEFAULT_CLASS_STRUCTURE_ID.equals(structureId);
  }

  /** ★ 该 {@link ClassPositionId} 是不是 7 个默认位置之一（只读查询；null ⇒ false）。 */
  public static boolean isLegacyDefault(ClassPositionId positionId) {
    return socialClassOf(positionId).isPresent();
  }

  /** 按 {@link SocialClassId#all()} 的稳定顺序生成 7 个位置；键 = 值内 id。 */
  private static Map<ClassPositionId, ClassPosition> buildDefaultPositions() {
    Map<ClassPositionId, ClassPosition> positions = new LinkedHashMap<>();
    for (SocialClassId socialClass : SocialClassId.all()) {
      ClassPosition position = positionOf(socialClass);
      positions.put(position.id(), position);
    }
    return Collections.unmodifiableMap(positions); // ★ 冻在赋值处
  }

  /** 一个社会阶层的位置本体：id 由 {@link #positionIdOf} 生成，三个 role 由 {@link #roleSpecOf} 裁决。 */
  private static ClassPosition positionOf(SocialClassId socialClass) {
    RoleSpec spec = roleSpecOf(socialClass);
    return new ClassPosition(
        positionIdOf(socialClass),
        DEFAULT_MODE_ID,
        spec.name(),
        spec.relationToMeans(),
        spec.laborRole(),
        spec.surplusRole());
  }

  /**
   * ★★ <b>7 个位置的 role 唯一裁决点</b>（类注释的表格逐行落在这里）。按 {@link SocialClassId#value()} 分支， 词表外的值走 {@code
   * default} 当场抛 —— 将来若给 {@code SocialClassId} 追加新阶层，默认结构的构建会立刻
   * fail-closed，而不是悄悄少一个位置（"少一个位置"正是迁移时最难查的那种静默漂移）。
   */
  private static RoleSpec roleSpecOf(SocialClassId socialClass) {
    return switch (socialClass.value()) {
      case "poor_peasant" ->
          new RoleSpec("贫农", RelationToMeans.MIXED, LaborRole.BOTH, SurplusRole.SELF_SUBSISTENCE);
      case "middle_peasant" ->
          new RoleSpec("中农", RelationToMeans.MIXED, LaborRole.BOTH, SurplusRole.SELF_SUBSISTENCE);
      case "rich_peasant" ->
          new RoleSpec("富农", RelationToMeans.MIXED, LaborRole.BOTH, SurplusRole.SELF_SUBSISTENCE);
      case "landlord" ->
          new RoleSpec("地主", RelationToMeans.OWNER, LaborRole.NONE, SurplusRole.SURPLUS_RECEIVER);
      case "landless_laborer" ->
          new RoleSpec(
              "无地雇农", RelationToMeans.DIRECT_LABORER, LaborRole.PROVIDER, SurplusRole.WAGE_EARNER);
      case "artisan" ->
          new RoleSpec("手工业者", RelationToMeans.MIXED, LaborRole.BOTH, SurplusRole.WAGE_EARNER);
      case "official" ->
          new RoleSpec("官署公职", RelationToMeans.MIXED, LaborRole.NONE, SurplusRole.SURPLUS_RECEIVER);
      default -> throw new IllegalStateException("旧档默认阶层结构尚未裁决该社会阶层的角色：" + socialClass);
    };
  }

  /** 默认份额：对每个位置写确定性 0 —— 语义见类注释（仅兼容占位，不驱动生产）。 */
  private static Map<ClassPositionId, Long> zeroDefaultShares(
      Map<ClassPositionId, ClassPosition> positions) {
    Map<ClassPositionId, Long> shares = new LinkedHashMap<>();
    for (ClassPositionId positionId : positions.keySet()) {
      shares.put(positionId, 0L);
    }
    return Collections.unmodifiableMap(shares); // ★ 冻在赋值处
  }

  /**
   * 一个位置的不可变角色模板（只在本类的 7 个分支里构造）。
   *
   * <p>它不落进任何状态：{@link ClassPosition} 才是权威形状；本 record 只是"常量表在代码里的中间变量"， 避免 7 个分支各写 6 个参数的重复。
   */
  private record RoleSpec(
      String name, RelationToMeans relationToMeans, LaborRole laborRole, SurplusRole surplusRole) {}
}
