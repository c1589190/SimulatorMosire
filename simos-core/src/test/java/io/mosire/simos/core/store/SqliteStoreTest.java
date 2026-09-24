package io.mosire.simos.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SqliteStore 的护栏自证：schema 形态（2 张领域表 + store_meta 标签表 + 4 索引 + 三条 PRAGMA）、R2（外键真的生效）、
 * R13（事务原子性，不留残行）、C23（单连接 + 私有锁互斥）、打开/关闭的幂等与收尾、**日制时间基门禁**（2026-09-24）。
 *
 * <p>全部 SQL 直写（不经 Timeline）：Task 5 是存储底座，上游类型还不存在也不该存在。
 */
class SqliteStoreTest {

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

  /** spec §3.2 + §6.1 的冻结 schema + 日制裁定的 store_meta：恰 3 表 + 4 索引，多一张少一张都算漂移。 */
  @Test
  void schemaIsExactlyThreeTablesAndFourIndexes() {
    List<String> names =
        store.inTransaction(
            conn -> {
              try (Statement s = conn.createStatement();
                  ResultSet rs =
                      s.executeQuery(
                          "SELECT name FROM sqlite_master WHERE type IN ('table','index')"
                              + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                  out.add(rs.getString(1));
                }
                return out;
              }
            });
    assertThat(names)
        .containsExactly(
            "events",
            "idx_events_correlation_id_seq",
            "idx_events_type_correlation_id_seq",
            "idx_revisions_correlation",
            "idx_revisions_parent",
            "revisions",
            "store_meta");
  }

  /** ★ 日制裁定：空库首启就地打标 {@code time_base=DAY} + {@code format_version}（空库还没有语义事实，打标不改任何东西）。 */
  @Test
  void freshStoreIsLabeledAsDayBased() {
    assertThat(storeMeta("time_base")).isEqualTo(Envelope.TIME_BASE_DAY);
    assertThat(storeMeta("format_version")).isEqualTo("1");
  }

  /**
   * ★★ **旧档门禁**：已有 revision 却没有 {@code time_base} 标签 ⇒ 打开即拒（旧档的 tick 代表小时， 按天重放会把"持续 24 小时"读成"持续 24
   * 天"）。造法：正常建库 → 插一行 revision → 删掉标签两行， 模拟日制裁定之前的库（它根本没有 store_meta 表）。
   */
  @Test
  void legacyStoreWithRevisionsButNoTimeBaseIsRejectedAtOpen() {
    Path db = tempDir.resolve("legacy.db");
    SqliteStore legacy = SqliteStore.open(db);
    legacy.inTransaction(
        conn -> {
          insertRevision(conn, "main", 1, null, null);
          return null;
        });
    legacy.inTransaction(
        conn -> {
          try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM store_meta");
          }
          return null;
        });
    legacy.close();

    assertThatThrownBy(() -> SqliteStore.open(db))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("time_base")
        .hasMessageContaining("旧档");
  }

  /** 标签存在但值不是 DAY（异基）⇒ 同样拒绝打开。 */
  @Test
  void storeWithANonDayTimeBaseIsRejectedAtOpen() {
    Path db = tempDir.resolve("hourly.db");
    SqliteStore hourly = SqliteStore.open(db);
    hourly.inTransaction(
        conn -> {
          try (Statement s = conn.createStatement()) {
            s.execute("UPDATE store_meta SET value = 'HOUR' WHERE key = 'time_base'");
          }
          return null;
        });
    hourly.close();

    assertThatThrownBy(() -> SqliteStore.open(db))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DAY");
  }

  /** 打开时的三条 PRAGMA 都真的在生效（WAL / busy_timeout / 外键）。 */
  @Test
  void theThreePragmasAreActuallyInEffect() {
    assertThat(pragma("journal_mode")).isEqualTo("wal");
    assertThat(pragma("busy_timeout")).isEqualTo("5000");
    assertThat(pragma("foreign_keys")).isEqualTo("1");
  }

  /** IF NOT EXISTS 幂等：同一文件二次打开不抛；close 后不留 -wal / -shm（spec §6.1 收尾）。 */
  @Test
  void reopenIsIdempotentAndCloseLeavesNoWalFiles() {
    Path db = tempDir.resolve("reopen.db");
    SqliteStore first = SqliteStore.open(db);
    first.close();
    assertThat(db).exists();
    SqliteStore second = SqliteStore.open(db);
    second.close();
    assertThat(tempDir.resolve("reopen.db-wal")).doesNotExist();
    assertThat(tempDir.resolve("reopen.db-shm")).doesNotExist();
  }

  /** R2：插一条 parent 不存在的 revision ⇒ 抛（证明 PRAGMA foreign_keys = ON 不是装饰）。 */
  @Test
  void foreignKeysRejectAMissingParent() {
    store.inTransaction(
        conn -> {
          insertRevision(conn, "main", 1, null, null);
          return null;
        });
    assertThatThrownBy(
            () ->
                store.inTransaction(
                    conn -> {
                      insertRevision(conn, "main", 2, "ghost", 99L);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事务失败，已回滚")
        // FK 的报错文本在 cause 上：外层只负责"事务失败，已回滚"
        .cause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("FOREIGN KEY");
    // 创世行在，越界的子行不在：回滚把整个事务撤干净了
    assertThat(countRevisions()).isEqualTo(1L);
  }

  /** R13：事务里写一行 revision + 一条违反 NOT NULL 的 events 行 ⇒ 抛，且 revisions 不留残行。 */
  @Test
  void notNullViolationLeavesNoResidue() {
    long baseline = countRevisions();
    assertThat(baseline).isZero();
    assertThatThrownBy(
            () ->
                store.inTransaction(
                    conn -> {
                      insertRevision(conn, "main", 1, null, null);
                      try (Statement s = conn.createStatement()) {
                        s.execute(
                            "INSERT INTO events (ts, type, agent)"
                                + " VALUES ('2026-09-18T08:00:00Z', NULL, 'core')");
                      }
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事务失败，已回滚");
    assertThat(countRevisions()).isEqualTo(baseline);
  }

  /** 正常路径：work 的返回值原样透传，且 COMMIT 真的发生（第二个事务读得到）。 */
  @Test
  void inTransactionCommitsAndReturnsTheWorkResult() {
    Long rowsInsideWork =
        store.inTransaction(
            conn -> {
              insertRevision(conn, "main", 1, null, null);
              return countRevisionsIn(conn);
            });
    assertThat(rowsInsideWork).isEqualTo(1L);
    assertThat(countRevisions()).isEqualTo(1L);
  }

  /** C23：甲在事务内持锁期间，乙必须等待——单连接 + 私有锁的互斥真的在。 */
  @Test
  void concurrentTransactionsSerializeOnThePrivateLock() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  store.inTransaction(
                      conn -> {
                        entered.countDown();
                        boolean released;
                        try {
                          released = release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          released = false;
                        }
                        if (!released) {
                          throw new AssertionError("release 闩超时：甲没能被放行");
                        }
                        return null;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<Long> waiter = pool.submit(() -> store.inTransaction(conn -> 42L));
      // 给乙留 200ms 调度余量：互斥生效时它不可能完成（坏锁会让它立刻带着异常完成）
      TimeUnit.MILLISECONDS.sleep(200);
      assertThat(waiter.isDone()).isFalse();
      release.countDown();
      assertThat(holder.get(5, TimeUnit.SECONDS)).isNull();
      assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(42L);
    } finally {
      pool.shutdownNow();
    }
  }

  /** close 幂等（AutoCloseable 契约）+ 关闭后拒绝再用。 */
  @Test
  void closeIsIdempotentAndStoreRejectsUseAfterClose() {
    store.close();
    store.close();
    assertThatThrownBy(() -> store.inTransaction(conn -> 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已关闭");
  }

  /** 读一条 store_meta（键不存在 ⇒ null）。 */
  private String storeMeta(String key) {
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

  /** PRAGMA 读一律经 store 的连接（连接是私有的，测试没有别的路可走）。 */
  private String pragma(String which) {
    return store.inTransaction(
        conn -> {
          try (Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery("PRAGMA " + which)) {
            rs.next();
            return rs.getString(1);
          }
        });
  }

  private long countRevisions() {
    return store.inTransaction(SqliteStoreTest::countRevisionsIn);
  }

  private static long countRevisionsIn(Connection conn) throws SQLException {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM revisions")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * 插一行 revision。{@code parentBranch} / {@code parentRevision} 同时为 null 即创世行； 给一个不存在的 parent 即 R2
   * 的越界行。
   */
  private static void insertRevision(
      Connection conn, String branch, long revision, String parentBranch, Long parentRevision)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO revisions (branch, revision, parent_branch, parent_revision, tick,"
                + " calendar_label, command_id, correlation_id, initiator, command_type,"
                + " changeset_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, branch);
      ps.setLong(2, revision);
      ps.setString(3, parentBranch);
      if (parentRevision == null) {
        ps.setNull(4, Types.INTEGER);
      } else {
        ps.setLong(4, parentRevision);
      }
      ps.setLong(5, 480L);
      ps.setNull(6, Types.VARCHAR);
      ps.setString(7, "cmd-" + branch + "-" + revision);
      ps.setString(8, "corr-" + branch + "-" + revision);
      ps.setString(9, "core:test");
      ps.setString(10, "core.AdvanceTime");
      ps.setString(11, "{}");
      ps.executeUpdate();
    }
  }
}
