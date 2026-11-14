// map-raster-draft.js —— 原图与参数的本地草稿；不保存可直接提交的旧计划。
(function () {
  "use strict";
  var opening;
  function database() {
    if (opening) return opening;
    opening = new Promise(function (resolve, reject) {
      if (!window.indexedDB) { reject(new Error("浏览器不支持本地草稿存储")); return; }
      var request = window.indexedDB.open("simos-map-raster-drafts", 1);
      request.onupgradeneeded = function () { request.result.createObjectStore("drafts"); };
      request.onsuccess = function () { resolve(request.result); };
      request.onerror = function () { reject(request.error || new Error("无法打开本地草稿")); };
      request.onblocked = function () { reject(new Error("本地草稿被其他页面占用")); };
    });
    return opening;
  }
  function transaction(key, mode, action, value) {
    return database().then(function (db) {
      return new Promise(function (resolve, reject) {
        var tx = db.transaction("drafts", mode);
        var store = tx.objectStore("drafts");
        var request = action === "put" ? store.put(value, key) : action === "delete" ? store.delete(key) : store.get(key);
        var result;
        request.onsuccess = function () { result = request.result; };
        tx.oncomplete = function () { resolve(result); };
        tx.onerror = function () { reject(tx.error || new Error("本地草稿操作失败")); };
        tx.onabort = function () { reject(tx.error || new Error("本地草稿操作中止")); };
      });
    });
  }
  window.SimosRasterDraft = {
    load: function (key) { return transaction(key, "readonly", "get"); },
    save: function (key, value) { return transaction(key, "readwrite", "put", value); },
    clear: function (key) { return transaction(key, "readwrite", "delete"); }
  };
})();
