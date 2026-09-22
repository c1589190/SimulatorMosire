# ④ 地图备份

- **源目录**：`/tmp/sept-rich5`
- **备份目录**：`/tmp/sept-rich5-backup-20260921T164709Z`（UTC 时间戳 `20260921T164709Z`）
- **方式**：`cp -a`（保留权限/时间戳，整目录）
- **大小**：`5.8M`（源与备份同为 5.8M）

## 一致性比对（两侧全等）

| 项 | 源 | 备份 | 结论 |
|----|----|------|------|
| 文件数 | 4 | 4 | ✅ |
| 文件树（相对路径） | — | — | ✅ `diff` 空 |
| `simos.db` md5 | `7521076b0e620fab0a1c8064e30a53bc` | `7521076b0e620fab0a1c8064e30a53bc` | ✅ |
| 逐文件 md5（4/4） | — | — | ✅ 全等 |
| `du -sh` | `5.8M` | `5.8M` | ✅ |

备份内容（4 个文件）：

```
simos.db                    4,096 bytes
simos.db-wal              440,872 bytes
simos.db-shm               32,768 bytes
checkpoints/main/1.json 5,520,042 bytes   ← 创世 checkpoint（59223 hex 的整图）
```

原始比对输出：`evidence/40-backup-verification.txt`；两侧 md5 清单：
`evidence/40a-backup-src-md5.txt` / `evidence/40b-backup-dst-md5.txt`。

> ⚠️ 说明：这是**文件级冷拷贝**（`cp -a` 三件套 `db`+`-wal`+`-shm` 一起拷），
> 不是 SQLite 的在线热备份 API。拷贝期间没有别的写者（本次任务串行操作），
> 且 `simos.db-wal` 与 `-shm` 一并复制，故本机这次拷贝是自洽的。
> 若将来在**有并发写**时备份，应改用 `sqlite3 .backup` 或停在写窗口外。
