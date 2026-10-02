package io.mosire.simos.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code store_meta} 通用键值 API 的护栏（2026-10-02 C5；设计稿 §七「core：SqliteStore 加一个通用键值存取 API， 不落
 * revision」）。
 *
 * <p>被测面：{@link SqliteStore#readMeta}/{@link SqliteStore#writeMeta} 的读/写/upsert、保留键与入参门禁， 以及 {@link
 * CoreSimos#readStoreMeta}/{@link CoreSimos#writeStoreMeta} 薄委托后 {@code branches}/{@code revisions}
 * 仍为空的对外观测面。全部用 {@link TempDir} 下的临时 SQLite 文件，不碰真实世界库；{@code time_base} / {@code format_version}
 * 两行由 {@link SqliteStore#open} 自动种入，通用 API 不读也不写它们。
 */
class StoreMetaTest {

  private static final String TIME_BASE = "time_base";
  private static final String FORMAT_VERSION = "format_version";

  @TempDir Path tempDir;

  private SqliteStore store;

  @BeforeEach
  void openStore() {
    store = SqliteStore.open(tempDir.resolve("timeline.db"));
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  /**
   * 判据：设计稿 §七 core 通用键值 API——库里没有该键 ⇒ 空 {@code Optional}（不是 null、不是默认值）。
   *
   * <p>判别力：把 {@code readMeta} 改成返回 {@code Optional.of("")} 或误落到别的键，本例如实红。
   */
  @Test
  void missingKeyReadsAsEmptyOptional() {
    assertThat(store.readMeta("no-such-key")).isEmpty();
  }

  /**
   * 判据：设计稿 §七——写后读 = 原值；同键再写 = upsert（后写覆盖，不抛主键冲突）。
   *
   * <p>判别力：把 UPSERT 退化成普通 INSERT ⇒ 第二次写撞主键抛错（红）；把读固定成查 {@code time_base} ⇒ 值断言红。
   */
  @Test
  void writeThenReadRoundTripsAndSecondWriteUpserts() {
    store.writeMeta("calendar", "{\"v\":1}");
    assertThat(store.readMeta("calendar")).contains("{\"v\":1}");

    store.writeMeta("calendar", "{\"v\":2}");
    assertThat(store.readMeta("calendar")).contains("{\"v\":2}");
  }

  /**
   * 判据：设计稿 §七——key 非空非空白；{@code null}、空串、纯空白都必须在碰 SQL 之前抛 IAE。
   *
   * <p>判别力：把 {@code key.isBlank()} 放宽成 {@code key.isEmpty()} ⇒ 纯空白键用例红；把校验挪到 prepare 之后 ⇒ 异常类型 从
   * IAE 变 SQLException（红）；只查不拦 ⇒ 空串会真的插进 PRIMARY KEY（红）。
   */
  @Test
  void blankOrNullKeysAreRejectedBeforeAnyWrite() {
    assertThatThrownBy(() -> store.readMeta(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");
    assertThatThrownBy(() -> store.readMeta(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");
    assertThatThrownBy(() -> store.readMeta("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");

    assertThatThrownBy(() -> store.writeMeta(null, "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");
    assertThatThrownBy(() -> store.writeMeta("", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");
    assertThatThrownBy(() -> store.writeMeta("   ", "v"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非空文本");

    // 拒绝要发生在写之前：空键 / 空白键都不该留下半条行。
    assertThat(rawMeta("")).isNull();
    assertThat(rawMeta("   ")).isNull();
  }

  /**
   * 判据：设计稿 §七——value 为 {@code null} 抛具名 IAE（不是 NPE），且不得顶掉已有值。
   *
   * <p>判别力：去掉 null 检查 ⇒ SQLite 的 NOT NULL 报错会以 IllegalStateException 逃出（红）；先写后校验 ⇒ 值断言红。
   */
  @Test
  void nullValueIsRejectedAndDoesNotOverwriteTheExistingValue() {
    store.writeMeta("k", "keep");
    assertThatThrownBy(() -> store.writeMeta("k", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
    assertThat(store.readMeta("k")).contains("keep");
  }

  /**
   * 判据（与任务书存在分歧，如实钉住实际契约）：任务书写「空白值拒绝」，而生产实现的 value 门禁只查 {@code value == null}（{@code
   * SqliteStore.java:330-336} 与 {@code requireUsableMetaKey}（348-359）都只看 key）——空串 / 纯空白串也会被当作合法
   * **不透明字符串**写入并原样读回。分歧已随任务报告上报；若生产侧将来补上 blank 校验，本用例应改为断言 IAE。
   *
   * <p>判别力：本用例只证明「值不被 core 改写」这一条（写什么读什么）；它**不**为「空白应被拒绝」背书。
   */
  @Test
  void blankValuesAreCurrentlyAcceptedAndRoundTripByteForByte() {
    store.writeMeta("empty", "");
    assertThat(store.readMeta("empty")).contains("");

    store.writeMeta("spaces", "   ");
    assertThat(store.readMeta("spaces")).contains("   ");
  }

  /**
   * 判据：设计稿 §七——{@code time_base}/{@code format_version} 是库级门禁键，通用 API **读也拒绝**（防止误碰）。
   *
   * <p>判别力：把 {@code RESERVED_META_KEYS} 检查从读侧拿掉 ⇒ 会读到 {@code Optional.of("DAY")} 而不是 IAE（红）。
   */
  @Test
  void reservedKeysAreRejectedOnRead() {
    assertThatThrownBy(() -> store.readMeta(TIME_BASE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("保留键")
        .hasMessageContaining(TIME_BASE);
    assertThatThrownBy(() -> store.readMeta(FORMAT_VERSION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("保留键")
        .hasMessageContaining(FORMAT_VERSION);
  }

  /**
   * 判据：设计稿 §七——保留键只走 {@link SqliteStore} 自己的 SQL，通用写口拒绝且**原有值不变**。
   *
   * <p>判别力：放开写口 ⇒ 本用例的 IAE 断言红，且 {@code time_base} 会被改成 HOUR（"值不变"断言红）。
   */
  @Test
  void reservedKeysAreRejectedOnWriteAndTheirValuesStayUnchanged() {
    assertThat(rawMeta(TIME_BASE)).isEqualTo(Envelope.TIME_BASE_DAY);
    assertThat(rawMeta(FORMAT_VERSION)).isEqualTo("1");

    assertThatThrownBy(() -> store.writeMeta(TIME_BASE, "HOUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("保留键");
    assertThatThrownBy(() -> store.writeMeta(FORMAT_VERSION, "99"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("保留键");

    assertThat(rawMeta(TIME_BASE)).isEqualTo(Envelope.TIME_BASE_DAY);
    assertThat(rawMeta(FORMAT_VERSION)).isEqualTo("1");
  }

  /**
   * 判据：设计稿 §七「不落 revision（改锚点不改变世界状态……）」——写 {@code store_meta} 不得新增 revision/事件/分支。
   *
   * <p>判别力：把 {@code writeMeta} 改成经 {@code inTransaction + appendRevision} 落一行 ⇒ 本用例的计数/分支集合当场红。
   */
  @Test
  void writeMetaCreatesNoRevisionRowAndNoBranch() {
    store.writeMeta("calendar", "{}");

    assertThat(revisionCount()).isZero();
    assertThat(branchesInRevisions()).isEmpty();
    assertThat(eventCount()).isZero();
  }

  /**
   * 同判据，但断言 **CoreSimos 公开 API** 的可观测面：{@code writeStoreMeta} 后 {@code branches()} 仍空、{@code
   * revisions("main")} 仍空。
   *
   * <p>判别力：若未来有人在 core 薄委托里顺手 {@code submit} 一条配置命令，本用例会看到非空 branches（红）。
   */
  @Test
  void coreFacadeWriteStoreMetaLeavesBranchesAndRevisionsEmpty() {
    try (CoreSimos core =
        new CoreSimos(
            new CoreConfig(tempDir.resolve("core-store"), 100, SimosObjectMapper.create()))) {
      core.writeStoreMeta("calendar", "{\"k\":1}");

      assertThat(core.readStoreMeta("calendar")).contains("{\"k\":1}");
      assertThat(core.branches()).isEmpty();
      assertThat(core.revisions(new BranchId("main"))).isEmpty();
    }
  }

  /** 直读 {@code store_meta}（绕过通用 API 的门禁，保留键才能这样读）。 */
  private String rawMeta(String key) {
    return store.inTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement("SELECT value FROM store_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? rs.getString(1) : null;
            }
          }
        });
  }

  private long revisionCount() {
    return countRows("revisions");
  }

  private long eventCount() {
    return countRows("events");
  }

  private long countRows(String table) {
    return store.inTransaction(
        conn -> {
          try (Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
          }
        });
  }

  private List<String> branchesInRevisions() {
    return store.inTransaction(
        conn -> {
          try (Statement s = conn.createStatement();
              ResultSet rs =
                  s.executeQuery("SELECT DISTINCT branch FROM revisions ORDER BY branch")) {
            List<String> branches = new ArrayList<>();
            while (rs.next()) {
              branches.add(rs.getString(1));
            }
            return branches;
          }
        });
  }
}
