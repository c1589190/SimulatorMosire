package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.CalendarDefaults;
import io.mosire.simos.calendar.SeasonBoundary;
import io.mosire.simos.calendar.TropicalModel;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CalendarService} 的装载与应用护栏（设计稿 §七「app CalendarService：启动时读 store_meta.calendar，
 * 缺省用默认配置，不静默写盘；apply 先校验、再持久化、后原子换快照」；§九 默认算法；§十判据 5）。
 *
 * <p>覆盖：空 store 的缺省值/来源且不写盘；坏 JSON、非法 epoch、雨季窗口倒置、只给一个分带界、不支持历法这五条 fail-closed（抛错、不换快照、不落盘）；dryRun
 * 不写盘不换快照且 warnings 含锚点平移语义；正式 apply 落盘 + 换快照 + 重启式复读逐项相等；{@code STORE_META_KEY} 常量。全部用 {@link
 * TempDir} 下的临时 SQLite 库，走公开 API，不碰真实世界库。
 */
class CalendarServiceTest {

  @TempDir Path tempDir;

  private CoreSimos core;

  @BeforeEach
  void openCore() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
  }

  @AfterEach
  void closeCore() {
    core.close();
  }

  /**
   * 判据：设计稿 §七/§九 + 计划 C5——空 store ⇒ {@link CalendarDefaults}（§九 表逐项），来源必须可辨为 default， 分带未配置 ⇒
   * zoneSource=fallback；且 load 缺省**不静默写盘**。
   *
   * <p>判别力：把缺省 load 写成"顺手 writeStoreMeta"⇒ calendar 键非空（红）；把来源标成 store ⇒ 来源断言红；静默改 §九 任一默认值 ⇒
   * 逐项断言红；把 zoneSource 标成 default ⇒ fallback 断言红。
   */
  @Test
  void emptyStoreLoadsDefaultsWithoutWriting() {
    CalendarService service = CalendarService.load(core);

    CalendarConfig config = service.config();
    assertThat(config.version()).isEqualTo(CalendarConfig.VERSION_1);
    assertThat(config.calendar()).isEqualTo(CalendarDefaults.CALENDAR_ID);
    assertThat(config.epoch()).isEqualTo(CalendarDefaults.EPOCH_DATE);
    assertThat(config.epochText()).isEqualTo("1445-01-01");
    assertThat(config.seasonBoundary()).isEqualTo(SeasonBoundary.SOLAR_TERM);
    assertThat(config.tropicalModel()).isEqualTo(TropicalModel.RAINY_DRY);
    assertThat(config.tropicalRainyStartLongitude()).isEqualTo(45.0);
    assertThat(config.tropicalRainyEndLongitude()).isEqualTo(165.0);
    assertThat(config.northIsNegative()).isTrue();
    assertThat(config.northMax()).isNull();
    assertThat(config.southMin()).isNull();

    assertThat(service.calendarSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(service.seasonSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(service.zoneSource()).isEqualTo(CalendarConfig.Source.FALLBACK);
    assertThat(config).isEqualTo(CalendarConfig.defaults());

    assertThat(core.readStoreMeta(CalendarService.STORE_META_KEY)).isEmpty();
    assertThat(core.branches()).isEmpty();
  }

  /**
   * 判据：设计稿 §七 fail-closed——坏 JSON 必须当场抛（不回落默认、不换快照、不落盘）。
   *
   * <p>判别力：把 load 的 catch 改成 return defaults() 或吞掉解析失败 ⇒ 异常断言红；把坏值改写成默认 JSON ⇒ raw 值断言红。
   */
  @Test
  void malformedJsonFailsClosedWithoutSwapOrWrite() {
    assertLoadFailsClosed("{not-json", "不是合法 JSON");
  }

  /**
   * 判据：设计稿 §七 fail-closed + §三「2/30 非法」——epoch 必须过儒略历月内校验。
   *
   * <p>判别力：去掉 {@code JulianCalendar.dayNumberOf(epoch)} 那次正算（或只信 CalendarDate 的 [1,31]）⇒ 1445-02-30
   * 会被静默收下，本用例红。
   */
  @Test
  void invalidEpochFailsClosedWithoutSwapOrWrite() {
    assertLoadFailsClosed("{\"epoch\":\"1445-02-30\"}", "1445-2-30");
  }

  /**
   * 判据：设计稿 §七 + §九 表——雨季窗口必须 {@code 0 ≤ start < end ≤ 360}；倒置必须当场拒。
   *
   * <p>判别力：把 CalendarConfig/SeasonSettings 的 {@code start < end} 校验删掉 ⇒ 165/45 会被收下，本用例红。
   */
  @Test
  void rainyWindowStartNotLessThanEndFailsClosedWithoutSwapOrWrite() {
    assertLoadFailsClosed(
        "{\"tropicalRainyStartLongitude\":165.0,\"tropicalRainyEndLongitude\":45.0}", "雨季窗口");
  }

  /**
   * 判据：D-018 补裁/设计稿 §七——{@code northMax}/{@code southMin} 必须成对；只给一个 ⇒ fail-closed（不静默补 null）。
   *
   * <p>判别力：把成对校验删掉 ⇒ 只给 northMax 会被收成"半个分带"，本用例红。
   */
  @Test
  void singleBandBoundFailsClosedWithoutSwapOrWrite() {
    assertLoadFailsClosed("{\"northMax\":-40}", "成对配置");
  }

  /**
   * 判据：设计稿 §七——{@code calendar} 只认 {@code julian}；不支持历法（gregorian）必须当场拒，不静默换算法。
   *
   * <p>判别力：把 CalendarConfig 的 {@code JulianCalendar.ID.equals(calendar)} 校验删掉 ⇒ gregorian 被收下，本用例红。
   */
  @Test
  void unsupportedCalendarFailsClosedWithoutSwapOrWrite() {
    assertLoadFailsClosed("{\"calendar\":\"gregorian\"}", "julian");
  }

  /**
   * 判据：设计稿 §七——dryRun=true 只预览：不写 store、不换内存快照；锚点变更时 warnings 必须给出"历史显示日期整体平移" 语义（按文案关键词断言，不写死整句）。
   *
   * <p>判别力：把 dryRun 分支去掉（照写盘/换快照）⇒ raw 值与 {@code service.config()} 断言当场红；删掉 warning 或只在正式 apply 时给
   * ⇒ 关键词断言红；忘记把预览结果的来源归一成 store ⇒ preview 来源断言红。
   */
  @Test
  void dryRunDoesNotWriteOrSwapAndWarnsAboutAnchorShift() {
    CalendarService service = CalendarService.load(core);
    CalendarConfig before = service.config();
    CalendarConfig candidate =
        candidate(service.config(), new CalendarDate(1445, 2, 1), null, null);

    CalendarService.ApplyResult preview = service.apply(candidate, true);

    assertThat(preview.applied()).isFalse();
    assertThat(preview.config().epochText()).isEqualTo("1445-02-01");
    assertThat(preview.config().calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(preview.config().seasonSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(preview.date()).isEqualTo(new CalendarDate(1445, 2, 1));
    assertThat(preview.warnings())
        .anySatisfy(
            warning ->
                assertThat(warning)
                    .contains("平移")
                    .contains("历史")
                    .contains("日期")
                    .contains("dryRun"));
    assertThat(preview.warnings()).anySatisfy(warning -> assertThat(warning).contains("分带未配置"));

    assertThat(service.config()).isEqualTo(before);
    assertThat(service.calendarSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(service.seasonSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(core.readStoreMeta(CalendarService.STORE_META_KEY)).isEmpty();
    assertThat(core.branches()).isEmpty();
  }

  /**
   * 判据：设计稿 §七 apply 三段式 + §十判据 5——正式 apply 先落盘、后换快照；重启（同一 store 新开 CoreSimos）后配置仍在，
   * 逐项相等；tick→日期取新锚点。
   *
   * <p>判别力：只换快照不写盘 ⇒ 重启复读拿回默认（epoch/日期断言红）；只写盘不换快照 ⇒ {@code service.dateOfTick(0)} 仍是旧锚点（红）；来源归一漏掉
   * store 标记 ⇒ 来源断言红；日期少加/多加一天 ⇒ 1445-06-01 oracle 红。
   */
  @Test
  void applyPersistsSwapsSnapshotAndRestartReloadsIdenticalConfig() {
    CalendarService service = CalendarService.load(core);
    CalendarConfig candidate = candidate(service.config(), new CalendarDate(1445, 2, 1), -40L, 40L);

    CalendarService.ApplyResult applied = service.apply(candidate, false, 120L, new HexCoord(0, 0));

    assertThat(applied.applied()).isTrue();
    // oracle：儒略 1445-02-01（JDN 2248876）+ 120 天 = 1445-06-01。
    assertThat(applied.date()).isEqualTo(new CalendarDate(1445, 6, 1));
    assertThat(service.config().epoch()).isEqualTo(new CalendarDate(1445, 2, 1));
    assertThat(service.dateOfTick(0)).isEqualTo(new CalendarDate(1445, 2, 1));
    assertThat(service.dateOfTick(120)).isEqualTo(new CalendarDate(1445, 6, 1));
    assertThat(service.calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(service.seasonSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(service.zoneSource()).isEqualTo(CalendarConfig.Source.STORE);

    String stored = core.readStoreMeta(CalendarService.STORE_META_KEY).orElseThrow();
    assertThat(stored).contains("\"epoch\":\"1445-02-01\"");
    assertThat(stored).contains("\"northMax\":-40");
    assertThat(stored).contains("\"southMin\":40");
    // store_meta 不落 revision：正式 apply 同样不产生分支/修订行。
    assertThat(core.branches()).isEmpty();

    CalendarConfig expected = service.config();
    CalendarService reloaded = reopenAndLoad();
    assertConfigEquals(reloaded.config(), expected);
    assertThat(reloaded.calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(reloaded.seasonSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(reloaded.zoneSource()).isEqualTo(CalendarConfig.Source.STORE);
  }

  /**
   * 判据：设计稿 §七 + §十判据 5——分带未配置的候选正式 apply 后 zoneSource 必须是 fallback（不得标 store），落盘后再复读 仍一致。
   *
   * <p>判别力：把 {@code candidate.bandsConfigured() ? STORE : FALLBACK} 归一去反 ⇒ 本用例来源断言红。
   */
  @Test
  void applyWithoutBandsKeepsZoneFallbackAfterRestart() {
    CalendarService service = CalendarService.load(core);
    CalendarConfig candidate =
        candidate(service.config(), new CalendarDate(1445, 2, 1), null, null);

    CalendarService.ApplyResult applied = service.apply(candidate, false);

    assertThat(applied.applied()).isTrue();
    assertThat(service.calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
    assertThat(service.zoneSource()).isEqualTo(CalendarConfig.Source.FALLBACK);

    String stored = core.readStoreMeta(CalendarService.STORE_META_KEY).orElseThrow();
    assertThat(stored).contains("\"northMax\":null");
    assertThat(stored).contains("\"southMin\":null");

    CalendarConfig expected = service.config();
    CalendarService reloaded = reopenAndLoad();
    assertConfigEquals(reloaded.config(), expected);
    assertThat(reloaded.zoneSource()).isEqualTo(CalendarConfig.Source.FALLBACK);
  }

  /**
   * 判据：设计稿 §七——配置键就是 {@code store_meta.calendar}（core 只当不透明字符串存取）。
   *
   * <p>判别力：常量被改/拼错 ⇒ 读写会落到另一个键（真实读写路径的 store 断言也会红），本用例把常量本身钉死。
   */
  @Test
  void storeMetaKeyIsCalendar() {
    assertThat(CalendarService.STORE_META_KEY).isEqualTo("calendar");
  }

  /**
   * 五条 fail-closed 的公共装置：先以空 store 取得基线服务快照，再种入坏 JSON，断言 load 当场抛 ISE（cause 为 IAE）、坏值原样留在
   * store（没被改写成默认）、基线服务快照不变、没有新 revision。
   *
   * <p>判别力：任一条被静默回落/吞错/顺手改写 store，上述四个观测面之一当场红。
   */
  private void assertLoadFailsClosed(String badJson, String expectedKeyword) {
    CalendarService baseline = CalendarService.load(core);
    CalendarConfig before = baseline.config();
    core.writeStoreMeta(CalendarService.STORE_META_KEY, badJson);

    assertThatThrownBy(() -> CalendarService.load(core))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fail-closed")
        .hasMessageContaining(expectedKeyword)
        .cause()
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(core.readStoreMeta(CalendarService.STORE_META_KEY)).contains(badJson);
    assertThat(baseline.config()).isEqualTo(before);
    assertThat(baseline.calendarSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(baseline.seasonSource()).isEqualTo(CalendarConfig.Source.DEFAULT);
    assertThat(core.branches()).isEmpty();
  }

  /** 候选配置：换 epoch、按需换分带；来源按数值自洽（分带已配置 ⇒ store，未配置 ⇒ fallback）。 */
  private static CalendarConfig candidate(
      CalendarConfig base, CalendarDate epoch, Long northMax, Long southMin) {
    return new CalendarConfig(
        base.version(),
        base.calendar(),
        epoch,
        base.seasonBoundary(),
        base.tropicalModel(),
        base.tropicalRainyStartLongitude(),
        base.tropicalRainyEndLongitude(),
        base.northIsNegative(),
        northMax,
        southMin,
        base.calendarSource(),
        base.seasonSource(),
        northMax == null ? CalendarConfig.Source.FALLBACK : CalendarConfig.Source.STORE);
  }

  /** 重启式复读：关掉当前 core，用同一个 store 目录新开一个 CoreSimos 再 load。 */
  private CalendarService reopenAndLoad() {
    core.close();
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    return CalendarService.load(core);
  }

  /** 逐项相等（不靠 record equals 一把梭：字段漂移时要能指出是哪一项）。 */
  private static void assertConfigEquals(CalendarConfig actual, CalendarConfig expected) {
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.version()).isEqualTo(expected.version());
    assertThat(actual.calendar()).isEqualTo(expected.calendar());
    assertThat(actual.epoch()).isEqualTo(expected.epoch());
    assertThat(actual.seasonBoundary()).isEqualTo(expected.seasonBoundary());
    assertThat(actual.tropicalModel()).isEqualTo(expected.tropicalModel());
    assertThat(actual.tropicalRainyStartLongitude())
        .isEqualTo(expected.tropicalRainyStartLongitude());
    assertThat(actual.tropicalRainyEndLongitude()).isEqualTo(expected.tropicalRainyEndLongitude());
    assertThat(actual.northIsNegative()).isEqualTo(expected.northIsNegative());
    assertThat(actual.northMax()).isEqualTo(expected.northMax());
    assertThat(actual.southMin()).isEqualTo(expected.southMin());
    assertThat(actual.calendarSource()).isEqualTo(expected.calendarSource());
    assertThat(actual.seasonSource()).isEqualTo(expected.seasonSource());
    assertThat(actual.zoneSource()).isEqualTo(expected.zoneSource());
  }
}
