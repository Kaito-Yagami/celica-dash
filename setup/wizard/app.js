/* The RUN ME wizard: pages out of the templates, answers saved as they change, jobs polled. */
(function () {
  "use strict";
  var STEPS = [
    ["welcome", "Welcome"], ["car", "Your car"], ["region", "Where you drive"], ["look", "The look"], ["phone", "Your phone"],
    ["maps", "Offline maps"], ["music", "Music"], ["install", "Install"], ["boot", "Boot splash"], ["done", "All set"],
  ];
  var state = null, regions = null, page = 0, answers = {}, saveTimer = null, pollTimer = null, regionQuery = "";
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  function api(path, opts) { return fetch("/api/" + path, opts).then(function (r) { return r.json(); }); }
  function get(obj, dotted) { return dotted.split(".").reduce(function (o, k) { return o == null ? undefined : o[k]; }, obj); }
  function set(obj, dotted, v) { var ks = dotted.split("."); var o = obj; ks.slice(0, -1).forEach(function (k) { o = o[k] = o[k] || {}; }); o[ks[ks.length - 1]] = v; }
  function fmt(n) { return n >= 1e9 ? (n / 1e9).toFixed(1) + " GB" : n >= 1e6 ? Math.round(n / 1e6) + " MB" : Math.round(n / 1e3) + " KB"; }

  // ---- answers: every field writes straight through, debounced ----
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () { api("answers", { method: "POST", body: JSON.stringify(answers) }); }, 250);
  }
  function bindFields(root) {
    $$("[data-field]", root).forEach(function (el) {
      var f = el.getAttribute("data-field");
      if (el.classList.contains("choices")) {
        var cur = get(answers, f);
        $$("button", el).forEach(function (b) {
          b.classList.toggle("on", b.getAttribute("data-value") === cur);
          b.onclick = function () {
            set(answers, f, b.getAttribute("data-value"));
            $$("button", el).forEach(function (x) { x.classList.toggle("on", x === b); });
            onChange(f); save();
          };
        });
      } else if (el.classList.contains("swatches")) {
        var cur2 = (get(answers, f) || "").toUpperCase();
        el.innerHTML = "";
        el.getAttribute("data-swatches").split(",").forEach(function (pair) {
          var hex = pair.split(":")[0], name = pair.split(":")[1];
          var d = document.createElement("div");
          d.innerHTML = "<i style='background:#" + hex + "'></i>" + name;
          d.classList.toggle("on", hex.toUpperCase() === cur2);
          d.onclick = function () {
            set(answers, f, hex);
            $$("div", el).forEach(function (x) { x.classList.toggle("on", x === d); });
            $$("input[data-field='" + f + "']", root).forEach(function (i) { i.value = hex; });
            onChange(f); save();
          };
          el.appendChild(d);
        });
      } else if (el.type === "checkbox") {
        el.checked = !!get(answers, f);
        el.onchange = function () { set(answers, f, el.checked); onChange(f); save(); };
      } else {
        var v = get(answers, f);
        el.value = v == null ? "" : v;
        el.oninput = function () {
          var val = el.value;
          if (el.type === "number") val = val === "" ? null : Number(val);
          if (el.maxLength === 7) val = val.replace("#", "").toUpperCase();
          set(answers, f, val);
          onChange(f); save();
        };
      }
    });
  }
  function onChange(f) {
    if (f === "car.preset") applyPreset();
    if (f.indexOf("look.") === 0) preview();
    if (f === "phone.mode") { var t = $("#tip-gps"); if (t) t.style.display = answers.phone && answers.phone.mode === "carplay" ? "" : "none"; }
    if (f === "car.noLift") { var l = $("#lift-field"); if (l) l.style.opacity = answers.car && answers.car.noLift ? .35 : 1; }
  }

  // ---- the car presets: the same defaults the app has ----
  var PRESETS = {
    celica_2zz: { name: "CELICA *T*-SPORT", alt: "セリカ", code: "ZZT231", sub: "2ZZ-GE · VVTL-i", note: "190 PS · 8,200 RPM / C60 · 6-SPEED", redline: 8200, lift: 6000, noLift: false, gears: "3.166, 1.904, 1.392, 1.031, 0.815, 0.725", final: 4.529, circumference: 1.921 },
    celica_1zz: { name: "CELICA", alt: "セリカ", code: "ZZT230", sub: "1ZZ-FE · VVT-i", note: "140 PS · 6,400 RPM / C59 · 5-SPEED", redline: 6400, lift: null, noLift: true, gears: "3.166, 1.904, 1.310, 0.969, 0.815", final: 4.312, circumference: 1.932 },
    other: { name: "DASH", alt: "", code: "", sub: "", note: "", redline: 7000, lift: null, noLift: true, gears: "", final: 4.0, circumference: 1.95 },
  };
  function applyPreset() {
    var p = PRESETS[answers.car.preset]; if (!p) return;
    var model = answers.car.model || "stock";
    answers.car = Object.assign({ preset: answers.car.preset, model: model }, p);
    render();
  }
  function preview() {
    var l = answers.look || {};
    var pv = $("#preview"); if (!pv) return;
    var accent = /^[0-9A-F]{6}$/i.test(l.accent || "") ? "#" + l.accent : (l.look === "contrast" ? "#E5372E" : "#D8433C");
    var bg = /^[0-9A-F]{6}$/i.test(l.background || "") ? "#" + l.background : (l.look === "contrast" ? "#000" : "#131216");
    var light = luminance(bg) > 0.5;
    pv.style.background = bg; pv.style.color = light ? "#15131A" : "#F1EDEA";
    $(".pv-mark i", pv).style.color = accent; $(".pv-live", pv).style.color = accent;
    $(".pv-data", pv).style.color = light ? "#3E4046" : "#C7CACF";
  }
  function luminance(hex) { var n = parseInt(hex.slice(1), 16); if (hex.length === 4) return 0; var r = (n >> 16) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255; return 0.2126 * r + 0.7152 * g + 0.0722 * b; }

  // ---- the region picker ----
  function regionPage(root) {
    var q = $("#region-q", root), list = $("#region-list", root), pick = $("#region-pick", root);
    function show() {
      var t = (q.value || "").trim().toLowerCase();
      var rows = regions.filter(function (r) { return !t ? r.depth <= 1 : r.name.toLowerCase().indexOf(t) >= 0 || r.id.indexOf(t) >= 0; }).slice(0, 60);
      list.innerHTML = rows.length ? "" : "<div class='none'>nothing by that name</div>";
      rows.forEach(function (r) {
        var d = document.createElement("div");
        d.innerHTML = "<span>" + r.name + (r.depth > 1 ? " <small>in " + (r.parent || "") + "</small>" : "") + "</span><small>" + (r.wide ? "too wide - pick a part" : r.units.toUpperCase()) + "</small>";
        d.classList.toggle("on", answers.region && answers.region.id === r.id);
        d.onclick = function () { choose(r); };
        list.appendChild(d);
      });
    }
    function choose(r) {
      answers.region = Object.assign(answers.region || {}, { id: r.id, name: r.name, units: r.units, locale: r.locale });
      save(); render();
      api("region/size?id=" + encodeURIComponent(r.id)).then(function (j) { var est = j.pbfBytes > 0 ? "map data " + fmt(j.pbfBytes) : ""; var e = $("#region-pick small.est"); if (e) e.textContent = est; });
    }
    q.value = regionQuery;
    q.oninput = function () { regionQuery = q.value; show(); };
    show();
    var r = answers.region && regions.filter(function (x) { return x.id === answers.region.id; })[0];
    pick.innerHTML = r
      ? "<div class='kv'><span>Chosen</span><b>" + r.name + "</b></div><div class='kv'><span>Box</span><b>" + r.bbox.join(", ") + "</b></div><div class='kv'><span>OpenStreetMap extract</span><b><small class='est'>…</small></b></div>" + (r.wide ? "<div class='kv'><span></span><b style='color:var(--red)'>This one wraps the world; pick a state or sub-region for the map</b></div>" : "")
      : "<div class='kv'><span>Nothing chosen yet</span><b>type above, then tap a row</b></div>";
    if (r) api("region/size?id=" + encodeURIComponent(r.id)).then(function (j) { var e = $("small.est", pick); if (e) e.textContent = j.pbfBytes > 0 ? fmt(j.pbfBytes) : "size unknown"; });
  }

  // ---- jobs ----
  function jobRows(root) {
    $$(".job", root).forEach(function (box) {
      var name = box.getAttribute("data-job");
      var btn = $("[data-run]", box);
      if (btn) btn.onclick = function () { if (btn.classList.contains("busy")) { api("stop/" + name, { method: "POST" }); return; } api("run/" + name, { method: "POST" }).then(poll); };
      if (!$(".showlog", box)) {
        var s = document.createElement("span"); s.className = "showlog"; s.textContent = "show log";
        var log = document.createElement("pre"); log.className = "log";
        s.onclick = function () { log.classList.toggle("show"); if (log.classList.contains("show")) api("log/" + name).then(function (j) { log.textContent = j.lines.join("\n"); log.scrollTop = 1e6; }); };
        box.appendChild(s); box.appendChild(log);
      }
    });
  }
  function paintJobs() {
    if (!state) return;
    $$(".job").forEach(function (box) {
      var name = box.getAttribute("data-job"), j = state.jobs[name] || { status: "idle" };
      var btn = $("[data-run]", box), bar = $(".bar", box), msg = $(".msg", box), log = $(".log", box);
      var running = j.status === "running";
      if (btn) {
        btn.classList.toggle("busy", running); btn.classList.toggle("ok", j.status === "done");
        btn.textContent = running ? "Stop" : j.status === "done" ? "Done · again" : j.status === "failed" ? "Try again" : btn.getAttribute("data-label") || btn.textContent;
        if (!btn.getAttribute("data-label")) btn.setAttribute("data-label", btn.textContent);
      }
      if (bar) { bar.classList.toggle("pulse", running && j.progress == null); $("i", bar).style.width = running ? ((j.progress || 0) * 100) + "%" : j.status === "done" ? "100%" : "0"; }
      if (msg) { msg.textContent = j.status === "failed" ? (j.error || "failed") : (j.message || ""); msg.className = "msg" + (j.status === "failed" ? " err" : j.status === "done" ? " ok" : ""); }
      if (log && log.classList.contains("show") && running) api("log/" + name).then(function (x) { log.textContent = x.lines.join("\n"); log.scrollTop = 1e6; });
    });
    var mc = $("#music-count"); if (mc) mc.textContent = state.musicIn + " files dropped" + (state.payload.filter(function (i) { return i.kind === "music"; }).map(function (i) { return " · packed: " + i.count + " songs"; })[0] || "");
    var mr = $("#maps-region"); if (mr) mr.textContent = state.region ? state.region.name : "your region (pick one on the previous page)";
    var bu = $("#boot-unit"); if (bu) bu.textContent = state.unit ? (state.unit.model + (state.unit.root ? " · root" : " · no root") + (state.unit.qf ? "" : " · not QF")) : "no unit connected - see Install";
    $$("#steps a").forEach(function (a, i) { var k = STEPS[i][0]; var done = { maps: ["tiles", "routing", "search"], music: ["music"], boot: ["boot"] }[k]; a.querySelector(".st").textContent = done && done.some(function (n) { return (state.jobs[n] || {}).status === "done"; }) ? "✓" : ""; });
    var rf = $("#railfoot"); rf.innerHTML = (state.ips[0] ? "PC " + state.ips[0] + ":" + state.port + "<br>" : "") + "RAM " + state.ramGb + " GB · disk " + state.diskFreeGb + " GB free" + (state.hasJar ? "" : "<br><span style='color:var(--red)'>dash-import.jar missing</span>");
  }

  // ---- music upload: one PUT per file, straight to disk ----
  function musicPage(root) {
    var drop = $("#drop", root), msg = $("#upload-msg", root), bar = $("i", $(".bar", drop));
    function sendAll(files) {
      var list = Array.prototype.slice.call(files).filter(function (f) { return /\.(mp3|m4a|flac|ogg|wav|aac|zip)$/i.test(f.name); });
      if (!list.length) { msg.textContent = "nothing playable in that"; return; }
      var i = 0, total = list.reduce(function (s, f) { return s + f.size; }, 0), done = 0;
      (function next() {
        if (i >= list.length) { msg.textContent = list.length + " files dropped - now Pack them"; bar.style.width = "100%"; poll(); return; }
        var f = list[i++];
        msg.textContent = "sending " + f.name + " (" + (i) + " of " + list.length + ")";
        var x = new XMLHttpRequest();
        x.open("PUT", "/api/upload?name=" + encodeURIComponent(f.name));
        x.upload.onprogress = function (e) { bar.style.width = ((done + e.loaded) / total * 100) + "%"; };
        x.onload = function () { done += f.size; next(); };
        x.onerror = function () { msg.textContent = "failed on " + f.name; };
        x.send(f);
      })();
    }
    ["dragenter", "dragover"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add("over"); }); });
    ["dragleave", "drop"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove("over"); }); });
    drop.addEventListener("drop", function (e) { sendAll(e.dataTransfer.files); });
    $("#music-files", root).onchange = function (e) { sendAll(e.target.files); };
    $("#music-folder", root).onchange = function (e) { sendAll(e.target.files); };
    $("#music-clear", root).onclick = function () { api("music/clear", { method: "POST" }).then(poll); };
  }

  // ---- install page ----
  function installPage(root) {
    var ip = state && state.ips[0];
    var url = "http://" + (ip || "…") + ":" + (state ? state.port : 8787) + "/payload/celicadash.apk";
    $("#apk-url", root).textContent = url;
    if (window.QRCode && ip) { $("#qr", root).innerHTML = ""; new QRCode($("#qr", root), { text: url, width: 132, height: 132, correctLevel: QRCode.CorrectLevel.M }); }
    if (state && !state.payload.some(function (i) { return i.kind === "apk"; })) api("run/apk", { method: "POST" });
    var unit = null;
    $("#adb-try", root).onclick = function () {
      $("#adb-msg", root).textContent = "trying " + $("#adb-ip", root).value + " (fetches adb first if needed)…";
      api("adb/scan?ip=" + encodeURIComponent($("#adb-ip", root).value)).then(function (j) {
        unit = j.unit;
        $("#adb-msg", root).textContent = unit ? "connected: " + unit.model + " · Android " + unit.android + (unit.root ? " · root (the QF door)" : "") + (unit.qf ? " · QF firmware" : "") : (j.error || "no answer on 9876 or 5555 - use the browser way above");
        $("#adb-install", root).hidden = !unit;
      });
    };
    $("#adb-install", root).onclick = function () {
      $("#adb-install-msg", root).textContent = "installing… (a minute)";
      api("adb/install", { method: "POST", body: JSON.stringify({ serial: unit.serial, root: unit.root }) }).then(function (j) {
        $("#adb-install-msg", root).textContent = j.ok ? "installed and opened - carry on with Setup on the unit" : "failed: " + j.out;
      });
    };
  }

  // ---- render ----
  function render() {
    var k = STEPS[page][0];
    var root = $("#page");
    root.innerHTML = "";
    root.appendChild(document.importNode($("#tpl-" + k).content, true));
    var nav = document.createElement("div"); nav.className = "nav";
    nav.innerHTML = (page > 0 ? "<button class='back'>Back</button>" : "") + "<span class='spacer'></span>" + (page < STEPS.length - 1 ? "<button class='next'>" + (page === 0 ? "Begin" : "Next") + "</button>" : "");
    root.appendChild(nav);
    var b = $(".back", nav); if (b) b.onclick = function () { page--; render(); };
    var n = $(".next", nav); if (n) n.onclick = function () { page++; render(); };
    bindFields(root); jobRows(root);
    if (k === "welcome" && state) { $("#w-ips").textContent = state.ips.join("  ·  ") || "not on a network"; $("#w-mem").textContent = state.ramGb + " GB · " + state.diskFreeGb + " GB"; $("#w-apk").textContent = state.apk || "none found - app/ folder empty?"; }
    if (k === "region") regionPage(root);
    if (k === "look") preview();
    if (k === "phone") onChange("phone.mode");
    if (k === "car") onChange("car.noLift");
    if (k === "music") musicPage(root);
    if (k === "install") installPage(root);
    if (k === "done" && state) $("#done-list").innerHTML = state.payload.map(function (i) { return "<div class='kv'><span>" + i.label + "</span><b>" + fmt(i.bytes) + "</b></div>"; }).join("") || "<div class='kv'><span>Nothing made yet</span><b>the unit can still be set up by hand</b></div>";
    $$("#steps a").forEach(function (a, i) { a.classList.toggle("on", i === page); a.classList.toggle("done", i < page); });
    paintJobs();
    window.scrollTo(0, 0);
  }
  function poll() {
    api("state").then(function (s) { state = s; if (!answers.car) { answers = s.answers || {}; if (!answers.car) { answers = { car: Object.assign({ preset: "celica_2zz", model: "stock" }, PRESETS.celica_2zz), look: { look: "ink" }, phone: { mode: "carplay" }, region: {}, music: { folder: "Celica" } }; save(); } render(); } paintJobs(); });
  }

  // ---- go ----
  $("#steps").innerHTML = STEPS.map(function (s) { return "<a href='#'><i></i>" + s[1] + "<span class='st'></span></a>"; }).join("");
  $$("#steps a").forEach(function (a, i) { a.onclick = function (e) { e.preventDefault(); page = i; render(); }; });
  api("regions").then(function (j) { regions = j.regions; poll(); pollTimer = setInterval(poll, 2000); });
})();
