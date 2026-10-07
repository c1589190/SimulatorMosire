package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★★ <b>{@code economy.UpsertIndustry} 的唯一语义落点</b>（Z1a）：创建／原地修改一份产业模板 （{@link Industry}），命令 handler
 * 与 app 的 GM 窄工具共用本类的纯函数（照 {@code EconomyGmAdjustments.project} 的先例）。
 *
 * <p>★★ <b>载荷形状 = {@code economy.Seed} 的 {@code industries[]} 节点</b>（模板字段逐字同形）： {@code
 * {id,name,regime,cycleDays,capacityPerUnit,dailyInputPerUnit?,dailyLaborPerUnit?,laborPerUnit?,outputPerUnit?,
 * cycleInputPerUnit?,slots,allocation}}。字段解析走 {@link
 * EconomyPayloads#parseIndustryNode(JsonNode)}（内部就是 Seed 的 {@code
 * industry(...)}）——<b>本类不另写第二套字段读取</b>；构造期守卫（{@code capacityPerUnit} 非空且逐值 &gt; 0、{@code cycleDays
 * ≥ 1}、{@code slots} 非空且 id 不重复、各表逐值 ≥ 0、{@code allocation} 合法且本轮只支持 {@code Split}）沿用 {@link
 * Industry} 与 Seed 解析器的既有判据，只把失败折成命令/工具的具名拒绝。
 *
 * <p>★★ <b>版本口径（冻结，spec §4.2）</b>：{@code IndustryId} 仍是 {@code <kind>@<q>_<r>}；<b>新版本 = 新的 kind 后缀
 * id</b>（{@code office@0_0 → office_v2@0_0}），kind 末尾的 {@code _v<N>} 就是版本号（缺省 = 1）。不改 {@link
 * Industry} record 形状、不改 Codec/ChangeSet 形状；老 unit 引用老 id、逐值零影响。{@code IndustryHexKeys.hexKeyOf}
 * 只按最后一个 {@code '@'} 取格键，{@code _v2} 不影响它。
 *
 * <p>★★ <b>两条写语义</b>：
 *
 * <ul>
 *   <li><b>id 不存在 ⇒ 创建</b>：格必须已有已激活的经济状态（{@link EconomySeedHandler#occupiedHexKeys(EconomyData)}
 *       的同一判据，拒绝对空白格造产业）；版本号不得低于同格同 base kind 的既有最大版本（版本倒退 ⇒ 具名拒），也不得与既有 版本撞号（同版本不同 id ⇒
 *       具名拒，指向下一版本）。
 *   <li><b>id 已存在 ⇒ 原地全量替换</b>：<b>仅当没有被任何 {@code units}/{@code assetShares}/{@code relations}
 *       引用</b>时允许；被引用 ⇒ {@link Rejection}，理由带 <b>引用总数与逐类计数</b>并指路新版本 id。逐值相同的重放是幂等
 *       no-op（不当作"改"，也不受引用守卫拦——它一个字节都不改）。
 * </ul>
 *
 * <p>★ <b>不从本类发明任何状态组件</b>：写口是既有的 {@link EconomyData#withIndustries(Map)}；变更集由 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)} 从两整份状态派生（铁律 5）；表的保序/不可变由 {@code EconomyData}
 * 与 {@link Industry} 的构造期冻结承担（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，无 {@code
 * Map.copyOf}）。
 *
 * <p>★ <b>引用守卫只认 spec 冻结的三张表</b>（units / assetShares / relations）：{@code relations} 的行业引用经它的 unit
 * 键间接成立（{@code ProductionRules} 自身没有 industry 字段，见其类注），故按“该 relation 的 unit 的 industry” 计数。{@code
 * outputQuantityOverrides} 等按 industry 键的派生表不在此列——它们不改变 unit 的生产行为（本区不做，测试要点见 Z1a 台账）。
 */
public final class EconomyIndustryUpserts {

  /** 命令类型（与 {@link EconomyUpsertIndustryHandler#TYPE} 同一拼写点）。 */
  public static final String COMMAND = EconomyUpsertIndustryHandler.TYPE;

  /** kind 末尾的版本后缀：{@code office_v2} ⇒ base=office、version=2；没有后缀 ⇒ version=1。 */
  private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.*)_v([0-9]+)$");

  /** {@code economy.Seed} 旧 Industry 实例字段（R3B.2 已迁出模板；本命令不收，避免"发了没生效"）。 */
  private static final List<String> LEGACY_INSTANCE_FIELDS =
      List.of("operator", "capacity", "progressDays", "cycleLaborMilli", "cycleInputUsedMilli");

  private EconomyIndustryUpserts() {}

  /**
   * 一次 upsert 的引用计数（units / assetShares / relations 三张表；{@code relations} 经其 unit 的 industry 间接命中）。
   */
  public record ReferenceCounts(int units, int assetShares, int relations) {

    public ReferenceCounts {
      if (units < 0 || assetShares < 0 || relations < 0) {
        throw new IllegalArgumentException(
            "ReferenceCounts 不得为负: units="
                + units
                + ", assetShares="
                + assetShares
                + ", relations="
                + relations);
      }
    }

    /** 三张表的引用总数（理由串与"是否允许原地改"的判据都用它）。 */
    public int total() {
      return units + assetShares + relations;
    }

    public boolean isEmpty() {
      return total() == 0;
    }
  }

  /**
   * 一次 upsert 的纯投影：{@code industry} = 目标模板（after）、{@code previous} = 既有模板（创建时 null）、 {@code
   * created} = 是否为新建、{@code references} = 既有引用计数、{@code projected} = 写出后的完整状态、 {@code changeSet} =
   * 由两整份状态派生的变更集。
   */
  public record Projection(
      Industry industry,
      Industry previous,
      boolean created,
      ReferenceCounts references,
      EconomyData projected,
      EconomyChangeSet changeSet) {}

  /**
   * <b>业务拒绝</b>（世界语义：被引用 / 版本倒退 / 空白格等）：handler 折成具名 {@code Rejected} + INFO； 工具折成 {@code
   * REJECTED}。载荷形状/类型错是普通 {@link IllegalArgumentException}，与它区分开。
   */
  public static final class Rejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public Rejection(String message) {
      super(Objects.requireNonNull(message, "message"));
    }
  }

  /**
   * 纯函数：读 base 状态与载荷 JSON，算出目标模板、引用计数与派生变更集；不写任何状态。
   *
   * @throws Rejection 业务规则拒绝（被引用原地改 / 版本倒退 / 同版本撞号 / 空白格 / 经济未激活）
   * @throws IllegalArgumentException 载荷形状/字段值违反既有解析器与构造期守卫（含旧实例字段、坏 id/hex）
   * @throws IllegalStateException 写出后的状态违反 {@link EconomyData} 跨表契约（装配/一致性故障，不是载荷错）
   */
  public static Projection project(EconomyData base, String payloadJson) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
    rejectLegacyInstanceFields(payload);
    Industry industry = EconomyPayloads.parseIndustryNode(payload);
    IndustryId id = industry.id();
    TargetHex hex = requireTargetHex(id);
    Industry previous = base.industries().get(id);
    if (previous != null) {
      ReferenceCounts references = references(base, id);
      if (previous.equals(industry)) {
        // ★ 逐值相同的重放 = 幂等 no-op：它一个字节都不改，不是"原地改"，引用守卫不适用（照 upsertProductionMode 先例）。
        return new Projection(
            industry, previous, false, references, base, EconomyChangeSet.between(base, base));
      }
      if (!references.isEmpty()) {
        throw new Rejection(referencedRejection(id, hex, references));
      }
      return apply(base, industry, previous, false, references);
    }
    requireActivatedHex(base, hex, id);
    requireVersionProgression(base, id, hex);
    return apply(base, industry, null, true, new ReferenceCounts(0, 0, 0));
  }

  /** 既有 id 的引用计数（units 值 / assetShares 值 / relations 的 unit 键；同一条 unit 与 relation 各计一次）。 */
  private static ReferenceCounts references(EconomyData base, IndustryId id) {
    int units = 0;
    for (ProductionProcess unit : base.units().values()) {
      if (unit.industry().equals(id)) {
        units++;
      }
    }
    int assetShares = 0;
    for (OwnershipStake share : base.assetShares().values()) {
      if (share.industry().equals(id)) {
        assetShares++;
      }
    }
    int relations = 0;
    for (ProductionUnitId activity : base.relations().keySet()) {
      ProductionProcess unit = base.units().get(activity);
      if (unit != null && unit.industry().equals(id)) {
        relations++;
      }
    }
    return new ReferenceCounts(units, assetShares, relations);
  }

  /** 唯一写口：既有 {@code withIndustries}（保序 Map 复制 + put）；变更集从两整份状态派生（铁律 5）。 */
  private static Projection apply(
      EconomyData base,
      Industry industry,
      Industry previous,
      boolean created,
      ReferenceCounts references) {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>(base.industries());
    industries.put(industry.id(), industry);
    EconomyData projected;
    try {
      projected = base.withIndustries(industries);
    } catch (IllegalArgumentException e) {
      // ★ withIndustries 的 IAE = 跨表契约被违反（如 unit 进度 > 新 cycleDays），不是载荷形状错：
      //   折成 IllegalStateException ⇒ handler 记 ERROR 并向上抛（不折 Rejected），避免把一致性问题说成业务拒绝。
      throw new IllegalStateException(
          COMMAND + " 写出后的 EconomyData 违反跨表契约（一致性故障，非载荷问题）: " + e.getMessage(), e);
    }
    return new Projection(
        industry,
        previous,
        created,
        references,
        projected,
        EconomyChangeSet.between(base, projected));
  }

  /** id 形状 = {@code <kind>@<q>_<r>}：不得含 {@code '.'}；格键必须是规范整数对（拒绝前导 0 / 正号 / -0）。 */
  private static TargetHex requireTargetHex(IndustryId id) {
    String value = id.value();
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          COMMAND + " 的产业 id 不得含 '.'（AddressParser 会在第一个 '.' 处切开名字，产业 id 只用 '@' 分隔）: " + value);
    }
    int at = value.lastIndexOf('@');
    if (at <= 0 || at == value.length() - 1) {
      throw new IllegalArgumentException(COMMAND + " 的产业 id 必须形如 <kind>@<q>_<r>: " + value);
    }
    String kind = value.substring(0, at);
    if (kind.isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 的产业 id 缺少非空 kind: " + value);
    }
    String hexKey = value.substring(at + 1);
    int separator = hexKey.lastIndexOf('_');
    if (separator <= 0 || separator == hexKey.length() - 1) {
      throw new IllegalArgumentException(COMMAND + " 的产业 id 格键必须形如 <q>_<r>: " + value);
    }
    int q;
    int r;
    try {
      q = Integer.parseInt(hexKey.substring(0, separator));
      r = Integer.parseInt(hexKey.substring(separator + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(COMMAND + " 的产业 id 格键不是 <q>_<r> 整数对: " + value, e);
    }
    if (!IndustryHexKeys.hexKey(q, r).equals(hexKey)) {
      throw new IllegalArgumentException(
          COMMAND
              + " 的产业 id 格键不是规范拼写（不得有前导 0、正号或 -0）: "
              + value
              + "，应为 "
              + IndustryHexKeys.hexKey(q, r));
    }
    return new TargetHex(hexKey, q, r);
  }

  /** 创建路径：格必须已有已激活经济状态（与 {@code economy.Seed} 追加播种同一判据；空白格不得凭空造产业）。 */
  private static void requireActivatedHex(EconomyData base, TargetHex hex, IndustryId id) {
    if (base.meta().isEmpty()) {
      throw new Rejection(COMMAND + " 需要经济已激活（EconomyData.meta 为空）才能创建产业: id=" + id.value());
    }
    if (!EconomySeedHandler.occupiedHexKeys(base).contains(hex.key())) {
      throw new Rejection(
          COMMAND + " 的格 " + hex.key() + " 还没有已激活的经济状态（无产业/家户行），拒绝对空白格创建产业: id=" + id.value());
    }
  }

  /** 创建路径：同格同 base kind 的版本号不得倒退、不得与既有版本撞号；提示指向的下一版本 id。 */
  private static void requireVersionProgression(EconomyData base, IndustryId id, TargetHex hex) {
    KindVersion incoming = kindVersion(id, true);
    int maxVersion = 0;
    IndustryId maxId = null;
    for (IndustryId existingId : IndustryHexKeys.at(base.industries(), hex.q(), hex.r())) {
      // ★ 既有 id 用宽容解析：旧档/Seed 可能把 `_v0`/`_v01` 之类当作普通 kind 拼写；只有**新载荷**的版本拼法才严格拒。
      KindVersion existing = kindVersion(existingId, false);
      if (!existing.baseKind().equals(incoming.baseKind()) || existing.version() <= maxVersion) {
        continue;
      }
      maxVersion = existing.version();
      maxId = existingId;
    }
    if (maxId == null) {
      return;
    }
    String suggestion = suggestedId(incoming.baseKind(), maxVersion + 1, hex.key());
    if (incoming.version() < maxVersion) {
      throw new Rejection(
          COMMAND
              + " 拒绝版本倒退：格 "
              + hex.key()
              + " 的 kind '"
              + incoming.baseKind()
              + "' 已有版本 "
              + maxVersion
              + "（"
              + maxId.value()
              + "），收到 "
              + id.value()
              + "（版本 "
              + incoming.version()
              + "）；请用新版本 id（如 "
              + suggestion
              + "）");
    }
    if (incoming.version() == maxVersion) {
      throw new Rejection(
          COMMAND
              + " 拒绝同版本重复 id：格 "
              + hex.key()
              + " 的 kind '"
              + incoming.baseKind()
              + "' 版本 "
              + maxVersion
              + " 已由 "
              + maxId.value()
              + " 占用；请推进版本号（如 "
              + suggestion
              + "）");
    }
  }

  /** 被引用 ⇒ 原地改的具名拒（带引用总数/逐类计数 + 具体的新版本 id 建议）。 */
  private static String referencedRejection(
      IndustryId id, TargetHex hex, ReferenceCounts references) {
    // ★ 既有 id 用宽容解析（理由同上：旧档的非规范 _v 拼写不该让"被引用"这条业务拒绝变成载荷错）。
    KindVersion version = kindVersion(id, false);
    return COMMAND
        + " 拒绝原地修改被引用的产业模板：id="
        + id.value()
        + " 有 "
        + references.total()
        + " 个引用（units="
        + references.units()
        + ", assetShares="
        + references.assetShares()
        + ", relations="
        + references.relations()
        + "），请用新版本 id（如 "
        + suggestedId(version.baseKind(), version.version() + 1, hex.key())
        + "）";
  }

  /** 版本 id 建议：{@code <base>_v<N>@<hex>}。 */
  private static String suggestedId(String baseKind, int version, String hexKey) {
    return baseKind + "_v" + version + "@" + hexKey;
  }

  /**
   * kind 的 base/版本拆解：{@code office} ⇒ (office,1)、{@code office_v2} ⇒ (office,2)。
   *
   * <p>★ {@code strict=true}（新载荷）：版本号必须是规范十进制（≥ 1、无前导 0、不超 {@link Integer#MAX_VALUE}）—— {@code
   * _v0}/{@code _v01} 等拼法具名拒，避免两个 id 表达同一版本。★ {@code strict=false}（既有状态里的 id）：非规范 {@code _v...}
   * 后缀读作**普通 kind 文本**（version=1），不让旧档里的怪拼法把业务守卫变成载荷错。
   */
  private static KindVersion kindVersion(IndustryId id, boolean strict) {
    String value = id.value();
    int at = value.lastIndexOf('@');
    String kind = at < 0 ? value : value.substring(0, at);
    Matcher matcher = VERSION_SUFFIX.matcher(kind);
    if (!matcher.matches()) {
      return new KindVersion(kind, 1);
    }
    String baseKind = matcher.group(1);
    String digits = matcher.group(2);
    if (baseKind.isBlank()) {
      if (strict) {
        throw new IllegalArgumentException(COMMAND + " 的产业 id 版本后缀前缺少 kind: " + value);
      }
      return new KindVersion(kind, 1);
    }
    if (digits.length() > 1 && digits.charAt(0) == '0') {
      if (strict) {
        throw new IllegalArgumentException(COMMAND + " 的产业版本号不得有前导 0: " + value);
      }
      return new KindVersion(kind, 1);
    }
    long parsed;
    try {
      parsed = Long.parseLong(digits);
    } catch (NumberFormatException e) {
      if (strict) {
        throw new IllegalArgumentException(COMMAND + " 的产业版本号超出范围: " + value, e);
      }
      return new KindVersion(kind, 1);
    }
    if (parsed < 1L || parsed > Integer.MAX_VALUE) {
      if (strict) {
        throw new IllegalArgumentException(
            COMMAND + " 的产业版本号必须 ∈ [1, " + Integer.MAX_VALUE + "]: " + value);
      }
      return new KindVersion(kind, 1);
    }
    return new KindVersion(baseKind, (int) parsed);
  }

  /** {@code economy.Seed} 的旧 Industry 实例字段在本命令一律具名拒（模板字段请用 {@code capacityPerUnit} 等）。 */
  private static void rejectLegacyInstanceFields(JsonNode payload) {
    for (String field : LEGACY_INSTANCE_FIELDS) {
      if (payload.has(field)) {
        throw new IllegalArgumentException(
            COMMAND
                + " 不接受旧 Industry 实例字段 "
                + field
                + "（本命令只收 R3B.2 起的产业模板；旧形状的 unit/进度/份额请走 economy.Seed 的 units[]/assetShares[]）");
      }
    }
  }

  /** 解析出的格键 + 规范整数对（q/r 供 {@link IndustryHexKeys#at(Map, int, int)} 取同格产业）。 */
  private record TargetHex(String key, int q, int r) {}

  /** kind 的 base 与版本（见 {@link #kindVersion(IndustryId, boolean)}）。 */
  private record KindVersion(String baseKind, int version) {}
}
