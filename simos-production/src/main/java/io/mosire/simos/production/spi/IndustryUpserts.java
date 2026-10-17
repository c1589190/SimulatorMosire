package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★★ <b>{@code production.UpsertIndustry} 的唯一语义落点</b>（D-031 P1；原 {@code economy.UpsertIndustry}
 * 的完整载荷与 幂等/版本/被引用拒绝语义迁入 production）。
 *
 * <p>★★ <b>两条写语义</b>：
 *
 * <ul>
 *   <li><b>id 不存在 ⇒ 创建</b>：格必须已有已激活经济状态（经 {@link ProductionConsumerPort} 判）；版本号不得低于同格同 base kind
 *       的既有最大版本（版本倒退 ⇒ 具名拒），也不得与既有版本撞号（同版本不同 id ⇒ 具名拒，指向下一版本）。
 *   <li><b>id 已存在 ⇒ 原地全量替换</b>：<b>仅当没有被任何 {@code units}/{@code assetShares}/{@code relations}
 *       引用</b>时允许；被引用 ⇒ {@link Rejection}，理由带引用总数与逐类计数并指路新版本 id。逐值相同的重放是幂等 no-op。
 * </ul>
 *
 * <p>★ 定义写入只走 {@link ProductionData#withIndustry}；变更集由 {@link ProductionChangeSet#between} 从两整份状态派生
 * （铁律 5）。
 */
public final class IndustryUpserts {

  /** 命令类型（与 {@link UpsertIndustryHandler#TYPE} 同一拼写点）。 */
  public static final String COMMAND = UpsertIndustryHandler.TYPE;

  /** kind 末尾的版本后缀：{@code office_v2} ⇒ base=office、version=2；没有后缀 ⇒ version=1。 */
  private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.*)_v([0-9]+)$");

  private IndustryUpserts() {}

  /** 一次 upsert 的纯投影。 */
  public record Projection(
      IndustryDefinitionView industry,
      IndustryDefinitionView previous,
      boolean created,
      ProductionConsumerPort.IndustryReferenceCounts references,
      ProductionData projected,
      ProductionChangeSet changeSet) {}

  /** <b>业务拒绝</b>（被引用 / 版本倒退 / 空白格等）：handler 折成具名 {@code Rejected} + INFO。 */
  public static final class Rejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public Rejection(String message) {
      super(Objects.requireNonNull(message, "message"));
    }
  }

  /**
   * 纯函数：读 base 定义状态 + 当刻 state（跨切片使用情况经 {@code port} 问它）与载荷 JSON，算出目标定义、引用计数与派生变更集； 不写任何状态。
   *
   * @param state 当刻状态（{@code port} 的唯一读源；必须与 {@code base} 同源 —— 调用方在装配期保证）
   * @throws Rejection 业务规则拒绝（被引用原地改 / 版本倒退 / 同版本撞号 / 空白格 / 经济未激活）
   * @throws IllegalArgumentException 载荷形状/字段值违反既有解析器与构造期守卫
   */
  public static Projection project(
      ProductionData base, SimulationState state, ProductionConsumerPort port, String payloadJson) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(port, "port");
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = ProductionPayloads.parse(payloadJson);
    IndustryDefinitionView industry = ProductionDefinitionPayloads.industry(payload);
    IndustryId id = industry.id();
    TargetHex hex = requireTargetHex(id);
    IndustryDefinitionView previous = base.industries().get(id);
    if (previous != null) {
      ProductionConsumerPort.IndustryReferenceCounts references =
          port.industryReferences(state, id);
      if (previous.equals(industry)) {
        return new Projection(
            industry, previous, false, references, base, ProductionChangeSet.between(base, base));
      }
      if (!references.isEmpty()) {
        throw new Rejection(referencedRejection(id, hex, references));
      }
      return apply(base, industry, previous, false, references);
    }
    requireActivatedHex(base, state, port, hex, id);
    requireVersionProgression(base, id, hex);
    return apply(
        base, industry, null, true, new ProductionConsumerPort.IndustryReferenceCounts(0, 0, 0));
  }

  /** 唯一写口：既有 {@code withIndustry}（保序 Map 复制 + put）；变更集从两整份状态派生（铁律 5）。 */
  private static Projection apply(
      ProductionData base,
      IndustryDefinitionView industry,
      IndustryDefinitionView previous,
      boolean created,
      ProductionConsumerPort.IndustryReferenceCounts references) {
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>(base.industries());
    industries.put(industry.id(), industry);
    ProductionData projected = base.withIndustries(industries);
    return new Projection(
        industry,
        previous,
        created,
        references,
        projected,
        ProductionChangeSet.between(base, projected));
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

  /** 创建路径：格必须已有已激活经济状态（经组合根端口判；空白格不得凭空造产业）。 */
  private static void requireActivatedHex(
      ProductionData base,
      SimulationState state,
      ProductionConsumerPort port,
      TargetHex hex,
      IndustryId id) {
    if (!port.isHexEconomicallyActive(state, hex.key())) {
      throw new Rejection(
          COMMAND + " 的格 " + hex.key() + " 还没有已激活的经济状态（无产业/家户行），拒绝对空白格创建产业: id=" + id.value());
    }
  }

  /** 创建路径：同格同 base kind 的版本号不得倒退、不得与既有版本撞号；提示指向的下一版本 id。 */
  private static void requireVersionProgression(ProductionData base, IndustryId id, TargetHex hex) {
    KindVersion incoming = kindVersion(id, true);
    int maxVersion = 0;
    IndustryId maxId = null;
    for (IndustryId existingId : IndustryHexKeys.at(base.industries(), hex.q(), hex.r())) {
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
      IndustryId id, TargetHex hex, ProductionConsumerPort.IndustryReferenceCounts references) {
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

  private static String suggestedId(String baseKind, int version, String hexKey) {
    return baseKind + "_v" + version + "@" + hexKey;
  }

  /**
   * kind 的 base/版本拆解：{@code office} ⇒ (office,1)、{@code office_v2} ⇒ (office,2)。
   *
   * <p>★ {@code strict=true}（新载荷）：版本号必须是规范十进制（≥ 1、无前导 0、不超 {@link Integer#MAX_VALUE}）。
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

  /** 解析出的格键 + 规范整数对。 */
  private record TargetHex(String key, int q, int r) {}

  /** kind 的 base 与版本。 */
  private record KindVersion(String baseKind, int version) {}
}
