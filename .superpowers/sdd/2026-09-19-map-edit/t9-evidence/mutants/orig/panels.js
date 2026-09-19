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
        app.setHighlightRegions(ids.slice());
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
            app.setHighlightRegions([region.id]);
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

  /** 工作台初始化：订阅状态并渲染左栏真读数 + 右栏区域分组。 */
  function init() {
    renderRight(app.getState());
    renderLeft(app.getState());
    app.onStateChange(function (state) {
      renderLeft(state);
      renderRight(state);
    });
  }

  window.SimosPanels = {
    init: init,
    renderLeft: renderLeft,
    renderRight: renderRight,
    groupByTag: groupByTag,
    normalizeTag: normalizeTag,
    movementReadout: movementReadout,
    // ★ M8 T9：左栏"从属区域"读数（纯函数）——门禁直接对它下断言（并集 ≠ 求和）。
    regionMembershipSummary: regionMembershipSummary,
    UNTAGGED_LABEL: UNTAGGED_LABEL,
  };
})();
