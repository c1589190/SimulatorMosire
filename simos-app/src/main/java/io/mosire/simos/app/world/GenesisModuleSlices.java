package io.mosire.simos.app.world;

import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>新世界初始构造的 production / culture 空快照统一登记点</b>（D-031 Z4；spec §8；协同报告 §6 文件范围补全）。
 *
 * <p>★ <b>为什么必须显式登记</b>：{@code CommandBus} 在命令的 namespace 切片缺席时<b>响亮失败</b>；只在 {@code Shell} 注册
 * {@code ProductionCodec}/{@code CultureCodec} <b>不会</b>让新命令拿到自己的初始 snapshot（{@code
 * SimulationState} 的 modules 是创世时一次组好的）。⇒ 四个新世界生成器在同一处补两份空快照。
 *
 * <p>★ <b>只登记空片</b>：{@code new ProductionSnapshot(ref, timestamp, ProductionData.empty())} / {@code
 * new CultureSnapshot(ref, timestamp, CultureData.empty())}，{@code ref}/{@code timestamp}
 * 取<b>本世界自己的 meta</b>；不写任何业务数据， 不新增激活位。
 *
 * <p>★★ <b>边界</b>：本助手只服务<b>生成新世界的初始构造</b>；{@link #ensureEmptyProductionAndCulture} 用 {@code
 * putIfAbsent} ——已有条目（例如富世界资源将来自带该片）绝不覆盖，也<b>不</b>读用户旧档补兼容、<b>不</b>把旧世界伪装成可用。
 *
 * <p>★★ <b>D-032 陈旧标记清理</b>：原"三处未完成"里<b>两处已作废</b>——“生产定义尚未迁移”已在 D-031 P1 完成 （定义权威在 {@code
 * simos-production}，D-032 进一步把实例/经验/效率/覆盖也收归该模块）；“真实文化效果未启用” 已随 D2 删除整条"文化 → 生产效率倍率"通路（用户
 * U5），文化只留占位与标签面，不再有待启用的效率效果。 ★ 仅剩一处仍然成立：旧双流动机制（{@code ClassSlideSettlement} / {@code
 * ModeMigrationPolicy}）未被替换。
 */
public final class GenesisModuleSlices {

  private GenesisModuleSlices() {}

  /**
   * 在既有 modules 表上补两份空快照（已存在则不覆盖）。
   *
   * @param modules 可变 modules 表（创世构造用）；不得为 null
   * @param ref 本世界的状态引用；不得为 null
   * @param at 本世界的时刻；不得为 null
   */
  public static void ensureEmptyProductionAndCulture(
      Map<String, Snapshot> modules, StateRef ref, SimosTimestamp at) {
    Objects.requireNonNull(modules, "modules");
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(at, "at");
    modules.putIfAbsent("production", new ProductionSnapshot(ref, at, ProductionData.empty()));
    modules.putIfAbsent("culture", new CultureSnapshot(ref, at, CultureData.empty()));
  }
}
