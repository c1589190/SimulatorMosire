// panels.js —— 左栏详情（M7 T2 骨架 → T4 接真读数 → T5 补全判据③：hex 加"人口"、单位补全字段）/ 右栏区域面板（M7 T6：按 RegionMeta.tag 分组）。
// ★ 无框架、无构建：原生 DOM；所有只读取数经 window.SimosApi 并带 window.SimosApp.target()
//   （T3 约定在 T4 由本文件收口：面板值随游标的 {branch, revision} 变化）。
// ★ 本文件不含任何写调用。
// ★ M7 T6：右栏 = 区域查看模式（`data-modes="region"` 控制可见性）；`groupByTag` 是**纯函数**
//   （不查 IO、不碰 DOM），分组规则：`meta.tag` 的 null/空白一律归入「未标注」桶，该桶排最后。
//   点区域 ⇒ `setHighlightRegions([id])` 只高亮该区 + 出详情；点标签 ⇒ 该标签下全部区域一起高亮。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var requestToken = 0;
  var lastKey = null;

  // ── 右栏区域分组（M7 T6）────────────────────────────────────────────
  var UNTAGGED_LABEL = "未标注";
  var rightToken = 0;
  var rightKey = null;
  var rightRegions = null;
  var selectedRegion = null;

  // ── 地图总览共享缓存（M7b T2 → M9 T2 移入 api.js 的共享记忆化层）──
  //   左栏 ETA / 右栏区域 / map.js 渲染共用同一份按 target 记忆化的缓存（同一 URL×target 只发一次）。

  /** 取当前目标的地图总览（M9 T2：与 map.js 同走 SimosApi.cachedMapOverview）。 */
  function loadOverview() {
    return api.cachedMapOverview(app.target());
  }

  /** tag 归一化：null / undefined / 纯空白 ⇒ 「未标注」（判据④ / R4）。 */
  function normalizeTag(tag) {
    if (tag === null || tag === undefined) {
      return UNTAGGED_LABEL;
    }
    var text = String(tag).trim();
    return text === "" ? UNTAGGED_LABEL : text;
  }

  /**
   * 纯函数：overview 的 regions → `[{tag, regions:[…]}, …]`。不查 IO、不碰 DOM。
   * 桶按 tag 字典序，`未标注` 恒排最后；桶内区域按 id 排序（输出与输入顺序无关，便于逐值断言）。
   */
  function groupByTag(regions) {
    var buckets = {};
    var order = [];
    (regions || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      var tag = normalizeTag(region.meta ? region.meta.tag : null);
      if (!Object.prototype.hasOwnProperty.call(buckets, tag)) {
        buckets[tag] = [];
        order.push(tag);
      }
      buckets[tag].push(region);
    });
    order.sort(function (a, b) {
      if (a === UNTAGGED_LABEL) {
        return 1;
      }
      if (b === UNTAGGED_LABEL) {
        return -1;
      }
      return a.localeCompare(b);
    });
    return order.map(function (tag) {
      var list = buckets[tag].slice().sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
      return { tag: tag, regions: list };
    });
  }

  /**
   * 纯函数（M8 T9，无 DOM/IO）：左栏"该格的从属区域"读数。
   *   `entries` = `[{id, name, hexCount, hexes}]`（各区域自己的详情；hexes 取不到 ⇒ null/undefined）
   * 返回 `{rows, unionCount, hexCountSum, sharedHexCount, complete}`：
   *   `rows`            —— 每个区域**它自己的** `hexCount`（**不**做任何跨区域合并）；
   *   `unionCount`      —— 合计 = **真并集**（逐 hex 去重，同一格多属只计一次）；
   *   `hexCountSum`     —— 各区域 `hexCount` 之和（**只用来核对"和是否等于并集"**，绝不当作面积/并集显示）；
   *   `sharedHexCount`  —— 被 ≥2 个区域共同拥有的格数；
   *   `complete`        —— 有区域取不到 hex 列表 ⇒ false（这时**不给并集数字**，宁可说"不知道"）。
   * ★★ 裁定 72.1：`hexCountSum` 与 `unionCount` 在重叠时**必然不等**（M8-U1：从属是多对多），
   *    任何把"各区域 hexCount 求和"当并集/总面积的读法都是错的 —— 本函数是这条裁定的落地点。
   */
  function regionMembershipSummary(entries) {
    var rows = [];
    var unionKeys = [];
    var ownersByKey = {};
    var sum = 0;
    var complete = true;
    (entries || []).forEach(function (entry) {
      if (!entry || entry.id === null || entry.id === undefined) {
        return;
      }
      var hexes = Array.isArray(entry.hexes) ? entry.hexes : null;
      if (!hexes) {
        complete = false;
      }
      var count = typeof entry.hexCount === "number" ? entry.hexCount : hexes ? hexes.length : null;
      if (typeof count === "number") {
        sum += count;
      }
      (hexes || []).forEach(function (h) {
        if (!h || h.q === null || h.q === undefined || h.r === null || h.r === undefined) {
          return;
        }
        var key = h.q + "_" + h.r;
        if (!Object.prototype.hasOwnProperty.call(ownersByKey, key)) {
          ownersByKey[key] = 0;
          unionKeys.push(key);
        }
        ownersByKey[key] += 1;
      });
      rows.push({ id: entry.id, name: entry.name || entry.id, hexCount: count });
    });
    var shared = 0;
    unionKeys.forEach(function (key) {
      if (ownersByKey[key] >= 2) {
        shared += 1;
      }
    });
    return {
      rows: rows,
      unionCount: complete ? unionKeys.length : null,
      hexCountSum: sum,
      sharedHexCount: complete ? shared : null,
      complete: complete,
    };
  }

  /** T9：把从属区域读数写进左栏（每个区域自己的 hexCount + **并集**合计；口径写在标签里）。 */
  function appendRegionMembership(detail, summary) {
    detail.appendChild(app.el("dt", { text: "从属区域（各区域自己的 hexCount，不合并不求和）" }));
    var box = app.el("dd", {
      class: "region-membership",
      id: "hex-region-membership",
      "data-region-count": String(summary.rows.length),
    });
    summary.rows.forEach(function (row) {
      var known = row.hexCount !== null && row.hexCount !== undefined;
      var line = app.el("div", { class: "region-membership-row" });
      line.setAttribute("data-region-id", row.id);
      line.appendChild(app.el("span", { class: "region-membership-name", text: app.text(row.name) }));
      line.appendChild(
        app.el("span", {
          class: "region-membership-hexcount",
          "data-hexcount": known ? String(row.hexCount) : "",
          text: known ? row.hexCount + " 格" : "—",
        })
      );
      box.appendChild(line);
    });
    detail.appendChild(box);
    detail.appendChild(app.el("dt", { text: "合计（并集：逐 hex 去重，重叠格只计一次）" }));
    var total = app.el("dd", {
      class: "region-membership-total",
      id: "hex-region-union",
      "data-metric": "union",
    });
    if (summary.complete) {
      total.setAttribute("data-union-count", String(summary.unionCount));
      total.setAttribute("data-shared-hex-count", String(summary.sharedHexCount));
      total.textContent =
        summary.unionCount +
        " 格（并集，逐 hex 去重；" +
        summary.rows.length +
        " 个区域，重叠 " +
        summary.sharedHexCount +
        " 格只计一次）";
    } else {
      total.setAttribute("data-union-count", "");
      total.setAttribute("data-shared-hex-count", "");
      total.textContent = "—（有区域未取到 hex 列表，不计算合计；不拿求和顶替）";
    }
    detail.appendChild(total);
  }

  /** 拉各区域详情后写读数（失败 ⇒ 该区域 hexes=null ⇒ 合计显示"不知道"，不静默求和）。 */
  function renderRegionMembership(detail, regionIds, token) {
    if (!regionIds.length) {
      return;
    }
    Promise.all(
      regionIds.map(function (id) {
        return api.mapRegion(id, app.target()).catch(function (e) {
          window.console.warn("[SimosPanels] 区域读取失败：" + id + "：" + e.message);
          return { id: id, name: id, hexes: null };
        });
      })
    ).then(function (regions) {
      if (token !== requestToken) {
        return;
      }
      appendRegionMembership(detail, regionMembershipSummary(regions));
    });
  }

  function targetLabel() {
    var t = app.target();
    return t.branch + "@" + (t.revision === null || t.revision === undefined ? "head" : t.revision);
  }

  function appendRow(detail, label, value) {
    detail.appendChild(app.el("dt", { text: label }));
    detail.appendChild(app.el("dd", { text: app.text(formatValue(value)) }));
  }

  /** 追加一行**由别的文件回填**的读数（带 id 锚点）：跨文件只经这一个 DOM 锚点，不互相持有状态。 */
  function appendLiveRow(detail, label, id, text) {
    detail.appendChild(app.el("dt", { text: label }));
    var dd = app.el("dd", { text: text });
    dd.setAttribute("id", id);
    detail.appendChild(dd);
  }

  /** 只读读数里的浮点数去掉二进制尾巴（0.6000000000000001 → 0.6）；整数/文本原样。 */
  function formatValue(value) {
    if (typeof value === "number" && Number.isFinite(value) && !Number.isInteger(value)) {
      return Number(value.toFixed(3));
    }
    return value;
  }

  /** 装备表渲染成 `键=值；…`（空表显示"（空）"）。 */
  function equipmentText(equipment) {
    if (!equipment) {
      return "（空）";
    }
    var keys = Object.keys(equipment);
    if (!keys.length) {
      return "（空）";
    }
    return keys
      .map(function (key) {
        return key + "=" + equipment[key];
      })
      .join("；");
  }

  function hexLabel(coord) {
    return "q=" + coord.q + ", r=" + coord.r;
  }

  var IMPASSABLE_MOVE_COST = 999;

  /**
   * 纯函数（不碰 DOM、不查 IO）：单位 + 总览 ⇒ 移动读数。
   *
   * 预算速率 = speedAtDeparture × 1000（毫 MP/tick，与 UnitMoves 的 budget 同式）；
   * 每格成本 = terrainTypes[地形(目标格)].moveCost × mobilityPerMilleAtDeparture（与 TerrainMovementCost 同式）；
   * 预计到达 tick = departedAt.tick + ceil(路线总成本 / 预算速率)——总成本 = 沿途每格成本之和。
   */
  function movementReadout(unit, overview) {
    var m = unit.movement;
    if (!m) {
      return null;
    }
    var path = (m.route && m.route.path) || [];
    var blocks = (overview && overview.blocks) || [];
    var typeByKey = {};
    ((overview && overview.terrainTypes) || []).forEach(function (t) {
      typeByKey[t.key] = t;
    });
    function costOf(coord) {
      if (!coord) {
        return null;
      }
      var terrain =
        window.SimosBlocks && window.SimosBlocks.terrainAt
          ? window.SimosBlocks.terrainAt(blocks, coord.q, coord.r)
          : null;
      var type = typeByKey[terrain];
      if (!type || type.moveCost >= IMPASSABLE_MOVE_COST) {
        return null;
      }
      return type.moveCost * m.mobilityPerMilleAtDeparture;
    }
    var totalCost = 0;
    var computable = true;
    for (var i = 0; i + 1 < path.length; i++) {
      var step = costOf(path[i + 1]);
      if (step === null) {
        computable = false;
        break;
      }
      totalCost += step;
    }
    var rate = m.speedAtDeparture * 1000;
    var etaTick =
      computable && rate > 0 ? m.departedAt.tick + Math.ceil(totalCost / rate) : null;
    return {
      budgetPerTickMillis: rate,
      stepCostMillis: costOf(m.nextHex),
      totalCostMillis: computable ? totalCost : null,
      status: m.status,
      currentHex: m.currentHex,
      nextHex: m.nextHex,
      remainingMillis: m.remainingMillis,
      etaTick: etaTick,
      departedAtTick: m.departedAt.tick,
      speedAtDeparture: m.speedAtDeparture,
      mobilityPerMilleAtDeparture: m.mobilityPerMilleAtDeparture,
      pathLength: path.length,
    };
  }

  /** 把移动读数逐行写进左栏（无路线 ⇒ 一行「无」）。 */
  function appendMovementRows(detail, unit, overview) {
    var readout = movementReadout(unit, overview);
    if (!readout) {
      appendRow(detail, "movement", "无（无在途路线）");
      return;
    }
    appendRow(detail, "movement", "有");
    appendRow(detail, "路线格数", readout.pathLength);
    appendRow(detail, "本 tick 预算（毫 MP）", readout.budgetPerTickMillis);
    appendRow(
      detail,
      "路线每格成本（毫 MP）",
      readout.stepCostMillis === null ? "—" : readout.stepCostMillis
    );
    appendRow(
      detail,
      "路线总成本（毫 MP）",
      readout.totalCostMillis === null ? "—" : readout.totalCostMillis
    );
    appendRow(detail, "status", readout.status);
    appendRow(detail, "currentHex", readout.currentHex ? hexLabel(readout.currentHex) : "—");
    appendRow(detail, "nextHex", readout.nextHex ? hexLabel(readout.nextHex) : "—");
    appendRow(
      detail,
      "remainingMillis",
      readout.remainingMillis === null ? "—" : readout.remainingMillis
    );
    appendRow(
      detail,
      "预计到达 tick",
      readout.etaTick === null ? "—（需重规划）" : readout.etaTick
    );
    appendRow(detail, "出发 tick", readout.departedAtTick);
    appendRow(detail, "出发速度（毫 MP/tick）", readout.speedAtDeparture * 1000);
    appendRow(detail, "出发机动‰", readout.mobilityPerMilleAtDeparture);
  }

  function renderHex(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询 " + hexLabel(selection) + "（" + targetLabel() + "）…", "muted");
    // 人口序列可能不存在（/api/social/population 404）⇒ 折成 null，不让整条详情失败（判据③"人口"）。
    Promise.all([
      api.mapHex(selection.q, selection.r, app.target()),
      api.cachedUnits(app.target()),
      api.population(selection.q, selection.r, app.target()).catch(function () {
        return null;
      }),
    ])
      .then(function (results) {
        if (token !== requestToken) {
          return;
        }
        var hex = results[0];
        var unitsHere = (results[1].units || []).filter(function (u) {
          return u.position && u.position.q === selection.q && u.position.r === selection.r;
        });
        var population = results[2];
        var terrainText = hex.terrain;
        if (hex.terrainType && hex.terrainType.name) {
          terrainText = hex.terrain + "（" + hex.terrainType.name + "）";
        }
        appendRow(detail, "q", hex.q);
        appendRow(detail, "r", hex.r);
        appendRow(detail, "terrain", terrainText);
        appendRow(detail, "height", hex.height);
        var regionIds = Array.isArray(hex.regions) ? hex.regions : [];
        appendRow(detail, "regions", regionIds.length ? regionIds.join("、") : "无区域");
        // ★ M8 T9：每个区域给出**它自己的** hexCount，合计是**真并集**（不是求和 —— 裁定 72.1）。
        renderRegionMembership(detail, regionIds, token);
        appendRow(
          detail,
          "该处单位",
          unitsHere.length
            ? unitsHere
                .map(function (u) {
                  return u.id + " " + app.text(u.name);
                })
                .join("；")
            : "无"
        );
        appendRow(
          detail,
          "人口",
          population && population.population !== null && population.population !== undefined
            ? population.population
            : "无序列"
        );
        app.statusMessage(status, hexLabel(hex) + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  function renderUnit(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询单位 " + selection.id + "（" + targetLabel() + "）…", "muted");
    Promise.all([
      api.unit(selection.id, app.target()),
      loadOverview().catch(function () {
        return null;
      }),
    ])
      .then(function (results) {
        if (token !== requestToken) {
          return;
        }
        var unit = results[0];
        var overview = results[1];
        appendRow(detail, "id", unit.id);
        appendRow(detail, "name", unit.name);
        appendRow(detail, "parent", unit.parent === null || unit.parent === undefined ? "—" : unit.parent);
        appendRow(detail, "position", unit.position ? hexLabel(unit.position) : "—");
        appendRow(detail, "member", unit.member);
        appendRow(detail, "equipment", equipmentText(unit.equipment));
        appendRow(detail, "speed", unit.speed);
        appendRow(detail, "mobilityPerMille", unit.mobilityPerMille);
        appendMovementRows(detail, unit, overview);
        app.statusMessage(status, "单位 " + unit.id + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  /** 左栏渲染；选择或目标坐标变化时才重取（模式/高亮变化不触发重取）。 */
  function renderLeft(state) {
    var status = app.byId("left-status");
    var detail = app.byId("selection-detail");
    if (!status || !detail) {
      return;
    }
    var selection = state ? state.selection : null;
    var key = JSON.stringify([selection, app.target()]);
    if (key === lastKey) {
      return;
    }
    lastKey = key;
    var token = ++requestToken;
    if (!selection) {
      app.clear(detail);
      app.statusMessage(status, "点选地图或单位以查看详情。", "muted");
      return;
    }
    if (selection.kind === "hex") {
      renderHex(selection, token);
    } else if (selection.kind === "unit") {
      renderUnit(selection, token);
    } else {
      app.clear(detail);
      app.statusMessage(status, "未知选择类型：" + app.text(selection.kind), "warn");
    }
  }

  function regionDetail(container) {
    var dl = app.el("dl", { class: "kv region-detail", id: "region-detail" });
    if (!selectedRegion) {
      appendRow(dl, "提示", "点区域看详情；点标签高亮该标签下全部区域。");
      container.appendChild(dl);
      return;
    }
    var meta = selectedRegion.meta || {};
    appendRow(dl, "name", selectedRegion.name || selectedRegion.id);
    appendRow(dl, "id", selectedRegion.id);
    appendRow(dl, "hexCount", selectedRegion.hexCount);
    appendRow(dl, "color", meta.color);
    appendRow(dl, "tag", meta.tag);
    appendRow(dl, "description", meta.description);
    appendRow(dl, "annexedBy", meta.annexedBy);
    container.appendChild(dl);
  }

  /** 用缓存的 regions + 当前 state 重画右栏（分组、高亮选中态、详情）。 */
  function drawRight(state) {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var groups = groupByTag(rightRegions || []);
    if (!groups.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "该快照无区域。" }));
      return;
    }
    var highlighted = state.highlightRegions || [];
    var highlightSet = {};
    highlighted.forEach(function (id) {
      highlightSet[id] = true;
    });
    // ★ M8 T10：区域编辑模式下"当前目标区域"（focus）也算选中态（它由 map.js 渲染正常色/其它淡色）。
    var regionFocus = state.regionFocus || null;
    groups.forEach(function (group) {
      var ids = group.regions.map(function (region) {
        return region.id;
      });
      var allActive =
        ids.length > 0 &&
        highlighted.length === ids.length &&
        ids.every(function (id) {
          return highlightSet[id];
        });
      var tagButton = app.el("button", {
        type: "button",
        class: "region-tag" + (allActive ? " active" : ""),
        "data-tag": group.tag,
        title: "高亮「" + group.tag + "」下全部 " + ids.length + " 个区域",
      });
      tagButton.appendChild(app.el("span", { class: "region-tag-name", text: group.tag }));
      tagButton.appendChild(app.el("span", { class: "region-tag-count", text: String(ids.length) }));
      tagButton.addEventListener("click", function () {
        selectedRegion = null;
        // ★ U3：点 tag ⇒ 该 tag 下所有区域**等亮度**（group）。
        app.setHighlightRegions(ids.slice(), "group");
      });
      mount.appendChild(tagButton);

      var list = app.el("div", { class: "region-list" });
      group.regions.forEach(function (region) {
        var isSelected = !!highlightSet[region.id] || region.id === regionFocus;
        var item = app.el("button", {
          type: "button",
          class: "region-item" + (isSelected ? " selected" : ""),
          "data-region-id": region.id,
        });
        item.appendChild(
          app.el("span", { class: "region-name", text: app.text(region.name || region.id) })
        );
        item.appendChild(
          app.el("span", { class: "region-hexcount", text: app.text(region.hexCount) + " 格" })
        );
        item.addEventListener("click", function () {
          selectedRegion = region;
          if (app.getState().mode === "region-edit") {
            app.setRegionFocus(region.id);
          } else {
            // ★ U3：点单个区域 ⇒ 该区域更亮、同 tag 其他区域淡色（single）。
            app.setHighlightRegions([region.id], "single");
          }
        });
        list.appendChild(item);
      });
      mount.appendChild(list);
    });
    regionDetail(mount);
  }

  /** 右栏渲染：目标 {branch,revision} 变化才重取 overview；高亮/选择变化只重画（不重取）。 */
  function renderRight(state) {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    state = state || app.getState();
    var key = targetLabel();
    if (key === rightKey) {
      if (rightRegions !== null) {
        drawRight(state);
      }
      return;
    }
    rightKey = key;
    var token = ++rightToken;
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入区域…（" + key + "）" }));
    loadOverview()
      .then(function (body) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = body.regions || [];
        drawRight(state);
      })
      .catch(function (e) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = null;
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "区域载入失败：" + e.message }));
      });
  }

  // ═══ 决策模式（T7）：一个模式两个子页 + 决策人交互 ═══════════════════════════
  //
  // ★★ 三件事模型（spec §四.1）在本文件里的边界：**只做"显示"**——
  //   ② 「本 tick 哪些决策人理论上有待决事项」是 **T9** 才有的服务端计算 ⇒ 本文件**不造待决数据**：
  //       `due` 取不到（null / undefined）就显示「—」（pendingStatusText），绝不拿 false 顶替。
  //   ④ 「开始决策」按钮是 **T10** 的入口 ⇒ 本文件**不实现它的行为**，也不放该按钮。
  //   ⑤ 决策人 Agent 的异步出令与 ⑥ 落 revision 都不在本模式内发生。
  //   ⇒ 本模式**只读**（modes.js 的 decision.writes 恒为 []）；唯一的写是审批子页的
  //     `POST /api/approvals/{id}`（审批裁决，非命令写，单独列在 api.js / write-allowlist 里）。

  var DECISION_SUBPAGES = [
    { id: "view", label: "决策人查看" },
    { id: "approval", label: "审批" },
    { id: "provider", label: "Provider 配置" },
  ];

  /**
   * 「可见范围（现算）」那一行的 **DOM 锚点 id**（**唯一拼写点**）。
   *
   * <p>★ 为什么要跨文件：这一行的**内容**来自只读端点 `/api/sd/decision-makers/{id}/scope`，而拉它并画高亮的是
   * `map.js`（可见范围高亮与区域高亮共用同一层渲染）。两个文件只经这一个 id 交接，不互相持有状态、也不各拉一次
   * ——各拉一次就是"同一件事两份实现"的开端。
   */
  var DECISION_SCOPE_SUMMARY_ID = "decision-scope-summary";

  /** 子页状态（fail-closed）：未知 id ⇒ `{ok:false, id:null, label:null}`（不兜成第一个子页）。 */
  function decisionSubpageState(id) {
    for (var i = 0; i < DECISION_SUBPAGES.length; i++) {
      if (DECISION_SUBPAGES[i].id === id) {
        return { ok: true, id: DECISION_SUBPAGES[i].id, label: DECISION_SUBPAGES[i].label };
      }
    }
    return { ok: false, id: null, label: null };
  }

  /**
   * 子页可见性（纯函数，无 DOM）：恰一个为 true；未知 id ⇒ **三个都 false**（fail-closed，
   * 不把"没选"变成"选了决策人查看"——与 map.js 的 mapEditPanelVisibility 同口径）。
   */
  function decisionSubpageVisibility(id) {
    var state = decisionSubpageState(id);
    return {
      view: state.ok && state.id === "view",
      approval: state.ok && state.id === "approval",
      provider: state.ok && state.id === "provider",
    };
  }

  /**
   * Provider 表单 → 端点载荷（纯函数，M11′ 配置页）。
   *
   * <p>★ **不造假**：缺 `baseUrl` / `model` / `id` 一律返回 `{ok:false, reason}` —— 绝不填默认值把空表单
   * 变成一条"看起来有效"的 provider（同 providerFields 的口径）。`apiKey` 只在非空时带上（空 = 不改密钥）。
   */
  function providerFormToPayload(form) {
    var src = form || {};
    var id = textOrNull(src.id);
    if (id === null) {
      return { ok: false, reason: "id 不得为空" };
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(id)) {
      return { ok: false, reason: "id 只能以字母/数字开头，其后可含字母/数字/下划线/连字符" };
    }
    var baseUrl = textOrNull(src.baseUrl);
    if (baseUrl === null) {
      return { ok: false, reason: "baseUrl 不得为空（API 根，含 /v1，不含 /chat/completions）" };
    }
    var model = textOrNull(src.model);
    if (model === null) {
      return { ok: false, reason: "model 不得为空" };
    }
    var payload = { id: id, baseUrl: baseUrl, model: model };
    var credentialsRef = textOrNull(src.credentialsRef);
    if (credentialsRef !== null) {
      payload.credentialsRef = credentialsRef;
    }
    if (src.readTimeoutMs !== null && src.readTimeoutMs !== undefined && src.readTimeoutMs !== "") {
      var timeout = Number(src.readTimeoutMs);
      if (!isFinite(timeout) || timeout <= 0) {
        return { ok: false, reason: "readTimeoutMs 必须是正整数毫秒数" };
      }
      payload.readTimeoutMs = timeout;
    }
    var apiKey = textOrNull(src.apiKey);
    if (apiKey !== null) {
      payload.apiKey = apiKey;
    }
    return { ok: true, payload: payload };
  }

  /**
   * Provider 视图 → 展示字段（纯函数，掩码，M11′）。
   *
   * <p>★ **`valid:false` 的坏条目照样列**（带 `errorCode`）——配置页要能看见自己写坏的那条，才谈得上去修它。
   * `keyConfigured` 三态：true / false / `null`（取不到 ⇒ 显 `—`，**不拿 false 顶替**）。
   */
  function providerFields(provider) {
    var p = provider || {};
    if (p.valid === false) {
      return {
        id: p.id === null || p.id === undefined ? "—" : String(p.id),
        valid: false,
        errorCode: p.errorCode === null || p.errorCode === undefined ? "—" : String(p.errorCode),
        rows: [],
      };
    }
    var rows = [
      ["baseUrl", valueOrDash(p.baseUrl)],
      ["model", valueOrDash(p.model)],
      ["protocol", valueOrDash(p.protocol)],
      ["credentialsRef", valueOrDash(p.credentialsRef)],
      ["readTimeoutMs", valueOrDash(p.readTimeoutMs)],
      ["keyConfigured", keyConfiguredText(p.keyConfigured)],
    ];
    return {
      id: p.id === null || p.id === undefined ? "—" : String(p.id),
      valid: true,
      errorCode: null,
      rows: rows,
    };
  }

  /** 三态文案：true/false 之外一律 `—`（"还没查" ≠ "不可解析"）。 */
  function keyConfiguredText(value) {
    if (value === true) {
      return "可解析";
    }
    if (value === false) {
      return "不可解析";
    }
    return "—";
  }

  /** 决策人绑定 provider 的载荷（纯函数）：两者都非空才 ok。 */
  function providerBindingPayload(decisionMakerId, providerId) {
    var maker = textOrNull(decisionMakerId);
    var provider = textOrNull(providerId);
    if (maker === null) {
      return { ok: false, reason: "未选中决策人" };
    }
    if (provider === null) {
      return { ok: false, reason: "providerId 不得为空" };
    }
    return { ok: true, decisionMakerId: maker, providerId: provider };
  }

  /** 非空文本或 null（不 trim 值本身之外的加工；空白一律 null）。 */
  function textOrNull(value) {
    if (value === null || value === undefined) {
      return null;
    }
    var text = String(value).trim();
    return text === "" ? null : text;
  }

  /** 值或 `—`（缺值不显示 `undefined`/`null` 字面量）。 */
  function valueOrDash(value) {
    if (value === null || value === undefined || value === "") {
      return "—";
    }
    return String(value);
  }

  var AFFILIATION_LABELS = { nation: "国家", army: "军队" };

  /** 归属种类 ⇒ 中文（未知种类原样返回，不静默造标签）。 */
  function affiliationKindLabel(kind) {
    if (kind === null || kind === undefined) {
      return "—";
    }
    var key = String(kind);
    return Object.prototype.hasOwnProperty.call(AFFILIATION_LABELS, key) ? AFFILIATION_LABELS[key] : key;
  }

  /** 归属可读文本：`国家：<显示名>（<id>）`；解析不出的字段显式 `—`（不编造）。 */
  function affiliationLabel(affiliation) {
    var aff = affiliation || {};
    var name = aff.displayName === null || aff.displayName === undefined ? "—" : String(aff.displayName);
    var id = aff.id === null || aff.id === undefined ? "—" : String(aff.id);
    return affiliationKindLabel(aff.kind) + "：" + name + "（" + id + "）";
  }

  /**
   * 待决状态文本（★ 三件事模型 ②）：`due` 只有 T9 才算得出来 ⇒ **null/undefined 一律「—」**。
   * 绝不把"还没算"读成"非待决"（那会造出假的待决数据）。
   */
  function pendingStatusText(due) {
    if (due === true) {
      return "待决";
    }
    if (due === false) {
      return "非待决";
    }
    return "—";
  }

  /**
   * 「开始决策」按钮闸门（纯函数，T10）：三件事模型 ④ 只对**本 tick 待决**（T9 的 `due`）的决策人可点。
   *
   * <p>★ `due` 取不到（null/undefined）⇒ **不可点**（"还没算" ≠ "可以点"，同 pendingStatusText 口径）。
   */
  function startDecisionGate(maker) {
    if (!maker || maker.id === null || maker.id === undefined || maker.id === "") {
      return { enabled: false, reason: "未选中决策人" };
    }
    if (maker.due === true) {
      return { enabled: true, reason: "可发起（本 tick 待决）" };
    }
    if (maker.due === false) {
      return { enabled: false, reason: "非待决（本 tick 未到决策周期）" };
    }
    return { enabled: false, reason: "待决状态未知（due 缺失）" };
  }

  /**
   * 右栏分类列表（纯函数）：`[{kind, label, makers:[…]}]`。
   * 组序：**国家 → 军队 → 其它（按 kind 字典序）**；组内按 id 字典序；**总长度 == 输入长度**
   * （未知 kind 归入「其它（kind）」桶，绝不静默丢弃）。
   */
  function decisionMakerGroups(makers) {
    var buckets = {};
    var kinds = [];
    (makers || []).forEach(function (maker) {
      if (!maker || maker.id === null || maker.id === undefined) {
        return;
      }
      var kind =
        maker.affiliation && maker.affiliation.kind !== null && maker.affiliation.kind !== undefined
          ? String(maker.affiliation.kind)
          : "";
      if (!Object.prototype.hasOwnProperty.call(buckets, kind)) {
        buckets[kind] = [];
        kinds.push(kind);
      }
      buckets[kind].push(maker);
    });
    kinds.sort(function (a, b) {
      var rank = function (kind) {
        if (kind === "nation") {
          return 0;
        }
        if (kind === "army") {
          return 1;
        }
        // 具名但未知的种类排第 3 档；**无归属（空 kind）恒排最后**（"没有归属"不是一种归属种类）。
        return kind === "" ? 3 : 2;
      };
      var ra = rank(a);
      var rb = rank(b);
      if (ra !== rb) {
        return ra - rb;
      }
      return a.localeCompare(b);
    });
    return kinds.map(function (kind) {
      var list = buckets[kind].slice().sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
      var label = Object.prototype.hasOwnProperty.call(AFFILIATION_LABELS, kind)
        ? AFFILIATION_LABELS[kind]
        : "其它（" + kind + "）";
      return { kind: kind, label: label, makers: list };
    });
  }

  /** 某国家的全部决策人（纯函数）：按 affiliation.kind==="nation" + id 逐值匹配，组内按 id 字典序。 */
  function decisionMakersForNation(makers, nationId) {
    if (nationId === null || nationId === undefined || nationId === "") {
      return [];
    }
    var want = String(nationId);
    return (makers || [])
      .filter(function (maker) {
        return (
          maker &&
          maker.affiliation &&
          maker.affiliation.kind === "nation" &&
          String(maker.affiliation.id) === want
        );
      })
      .sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
  }

  /**
   * 某单位的决策人（纯函数）：军队决策人的 `affiliation.rootUnit` 指向**单位树的一个根**；
   * 选中的单位若是该根的**后代**也算"有决策人"（沿 `parent` 链上溯，与单位树同一口径）。
   * 多军队命中同一根时取 id 字典序最小者（确定性）。无命中 ⇒ null（调用方显示"无决策人"）。
   */
  function decisionMakerForUnit(makers, units, unitId) {
    if (unitId === null || unitId === undefined || unitId === "") {
      return null;
    }
    var byId = {};
    (units || []).forEach(function (unit) {
      if (unit && unit.id !== null && unit.id !== undefined) {
        byId[String(unit.id)] = unit;
      }
    });
    var armies = (makers || [])
      .filter(function (maker) {
        return (
          maker &&
          maker.affiliation &&
          maker.affiliation.kind === "army" &&
          maker.affiliation.rootUnit !== null &&
          maker.affiliation.rootUnit !== undefined
        );
      })
      .sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
    if (!armies.length) {
      return null;
    }
    var visited = {};
    var cursor = String(unitId);
    while (cursor && !Object.prototype.hasOwnProperty.call(visited, cursor)) {
      visited[cursor] = true;
      for (var i = 0; i < armies.length; i++) {
        if (String(armies[i].affiliation.rootUnit) === cursor) {
          return armies[i];
        }
      }
      var unit = byId[cursor];
      var parent = unit ? unit.parent : null;
      cursor = parent === null || parent === undefined || parent === "" ? null : String(parent);
    }
    return null;
  }

  /** 左栏决策人详情的字段投影（纯函数）：与 `GET /api/sd/decision-makers/{id}` 逐值一致。 */
  function decisionMakerFields(maker) {
    // ★ T9：`viewScope` → `accessLimit`（语义变了：不再是"绝对可见集合"，而是 GM 配的**额外限制**）。
    var limit = maker ? maker.accessLimit : null;
    return {
      id: maker ? maker.id : null,
      affiliation: affiliationLabel(maker ? maker.affiliation : null),
      cadence: maker ? maker.cadence : null,
      allowedTools:
        maker && Array.isArray(maker.allowedTools) && maker.allowedTools.length
          ? maker.allowedTools.join("、")
          : "（无）",
      accessLimit: limit
        ? {
            prefixesByNamespace: prefixSummary(limit.prefixesByNamespace),
            adjudicationDisclosure: limit.adjudicationDisclosure,
            redactedFields:
              Array.isArray(limit.redactedFields) && limit.redactedFields.length
                ? limit.redactedFields.join("、")
                : "（无）",
          }
        : null,
      pending: pendingStatusText(maker ? maker.due : null),
      // ★ 2026-09-23：**最近一次在第几 tick 出的令**（服务端 `lastDirectiveTick` / `ticksSinceLast`，T9 起就是真值）。
      //   从未出过令 ⇒ `—`（"没有基准"与"tick 0 出过令"是两件事，不拿 0 顶替）。
      lastDirectiveTick: valueOrDash(maker ? maker.lastDirectiveTick : null),
      ticksSinceLast: valueOrDash(maker ? maker.ticksSinceLast : null),
      // ★ 会话世代 + 派生出的会话 id（服务端 `conversationGeneration` / `conversationId`，T9 之后的世界事实）：
      //   GM 据此知道"这个人换过几次会话"。缺字段 ⇒ 显式"—"（不编造"第 0 代"）。
      conversationGeneration:
        maker && maker.conversationGeneration !== null && maker.conversationGeneration !== undefined
          ? maker.conversationGeneration
          : "—",
      conversationId:
        maker && maker.conversationId !== null && maker.conversationId !== undefined
          ? maker.conversationId
          : "—",
    };
  }

  /** 前缀图摘要（纯函数）：`{命名空间: 条数}` → `map=2、unit=5`；空/缺 ⇒ `（无额外限制）`。 */
  function prefixSummary(byNamespace) {
    if (!byNamespace || typeof byNamespace !== "object") {
      return "（无额外限制）";
    }
    var parts = Object.keys(byNamespace).sort();
    if (!parts.length) {
      return "（无额外限制）";
    }
    var out = [];
    for (var i = 0; i < parts.length; i += 1) {
      out.push(parts[i] + "=" + byNamespace[parts[i]]);
    }
    return out.join("、");
  }

  // ── 决策模式左栏渲染 ────────────────────────────────────────────────

  var decisionLeftToken = 0;
  var decisionLeftKey = null;

  function setDecisionViewStatus(message, tone) {
    app.statusMessage(app.byId("decision-view-status"), message, tone);
  }

  // ── 「开始决策」入口（T10，三件事模型 ④）────────────────────────────────
  // ★ 目标 = 左栏当前展示的那个决策人（右栏点选 / 单位解析出的单个；国家多决策人时无单一目标 ⇒ 不可点）。
  // ★ 写路径 = api.startDecision（POST /api/sd/start-decision 窄端点，服务端写死 sd.StartDecision）——
  //   本文件**不发通用命令写**（通用写只归 app 的 writeCommand）；审批裁决那条写另有其处（decideApproval）。
  var startDecisionTarget = null;
  var lastStartGateStamp = null;

  function setStartDecisionStatus(message, tone) {
    app.statusMessage(app.byId("decision-start-status"), message, tone);
  }

  function updateStartDecisionControl() {
    var button = app.byId("decision-start");
    var gate = startDecisionGate(startDecisionTarget);
    var targetId = startDecisionTarget ? String(startDecisionTarget.id) : "";
    if (button) {
      button.disabled = !gate.enabled;
      button.setAttribute("data-decision-target", targetId);
    }
    var stamp = targetId + "|" + gate.enabled + "|" + gate.reason;
    if (stamp !== lastStartGateStamp) {
      lastStartGateStamp = stamp;
      setStartDecisionStatus(gate.reason, gate.enabled ? "ok" : "muted");
    }
  }

  /**
   * 设定两个窄写的当前目标（「开始决策」/「让它跑一轮」）——**同一个目标**，一处设两处生效。
   *
   * <p>★ 合成一处而不是各设一份：两者若各持有自己的目标，界面上就会出现"按钮 A 指向甲、按钮 B 指向乙"而**没有任何症状**。
   */
  function setStartDecisionTarget(maker) {
    startDecisionTarget = maker || null;
    setRunDecisionTarget(maker);
    updateStartDecisionControl();
  }

  /** 点「开始决策」：只对**待决**的当前目标发起（闸门是纯 UX 前置；服务端另做领域校验）。 */
  function decideStartDecision() {
    var target = startDecisionTarget;
    if (!target || !startDecisionGate(target).enabled) {
      setStartDecisionStatus("未选中可发起的决策人", "warn");
      return;
    }
    var at = app.target() || {};
    setStartDecisionStatus("发起决策 " + target.id + "…", "muted");
    attemptStartDecision(target, at.branch, at.revision, true);
  }

  /**
   * 发起一次「开始决策」；★ 收到 409（游标过期）时**重取 head、把游标拉到服务端 current.revision，并自动重试一次**
   * ⇒ 用户点一次即可成功（成功分支也会把游标推进到新 head，避免下一次点击再用旧 revision）。
   *
   * @param mayRetry 只允许自动重试一次（防冲突循环）；重试轮不再重试
   */
  function attemptStartDecision(target, branch, revision, mayRetry) {
    return api
      .startDecision(branch, revision, target.id)
      .then(function (body) {
        if (body && body.ref && body.ref.revision !== undefined && app.setRevision) {
          app.setRevision(body.ref.revision);
        }
        setStartDecisionStatus(
          "已发起：" + target.id + "（" + ((body && body.result) || "committed") + "）",
          "ok"
        );
        if (app.refreshState) {
          app.refreshState(true);
        }
        return body;
      })
      .catch(function (e) {
        if (e && e.status === 409 && mayRetry) {
          var current = e.body && e.body.current ? e.body.current : null;
          setStartDecisionStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return Promise.resolve()
            .then(function () {
              return app.refreshState ? app.refreshState(true) : null;
            })
            .catch(function () {
              return null;
            })
            .then(function () {
              var fresh =
                current && current.revision !== undefined
                  ? current.revision
                  : (app.target() || {}).revision;
              if (app.setRevision && fresh !== null && fresh !== undefined) {
                app.setRevision(fresh);
              }
              return attemptStartDecision(target, branch, fresh, false);
            });
        }
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setStartDecisionStatus("发起失败：" + reason, "err");
        return null;
      });
  }

  // ── 「让它跑一轮」（sd.RunDecision，2026-09-23）─────────────────────────
  //
  // ★★ **它补的是哪一处空白**：让某个决策人**真跑一轮**（真 LLM 自行读世界、出令）此前只有 GM 的 MCP 窄工具
  //   （sd.RunDecision）做得到 ⇒ 界面上**点不出来**。本入口把它接进工作台：写仍走**窄端点**
  //   POST /api/sd/run-decision（服务端写死命令类型，前端不传 type）。
  //
  // ★★ **它会跑很久，且可能停在"等审批"上**：这一轮里决策人若出令（sd.IssueDirective 是敏感写），那次工具调用要
  //   **阻塞式**等审批（上限 = 壳的 APPROVAL_TIMEOUT）。故状态行一直显示"已 Ns"并点明可能在等审批
  //   ——**不能表现为卡死**（这是本入口最容易做错的地方）。
  //
  // ★★ **轨迹只显示当次返回的那一份**（服务端明确取舍：轨迹是过程观测、不是世界事实 ⇒ 不落盘）。
  //   换 target / 刷新之后它就不在了——读数里有一行明说这件事。
  var runDecisionTarget = null;
  var lastRunGateStamp = null;
  var runDecisionStartedAt = 0;
  var runDecisionTicker = null;

  /**
   * 「让它跑一轮」闸门（纯函数）：有目标 + 它**绑了 provider** 才可点。
   *
   * <p>★ 未绑定 ⇒ 不可点：服务端那条路是 fail-closed（未绑定 provider **抛**，绝不落到某个默认 provider），
   * 让用户点了再等出错不如当场说清。`providerId` 取不到（undefined）与空串同判——**不猜"大概绑了"**。
   */
  function runDecisionGate(maker) {
    if (!maker || maker.id === null || maker.id === undefined || maker.id === "") {
      return { enabled: false, reason: "未选中决策人" };
    }
    var provider = maker.providerId;
    if (provider === null || provider === undefined || String(provider).trim() === "") {
      return { enabled: false, reason: "未绑定 LLM provider（先到「Provider 配置」子页绑定再跑）" };
    }
    return { enabled: true, reason: "可跑一轮（真 LLM 自行读世界、出令；耗时数十秒，可能停在等审批）" };
  }

  function setRunDecisionStatus(message, tone) {
    app.statusMessage(app.byId("decision-run-status"), message, tone);
  }

  /** 运行中的状态文案（**带秒数**）：这是"看起来没卡死"的唯一判据——秒数在动 ⇒ 这一轮还在跑。 */
  function runningText(id) {
    var seconds = Math.max(0, Math.round((Date.now() - runDecisionStartedAt) / 1000));
    return (
      "正在跑一轮：" + id + "（已 " + seconds + "s）——真 LLM 多轮工具调用；" +
      "若它出令（sd.IssueDirective），会在审批栏等审批（右下方通知栏 / 「审批」子页）"
    );
  }

  function startRunTicker(id) {
    stopRunTicker();
    runDecisionStartedAt = Date.now();
    setRunDecisionStatus(runningText(id), "muted");
    runDecisionTicker = setInterval(function () {
      setRunDecisionStatus(runningText(id), "muted");
    }, 1000);
  }

  function stopRunTicker() {
    if (runDecisionTicker) {
      clearInterval(runDecisionTicker);
      runDecisionTicker = null;
    }
  }

  function updateRunDecisionControl() {
    var button = app.byId("decision-run");
    var gate = runDecisionGate(runDecisionTarget);
    var targetId = runDecisionTarget ? String(runDecisionTarget.id) : "";
    if (button) {
      button.disabled = !gate.enabled;
      button.setAttribute("data-decision-target", targetId);
    }
    var stamp = targetId + "|" + gate.enabled + "|" + gate.reason;
    if (stamp !== lastRunGateStamp) {
      lastRunGateStamp = stamp;
      // ★ 正在跑的时候不让闸门文案把它盖掉（否则"已 Ns"会被门禁原因刷掉 ⇒ 看起来又像卡死）。
      if (!runDecisionTicker) {
        setRunDecisionStatus(gate.reason, gate.enabled ? "ok" : "muted");
      }
    }
  }

  function setRunDecisionTarget(maker) {
    runDecisionTarget = maker || null;
    updateRunDecisionControl();
  }

  function decideRunDecision() {
    var target = runDecisionTarget;
    if (!target || !runDecisionGate(target).enabled) {
      setRunDecisionStatus("未选中可跑的决策人", "warn");
      return;
    }
    var at = app.target() || {};
    clearRunTrace();
    startRunTicker(target.id);
    attemptRunDecision(target, at.branch, at.revision, true);
  }

  /**
   * 跑一轮；★ 收到 409（游标过期）时**重取 head、把游标拉到服务端 current.revision，并自动重试一次**（与
   * {@link attemptStartDecision} 同制：重试的那一轮**重新**开始计时，状态行不撒谎）。
   */
  function attemptRunDecision(target, branch, revision, mayRetry) {
    return api
      .runDecision(branch, revision, target.id)
      .then(function (body) {
        stopRunTicker();
        if (body && body.ref && body.ref.revision !== undefined && app.setRevision) {
          app.setRevision(body.ref.revision);
        }
        renderRunTrace(body);
        setRunDecisionStatus(runOutcomeText(body), runOutcomeTone(body));
        if (app.refreshState) {
          app.refreshState(true);
        }
        // ★ 这一轮真出过令 ⇒ 决策记录多了一条，当场重取（否则用户得自己刷新才看得见）。
        loadDecisionDirectives([target.id]);
        return body;
      })
      .catch(function (e) {
        if (e && e.status === 409 && mayRetry) {
          var current = e.body && e.body.current ? e.body.current : null;
          setRunDecisionStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return Promise.resolve()
            .then(function () {
              return app.refreshState ? app.refreshState(true) : null;
            })
            .catch(function () {
              return null;
            })
            .then(function () {
              var fresh =
                current && current.revision !== undefined
                  ? current.revision
                  : (app.target() || {}).revision;
              if (app.setRevision && fresh !== null && fresh !== undefined) {
                app.setRevision(fresh);
              }
              startRunTicker(target.id);
              return attemptRunDecision(target, fresh, false);
            });
        }
        stopRunTicker();
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setRunDecisionStatus("跑一轮失败：" + reason, "err");
        return null;
      });
  }

  /**
   * 结局文案（纯函数）：**提交结局与这一轮自己的结局分开说**——服务端的 HTTP 200 只保证"触发事实已落盘"，
   * 那一轮可能中止（预算）或没跑成（未绑定 / 路由坏 / 查无）。把它读成"跑好了"就是把两件事混成一件。
   */
  function runOutcomeText(body) {
    var b = body || {};
    if (b.result !== "committed") {
      return "触发未落盘：" + valueOrDash(b.result) + "（这一轮没有跑）";
    }
    if (b.reason === "turn-budget") {
      return "已中止：撞上回合预算（llmCalls=" + valueOrDash(b.llmCalls) + "）；历史已落盘，下一轮可续";
    }
    if (b.reason) {
      return "这一轮没跑成：" + valueOrDash(b.reason) + " —— " + valueOrDash(b.detail);
    }
    return (
      "跑完一轮：llmCalls=" + valueOrDash(b.llmCalls) + "，工具调用 " + toolCallCount(b) + " 次"
    );
  }

  function runOutcomeTone(body) {
    var b = body || {};
    if (b.result !== "committed" || b.reason) {
      return "warn";
    }
    return "ok";
  }

  function toolCallCount(body) {
    var calls = body && Array.isArray(body.toolCalls) ? body.toolCalls : [];
    return calls.length;
  }

  /**
   * 本轮轨迹的字段投影（纯函数，与 `POST /api/sd/run-decision` 的返回体逐字段对应）。
   *
   * <p>★ 缺值一律 `—`（**不拿 0 / false / 空串顶替**）：`llmCalls` 取不到与"一次都没调"是两件事。
   */
  function runTraceFields(body) {
    var b = body || {};
    var calls = Array.isArray(b.toolCalls) ? b.toolCalls : [];
    return {
      decisionMakerId: valueOrDash(b.decisionMakerId),
      conversationId: valueOrDash(b.conversationId),
      llmCalls: b.llmCalls === null || b.llmCalls === undefined ? "—" : String(b.llmCalls),
      abortedByBudget: b.abortedByBudget === true,
      finalText:
        b.finalText === null || b.finalText === undefined
          ? "（本轮没有收尾文本）"
          : String(b.finalText),
      revision: b.ref && b.ref.revision !== undefined ? String(b.ref.revision) : "—",
      // ★ 这一轮**没跑成**时服务端给的两个字段（未绑 provider / 路由查无 / 查无此人 / 撞预算）：
      //   有 reason 就说明**没有轨迹可言**——此时"工具调用 0 次"会被读成"跑得好、只是没调工具"，那是假的。
      reason: b.reason === null || b.reason === undefined ? null : String(b.reason),
      detail: b.detail === null || b.detail === undefined ? "—" : String(b.detail),
      toolCalls: calls.map(function (call) {
        var c = call || {};
        return {
          tool: valueOrDash(c.tool),
          ok: c.ok === true,
          code: valueOrDash(c.code),
          summary: valueOrDash(c.summary),
        };
      }),
    };
  }

  function clearRunTrace() {
    var mount = app.byId("decision-run-trace");
    if (mount) {
      app.clear(mount);
    }
  }

  /** 画本轮轨迹（`toolCalls` 逐个 + 收尾文本 + llmCalls）——**只画当次返回的那一份**。 */
  function renderRunTrace(body) {
    var mount = app.byId("decision-run-trace");
    if (!mount) {
      return;
    }
    var fields = runTraceFields(body);
    app.clear(mount);
    var head = app.el("div", { class: "run-trace-head" });
    head.setAttribute("data-decision-maker-id", fields.decisionMakerId);
    head.appendChild(
      app.el("span", { class: "run-trace-title", text: "本轮轨迹（决策人 " + fields.decisionMakerId + "）" })
    );
    head.appendChild(
      app.el("span", {
        class: "run-trace-meta",
        text:
          "llmCalls=" +
          fields.llmCalls +
          " · 落 " +
          fields.revision +
          " · 会话 " +
          fields.conversationId +
          (fields.abortedByBudget ? " · 因预算中止" : ""),
      })
    );
    mount.appendChild(head);
    // ★★ **"没跑成"与"跑了但没调工具"必须分得开**（都是空轨迹，含义相反）：前者有 reason（服务端如实报的
    //   失败码），后者才是真的空跑。折成同一句话会让"未绑定 provider"看起来像"跑得很顺"。
    if (fields.reason) {
      mount.appendChild(
        app.el("p", {
          class: "empty run-trace-failed",
          text: "这一轮没跑成：" + fields.reason + " —— " + fields.detail + "（没有轨迹可看）",
        })
      );
      appendRunTraceNote(mount);
      return;
    }
    if (!fields.toolCalls.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "这一轮没有调用任何工具。" }));
    }
    fields.toolCalls.forEach(function (call, index) {
      var row = app.el("div", { class: "run-trace-call" + (call.ok ? " ok" : " failed") });
      row.setAttribute("data-tool", call.tool);
      row.appendChild(
        app.el("span", { class: "run-trace-tool", text: index + 1 + ". " + call.tool })
      );
      row.appendChild(
        app.el("span", {
          class: "run-trace-ok",
          text: call.ok ? "OK" : "失败" + (call.code === "—" ? "" : "（" + call.code + "）"),
        })
      );
      row.appendChild(app.el("pre", { class: "run-trace-summary", text: call.summary }));
      mount.appendChild(row);
    });
    mount.appendChild(app.el("pre", { class: "run-trace-final", text: fields.finalText }));
    appendRunTraceNote(mount);
  }

  /** 读数里明说这件取舍：轨迹**不落盘** ⇒ 换 target / 刷新之后它就不在了（成功/失败两条路都带上）。 */
  function appendRunTraceNote(mount) {
    mount.appendChild(
      app.el("p", {
        class: "status muted run-trace-note",
        text: "轨迹只在这一次响应里（不落盘、不进 revision）——刷新或换时间点后不再复现；决策本身落的令见下方「决策记录」。",
      })
    );
  }

  // ── 决策记录（GET /api/sd/directives，2026-09-23）──────────────────────

  var decisionDirectivesToken = 0;
  var expandedDirectiveId = null;

  /** 决策记录的字段投影（纯函数，与 `GET /api/sd/directives` 逐字段对应；缺值 `—`，不编造）。 */
  function directiveFields(directive) {
    var d = directive || {};
    var commands = Array.isArray(d.commands) ? d.commands : [];
    var effects = Array.isArray(d.effects) ? d.effects : [];
    return {
      directiveId: valueOrDash(d.directiveId),
      decisionMakerId: valueOrDash(d.decisionMakerId),
      tick: d.tick === null || d.tick === undefined ? "—" : String(d.tick),
      target: d.target === null || d.target === undefined ? "（无目标）" : String(d.target),
      // ★ 执行原文（决心的"理由"）由服务端从 sd INFO 覆盖层取回；取不到 ⇒ 显式说没有，不拿 key 名顶替。
      intentInfo:
        d.intentInfo === null || d.intentInfo === undefined
          ? "（取不到执行原文）"
          : String(d.intentInfo),
      intentInfoKey: valueOrDash(d.intentInfoKey),
      status: valueOrDash(d.status),
      verdict: d.verdict === null || d.verdict === undefined ? "（无判决）" : String(d.verdict),
      effects: effects.length ? effects.join("、") : "（无）",
      commands: commands.map(function (command) {
        var c = command || {};
        return { type: valueOrDash(c.type), payloadJson: valueOrDash(c.payloadJson) };
      }),
    };
  }

  /** 列表项的一行摘要：`tick N · <执行原文首行>`（原文可能很长，只取首行、按字符截断）。 */
  function directiveHeadline(directive) {
    var fields = directiveFields(directive);
    var firstLine = fields.intentInfo.split("\n")[0];
    if (firstLine.length > 60) {
      firstLine = firstLine.slice(0, 60) + "…";
    }
    return "tick " + fields.tick + " · " + firstLine + "（" + fields.commands.length + " 条命令）";
  }

  /** 点一条 ⇒ 展开/收起它的详情（决心/理由/命令清单）。 */
  function toggleDirective(id) {
    expandedDirectiveId = expandedDirectiveId === id ? null : id;
    drawDirectiveList();
  }

  var loadedDirectives = [];

  function drawDirectiveList() {
    var mount = app.byId("decision-directives");
    if (!mount) {
      return;
    }
    var directives = loadedDirectives || [];
    app.clear(mount);
    if (!directives.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "这个决策人还没有出过令。" }));
      return;
    }
    var list = app.el("div", { class: "directive-list" });
    directives.forEach(function (directive) {
      var fields = directiveFields(directive);
      var item = app.el("div", { class: "directive-item" });
      item.setAttribute("data-directive-id", fields.directiveId);
      var head = app.el("button", { type: "button", class: "directive-head" });
      head.setAttribute("data-directive-tick", fields.tick);
      head.appendChild(app.el("span", { class: "directive-id", text: fields.directiveId }));
      head.appendChild(
        app.el("span", { class: "directive-headline", text: directiveHeadline(directive) })
      );
      head.addEventListener("click", function () {
        toggleDirective(fields.directiveId);
      });
      item.appendChild(head);
      if (expandedDirectiveId === fields.directiveId) {
        var detail = app.el("dl", { class: "kv directive-detail" });
        appendRow(detail, "决心 / 理由（intentInfo）", fields.intentInfo);
        appendRow(detail, "意图 INFO key", fields.intentInfoKey);
        appendRow(detail, "目标", fields.target);
        appendRow(detail, "状态", fields.status);
        appendRow(detail, "判决", fields.verdict);
        appendRow(detail, "效果", fields.effects);
        appendRow(
          detail,
          "命令清单（" + fields.commands.length + "）",
          fields.commands.length ? "" : "（无）"
        );
        fields.commands.forEach(function (command, index) {
          appendRow(detail, "  " + (index + 1) + ". " + command.type, command.payloadJson);
        });
        item.appendChild(detail);
      }
      list.appendChild(item);
    });
    mount.appendChild(list);
  }

  /**
   * 载入这些决策人的决策记录（0 个 ⇒ 提示；1 个 ⇒ 拉它的；多个 ⇒ **不猜哪一个**，让用户从右栏点选）。
   *
   * <p>★ 未知 id ⇒ 服务端 404（**不是空列表**）：那说明这个快照里没有这个人 —— 如实显示，不折成"还没出过令"。
   */
  function loadDecisionDirectives(makerIds) {
    var mount = app.byId("decision-directives");
    if (!mount) {
      return;
    }
    var ids = Array.isArray(makerIds) ? makerIds.filter(Boolean) : [];
    var token = ++decisionDirectivesToken;
    if (ids.length !== 1) {
      loadedDirectives = [];
      expandedDirectiveId = null;
      app.clear(mount);
      mount.appendChild(
        app.el("p", {
          class: "empty",
          text: ids.length
            ? "本格有 " + ids.length + " 个决策人；从右栏点选一个查看它的决策记录。"
            : "选中决策人后可看它的决策记录（出过的令：决心 / 理由 / 命令清单）。",
        })
      );
      return;
    }
    var makerId = String(ids[0]);
    loadedDirectives = [];
    expandedDirectiveId = null;
    app.clear(mount);
    // ★ **api 口不在就什么都不做**（与上面 SimosMap 那几处同口径的存在性判）：本页可能被旧版/嵌入方的
    //   api.js 驱动，那时光是"没有这个端点"，不是"这个人没有决策记录"——两者必须可区分（后者才显示空态）。
    if (typeof api.directives !== "function") {
      mount.appendChild(
        app.el("p", { class: "empty", text: "决策记录端点未接入（本页的 api.js 里没有 directives）" })
      );
      return;
    }
    mount.appendChild(app.el("p", { class: "empty", text: "载入决策记录 " + makerId + "…" }));
    api
      .directives(makerId, app.target())
      .then(function (body) {
        if (token !== decisionDirectivesToken) {
          return;
        }
        loadedDirectives = (body && body.directives) || [];
        drawDirectiveList();
      })
      .catch(function (e) {
        if (token !== decisionDirectivesToken) {
          return;
        }
        loadedDirectives = [];
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "决策记录载入失败：" + e.message }));
      });
  }

  /** 把一个决策人的详情写进容器（`data-decision-maker-id` 供 e2e/断言锚定）。 */
  function appendDecisionMakerDetail(container, maker, note) {
    var fields = decisionMakerFields(maker);
    var dl = app.el("dl", { class: "kv decision-maker" });
    dl.setAttribute("data-decision-maker-id", String(fields.id));
    appendRow(dl, "id", fields.id);
    appendRow(dl, "归属", fields.affiliation);
    appendRow(dl, "cadence（decisionCadenceTicks）", fields.cadence);
    appendRow(dl, "allowedTools", fields.allowedTools);
    if (fields.accessLimit) {
      appendRow(dl, "accessLimit.prefixesByNamespace", fields.accessLimit.prefixesByNamespace);
      appendRow(dl, "accessLimit.adjudicationDisclosure", fields.accessLimit.adjudicationDisclosure);
      appendRow(dl, "accessLimit.redactedFields", fields.accessLimit.redactedFields);
    } else {
      appendRow(dl, "accessLimit", "—");
    }
    appendRow(dl, "会话世代", fields.conversationGeneration);
    appendRow(dl, "会话 id", fields.conversationId);
    appendRow(dl, "绑定的 provider", maker && maker.providerId ? maker.providerId : "（未绑定）");
    appendRow(dl, "待决状态", fields.pending);
    // ★ 2026-09-23：这一行的**内容**（决心/理由/命令清单）在下方「决策记录」里——本行只报"最近一次在第几 tick"。
    appendRow(dl, "最近一次出令的 tick", fields.lastDirectiveTick);
    appendRow(dl, "距上次出令（tick）", fields.ticksSinceLast);
    // ★ 这一行由 map.js 回填（它才是拉 `/scope` 的那个文件）：内容 = "可见 N 格 / M 区域（国家级|军队级）"。
    appendLiveRow(dl, "可见范围（现算）", DECISION_SCOPE_SUMMARY_ID, "载入中…");
    if (note) {
      appendRow(dl, "来源", note);
    }
    container.appendChild(dl);
  }

  /** 无决策人时的显式文案（C14：不静默空白）。 */
  function appendNoDecisionMaker(container, reason) {
    container.appendChild(app.el("p", { class: "empty decision-empty", text: reason }));
  }

  function renderDecisionMakers(container, makers, note) {
    if (!makers.length) {
      appendNoDecisionMaker(container, "无决策人");
      return;
    }
    makers.forEach(function (maker) {
      appendDecisionMakerDetail(container, maker, note);
    });
  }

  /** 决策模式左栏：焦点决策人 > 地图选中的 hex / 单位；都没有 ⇒ 提示。 */
  function renderDecisionLeft(state) {
    if (!state || state.mode !== "decision") {
      return;
    }
    var status = app.byId("decision-view-status");
    var container = app.byId("decision-maker-detail");
    if (!status || !container) {
      return;
    }
    var focus = state.decisionMakerFocus || null;
    var selection = state.selection || null;
    var key = JSON.stringify([focus, selection, app.target()]);
    if (key === decisionLeftKey) {
      return;
    }
    decisionLeftKey = key;
    var token = ++decisionLeftToken;
    app.clear(container);

    if (focus) {
      setDecisionViewStatus("查询决策人 " + focus + "…", "muted");
      api
        .decisionMaker(focus, app.target())
        .then(function (maker) {
          if (token !== decisionLeftToken) {
            return;
          }
          app.clear(container);
          appendDecisionMakerDetail(container, maker, "右栏列表");
          // ★ 重画把「可见范围」那一行重置成了占位文本 ⇒ 让 map.js 把最近算好的摘要写回来
          //   （两处写同一个值，谁后落地都收敛）。
          if (window.SimosMap && window.SimosMap.republishDecisionScopeSummary) {
            window.SimosMap.republishDecisionScopeSummary();
          }
          setStartDecisionTarget(maker);
          loadDecisionDirectives([maker.id]);
          setDecisionViewStatus("决策人 " + maker.id + " · " + targetLabel(), "ok");
        })
        .catch(function (e) {
          if (token !== decisionLeftToken) {
            return;
          }
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus("决策人查询失败：" + e.message, "err");
        });
      return;
    }

    if (!selection) {
      setStartDecisionTarget(null);
      loadDecisionDirectives([]);
      setDecisionViewStatus("点选地图上的国家区域或有决策人的单位，查看其决策人信息。", "muted");
      return;
    }

    if (selection.kind === "hex") {
      setDecisionViewStatus("查询 " + hexLabel(selection) + " 的国家决策人…", "muted");
      Promise.all([
        api.mapHex(selection.q, selection.r, app.target()),
        loadOverview().catch(function () {
          return { regions: [] };
        }),
        api.cachedDecisionMakers(app.target()).catch(function () {
          return { decisionMakers: [] };
        }),
      ]).then(function (results) {
        if (token !== decisionLeftToken) {
          return;
        }
        var hex = results[0];
        var regions = results[1].regions || [];
        var makers = results[2].decisionMakers || [];
        var regionIds = Array.isArray(hex.regions) ? hex.regions : [];
        var nationIds =
          window.SimosMap && window.SimosMap.nationIdsOfRegions
            ? window.SimosMap.nationIdsOfRegions(regions, regionIds)
            : [];
        app.clear(container);
        if (!nationIds.length) {
          appendNoDecisionMaker(container, "该格不属于任何国家区域（无国家决策人）");
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus(hexLabel(hex) + " · 无国家区域", "muted");
          return;
        }
        var all = [];
        nationIds.forEach(function (nationId) {
          all = all.concat(decisionMakersForNation(makers, nationId));
        });
        renderDecisionMakers(container, all, "国家区域 " + nationIds.join("、"));
        // 多个国家决策人 ⇒ 无单一发起目标（不猜"哪一个"）。
        setStartDecisionTarget(all.length === 1 ? all[0] : null);
        loadDecisionDirectives(
          all.map(function (maker) {
            return maker.id;
          })
        );
        setDecisionViewStatus(hexLabel(hex) + " · " + targetLabel(), "ok");
      });
      return;
    }

    if (selection.kind === "unit") {
      setDecisionViewStatus("查询单位 " + selection.id + " 的决策人…", "muted");
      Promise.all([
        api.cachedUnits(app.target()).catch(function () {
          return { units: [] };
        }),
        api.cachedDecisionMakers(app.target()).catch(function () {
          return { decisionMakers: [] };
        }),
      ]).then(function (results) {
        if (token !== decisionLeftToken) {
          return;
        }
        var units = results[0].units || [];
        var makers = results[1].decisionMakers || [];
        var maker = decisionMakerForUnit(makers, units, selection.id);
        app.clear(container);
        if (!maker) {
          appendNoDecisionMaker(container, "无决策人");
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus("单位 " + selection.id + " · 无决策人", "muted");
          return;
        }
        appendDecisionMakerDetail(container, maker, "单位 " + selection.id);
        setStartDecisionTarget(maker);
        loadDecisionDirectives([maker.id]);
        setDecisionViewStatus("单位 " + selection.id + " · " + targetLabel(), "ok");
      });
      return;
    }

    setStartDecisionTarget(null);
    setDecisionViewStatus("未知选择类型：" + app.text(selection.kind), "warn");
  }

  // ── 决策模式右栏：分类列表（子页 A）+ 审批（子页 B）────────────────────

  var decisionRightToken = 0;
  var decisionRightKey = null;
  var decisionRightMakers = null;
  var decisionApprovalToken = 0;
  var decisionApprovalKey = null;
  var lastDecisionSubpage = null;

  /**
   * 右栏列表点一个决策人 ⇒ 聚焦它，**并清掉区域高亮**（可见范围的高亮改由 map.js 按 `/scope` 现算画）。
   *
   * <p>★★ **旧实现是第二份真相**：国家决策人按 `affiliation.id` 在**前端**从 overview 重算"该 tag 的全部区域"
   * 并整片高亮；军队决策人则只选中根单位、**什么都不画**。两者都不随 GM 的 `accessLimit` 变——GM 把某人的可见范围
   * 收窄到一个区域之后，界面照样画整片国土：**看起来对、其实是假的**（且不会报错）。
   *
   * <p>★ 军队级不再改选中单位（`setSelection` 会清掉 focus，两者互斥）：聚焦决策人后，它的根单位由左栏
   * `affiliation.rootUnit` 显示、位置由可见范围高亮覆盖——即"它看得见哪一圈"正是用户要看的东西。
   */
  function selectDecisionMakerFromList(maker) {
    app.setHighlightRegions([]);
    app.setDecisionMakerFocus(maker.id);
  }

  function drawDecisionList(state) {
    var mount = app.byId("decision-maker-list-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var makers = decisionRightMakers || [];
    if (!makers.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "该快照没有决策人。" }));
      return;
    }
    var groups = decisionMakerGroups(makers);
    var focus = state.decisionMakerFocus || null;
    groups.forEach(function (group) {
      var section = app.el("div", { class: "decision-group" });
      section.setAttribute("data-affiliation-kind", group.kind);
      var header = app.el("div", { class: "decision-group-head" });
      header.appendChild(app.el("span", { class: "decision-group-name", text: group.label }));
      header.appendChild(app.el("span", { class: "decision-group-count", text: String(group.makers.length) }));
      section.appendChild(header);
      var list = app.el("div", { class: "decision-list" });
      group.makers.forEach(function (maker) {
        var item = app.el("button", {
          type: "button",
          class: "decision-maker-item" + (maker.id === focus ? " selected" : ""),
        });
        item.setAttribute("data-decision-maker-id", String(maker.id));
        item.setAttribute("data-affiliation-kind", group.kind);
        item.appendChild(
          app.el("span", {
            class: "decision-maker-name",
            text: app.text(maker.affiliation ? maker.affiliation.displayName : maker.id),
          })
        );
        item.appendChild(app.el("span", { class: "decision-maker-id", text: String(maker.id) }));
        item.appendChild(
          app.el("span", { class: "decision-maker-pending", text: pendingStatusText(maker.due) })
        );
        item.addEventListener("click", function () {
          selectDecisionMakerFromList(maker);
        });
        list.appendChild(item);
      });
      section.appendChild(list);
      mount.appendChild(section);
    });
  }

  /** 右栏子页 A：全部决策人按类型分类排列（C15）。 */
  function renderDecisionRight(state) {
    var mount = app.byId("decision-maker-list-mount");
    if (!mount) {
      return;
    }
    var key = targetLabel();
    if (key === decisionRightKey) {
      if (decisionRightMakers !== null) {
        drawDecisionList(state);
      }
      return;
    }
    decisionRightKey = key;
    var token = ++decisionRightToken;
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入决策人…（" + key + "）" }));
    Promise.all([
      api.cachedDecisionMakers(app.target()),
      loadOverview().catch(function () {
        return { regions: [] };
      }),
    ])
      .then(function (results) {
        if (token !== decisionRightToken) {
          return;
        }
        decisionRightMakers = results[0].decisionMakers || [];
        drawDecisionList(state);
      })
      .catch(function (e) {
        if (token !== decisionRightToken) {
          return;
        }
        decisionRightMakers = null;
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "决策人载入失败：" + e.message }));
      });
  }

  function setDecisionApprovalStatus(message, tone) {
    app.statusMessage(app.byId("decision-approval-status"), message, tone);
  }

  function drawApprovalList(pending) {
    var mount = app.byId("decision-approval-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    if (!pending.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "无待批。" }));
      return;
    }
    pending.forEach(function (item) {
      var id = item && item.id !== null && item.id !== undefined ? String(item.id) : "";
      var row = app.el("div", { class: "approval-item" });
      row.setAttribute("data-approval-id", id);
      row.appendChild(app.el("div", { class: "approval-tool", text: app.text(item ? item.tool : null) }));
      row.appendChild(
        app.el("div", { class: "approval-summary", text: app.text(item ? item.summary : null) })
      );
      var actions = app.el("div", { class: "approval-actions" });
      [["approve", "批准"], ["deny", "驳回"]].forEach(function (pair) {
        var button = app.el("button", { type: "button", class: "approval-" + pair[0], text: pair[1] });
        button.setAttribute("data-decision", pair[0]);
        button.disabled = !id;
        button.addEventListener("click", function () {
          decideApproval(id, pair[0]);
        });
        actions.appendChild(button);
      });
      row.appendChild(actions);
      mount.appendChild(row);
    });
  }

  /** 审批裁决：打 POST /api/approvals/{id}（非命令写），成功后重取待批列表。 */
  function decideApproval(id, decision) {
    if (!id) {
      return;
    }
    setDecisionApprovalStatus("提交「" + decision + "」…", "muted");
    api
      .approve(id, decision, "once")
      .then(function () {
        setDecisionApprovalStatus("已提交：" + decision + " " + id, "ok");
        renderDecisionApproval(true);
      })
      .catch(function (e) {
        setDecisionApprovalStatus("审批提交失败：" + e.message, "err");
      });
  }

  /** 右栏子页 B：待批列表（读 GET /api/approvals；force ⇒ 忽略同 key 缓存）。 */
  function renderDecisionApproval(force) {
    var mount = app.byId("decision-approval-mount");
    if (!mount) {
      return;
    }
    var key = "approvals";
    if (!force && key === decisionApprovalKey) {
      return;
    }
    decisionApprovalKey = key;
    var token = ++decisionApprovalToken;
    api
      .approvals()
      .then(function (body) {
        if (token !== decisionApprovalToken) {
          return;
        }
        var pending = Array.isArray(body) ? body : body && Array.isArray(body.pending) ? body.pending : [];
        drawApprovalList(pending);
      })
      .catch(function (e) {
        if (token !== decisionApprovalToken) {
          return;
        }
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "审批未接入：" + e.message }));
      });
  }

  /** 决策模式总入口：只在 decision 模式渲染（左栏详情 + 右栏列表/审批/provider）。 */
  function renderDecision(state) {
    if (!state || state.mode !== "decision") {
      lastDecisionSubpage = null;
      return;
    }
    var subpage = decisionSubpageVisibility(state.decisionSubpage);
    // 切进审批子页时强制重取（否则同 key 缓存会让"刚提交的审批"看起来没变化）。
    var entered = state.decisionSubpage !== lastDecisionSubpage;
    lastDecisionSubpage = state.decisionSubpage;
    if (subpage.view) {
      renderDecisionLeft(state);
      renderDecisionRight(state);
    } else if (subpage.approval) {
      renderDecisionApproval(entered);
    } else if (subpage.provider) {
      renderLlmProviders(entered);
    }
  }

  // ── Provider 配置子页（M11′）：读写 AgentLib 的 ConfigStore ───────────────
  var lastLlmProvidersLoaded = false;

  function setLlmProviderStatus(message, tone) {
    app.statusMessage(app.byId("llm-provider-status"), message, tone);
  }

  /** 拉列表并渲染（掩码视图；坏条目带错误码照样列）。 */
  function renderLlmProviders(force) {
    var mount = app.byId("llm-provider-list");
    if (!mount) {
      return;
    }
    if (!force && lastLlmProvidersLoaded) {
      return;
    }
    lastLlmProvidersLoaded = true;
    api
      .llmProviders()
      .then(function (body) {
        mount.textContent = "";
        var providers = (body && body.providers) || [];
        if (providers.length === 0) {
          mount.appendChild(app.el("p", { class: "empty", text: "还没有配置任何 provider。" }));
          return;
        }
        providers.forEach(function (provider) {
          var fields = providerFields(provider);
          var row = app.el("div", { class: "llm-provider-row" });
          row.setAttribute("data-provider-id", fields.id);
          row.setAttribute("data-provider-valid", fields.valid ? "true" : "false");
          row.appendChild(
            app.el("strong", { text: fields.id + (fields.valid ? "" : "（配置不完整）") })
          );
          if (!fields.valid) {
            row.appendChild(app.el("span", { class: "muted", text: " " + fields.errorCode }));
          } else {
            var dl = app.el("dl", { class: "kv llm-provider-kv" });
            fields.rows.forEach(function (pair) {
              dl.appendChild(app.el("dt", { text: pair[0] }));
              dl.appendChild(app.el("dd", { text: pair[1] }));
            });
            row.appendChild(dl);
          }
          mount.appendChild(row);
        });
      })
      .catch(function (e) {
        mount.textContent = "";
        mount.appendChild(app.el("p", { class: "empty", text: "provider 载入失败：" + e.message }));
      });
  }

  /** 保存 provider：表单 → 载荷（纯函数校验）→ POST；成功后重取列表。 */
  function saveLlmProviderFromForm() {
    var result = providerFormToPayload({
      id: valueOf("llm-provider-id"),
      baseUrl: valueOf("llm-provider-baseurl"),
      model: valueOf("llm-provider-model"),
      apiKey: valueOf("llm-provider-apikey"),
      readTimeoutMs: valueOf("llm-provider-timeout"),
    });
    if (!result.ok) {
      setLlmProviderStatus("保存失败：" + result.reason, "err");
      return;
    }
    setLlmProviderStatus("保存中 " + result.payload.id + "…", "muted");
    api
      .saveLlmProvider(result.payload)
      .then(function () {
        setLlmProviderStatus("已保存：" + result.payload.id, "ok");
        renderLlmProviders(true);
      })
      .catch(function (e) {
        setLlmProviderStatus("保存失败：" + (e && e.message ? e.message : String(e)), "err");
      });
  }

  /** 测试连接：用列表当前选中/表单的 id 打一次真调用（服务端），回 ok/detail。 */
  function testLlmProviderFromForm() {
    var id = valueOrDash(valueOf("llm-provider-id"));
    if (id === "—") {
      setLlmProviderStatus("测试连接：先在 id 里填一个 provider", "warn");
      return;
    }
    setLlmProviderStatus("测试连接 " + id + "…", "muted");
    api
      .testLlmProvider(id)
      .then(function (body) {
        var ok = body && body.ok === true;
        setLlmProviderStatus(
          (ok ? "连接成功：" : "连接失败：") + ((body && body.detail) || ""),
          ok ? "ok" : "err"
        );
      })
      .catch(function (e) {
        setLlmProviderStatus("测试连接失败：" + (e && e.message ? e.message : String(e)), "err");
      });
  }

  /** 决策人绑定 provider（世界写，落 revision）。 */
  function saveDecisionMakerProviderFromForm() {
    var result = providerBindingPayload(valueOf("llm-binding-maker"), valueOf("llm-binding-provider"));
    if (!result.ok) {
      setLlmProviderStatus("绑定失败：" + result.reason, "err");
      return;
    }
    var at = app.target() || {};
    setLlmProviderStatus("绑定 " + result.decisionMakerId + " → " + result.providerId + "…", "muted");
    api
      .setDecisionMakerProvider(at.branch, at.revision, result.decisionMakerId, result.providerId)
      .then(function () {
        setLlmProviderStatus(
          "已绑定：" + result.decisionMakerId + " → " + result.providerId + "（落 revision）",
          "ok"
        );
        if (app.refreshState) {
          app.refreshState(true);
        }
      })
      .catch(function (e) {
        if (e && e.status === 409) {
          setLlmProviderStatus("末端已移动（409），已重取最新状态", "warn");
          if (app.refreshState) {
            app.refreshState(true);
          }
          return;
        }
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setLlmProviderStatus("绑定失败：" + reason, "err");
      });
  }

  /** 表单字段读值（缺节点 ⇒ ""，不抛）。 */
  function valueOf(id) {
    var node = app.byId(id);
    return node && node.value !== undefined ? node.value : "";
  }

  /** 工作台初始化：订阅状态并渲染左栏真读数 + 右栏区域分组。 */
  function init() {
    var startButton = app.byId("decision-start");
    if (startButton && startButton.addEventListener) {
      startButton.addEventListener("click", decideStartDecision);
    }
    var runButton = app.byId("decision-run");
    if (runButton && runButton.addEventListener) {
      runButton.addEventListener("click", decideRunDecision);
    }
    var providerSave = app.byId("llm-provider-save");
    if (providerSave && providerSave.addEventListener) {
      providerSave.addEventListener("click", saveLlmProviderFromForm);
    }
    var providerTest = app.byId("llm-provider-test");
    if (providerTest && providerTest.addEventListener) {
      providerTest.addEventListener("click", testLlmProviderFromForm);
    }
    var bindingSave = app.byId("llm-binding-save");
    if (bindingSave && bindingSave.addEventListener) {
      bindingSave.addEventListener("click", saveDecisionMakerProviderFromForm);
    }
    updateStartDecisionControl();
    updateRunDecisionControl();
    renderRight(app.getState());
    renderLeft(app.getState());
    renderDecision(app.getState());
    app.onStateChange(function (state) {
      renderLeft(state);
      renderRight(state);
      renderDecision(state);
    });
  }

  window.SimosPanels = {
    init: init,
    renderLeft: renderLeft,
    renderRight: renderRight,
    renderDecision: renderDecision,
    renderDecisionLeft: renderDecisionLeft,
    renderDecisionRight: renderDecisionRight,
    groupByTag: groupByTag,
    normalizeTag: normalizeTag,
    movementReadout: movementReadout,
    // ★ M8 T9：左栏"从属区域"读数（纯函数）——门禁直接对它下断言（并集 ≠ 求和）。
    regionMembershipSummary: regionMembershipSummary,
    UNTAGGED_LABEL: UNTAGGED_LABEL,
    // ★ T7：决策模式的纯函数（门禁直接断言；无 DOM/IO）——子页、分类分组、国家/单位解析、待决文本。
    DECISION_SUBPAGES: DECISION_SUBPAGES,
    DECISION_SCOPE_SUMMARY_ID: DECISION_SCOPE_SUMMARY_ID,
    decisionSubpageState: decisionSubpageState,
    decisionSubpageVisibility: decisionSubpageVisibility,
    // ★ M11′：Provider 配置页的纯函数（表单 → 载荷 / 掩码视图 / 绑定载荷）。
    providerFormToPayload: providerFormToPayload,
    providerFields: providerFields,
    providerBindingPayload: providerBindingPayload,
    affiliationKindLabel: affiliationKindLabel,
    affiliationLabel: affiliationLabel,
    pendingStatusText: pendingStatusText,
    // ★ T10：「开始决策」闸门（纯函数）+ 当前目标（e2e/调试用）。
    startDecisionGate: startDecisionGate,
    startDecision: decideStartDecision,
    startDecisionTargetId: function () {
      return startDecisionTarget ? String(startDecisionTarget.id) : null;
    },
    // ★ 2026-09-23：「让它跑一轮」（sd.RunDecision）+ 决策记录（/api/sd/directives）的纯函数投影。
    runDecisionGate: runDecisionGate,
    runDecision: decideRunDecision,
    runDecisionTargetId: function () {
      return runDecisionTarget ? String(runDecisionTarget.id) : null;
    },
    runOutcomeText: runOutcomeText,
    runTraceFields: runTraceFields,
    directiveFields: directiveFields,
    directiveHeadline: directiveHeadline,
    decisionMakerGroups: decisionMakerGroups,
    decisionMakersForNation: decisionMakersForNation,
    decisionMakerForUnit: decisionMakerForUnit,
    decisionMakerFields: decisionMakerFields,
    /** 决策模式只读投影（e2e/调试）：已载入的决策人数、分组数、聚焦 id、子页可见性。 */
    decisionDebug: function () {
      var state = app.getState();
      var groups = decisionMakerGroups(decisionRightMakers || []);
      return {
        mode: state.mode,
        subpage: state.decisionSubpage,
        subpageVisibility: decisionSubpageVisibility(state.decisionSubpage),
        focus: state.decisionMakerFocus,
        makerCount: (decisionRightMakers || []).length,
        groupKinds: groups.map(function (group) {
          return group.kind;
        }),
        groupCounts: groups.map(function (group) {
          return group.makers.length;
        }),
        nationRegionCount: (app.getState().highlightRegions || []).length,
      };
    },
  };
})();
