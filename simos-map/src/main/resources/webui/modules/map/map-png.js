// map-png.js —— 地图编辑的底图导入工作区：载入、识别、修整、单命令确认。
(function () {
  "use strict";
  var core = window.SimosRasterCore;
  var draftStore = window.SimosRasterDraft;
  var image = null, blob = null, fileName = "", pixels = null, imageWidth = 0, imageHeight = 0;
  var terrains = [], hexes = [], mapId = "", plan = null, overrides = {}, sourceVersion = 0;
  var loadToken = 0, previewToken = 0, contextToken = 0, parameterVersion = 0, restoreVersion = 0;
  var draftKey = "", draftTimer = null, loading = false, contextLoading = false;
  var applying = false, completedPlan = null, bound = false, restorePromise = null, reviewed = false;
  var activeSubmission = null, controlLocks = [];
  var previewView = { scale: 1, tx: 0, ty: 0 };
  var PARAMETERS = ["raster-kind", "raster-grid", "raster-columns", "raster-origin-q", "raster-origin-r", "raster-crop-left", "raster-crop-right", "raster-crop-top", "raster-crop-bottom", "png-offset-x", "png-offset-y", "png-scale", "png-threshold", "raster-water", "raster-sensitivity", "raster-invert", "raster-black", "raster-white", "raster-sea"];
  var WORKSPACE_CONTROLS = PARAMETERS.concat(["raster-open", "raster-close", "png-file", "png-example", "png-clear", "png-sample", "raster-paint", "raster-view", "png-opacity", "raster-outline", "raster-uncertain", "raster-review", "raster-confirm", "png-apply", "raster-refine"]);
  function refreshAvailability() {
    if (applying) return;
    if (byId("png-sample")) byId("png-sample").disabled = !pixels || loading || contextLoading;
    if (byId("raster-review")) byId("raster-review").disabled = loading || !currentPlan() || !plan.assignments.length;
    if (byId("png-apply")) byId("png-apply").disabled = !reviewed || !check("raster-confirm") || !currentPlan() || !isWritable();
  }
  function lockWorkspace() {
    controlLocks = WORKSPACE_CONTROLS.map(function (id) {
      var node = byId(id), previous = node ? !!node.disabled : false;
      if (node) node.disabled = true;
      return { node: node, disabled: previous };
    });
    var canvas = byId("raster-canvas");
    if (canvas) { canvas.setAttribute("aria-disabled", "true"); canvas.style.pointerEvents = "none"; }
    var dialog = byId("raster-dialog"); if (dialog) dialog.setAttribute("aria-busy", "true");
  }
  function unlockWorkspace() {
    controlLocks.forEach(function (entry) { if (entry.node) entry.node.disabled = entry.disabled; }); controlLocks = [];
    var canvas = byId("raster-canvas");
    if (canvas) { canvas.removeAttribute("aria-disabled"); canvas.style.pointerEvents = ""; }
    var dialog = byId("raster-dialog"); if (dialog) dialog.removeAttribute("aria-busy");
    refreshAvailability();
  }
  function closeWorkspace() {
    if (applying) return false;
    var dialog = byId("raster-dialog"); if (!dialog) return false;
    if (dialog.close) dialog.close(); else dialog.removeAttribute("open");
    return true;
  }
  function byId(id) { return document.getElementById(id); }
  function app() { return window.SimosApp; }
  function status(text, tone) {
    var node = byId("png-status");
    if (node) { node.textContent = text || ""; node.className = "status " + (tone || "muted"); }
    var entry = byId("raster-entry-status");
    if (entry) entry.textContent = text || "载入 → 识别修整 → 确认应用";
  }
  function note(text) { var node = byId("raster-draft-status"); if (node) node.textContent = text; }
  function val(id) { var node = byId(id); return node ? node.value : ""; }
  function num(id) { var value = val(id); return String(value).trim() === "" ? NaN : Number(value); }
  function check(id) { return !!(byId(id) && byId(id).checked); }
  function signature() {
    var state = app() && app().getState ? app().getState() : {};
    return JSON.stringify([state.branch, state.revision, state.tick, state.writeEpoch]);
  }
  function isWritable() {
    var state = app().getState();
    var host = window.SimosMapCore && window.SimosMapCore.host;
    return state.mode === "map-edit" && host && host.mapEditTool === "image-import"
      && state.revision !== null && state.revision !== undefined
      && state.heads && Number(state.heads[state.branch]) === Number(state.revision);
  }
  function stage(name) {
    ["load", "recognize", "confirm"].forEach(function (step) {
      var n = byId("raster-step-" + step); if (n) n.classList.toggle("active", step === name);
    });
  }
  function invalidateReview() {
    reviewed = false;
    if (byId("raster-confirmation")) byId("raster-confirmation").hidden = true;
    if (byId("raster-confirm")) byId("raster-confirm").checked = false;
    if (byId("png-apply")) byId("png-apply").disabled = true;
  }
  function invalidate(message, resetOverrides) {
    previewToken++; plan = null; completedPlan = null;
    if (resetOverrides) overrides = {};
    invalidateReview();
    if (byId("raster-finished")) byId("raster-finished").hidden = true;
    if (byId("raster-review")) byId("raster-review").disabled = true;
    if (byId("png-preview")) byId("png-preview").textContent = "";
    if (byId("raster-statistics")) byId("raster-statistics").textContent = "";
    if (image) { stage("recognize"); if (message) status(message, "warn"); }
    draw();
  }
  function readOptions() {
    return { kind: val("raster-kind") || "relief", grid: val("raster-grid") || "generate", columns: num("raster-columns"),
      originQ: num("raster-origin-q"), originR: num("raster-origin-r"), cropLeft: num("raster-crop-left"), cropRight: num("raster-crop-right"), cropTop: num("raster-crop-top"), cropBottom: num("raster-crop-bottom"),
      offsetX: num("png-offset-x"), offsetY: num("png-offset-y"), scale: num("png-scale"), threshold: num("png-threshold"),
      waterThreshold: num("raster-water"), sensitivity: num("raster-sensitivity"), invert: check("raster-invert"), black: num("raster-black"), white: num("raster-white"), seaLevel: num("raster-sea"),
      cell: window.SimosMapCore && window.SimosMapCore.BASE_CELL ? window.SimosMapCore.BASE_CELL : 22 };
  }
  function parameters() {
    var out = {}; PARAMETERS.forEach(function (id) { var n = byId(id); if (n) out[id] = n.type === "checkbox" ? n.checked : n.value; }); return out;
  }
  function putParameters(values) {
    PARAMETERS.forEach(function (id) { var n = byId(id); if (n && values && Object.prototype.hasOwnProperty.call(values, id)) { if (n.type === "checkbox") n.checked = !!values[id]; else n.value = values[id]; } });
    updateControls();
  }
  function updateControls() {
    var kind = val("raster-kind"), generate = val("raster-grid") === "generate";
    Array.prototype.forEach.call(document.querySelectorAll("[data-raster-kind]"), function (n) { n.hidden = n.getAttribute("data-raster-kind") !== kind; });
    if (byId("raster-grid-generate")) byId("raster-grid-generate").hidden = !generate;
    if (byId("raster-grid-existing")) byId("raster-grid-existing").hidden = generate;
    var n = byId("raster-kind-note");
    if (n) n.textContent = kind === "relief" ? "按水体颜色与局部纹理近似分类，不代表真实海拔；细河线不会直接变成整格海洋。"
      : kind === "height" ? "灰度表示相对高度。请按原图校准黑白方向和海陆分界，不从高度推断沙漠。" : "按格内颜色统计与当前地图图例匹配；超出颜色容差的格会跳过。";
  }
  function draftRecord() {
    return { version: 1, blob: blob, fileName: fileName, parameters: parameters(), corrections: Object.assign({}, overrides), savedAt: Date.now() };
  }
  function persistDraft(key, value, version) {
    if (!key || !value.blob) return;
    draftStore.save(key, value).then(function () { if (key === draftKey && version === sourceVersion) note("图片与参数已保存在本机；刷新恢复后仍需重新识别确认。"); })
      .catch(function () { if (key === draftKey && version === sourceVersion) note("本地草稿不可用，仅本次有效。请保留原图。"); });
  }
  function scheduleSave() {
    if (applying) return;
    if (draftTimer) clearTimeout(draftTimer);
    if (!blob || !draftKey) return;
    var key = draftKey, version = sourceVersion, value = draftRecord();
    draftTimer = setTimeout(function () {
      draftTimer = null;
      if (!blob || key !== draftKey || version !== sourceVersion || applying) return;
      persistDraft(key, value, version);
    }, 250);
  }
  function resetImage() {
    var renderer = window.SimosMapCore && window.SimosMapCore.active;
    if (renderer && renderer.setPngOverlay) renderer.setPngOverlay(null);
    loadToken++; restoreVersion++; loading = false; sourceVersion++; image = null; blob = null; pixels = null; imageWidth = 0; imageHeight = 0; fileName = "";
    invalidate("", true);
    if (byId("png-sample")) byId("png-sample").disabled = true;
    if (byId("raster-file-info")) byId("raster-file-info").textContent = "自动识别在浏览器本地处理，不上传原图；点“共享当前底图”给判图工具时，会把当前原图上传到本机世界服务。";
    stage("load"); draw();
  }
  async function loadSource(nextBlob, name, restoring) {
    if (applying) return;
    if (!restoring) restoreVersion++;
    var token = ++loadToken; loading = true;
    invalidate("正在解码图片…", !restoring);
    if (byId("png-sample")) byId("png-sample").disabled = true;
    if (!nextBlob || nextBlob.size > 32 * 1024 * 1024) {
      loading = false; refreshAvailability();
      throw new Error("图片文件须不超过 32MB" + (image ? "；保留此前底图，需重新识别" : ""));
    }
    var url = URL.createObjectURL(nextBlob);
    var decoded = new Image();
    try {
      await new Promise(function (resolve, reject) { decoded.onload = resolve; decoded.onerror = function () { reject(new Error("图片无法解码，请选择 PNG/JPEG/WebP")); }; decoded.src = url; });
      if (token !== loadToken) return;
      var width = decoded.naturalWidth || decoded.width, height = decoded.naturalHeight || decoded.height;
      if (!width || !height || width * height > 32000000) throw new Error("图片须不超过 3200 万像素");
      var canvas = document.createElement("canvas"); canvas.width = width; canvas.height = height;
      var ctx = canvas.getContext("2d", { willReadFrequently: true }); ctx.drawImage(decoded, 0, 0);
      var nextPixels = ctx.getImageData(0, 0, width, height).data;
      if (token !== loadToken) return;
      image = decoded; blob = nextBlob; fileName = name || "本地图像"; pixels = nextPixels; imageWidth = width; imageHeight = height; sourceVersion++;
      byId("raster-file-info").textContent = fileName + " · " + width + "×" + height
        + " · 自动识别在本地；共享参考图时上传到本机世界服务。";
      byId("png-sample").disabled = false; stage("recognize");
      status(restoring ? "已恢复底图与参数；请重新识别，确认后才会写入地图。" : "底图已载入。调整格网与识别方式，再生成预览。", "ok");
      draw(); if (!restoring) scheduleSave();
    } catch (error) {
      if (token === loadToken) {
        if (byId("png-sample")) byId("png-sample").disabled = !pixels;
        status(error.message + (image ? "；保留此前底图，需重新识别。" : "；请重新选择图片。"), "err");
      }
      throw error;
    } finally {
      URL.revokeObjectURL(url);
      if (token === loadToken) { loading = false; refreshAvailability(); }
    }
  }
  async function ensureContext() {
    if (applying) return;
    if (!app() || !app().getState().revision) throw new Error("世界尚未就绪，请稍后再试");
    var token = ++contextToken, target = app().target(), sig = signature();
    var values = await Promise.all([
      window.SimosApi.getJson(window.SimosApi.withTarget("/map/overview", target)),
      window.SimosApi.getJson(window.SimosApi.withTarget("/map/hexes", target))
    ]);
    if (applying) return;
    if (token !== contextToken || sig !== signature()) throw new Error("目标节点已变化，请重新打开导入工作区");
    terrains = values[0].terrainTypes || []; hexes = values[1].hexes || []; mapId = values[0].mapId || "Map1";
    var key = String(window.location && window.location.origin || "local") + "|" + target.branch + "|" + mapId;
    fillPalette();
    if (key !== draftKey) {
      resetImage(); draftKey = key;
      var restoreToken = loadToken, savedParameterVersion = parameterVersion, savedRestoreVersion = restoreVersion;
      restorePromise = draftStore.load(key).then(async function (saved) {
        // 图片恢复只看恢复/载入代次；参数版本只决定是否回填草稿参数，二者不能互相取消。
        if (applying || draftKey !== key || loadToken !== restoreToken || savedRestoreVersion !== restoreVersion) return;
        if (saved && saved.version === 1 && saved.blob) {
          if (savedParameterVersion === parameterVersion) putParameters(saved.parameters);
          overrides = saved.corrections || {};
          await loadSource(saved.blob, saved.fileName, true);
          if (!applying && draftKey === key && loadToken === restoreToken + 1 && image) note(savedParameterVersion === parameterVersion ? "已恢复本机草稿；旧预览没有恢复。" : "已恢复底图，保留本次编辑的参数；请重新识别。");
        } else note("尚无本机草稿；选择图片或载入示例。");
      }).catch(function () { if (key === draftKey) note("本地草稿不可用，仅本次有效。"); });
      await restorePromise;
    }
  }
  function fillPalette() {
    var select = byId("raster-paint"); if (!select) return;
    var previous = select.value; select.textContent = "";
    var empty = document.createElement("option"); empty.value = ""; empty.textContent = "仅查看，不修改"; select.appendChild(empty);
    terrains.forEach(function (t) { var option = document.createElement("option"); option.value = t.key; option.textContent = t.name || t.key; select.appendChild(option); });
    select.value = previous;
  }
  async function openWorkspace() {
    if (applying || contextLoading) return;
    contextLoading = true;
    var dialog = byId("raster-dialog");
    if (!dialog) { contextLoading = false; return; }
    if (dialog.showModal && !dialog.open) dialog.showModal(); else dialog.setAttribute("open", "");
    byId("png-file").disabled = true; byId("png-example").disabled = true; byId("png-clear").disabled = true;
    try { await ensureContext(); if (!applying) draw(); } catch (e) { if (!applying) status(e.message, "err"); }
    finally { contextLoading = false; if (!applying) { byId("png-file").disabled = false; byId("png-example").disabled = false; byId("png-clear").disabled = false; refreshAvailability(); } }
  }
  async function loadExample() {
    if (applying) return;
    restoreVersion++; parameterVersion++;
    byId("png-file").value = "";
    byId("raster-kind").value = "relief"; byId("raster-grid").value = "generate"; updateControls();
    var token = ++loadToken; loading = true; invalidate("正在读取非洲之角示例…", true);
    byId("png-sample").disabled = true;
    try {
      var response = await fetch("/webui/modules/map/examples/horn-of-africa.png");
      if (!response.ok) throw new Error("示例图片读取失败");
      var next = await response.blob();
      if (token !== loadToken) return;
      await loadSource(next, "非洲之角示例", false);
    } catch (e) { if (token === loadToken) status(e.message, "err"); }
    finally { if (token === loadToken) { loading = false; refreshAvailability(); } }
  }
  function recount() {
    if (!plan) return;
    plan.counts = {};
    plan.assignments.forEach(function (item) { plan.counts[item.terrain] = (plan.counts[item.terrain] || 0) + 1; });
  }
  async function preview() {
    if (applying || loading || contextLoading) return;
    if (!pixels) { status("先载入底图。", "warn"); return; }
    invalidate("", false);
    var token = ++previewToken, sig = signature(), version = sourceVersion;
    invalidateReview(); status("正在按六角形覆盖区域采样…", "muted");
    try {
      var opts = readOptions();
      var target = app().target();
      var values = await Promise.all([
        window.SimosApi.getJson(window.SimosApi.withTarget("/map/hexes", target)),
        window.SimosApi.getJson(window.SimosApi.withTarget("/map/overview", target))
      ]);
      if (token !== previewToken || sig !== signature() || version !== sourceVersion) return;
      hexes = values[0].hexes || []; terrains = values[1].terrainTypes || []; fillPalette();
      var next = core.buildPlan(hexes, pixels, imageWidth, imageHeight, opts, terrains);
      if (token !== previewToken || sig !== signature() || version !== sourceVersion) return;
      next.signature = sig; next.sourceVersion = version; next.options = opts;
      next.assignments.forEach(function (item) {
        var correction = overrides[item.q + "_" + item.r];
        var def = terrains.find(function (t) { return t.key === correction; });
        if (def) { item.terrain = def.key; if (opts.kind !== "height") item.height = core.heightOf(def); item.manual = true; }
      });
      plan = next; completedPlan = null; recount(); renderCounts(); draw(); stage("recognize");
      byId("raster-review").disabled = !next.assignments.length;
      byId("raster-finished").hidden = true;
      status(next.assignments.length ? "识别完成。可点击格子修整，检查后再确认导入。" : "没有可导入的格。检查对齐、图例或透明区域。", next.assignments.length ? "ok" : "warn");
      setOverlay(next.overlay); scheduleSave();
    } catch (e) { if (token === previewToken) { invalidate("", false); status("识别失败：" + e.message, "err"); } }
  }
  function renderCounts() {
    var mount = byId("png-preview"); if (!mount) return; mount.textContent = "";
    if (!plan) return;
    Object.keys(plan.counts).sort(function (a, b) { return plan.counts[b] - plan.counts[a]; }).forEach(function (key) {
      var def = terrains.find(function (t) { return t.key === key; });
      var row = document.createElement("span"); row.className = "png-preview-row";
      var dot = document.createElement("span"); dot.className = "png-swatch"; dot.style.background = def ? def.color : "#999";
      row.appendChild(dot); row.appendChild(document.createTextNode((def ? def.name : key) + " " + plan.counts[key])); mount.appendChild(row);
    });
    byId("raster-statistics").textContent = "有效 " + plan.assignments.length + " 格 · 新增 " + plan.added + " · 覆盖 " + plan.updated + " · 跳过 " + plan.skipped + " · 人工修整 " + plan.assignments.filter(function (x) { return x.manual; }).length;
  }
  function draw() {
    var canvas = byId("raster-canvas"); if (!canvas || !canvas.getContext) return;
    var ctx = canvas.getContext("2d"); if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (byId("raster-empty")) byId("raster-empty").hidden = !!image;
    if (!image) return;
    var scale = Math.min((canvas.width - 20) / imageWidth, (canvas.height - 20) / imageHeight);
    var tx = (canvas.width - imageWidth * scale) / 2, ty = (canvas.height - imageHeight * scale) / 2;
    previewView = { scale: scale, tx: tx, ty: ty };
    var view = val("raster-view") || "overlay";
    if (view !== "result") ctx.drawImage(image, tx, ty, imageWidth * scale, imageHeight * scale);
    if (!plan || view === "source") return;
    var defs = {}; terrains.forEach(function (t) { defs[t.key] = t; });
    plan.assignments.forEach(function (hex) {
      var corners = window.SimosHexGeom.hexCorners(hex.imageX, hex.imageY, hex.imageRadius);
      ctx.beginPath(); corners.forEach(function (p, i) { if (i) ctx.lineTo(tx + p.x * scale, ty + p.y * scale); else ctx.moveTo(tx + p.x * scale, ty + p.y * scale); }); ctx.closePath();
      ctx.globalAlpha = view === "result" ? 1 : num("png-opacity");
      ctx.fillStyle = defs[hex.terrain] ? defs[hex.terrain].color : "#999"; ctx.fill(); ctx.globalAlpha = 1;
      if (check("raster-outline") || (check("raster-uncertain") && hex.confidence < 0.65)) {
        ctx.strokeStyle = check("raster-uncertain") && hex.confidence < 0.65 ? "#ffbf4b" : "#0b14213d";
        ctx.lineWidth = check("raster-uncertain") && hex.confidence < 0.65 ? 1.3 : 0.4; ctx.stroke();
      }
    });
  }
  function pickPreview(event) {
    if (applying || loading) return;
    if (!plan) return;
    var canvas = byId("raster-canvas"), rect = canvas.getBoundingClientRect();
    var x = ((event.clientX - rect.left) * canvas.width / rect.width - previewView.tx) / previewView.scale;
    var y = ((event.clientY - rect.top) * canvas.height / rect.height - previewView.ty) / previewView.scale;
    var item = plan.assignments.find(function (h) { return core.insideHex(x - h.imageX, y - h.imageY, h.imageRadius); });
    if (!item) return;
    var def = terrains.find(function (t) { return t.key === val("raster-paint"); });
    if (def) {
      item.terrain = def.key; if (plan.options.kind !== "height") item.height = core.heightOf(def);
      item.manual = true; overrides[item.q + "_" + item.r] = def.key;
      invalidateReview(); recount(); renderCounts(); draw(); scheduleSave();
    }
    byId("raster-cell-info").textContent = "格 " + item.q + "," + item.r + " · " + item.terrain + " · 相对高度 " + item.height.toFixed(3) + (item.manual ? " · 已修整" : "");
  }
  function currentPlan() { return plan && plan.signature === signature() && plan.sourceVersion === sourceVersion; }
  function review() {
    if (applying || loading || contextLoading) return;
    if (!currentPlan() || !plan.assignments.length) { invalidate("预览已过期，请重新识别。", false); return; }
    if (!isWritable()) { status("请在地图编辑的底图导入子工具中，选择当前分支末端节点后应用。", "warn"); return; }
    byId("raster-summary").textContent = "将在当前节点一次写入 " + plan.assignments.length + " 格，其中新增 " + plan.added + " 格、覆盖 " + plan.updated + " 格。图幅以外的地图、区域、城市和其他实体保持原样。";
    reviewed = true;
    byId("raster-confirmation").hidden = false; byId("raster-confirm").checked = false; byId("png-apply").disabled = true; stage("confirm");
    byId("raster-confirmation").scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
  function captureSubmission() {
    var state = app().getState();
    var frozen = Object.assign({}, plan, {
      assignments: plan.assignments.map(function (cell) { return Object.assign({}, cell); }),
      options: Object.assign({}, plan.options), overlay: Object.assign({}, plan.overlay)
    });
    return { plan: frozen, image: image, blob: blob, fileName: fileName, sourceVersion: sourceVersion,
      draftKey: draftKey, record: draftRecord(), parameters: parameters(),
      branch: state.branch, revision: state.revision, tick: state.tick, foreignContext: false };
  }
  function submissionMatchesCurrent(submission, result) {
    var state = app().getState(), ref = result && result.body && result.body.ref;
    var branch = ref && ref.branch !== undefined ? ref.branch : submission.branch;
    var revision = ref && ref.revision !== undefined ? ref.revision : submission.revision;
    return !submission.foreignContext && state.branch === branch && state.branch === submission.branch
      && Number(state.revision) === Number(revision) && state.tick === submission.tick
      && image === submission.image && sourceVersion === submission.sourceVersion && draftKey === submission.draftKey && isWritable();
  }
  async function apply() {
    if (applying || loading || contextLoading || !reviewed || !check("raster-confirm")) return;
    if (!isWritable() || !currentPlan()) { invalidate("目标节点或预览已变化，请重新识别确认。", false); return; }
    var submission = captureSubmission(), submitted = submission.plan;
    if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
    previewToken++; contextToken++; restoreVersion++;
    activeSubmission = submission; applying = true; lockWorkspace();
    status("正在原子应用底图，请等待完成；导入期间工作区已锁定。", "muted");
    try {
      var result = await app().writeCommand("map.ImportRaster", { mode: submitted.mode,
        cells: submitted.assignments.map(function (h) { return { q: h.q, r: h.r, terrain: h.terrain, height: h.height }; }) });
      if (!result || !result.ok) {
        if (submission.foreignContext || submission.branch !== app().getState().branch) invalidate("", false);
        status("导入未成功：" + (result && result.message || "未知错误") + "。请检查后重新识别。", "err"); invalidateReview(); return;
      }
      // 草稿始终写回此次确认的源和键，不能从响应到达时的工作区重新取值。
      persistDraft(submission.draftKey, submission.record, submission.sourceVersion);
      if (!submissionMatchesCurrent(submission, result)) {
        invalidate("", false);
        var renderer = window.SimosMapCore && window.SimosMapCore.active;
        if (renderer && renderer.setPngOverlay) renderer.setPngOverlay(null);
        status("底图已提交至确认时的节点；当前目标已变化，请重新打开工作区核对。", "warn");
        return;
      }
      completedPlan = submitted; plan = null; invalidateReview(); byId("raster-review").disabled = true;
      byId("raster-finished").hidden = false; stage("confirm"); setOverlay(submitted.overlay, submission.image);
      status("已原子导入 " + submitted.assignments.length + " 格，地图已写入当前节点。", "ok");
    } catch (e) { status("导入未成功：" + e.message, "err"); invalidateReview(); }
    finally {
      applying = false; activeSubmission = null;
      if (submission.branch !== app().getState().branch) { contextToken++; draftKey = ""; resetImage(); }
      unlockWorkspace();
    }
  }
  function setOverlay(overlay, sourceImage) {
    var renderer = window.SimosMapCore && window.SimosMapCore.active;
    if (renderer && renderer.setPngOverlay && (sourceImage || image) && overlay) renderer.setPngOverlay({ image: sourceImage || image, offsetX: overlay.offsetX, offsetY: overlay.offsetY, scale: overlay.scale, opacity: 0.35 });
  }
  function refine() {
    if (applying) return;
    var submitted = completedPlan;
    if (!submitted) return;
    closeWorkspace();
    window.SimosMapEditor.selectMapEditTool("terrain");
    var renderer = window.SimosMapCore && window.SimosMapCore.active;
    if (!renderer || !renderer.setView) return;
    var main = document.querySelector("#canvas-mount canvas") || byId("canvas");
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity, size = submitted.options.cell;
    submitted.assignments.forEach(function (h) { var p = window.SimosHexGeom.hexToPixel(h.q, h.r, size); minX = Math.min(minX, p.x - size); maxX = Math.max(maxX, p.x + size); minY = Math.min(minY, p.y - size); maxY = Math.max(maxY, p.y + size); });
    var width = main && main.clientWidth || 900, height = main && main.clientHeight || 600;
    var scale = Math.min(width / (maxX - minX + size * 2), height / (maxY - minY + size * 2));
    renderer.setView({ scale: Math.max(0.03, Math.min(12, scale)), tx: width / 2 - (minX + maxX) / 2 * scale, ty: height / 2 - (minY + maxY) / 2 * scale });
  }
  async function clear() {
    if (applying) return;
    if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
    var key = draftKey; resetImage();
    if (byId("png-file")) byId("png-file").value = "";
    var renderer = window.SimosMapCore && window.SimosMapCore.active;
    if (renderer && renderer.setPngOverlay) renderer.setPngOverlay(null);
    if (byId("png-preview")) byId("png-preview").textContent = "";
    if (byId("raster-statistics")) byId("raster-statistics").textContent = "";
    try { if (key) await draftStore.clear(key); note("本机草稿已清除。"); status("已清除底图和草稿；已写入的地图不受影响。", "ok"); }
    catch (e) { note("图片已清除，但本地草稿删除失败。"); }
  }
  function onToolChange(tool) {
    if (applying) return;
    if (tool !== "image-import") invalidateReview();
  }
  function bind() {
    if (bound || !byId("raster-open")) return; bound = true;
    byId("raster-open").addEventListener("click", openWorkspace);
    byId("raster-close").addEventListener("click", closeWorkspace);
    byId("raster-dialog").addEventListener("cancel", function (event) { if (applying) event.preventDefault(); });
    byId("png-file").addEventListener("change", async function () {
      if (applying) { byId("png-file").value = ""; return; }
      try { var file = byId("png-file").files[0]; if (file) await loadSource(file, file.name, false); } catch (e) { status(e.message, "err"); }
    });
    byId("png-example").addEventListener("click", loadExample);
    byId("png-sample").addEventListener("click", preview);
    byId("raster-review").addEventListener("click", review);
    byId("png-apply").addEventListener("click", apply);
    byId("raster-refine").addEventListener("click", refine);
    byId("png-clear").addEventListener("click", clear);
    byId("raster-confirm").addEventListener("change", function () { if (applying) { byId("raster-confirm").checked = true; return; } byId("png-apply").disabled = !reviewed || !check("raster-confirm") || !currentPlan() || !isWritable() || applying; });
    byId("raster-canvas").addEventListener("click", pickPreview);
    PARAMETERS.forEach(function (id) { var node = byId(id); if (node) node.addEventListener("input", function () {
      if (applying) { if (activeSubmission) putParameters(activeSubmission.parameters); return; }
      parameterVersion++; updateControls(); invalidate(loading ? "参数已更新，正在载入新图…" : "参数已变化，请重新识别。", true); refreshAvailability(); scheduleSave();
    }); });
    ["raster-view", "png-opacity", "raster-outline", "raster-uncertain"].forEach(function (id) { byId(id).addEventListener("input", function () { if (!applying) draw(); }); });
    var lastSignature = signature(), lastBranch = app().getState().branch;
    app().onStateChange(function () {
      var next = signature(), state = app().getState(), branch = state.branch;
      if (applying) {
        if (activeSubmission && (branch !== activeSubmission.branch || state.tick !== activeSubmission.tick)) activeSubmission.foreignContext = true;
        lastBranch = branch; lastSignature = next; return;
      }
      if (next !== lastSignature && !applying) invalidate(image ? "世界节点已变化，请重新识别。" : "", false);
      if (branch !== lastBranch) { contextToken++; draftKey = ""; resetImage(); lastBranch = branch; }
      lastSignature = next;
      if (!isWritable()) invalidateReview();
    });
    updateControls(); draw();
  }
  // ★ 2026-11-14 自由判图工具：只读导出当前底图 + 当前格网选择，不生成识别 plan、不写世界。
  //   共享参考图与“创建缺失格”都直接取这一份输入；格网生成/套用沿用本文件已有的 options 与 core 几何。
  function referenceInput() {
    if (!blob || !imageWidth || !imageHeight) throw new Error("先在底图导入工作区载入图片");
    var opts = readOptions();
    var grid = opts.grid === "generate"
      ? core.makeGrid(imageWidth, imageHeight, opts)
      : core.existingGrid(hexes, opts);
    if (!grid.length) throw new Error("当前格网没有可导出的格");
    return { blob: blob, fileName: fileName || "本地底图", width: imageWidth, height: imageHeight, grid: grid, options: opts };
  }
  function blobBase64(value) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var text = String(reader.result || "");
        var at = text.indexOf(",");
        resolve(at >= 0 ? text.slice(at + 1) : text);
      };
      reader.onerror = function () { reject(new Error("底图读取失败")); };
      reader.readAsDataURL(value);
    });
  }
  window.SimosMapPng = {
    parseHexColor: core && core.parseHexColor, nearestTerrain: core && core.nearestTerrain, buildPlan: core && core.buildPlan,
    readOptions: readOptions, preview: preview, apply: apply, clear: clear, open: openWorkspace, close: closeWorkspace, loadExample: loadExample,
    onToolChange: onToolChange, getPlan: function () { return plan; },
    getReferenceInput: referenceInput, blobBase64: blobBase64,
    debugState: function () { return { fileName: fileName, width: imageWidth, height: imageHeight, hasImage: !!image, plan: plan, completed: !!completedPlan, overrides: Object.assign({}, overrides), draftKey: draftKey, sourceVersion: sourceVersion, parameterVersion: parameterVersion, restoreVersion: restoreVersion, loading: loading, applying: applying }; }
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", bind); else bind();
})();
