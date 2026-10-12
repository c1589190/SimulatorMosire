package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.unit.UnitId;
import java.util.Optional;

/**
 * 军队归属（阶段 12，2026-10-01 用户裁定 6/8）：把 {@code ArmyId} 与一个**单位根**（{@code UnitId}）关联起来， 并可选地记一个**认领的 GOV
 * 单位**（{@code masterGovUnitId}）。
 *
 * <p>★★ <b>Army 不再认识 Nation</b>：用户原话「Army 我从来没要求算战力，只是根据 GOV 的这个包对应语义，把 Nation 一起拆除去而已」。 国家实体
 * （{@code Nation}）2026-10-12 批次 3c 起住 {@code simos-gov}（"外交是 gov 的事"）；军队只认 GOV 编制，不把国家 id 硬映射成 GOV。
 *
 * <p>★ <b>编制本身仍在 unit</b>（铁律 3）：sd 只存**归属关系**，不拥有编制树；{@code rootUnit} 与 {@code masterGovUnitId}
 * 的存在性由命令期经 {@code state.module("unit")} 只读（spec §四）。{@code masterGovUnitId} 是 {@link Optional} 且
 * Optional 本身非 null；缺省 = 未认主子。
 *
 * <p>★ <b>旧档兼容</b>：旧 JSON 里的 {@code "nationId"} 键已被本类型移除；类型上的
 * {@code @JsonIgnoreProperties("nationId")} 只忽略这一个已退役键、**不关掉全局的 fail-on-unknown**，也不把 nationId 映射成
 * GOV。
 *
 * <p>★★ <b>2026-10-12 批次 3c：deprecated 的 4 参源码兼容构造器（{@code (id, nationId, rootUnit,
 * name)}）<b>整体删除</b></b> ——它收的 {@code NationId} 已随外交搬进 {@code simos-gov}，留着它就等于给 {@code sd → gov}
 * 开一条<b>被 enforcer 禁的依赖边</b>。依据 AGENTS §一.11（「旧设计和数据类型直接重建不用留，删就删了」）：那只是"给旧调用点省一次改动"的源码兼容壳，不是世界事实。★
 * 生产代码一直走 canonical 4 参（第二参 {@code Optional<UnitId>}），故本删只影响仍用旧签名的夹具/测试（记入台账）。
 *
 * <p>★ <b>不算战力</b>：本类型不持有任何力量/战斗力字段；{@code simos-army} 一期也只做视野派生（裁定 6）。
 *
 * @param id 军队 id（非 null）
 * @param masterGovUnitId 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param rootUnit 军队根单位（非 null）
 * @param name 展示名（非空白）
 */
@JsonIgnoreProperties({"nationId"})
public record Army(ArmyId id, Optional<UnitId> masterGovUnitId, UnitId rootUnit, String name) {

  public Army {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (masterGovUnitId == null) {
      throw new IllegalArgumentException("masterGovUnitId 不得为 null（未认主子用 Optional.empty()）");
    }
    if (rootUnit == null) {
      throw new IllegalArgumentException("rootUnit 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
  }
}
