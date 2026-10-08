/* Онлайн-редактор глаз: слои, палитра, все поля формата, живой пример и проверка как в плагине. */
(function () {
  'use strict';

  var PE = window.PixelEyes;
  var S = window.PESite;
  var DRAFT = 'pe-editor-draft';
  var LAYERS = [['eye', 'Глаз'], ['iris', 'Радужка'], ['highlight', 'Блик']];
  var FRAME_LABELS = {
    closed: 'Сон', happy: 'Радость', love: 'Любовь', dizzy: 'Головокружение', angry: 'Злость', sad: 'Грусть',
    ouch: 'Ай', surprised: 'Удивление'
  };
  var FRAME_EMO = {closed: 'asleep', happy: 'happy', love: 'love', dizzy: 'dizzy', angry: 'angry', sad: 'sad',
    ouch: 'ouch', surprised: 'surprised'};
  var CHARS = 'abcdefgijklmnoqrtuvxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@$%&*+=?!~^';
  var PRESETS = ['#000000', '#1b1b22', '#3b2a4a', '#5f574f', '#8a8a99', '#c2c3c7', '#dfe3ee', '#ffffff',
    '#ff004d', '#c4223a', '#7e2553', '#ff77a8', '#ffccaa', '#f2c9b0', '#ab5236', '#ffa300', '#ffec27',
    '#e8e070', '#00e436', '#4caf3a', '#008751', '#1d2b53', '#065ab5', '#3d7bff', '#29adff', '#83769c',
    '#7a45d6', '#b88cff', '#e2ccff', '#22f0ff', '#d8ffe6', '#ff4f7b'];
  var TAGS = ['милые', 'жуткие', 'забавные', 'животные', 'техника', 'аниме', 'классика', 'яркие', 'космос'];
  var EMOS = [['happy', '😊'], ['love', '😍'], ['surprised', '😮'], ['angry', '😠'], ['sad', '😢'],
    ['dizzy', '😵'], ['ouch', '><'], ['wink', '😉'], ['suspicious', '🤨'], ['stare', '👀'], ['curious', '🤔'],
    ['sleepy', '🥱'], ['asleep', '😴'], ['scared', '😱']];

  var st;
  var layer = 'eye';
  var tool = 'brush';
  var cur = 0;
  var mirror = false;
  var undo = [];
  var redo = [];
  var eyes;
  var lastJson = null;
  var lastObj = null;
  var jsonEditing = false;
  var bg = 'dark';
  var els = {};

  // ---- помощники -------------------------------------------------------------

  function h(tag, attrs, kids) {
    var e = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (k === 'class') {
          e.className = v;
        } else if (k === 'text') {
          e.textContent = v;
        } else if (k === 'html') {
          e.innerHTML = v;
        } else if (k.indexOf('on') === 0) {
          e.addEventListener(k.substring(2), v);
        } else if (v === true) {
          e.setAttribute(k, '');
        } else if (v !== false && v !== null && v !== undefined) {
          e.setAttribute(k, v);
        }
      });
    }
    (kids || []).forEach(function (k) {
      if (k !== null && k !== undefined) {
        e.appendChild(typeof k === 'string' ? document.createTextNode(k) : k);
      }
    });
    return e;
  }

  function grid(w, h0, fill) {
    var rows = [];
    for (var y = 0; y < h0; y++) {
      rows.push(new Array(w).fill(fill || '.'));
    }
    return {w: w, h: h0, rows: rows};
  }

  function gridFrom(rowsIn) {
    if (rowsIn === undefined || rowsIn === null) {
      return null;
    }
    var list = typeof rowsIn === 'string' ? rowsIn.split('\n') : rowsIn;
    if (!Array.isArray(list) || !list.length) {
      return null;
    }
    list = list.map(function (r) {
      return typeof r === 'string' ? r : '';
    });
    var w = Math.max.apply(null, list.map(function (r) {
      return r.length;
    }).concat([1]));
    var g = grid(Math.min(32, w), Math.min(32, list.length));
    for (var y = 0; y < g.h; y++) {
      for (var x = 0; x < g.w; x++) {
        var c = list[y][x];
        g.rows[y][x] = !c || c === ' ' ? '.' : c;
      }
    }
    return g;
  }

  function gridRows(g) {
    return g.rows.map(function (r) {
      return r.join('');
    });
  }

  function cloneGrid(g) {
    return g ? {w: g.w, h: g.h, rows: g.rows.map(function (r) {
      return r.slice();
    })} : null;
  }

  function hex2(n) {
    return ('0' + n.toString(16)).slice(-2);
  }

  /** Цвет в {rgb: '#rrggbb', a: 0..255} или null. */
  function splitColor(v) {
    try {
      var c = PE.parseColor(v, 'цвет');
      return {rgb: '#' + hex2((c >>> 16) & 255) + hex2((c >>> 8) & 255) + hex2(c & 255), a: c >>> 24};
    } catch (e) {
      return null;
    }
  }

  function joinColor(rgb, a) {
    return a >= 255 ? rgb.toLowerCase() : '#' + hex2(a) + rgb.substring(1).toLowerCase();
  }

  function cssColor(v) {
    var c = splitColor(v);
    if (!c) {
      return 'transparent';
    }
    var n = parseInt(c.rgb.substring(1), 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + (c.a / 255).toFixed(3) + ')';
  }

  function palIndex(ch) {
    for (var i = 0; i < st.pal.length; i++) {
      if (st.pal[i].ch === ch) {
        return i;
      }
    }
    return -1;
  }

  function freeChar() {
    for (var i = 0; i < CHARS.length; i++) {
      if (palIndex(CHARS[i]) < 0) {
        return CHARS[i];
      }
    }
    return null;
  }

  function currentGrid() {
    if (layer === 'eye' || layer === 'iris' || layer === 'highlight') {
      return st[layer];
    }
    return st.frames[layer.substring(6)] || null;
  }

  function setCurrentGrid(g) {
    if (layer === 'eye' || layer === 'iris' || layer === 'highlight') {
      st[layer] = g;
    } else {
      st.frames[layer.substring(6)] = g;
    }
  }

  // ---- состояние <-> JSON -------------------------------------------------------

  function defaults() {
    return {
      meta: {id: 'my_eyes', name: 'Мои глаза', author: '', description: '', tags: []},
      pal: [], sclera: '', pupil: '', eye: grid(10, 8), iris: null, highlight: null, frames: {},
      irisOffset: [0, 0], hlOffset: [0, 0], hlFixed: false, travelAuto: true, travel: [2, 1],
      lid: {mode: 'auto', col: '#f2c9b0'}, lash: {mode: 'auto', col: '#1b1b22'},
      layout: {mode: 'two', gap: 2, mirror: true, eyes: [{x: 0, y: 0, mirror: false, scale: 1}]},
      beh: {never: false, blink: [2.5, 6], style: 'smooth', speed: 1, stare: 0, twitch: 0, open: 1, slant: 0,
        independent: false, creepy: false},
      extra: {}
    };
  }

  var KNOWN = ['format', 'id', 'name', 'author', 'description', 'tags', 'palette', 'eye', 'sclera', 'iris',
    'iris_offset', 'pupil', 'highlight', 'highlight_offset', 'highlight_fixed', 'travel', 'lid', 'lash', 'layout',
    'frames', 'behavior'];

  function num(v, d) {
    return typeof v === 'number' && isFinite(v) ? v : d;
  }

  function pairOf(v, d) {
    return Array.isArray(v) && v.length === 2 ? [num(v[0], d[0]), num(v[1], d[1])] : d.slice();
  }

  function fromObj(o) {
    var s = defaults();
    if (!o || typeof o !== 'object' || Array.isArray(o)) {
      throw new Error('ожидался объект { ... }');
    }
    s.meta.id = typeof o.id === 'string' ? o.id : 'my_eyes';
    s.meta.name = typeof o.name === 'string' ? o.name : s.meta.id;
    s.meta.author = typeof o.author === 'string' ? o.author : '';
    s.meta.description = typeof o.description === 'string' ? o.description : '';
    s.meta.tags = Array.isArray(o.tags) ? o.tags.filter(function (t) {
      return typeof t === 'string';
    }) : [];
    var pal = o.palette && typeof o.palette === 'object' && !Array.isArray(o.palette) ? o.palette : {};
    Object.keys(pal).forEach(function (k) {
      if (k.length === 1 && k !== '.' && k !== ' ') {
        var c = typeof pal[k] === 'string' ? splitColor(pal[k]) : null;
        s.pal.push({ch: k, col: c ? joinColor(c.rgb, c.a) : String(pal[k])});
      }
    });
    s.eye = gridFrom(o.eye) || grid(10, 8);
    s.sclera = typeof o.sclera === 'string' ? o.sclera : 'w';
    s.iris = gridFrom(o.iris);
    s.pupil = typeof o.pupil === 'string' ? o.pupil : '';
    s.highlight = gridFrom(o.highlight);
    s.irisOffset = pairOf(o.iris_offset, [0, 0]);
    s.hlOffset = pairOf(o.highlight_offset, [0, 0]);
    s.hlFixed = o.highlight_fixed === true;
    if (Array.isArray(o.travel) && o.travel.length === 2) {
      s.travelAuto = false;
      s.travel = pairOf(o.travel, [2, 1]);
    }
    s.lid = colorRef(o.lid, pal, 'auto');
    s.lash = colorRef(o.lash, pal, 'auto');
    var lay = o.layout && typeof o.layout === 'object' ? o.layout : null;
    if (lay && Array.isArray(lay.eyes)) {
      s.layout.mode = 'custom';
      s.layout.eyes = lay.eyes.filter(function (e) {
        return e && typeof e === 'object';
      }).map(function (e) {
        return {x: num(e.x, 0), y: num(e.y, 0), mirror: e.mirror === true, scale: num(e.scale, 1)};
      });
    } else if (lay) {
      s.layout.mode = num(lay.count, 2) === 1 ? 'one' : 'two';
      s.layout.gap = num(lay.gap, 2);
      s.layout.mirror = lay.mirror !== false;
    }
    if (o.frames && typeof o.frames === 'object') {
      Object.keys(o.frames).forEach(function (k) {
        var g = gridFrom(o.frames[k]);
        if (g) {
          s.frames[k] = g;
        }
      });
    }
    var b = o.behavior && typeof o.behavior === 'object' ? o.behavior : {};
    if (Array.isArray(b.blink) && b.blink.length === 2) {
      s.beh.blink = [num(b.blink[0], 2.5), num(b.blink[1], 6)];
    } else if (typeof b.blink === 'number') {
      s.beh.blink = [Math.round(b.blink * 6) / 10, Math.round(b.blink * 14) / 10];
    }
    s.beh.never = s.beh.blink[1] <= 0;
    if (s.beh.never) {
      s.beh.blink = [2.5, 6];
    }
    ['style', 'speed', 'stare', 'twitch', 'open', 'slant', 'independent', 'creepy'].forEach(function (k) {
      if (b[k] !== undefined) {
        s.beh[k] = b[k];
      }
    });
    Object.keys(o).forEach(function (k) {
      if (KNOWN.indexOf(k) < 0) {
        s.extra[k] = o[k];
      }
    });
    return s;
  }

  function colorRef(v, pal, d) {
    if (typeof v !== 'string' || !v) {
      return {mode: d, col: '#1b1b22'};
    }
    if (v.toLowerCase() === 'none' || v.toLowerCase() === 'transparent') {
      return {mode: 'none', col: '#1b1b22'};
    }
    var raw = v.length === 1 && typeof pal[v] === 'string' ? pal[v] : v;
    var c = splitColor(raw);
    return {mode: 'color', col: c ? joinColor(c.rgb, c.a) : raw};
  }

  function toObj() {
    var o = {format: 1};
    var m = st.meta;
    o.id = m.id;
    o.name = m.name || 'Мои глаза';
    o.author = m.author;
    if (m.description) {
      o.description = m.description;
    }
    if (m.tags.length) {
      o.tags = m.tags.slice();
    }
    o.palette = {};
    st.pal.forEach(function (p) {
      o.palette[p.ch] = p.col;
    });
    o.eye = gridRows(st.eye);
    o.sclera = st.sclera;
    if (st.iris) {
      o.iris = gridRows(st.iris);
    }
    if (st.irisOffset[0] || st.irisOffset[1]) {
      o.iris_offset = st.irisOffset.slice();
    }
    if (st.pupil) {
      o.pupil = st.pupil;
    }
    if (st.highlight) {
      o.highlight = gridRows(st.highlight);
      if (st.hlOffset[0] || st.hlOffset[1]) {
        o.highlight_offset = st.hlOffset.slice();
      }
      if (st.hlFixed) {
        o.highlight_fixed = true;
      }
    }
    if (!st.travelAuto) {
      o.travel = st.travel.slice();
    }
    if (st.lid.mode === 'color') {
      o.lid = st.lid.col;
    } else if (st.lid.mode === 'none') {
      o.lid = 'none';
    }
    if (st.lash.mode === 'color') {
      o.lash = st.lash.col;
    } else if (st.lash.mode === 'none') {
      o.lash = 'none';
    }
    var L = st.layout;
    if (L.mode === 'custom') {
      o.layout = {eyes: L.eyes.map(function (e) {
        var r = {x: e.x, y: e.y};
        if (e.mirror) {
          r.mirror = true;
        }
        if (e.scale > 1) {
          r.scale = e.scale;
        }
        return r;
      })};
    } else if (L.mode === 'one') {
      o.layout = {count: 1};
    } else {
      o.layout = {count: 2, gap: L.gap, mirror: L.mirror};
    }
    var fr = {};
    PE.FRAME_NAMES.forEach(function (k) {
      if (st.frames[k]) {
        fr[k] = gridRows(st.frames[k]);
      }
    });
    if (Object.keys(fr).length) {
      o.frames = fr;
    }
    var b = st.beh;
    var beh = {blink: b.never ? [0, 0] : [b.blink[0], Math.max(b.blink[0], b.blink[1])], style: b.style};
    if (b.speed !== 1) {
      beh.speed = b.speed;
    }
    if (b.stare) {
      beh.stare = b.stare;
    }
    if (b.twitch) {
      beh.twitch = b.twitch;
    }
    if (b.open !== 1) {
      beh.open = b.open;
    }
    if (b.slant) {
      beh.slant = b.slant;
    }
    if (b.independent) {
      beh.independent = true;
    }
    if (b.creepy) {
      beh.creepy = true;
    }
    o.behavior = beh;
    Object.keys(st.extra).forEach(function (k) {
      o[k] = st.extra[k];
    });
    return o;
  }

  // ---- история -----------------------------------------------------------------

  function snapshot() {
    return JSON.stringify(toObj());
  }

  function remember() {
    undo.push(snapshot());
    if (undo.length > 80) {
      undo.shift();
    }
    redo = [];
    syncUndo();
  }

  function restore(text) {
    var keepLayer = layer;
    st = fromObj(JSON.parse(text));
    layer = keepLayer;
    if (!currentGrid() && layer !== 'eye' && layer.indexOf('frame:') !== 0) {
      layer = 'eye';
    }
    cur = Math.min(cur, Math.max(0, st.pal.length - 1));
    renderAll();
  }

  function syncUndo() {
    if (els.undo) {
      els.undo.disabled = !undo.length;
      els.redo.disabled = !redo.length;
    }
  }

  // ---- изменения -----------------------------------------------------------------

  var changeTimer = 0;

  /** После любой правки: пример, проверка, JSON, черновик. */
  function changed(full) {
    if (full) {
      renderSide();
    }
    drawGrid();
    refreshPalette();
    validate();
    clearTimeout(changeTimer);
    changeTimer = setTimeout(function () {
      S.store(DRAFT, snapshot());
    }, 400);
  }

  function validate() {
    var o = toObj();
    lastObj = o;
    var text = PE.writeJson(o);
    lastJson = text;
    if (!jsonEditing) {
      els.json.value = text;
    }
    els.size.textContent = text.length + ' символов' + (text.length > 4096 ? ', в сообщение не влезет, отправляйте файлом' : '');
    var box = els.status;
    box.innerHTML = '';
    if (!st.sclera) {
      box.appendChild(h('div', {class: 'note bad', html: '<b>Отметьте цвета яблока.</b> Выберите в палитре цвет, ' +
        'из которого сделано глазное яблоко, и включите "Яблоко". По нему ходит радужка, его закрывают веки.'}));
      return;
    }
    var skin;
    try {
      skin = PE.parseSkin(JSON.parse(JSON.stringify(o)));
    } catch (e) {
      box.appendChild(h('div', {class: 'note bad', html: '<b>Плагин не примет:</b> ' + S.esc(e.message)}));
      return;
    }
    eyes.setSkin(skin);
    var w = PE.warnings(skin, o).filter(function (x) {
      return x.indexOf('"author"') < 0;
    });
    if (!o.author) {
      w.push('автор пустой: плагин подставит ваш @username при сохранении');
    }
    if (S.BUILTIN.indexOf(PE.cleanId(o.id)) >= 0) {
      w.push('id "' + o.id + '" занят встроенными глазами: плагин сохранит их как "' + PE.cleanId(o.id) + '_2"');
    }
    if (st.pupil && st.iris && !st.iris.rows.some(function (r) {
      return r.indexOf(st.pupil) >= 0;
    })) {
      w.push('цвета зрачка "' + st.pupil + '" нет в радужке: зрачок не будет меняться');
    }
    box.appendChild(h('div', {class: 'note', style: 'border-color:var(--ok)', html: '<b>✓ Плагин примет эти глаза.</b> ' +
      skin.eye.w + '×' + skin.eye.h + ' пикселей, глаз: ' + skin.places.length + ', ход радужки ' + skin.travelX +
      '×' + skin.travelY + (st.travelAuto ? ' (сам)' : '')}));
    if (w.length) {
      box.appendChild(h('div', {class: 'note warn', html: '<b>Подсказки:</b><ul style="margin:6px 0 0">' +
        w.map(function (x) {
          return '<li>' + S.esc(x) + '</li>';
        }).join('') + '</ul>'}));
    }
    if (els.travelAuto && st.travelAuto) {
      els.travelX.value = skin.travelX;
      els.travelY.value = skin.travelY;
    }
  }

  // ---- холст ---------------------------------------------------------------------

  var cell = 20;

  function drawGrid() {
    var g = currentGrid();
    var cv = els.canvas;
    var holder = els.canvasHolder;
    if (!g) {
      cv.style.display = 'none';
      els.emptyLayer.style.display = '';
      return;
    }
    cv.style.display = '';
    els.emptyLayer.style.display = 'none';
    var avail = Math.max(160, holder.clientWidth - 4);
    cell = Math.max(7, Math.min(34, Math.floor(avail / g.w)));
    var dpr = window.devicePixelRatio || 1;
    var W = g.w * cell;
    var H = g.h * cell;
    cv.width = Math.round(W * dpr);
    cv.height = Math.round(H * dpr);
    cv.style.width = W + 'px';
    cv.style.height = H + 'px';
    var c = cv.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    var dark = S.isDark();
    // средне-серая шахматка: на ней видно и чёрный контур, и белое яблоко
    var a = '#8d8a99';
    var b = '#7d7a89';
    var x;
    var y;
    for (y = 0; y < g.h; y++) {
      for (x = 0; x < g.w; x++) {
        var half = cell / 2;
        c.fillStyle = a;
        c.fillRect(x * cell, y * cell, cell, cell);
        c.fillStyle = b;
        c.fillRect(x * cell, y * cell, half, half);
        c.fillRect(x * cell + half, y * cell + half, half, half);
      }
    }
    var ghost = layer.indexOf('frame:') === 0 ? st.eye : null;
    if (ghost) {
      c.globalAlpha = 0.22;
      var ox = Math.trunc((g.w - ghost.w) / 2);
      var oy = Math.trunc((g.h - ghost.h) / 2);
      for (y = 0; y < ghost.h; y++) {
        for (x = 0; x < ghost.w; x++) {
          var gi = palIndex(ghost.rows[y][x]);
          if (gi >= 0) {
            c.fillStyle = cssColor(st.pal[gi].col);
            c.fillRect((x + ox) * cell, (y + oy) * cell, cell, cell);
          }
        }
      }
      c.globalAlpha = 1;
    }
    for (y = 0; y < g.h; y++) {
      for (x = 0; x < g.w; x++) {
        var ch = g.rows[y][x];
        if (ch === '.') {
          continue;
        }
        var i = palIndex(ch);
        c.fillStyle = i >= 0 ? cssColor(st.pal[i].col) : '#ff00ff';
        c.fillRect(x * cell, y * cell, cell, cell);
        if (i < 0) {
          c.fillStyle = '#fff';
          c.font = Math.round(cell * 0.6) + 'px monospace';
          c.textAlign = 'center';
          c.textBaseline = 'middle';
          c.fillText(ch, x * cell + cell / 2, y * cell + cell / 2);
        } else if (layer === 'eye' && els.showSclera.checked && st.sclera.indexOf(ch) >= 0 && cell >= 10) {
          c.fillStyle = 'rgba(122,69,214,.75)';
          var d = Math.max(2, Math.round(cell / 7));
          c.fillRect(x * cell + cell / 2 - d / 2, y * cell + cell / 2 - d / 2, d, d);
        }
      }
    }
    c.strokeStyle = dark ? 'rgba(255,255,255,.07)' : 'rgba(0,0,0,.07)';
    c.lineWidth = 1;
    c.beginPath();
    for (x = 1; x < g.w; x++) {
      c.moveTo(x * cell + 0.5, 0);
      c.lineTo(x * cell + 0.5, H);
    }
    for (y = 1; y < g.h; y++) {
      c.moveTo(0, y * cell + 0.5);
      c.lineTo(W, y * cell + 0.5);
    }
    c.stroke();
    if (mirror) {
      c.strokeStyle = 'rgba(255,79,123,.6)';
      c.setLineDash([4, 4]);
      c.beginPath();
      c.moveTo(W / 2, 0);
      c.lineTo(W / 2, H);
      c.stroke();
      c.setLineDash([]);
    }
    els.sizeLabel.textContent = g.w + ' × ' + g.h;
  }

  function cellAt(e) {
    var r = els.canvas.getBoundingClientRect();
    var g = currentGrid();
    var x = Math.floor((e.clientX - r.left) / (r.width / g.w));
    var y = Math.floor((e.clientY - r.top) / (r.height / g.h));
    if (x < 0 || y < 0 || x >= g.w || y >= g.h) {
      return null;
    }
    return [x, y];
  }

  function brushChar() {
    if (tool === 'erase') {
      return '.';
    }
    return st.pal[cur] ? st.pal[cur].ch : '.';
  }

  function plot(g, x, y, ch) {
    g.rows[y][x] = ch;
    if (mirror) {
      g.rows[y][g.w - 1 - x] = ch;
    }
  }

  function flood(g, x, y, ch) {
    var from = g.rows[y][x];
    if (from === ch) {
      return;
    }
    var stack = [[x, y]];
    while (stack.length) {
      var p = stack.pop();
      var px = p[0];
      var py = p[1];
      if (px < 0 || py < 0 || px >= g.w || py >= g.h || g.rows[py][px] !== from) {
        continue;
      }
      g.rows[py][px] = ch;
      stack.push([px + 1, py], [px - 1, py], [px, py + 1], [px, py - 1]);
    }
  }

  function bindCanvas() {
    var cv = els.canvas;
    var down = false;
    var last = null;
    cv.addEventListener('pointerdown', function (e) {
      var g = currentGrid();
      var p = g && cellAt(e);
      if (!p) {
        return;
      }
      e.preventDefault();
      if (tool === 'pick') {
        var ch = g.rows[p[1]][p[0]];
        var i = palIndex(ch);
        if (i >= 0) {
          cur = i;
          tool = 'brush';
        } else {
          tool = 'erase';
        }
        renderTools();
        refreshPalette();
        return;
      }
      remember();
      if (tool === 'fill') {
        flood(g, p[0], p[1], brushChar());
        if (mirror) {
          flood(g, g.w - 1 - p[0], p[1], brushChar());
        }
        changed();
        return;
      }
      down = true;
      last = p;
      try {
        cv.setPointerCapture(e.pointerId);
      } catch (ignored) {
        // старые браузеры
      }
      plot(g, p[0], p[1], brushChar());
      drawGrid();
    });
    cv.addEventListener('pointermove', function (e) {
      if (!down) {
        return;
      }
      var g = currentGrid();
      var p = cellAt(e);
      if (!p || (last && p[0] === last[0] && p[1] === last[1])) {
        return;
      }
      line(last || p, p).forEach(function (q) {
        plot(g, q[0], q[1], brushChar());
      });
      last = p;
      drawGrid();
      validateSoon();
    });
    var up = function () {
      if (!down) {
        return;
      }
      down = false;
      last = null;
      changed();
    };
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', up);
  }

  var vTimer = 0;

  function validateSoon() {
    if (vTimer) {
      return;
    }
    vTimer = requestAnimationFrame(function () {
      vTimer = 0;
      validate();
    });
  }

  function line(a, b) {
    var pts = [];
    var x0 = a[0];
    var y0 = a[1];
    var dx = Math.abs(b[0] - x0);
    var dy = -Math.abs(b[1] - y0);
    var sx = x0 < b[0] ? 1 : -1;
    var sy = y0 < b[1] ? 1 : -1;
    var err = dx + dy;
    for (;;) {
      pts.push([x0, y0]);
      if (x0 === b[0] && y0 === b[1]) {
        break;
      }
      var e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
    return pts;
  }

  // ---- операции со слоем ---------------------------------------------------------------

  function resize(dw, dh) {
    var g = currentGrid();
    if (!g) {
      return;
    }
    var max = layer === 'iris' || layer === 'highlight' ? [st.eye.w, st.eye.h] : [32, 32];
    var w = Math.max(1, Math.min(max[0], g.w + dw));
    var hh = Math.max(1, Math.min(max[1], g.h + dh));
    if (w === g.w && hh === g.h) {
      S.toast(layer === 'eye' || layer.indexOf('frame:') === 0 ? 'Больше 32 на 32 нельзя' : 'Не больше самого глаза');
      return;
    }
    remember();
    var n = grid(w, hh);
    for (var y = 0; y < Math.min(hh, g.h); y++) {
      for (var x = 0; x < Math.min(w, g.w); x++) {
        n.rows[y][x] = g.rows[y][x];
      }
    }
    setCurrentGrid(n);
    changed();
  }

  function shift(dx, dy) {
    var g = currentGrid();
    if (!g) {
      return;
    }
    remember();
    var n = grid(g.w, g.h);
    for (var y = 0; y < g.h; y++) {
      for (var x = 0; x < g.w; x++) {
        var tx = x + dx;
        var ty = y + dy;
        if (tx >= 0 && ty >= 0 && tx < g.w && ty < g.h) {
          n.rows[ty][tx] = g.rows[y][x];
        }
      }
    }
    setCurrentGrid(n);
    changed();
  }

  function flip() {
    var g = currentGrid();
    if (!g) {
      return;
    }
    remember();
    g.rows = g.rows.map(function (r) {
      return r.slice().reverse();
    });
    changed();
  }

  function clearLayer() {
    var g = currentGrid();
    if (!g) {
      return;
    }
    remember();
    setCurrentGrid(grid(g.w, g.h));
    changed();
  }

  function createLayer() {
    remember();
    if (layer === 'iris') {
      st.iris = grid(Math.min(4, st.eye.w), Math.min(4, st.eye.h));
    } else if (layer === 'highlight') {
      st.highlight = grid(1, 1);
    } else {
      st.frames[layer.substring(6)] = cloneGrid(st.eye);
    }
    changed(true);
  }

  function removeLayer() {
    if (layer === 'eye') {
      return;
    }
    if (!confirm('Убрать этот слой целиком?')) {
      return;
    }
    remember();
    setCurrentGrid(null);
    changed(true);
  }

  // ---- палитра -------------------------------------------------------------------

  function refreshPalette() {
    var row = els.palette;
    row.innerHTML = '';
    st.pal.forEach(function (p, i) {
      var used = isUsed(p.ch);
      var b = h('button', {type: 'button', class: 'swatch' + (i === cur && tool !== 'erase' ? ' on' : ''),
        title: '"' + p.ch + '" ' + p.col + (st.sclera.indexOf(p.ch) >= 0 ? ', яблоко' : '') +
          (st.pupil === p.ch ? ', зрачок' : '') + (used ? '' : ', не используется'),
        onclick: function () {
          cur = i;
          if (tool === 'erase' || tool === 'pick') {
            tool = 'brush';
          }
          renderTools();
          refreshPalette();
        }}, [
        h('span', {class: 'sw-fill', style: 'background:' + cssColor(p.col)}),
        h('span', {class: 'sw-ch', text: p.ch})
      ]);
      if (st.sclera.indexOf(p.ch) >= 0) {
        b.appendChild(h('span', {class: 'sw-dot', title: 'яблоко'}));
      }
      if (st.pupil === p.ch) {
        b.appendChild(h('span', {class: 'sw-ring', title: 'зрачок'}));
      }
      if (!used) {
        b.classList.add('unused');
      }
      row.appendChild(b);
    });
    row.appendChild(h('button', {type: 'button', class: 'swatch add', title: 'Новый цвет', text: '+',
      onclick: addColor}));
    var p = st.pal[cur];
    els.colorBox.style.display = p ? '' : 'none';
    if (!p) {
      return;
    }
    var c = splitColor(p.col) || {rgb: '#000000', a: 255};
    els.colorInput.value = c.rgb;
    els.alpha.value = c.a;
    els.alphaVal.textContent = Math.round(c.a / 2.55) + '%';
    if (document.activeElement !== els.hexInput) {
      els.hexInput.value = p.col;
    }
    if (document.activeElement !== els.charInput) {
      els.charInput.value = p.ch;
    }
    els.isSclera.checked = st.sclera.indexOf(p.ch) >= 0;
    els.isPupil.checked = st.pupil === p.ch;
  }

  function isUsed(ch) {
    var grids = [st.eye, st.iris, st.highlight].concat(Object.keys(st.frames).map(function (k) {
      return st.frames[k];
    }));
    return grids.some(function (g) {
      return g && g.rows.some(function (r) {
        return r.indexOf(ch) >= 0;
      });
    });
  }

  function addColor() {
    var ch = freeChar();
    if (!ch) {
      S.toast('Палитра заполнена');
      return;
    }
    remember();
    st.pal.push({ch: ch, col: PRESETS[(st.pal.length * 7 + 3) % PRESETS.length]});
    cur = st.pal.length - 1;
    tool = 'brush';
    renderTools();
    changed();
  }

  function setColor(col) {
    var p = st.pal[cur];
    if (!p) {
      return;
    }
    p.col = col;
    changed();
  }

  function renameChar(to) {
    var p = st.pal[cur];
    if (!p || !to || to === p.ch) {
      return;
    }
    to = to[to.length - 1];
    if (to === '.' || to === ' ') {
      S.toast('Точка и пробел заняты под прозрачность');
      return;
    }
    if (palIndex(to) >= 0) {
      S.toast('Символ "' + to + '" уже есть в палитре');
      return;
    }
    remember();
    var from = p.ch;
    var swap = function (g) {
      if (g) {
        g.rows = g.rows.map(function (r) {
          return r.map(function (c) {
            return c === from ? to : c;
          });
        });
      }
    };
    [st.eye, st.iris, st.highlight].forEach(swap);
    Object.keys(st.frames).forEach(function (k) {
      swap(st.frames[k]);
    });
    st.sclera = st.sclera.split(from).join(to);
    if (st.pupil === from) {
      st.pupil = to;
    }
    p.ch = to;
    changed();
  }

  function deleteColor() {
    var p = st.pal[cur];
    if (!p) {
      return;
    }
    if (isUsed(p.ch) && !confirm('Цвет "' + p.ch + '" есть на рисунке. Удалить его и стереть эти пиксели?')) {
      return;
    }
    remember();
    var ch = p.ch;
    var wipe = function (g) {
      if (g) {
        g.rows = g.rows.map(function (r) {
          return r.map(function (c) {
            return c === ch ? '.' : c;
          });
        });
      }
    };
    [st.eye, st.iris, st.highlight].forEach(wipe);
    Object.keys(st.frames).forEach(function (k) {
      wipe(st.frames[k]);
    });
    st.sclera = st.sclera.split(ch).join('');
    if (st.pupil === ch) {
      st.pupil = '';
    }
    st.pal.splice(cur, 1);
    cur = Math.max(0, cur - 1);
    changed();
  }

  // ---- разметка ---------------------------------------------------------------------

  function chip(label, on, fn, title) {
    return h('button', {type: 'button', class: 'chip' + (on ? ' on' : ''), title: title || null, onclick: fn,
      text: label});
  }

  function renderLayers() {
    var box = els.layers;
    box.innerHTML = '';
    LAYERS.forEach(function (l) {
      box.appendChild(chip(l[1] + (l[0] !== 'eye' && !st[l[0]] ? ' +' : ''), layer === l[0], function () {
        layer = l[0];
        renderLayers();
        renderTools();
        drawGrid();
      }));
    });
    var sel = h('select', {class: 'chip' + (layer.indexOf('frame:') === 0 ? ' on' : ''), 'aria-label': 'Кадр эмоции',
      onchange: function () {
        layer = sel.value ? 'frame:' + sel.value : 'eye';
        renderLayers();
        renderTools();
        drawGrid();
        if (sel.value) {
          eyes.event('emo:' + FRAME_EMO[sel.value]);
          if (sel.value === 'ouch') {
            eyes.brain.poke();
          }
        }
      }});
    sel.appendChild(h('option', {value: '', text: 'Кадры эмоций ▾'}));
    PE.FRAME_NAMES.forEach(function (k) {
      var o = h('option', {value: k, text: FRAME_LABELS[k] + ' (' + k + ')' + (st.frames[k] ? ' ✓' : '')});
      if (layer === 'frame:' + k) {
        o.selected = true;
      }
      sel.appendChild(o);
    });
    box.appendChild(sel);
    var g = currentGrid();
    els.emptyText.textContent = layer === 'iris' ? 'Радужки нет: плагин нарисует чёрный квадрат 2 на 2.'
      : layer === 'highlight' ? 'Блика нет.' : 'Своего кадра нет: эмоцию нарисует плагин.';
    els.createBtn.textContent = layer === 'iris' ? 'Нарисовать радужку' : layer === 'highlight' ? 'Добавить блик'
      : 'Нарисовать кадр (из копии глаза)';
    els.removeBtn.style.display = g && layer !== 'eye' ? '' : 'none';
    var hint = {
      eye: 'Глаз рисуется для левого, правый отражается. Цвета яблока отметьте в палитре.',
      iris: 'Радужка встаёт в центр яблока и ездит за взглядом. Зрачок отметьте в палитре.',
      highlight: 'Блик рисуется поверх радужки. Сдвиг и "на месте" в настройках ниже.'
    }[layer] || 'Кадр показывается вместо всего глаза, пока длится эмоция. Под сеткой бледно виден глаз.';
    els.layerHint.textContent = hint;
  }

  function renderTools() {
    var box = els.tools;
    box.innerHTML = '';
    [['brush', '✏️', 'Кисть'], ['erase', '🧽', 'Ластик'], ['fill', '🔲', 'Заливка'], ['pick', '💧', 'Пипетка']]
      .forEach(function (t) {
        box.appendChild(chip(t[1] + ' ' + t[2], tool === t[0], function () {
          tool = t[0];
          renderTools();
          refreshPalette();
        }));
      });
    box.appendChild(chip('↔️ Зеркало', mirror, function () {
      mirror = !mirror;
      renderTools();
      drawGrid();
    }, 'Рисовать сразу с двух сторон'));
  }

  function field(label, input, hint) {
    return h('label', {class: 'fld'}, [h('span', {text: label}), input, hint ? h('small', {text: hint}) : null]);
  }

  function numInput(value, min, max, step, onInput) {
    var i = h('input', {type: 'number', value: value, min: min, max: max, step: step || 1, inputmode: 'decimal'});
    i.addEventListener('change', function () {
      var v = Number(i.value);
      if (isNaN(v)) {
        return;
      }
      v = Math.max(min, Math.min(max, v));
      i.value = v;
      remember();
      onInput(v);
      changed();
    });
    return i;
  }

  function range(value, min, max, step, onInput) {
    var out = h('output', {text: String(value)});
    var i = h('input', {type: 'range', value: value, min: min, max: max, step: step});
    var started = false;
    i.addEventListener('input', function () {
      if (!started) {
        remember();
        started = true;
      }
      out.textContent = i.value;
      onInput(Number(i.value));
      changed();
    });
    i.addEventListener('change', function () {
      started = false;
    });
    return h('span', {class: 'rng'}, [i, out]);
  }

  function check(label, value, onChange) {
    var i = h('input', {type: 'checkbox'});
    i.checked = !!value;
    i.addEventListener('change', function () {
      remember();
      onChange(i.checked);
      changed();
    });
    return h('label', {class: 'chk'}, [i, h('span', {text: label})]);
  }

  function select(options, value, onChange) {
    var s = h('select');
    options.forEach(function (o) {
      var op = h('option', {value: o[0], text: o[1]});
      if (o[0] === value) {
        op.selected = true;
      }
      s.appendChild(op);
    });
    s.addEventListener('change', function () {
      remember();
      onChange(s.value);
      changed(true);
    });
    return s;
  }

  function colorPick(ref, onChange) {
    var c = splitColor(ref.col) || {rgb: '#1b1b22', a: 255};
    var i = h('input', {type: 'color', value: c.rgb});
    var started = false;
    i.addEventListener('input', function () {
      if (!started) {
        remember();
        started = true;
      }
      onChange(i.value);
      changed();
    });
    i.addEventListener('change', function () {
      started = false;
    });
    return i;
  }

  /** Боковая панель: все поля, кроме рисунка и палитры. */
  function renderSide() {
    var m = st.meta;
    // описание
    var d = els.meta;
    d.innerHTML = '';
    var idIn = h('input', {type: 'text', value: m.id, maxlength: 32, autocomplete: 'off', spellcheck: 'false'});
    var idHint = h('small');
    var showId = function () {
      var clean = PE.cleanId(idIn.value);
      idHint.textContent = clean === idIn.value ? 'Латиница, цифры, _ и -. Свои глаза с тем же id плагин заменит этими'
        : 'Плагин превратит в "' + clean + '"';
    };
    idIn.addEventListener('input', function () {
      m.id = idIn.value;
      showId();
      validateSoon();
    });
    idIn.addEventListener('change', function () {
      remember();
      changed();
    });
    showId();
    d.appendChild(h('label', {class: 'fld'}, [h('span', {text: 'id'}), idIn, idHint]));
    var auth = h('input', {type: 'text', value: m.author, maxlength: 40, placeholder: '@username'});
    auth.addEventListener('input', function () {
      m.author = auth.value;
      validateSoon();
    });
    d.appendChild(field('Автор', auth, 'Пустое плагин заполнит вашим @username'));
    var desc = h('textarea', {rows: 2, maxlength: 200});
    desc.value = m.description;
    desc.addEventListener('input', function () {
      m.description = desc.value;
      validateSoon();
    });
    d.appendChild(field('Описание', desc, 'До 200 символов, видно в каталоге'));
    var tagBox = h('div', {class: 'chips'});
    TAGS.forEach(function (t) {
      tagBox.appendChild(chip(t, m.tags.indexOf(t) >= 0, function () {
        remember();
        var k = m.tags.indexOf(t);
        if (k >= 0) {
          m.tags.splice(k, 1);
        } else if (m.tags.length < 8) {
          m.tags.push(t);
        }
        changed(true);
      }));
    });
    d.appendChild(h('div', {class: 'fld'}, [h('span', {text: 'Метки'}), tagBox]));

    // радужка, блик, веки
    var e = els.eyeOpts;
    e.innerHTML = '';
    e.appendChild(h('div', {class: 'two'}, [
      field('Сдвиг радужки x', numInput(st.irisOffset[0], -32, 32, 1, function (v) {
        st.irisOffset[0] = v;
      })),
      field('y', numInput(st.irisOffset[1], -32, 32, 1, function (v) {
        st.irisOffset[1] = v;
      }))
    ]));
    els.travelX = numInput(st.travel[0], 0, 10, 1, function (v) {
      st.travel[0] = v;
    });
    els.travelY = numInput(st.travel[1], 0, 10, 1, function (v) {
      st.travel[1] = v;
    });
    els.travelX.disabled = els.travelY.disabled = st.travelAuto;
    els.travelAuto = check('сам по размеру', st.travelAuto, function (v) {
      st.travelAuto = v;
      if (!v && lastObj) {
        st.travel = [Number(els.travelX.value) || 1, Number(els.travelY.value) || 0];
      }
      els.travelX.disabled = els.travelY.disabled = v;
    });
    e.appendChild(h('div', {class: 'fld'}, [h('span', {text: 'Ход радужки (travel)'}),
      h('div', {class: 'two'}, [els.travelX, els.travelY]), els.travelAuto,
      h('small', {text: 'На сколько пикселей радужка уходит от центра, когда глаза смотрят в сторону'})]));
    e.appendChild(h('div', {class: 'two'}, [
      field('Сдвиг блика x', numInput(st.hlOffset[0], -32, 32, 1, function (v) {
        st.hlOffset[0] = v;
      })),
      field('y', numInput(st.hlOffset[1], -32, 32, 1, function (v) {
        st.hlOffset[1] = v;
      }))
    ]));
    e.appendChild(check('Блик стоит на месте, радужка ездит под ним', st.hlFixed, function (v) {
      st.hlFixed = v;
    }));
    var lidSel = select([['auto', 'Цвет контура'], ['color', 'Свой цвет'], ['none', 'Прозрачное']], st.lid.mode,
      function (v) {
        st.lid.mode = v;
      });
    var lidCol = colorPick(st.lid, function (v) {
      st.lid.col = v;
      st.lid.mode = 'color';
      lidSel.value = 'color';
    });
    e.appendChild(field('Веко (lid)', h('div', {class: 'two'}, [lidSel, lidCol]),
      'Закрывает яблоко при моргании и сне. Должно отличаться от цвета яблока'));
    var lashSel = select([['auto', 'Веко темнее'], ['color', 'Свой цвет'], ['none', 'Нет']], st.lash.mode,
      function (v) {
        st.lash.mode = v;
      });
    var lashCol = colorPick(st.lash, function (v) {
      st.lash.col = v;
      st.lash.mode = 'color';
      lashSel.value = 'color';
    });
    e.appendChild(field('Ресницы (lash)', h('div', {class: 'two'}, [lashSel, lashCol]),
      'Кромка века и линия закрытого глаза'));

    // раскладка
    var L = st.layout;
    var lb = els.layoutOpts;
    lb.innerHTML = '';
    lb.appendChild(field('Глаз', select([['two', 'Два'], ['one', 'Один'], ['custom', 'Свои места (до 12)']], L.mode,
      function (v) {
        L.mode = v;
        if (v === 'custom' && L.eyes.length < 2) {
          L.eyes = [{x: 0, y: 0, mirror: false, scale: 1}, {x: st.eye.w + L.gap, y: 0, mirror: true, scale: 1}];
        }
      })));
    if (L.mode === 'two') {
      lb.appendChild(field('Отступ между глазами (gap)', range(L.gap, 0, 16, 1, function (v) {
        L.gap = v;
      })));
      lb.appendChild(check('Правый глаз зеркальный (mirror)', L.mirror, function (v) {
        L.mirror = v;
      }));
    } else if (L.mode === 'custom') {
      var tbl = h('div', {class: 'eyes-list'});
      tbl.appendChild(h('div', {class: 'eyes-row head'}, ['x', 'y', 'размер', 'отразить', ''].map(function (t) {
        return h('span', {text: t});
      })));
      L.eyes.forEach(function (ey, k) {
        tbl.appendChild(h('div', {class: 'eyes-row'}, [
          numInput(ey.x, 0, 120, 1, function (v) {
            ey.x = v;
          }),
          numInput(ey.y, 0, 60, 1, function (v) {
            ey.y = v;
          }),
          select([['1', '×1'], ['2', '×2'], ['3', '×3']], String(ey.scale), function (v) {
            ey.scale = Number(v);
          }),
          (function () {
            var c = h('input', {type: 'checkbox'});
            c.checked = ey.mirror;
            c.addEventListener('change', function () {
              remember();
              ey.mirror = c.checked;
              changed();
            });
            return c;
          })(),
          h('button', {type: 'button', class: 'icon-btn', title: 'Убрать глаз', text: '✕', onclick: function () {
            if (L.eyes.length <= 1) {
              return;
            }
            remember();
            L.eyes.splice(k, 1);
            changed(true);
          }})
        ]));
      });
      lb.appendChild(tbl);
      lb.appendChild(h('button', {type: 'button', class: 'btn small alt', text: '+ Глаз', onclick: function () {
        if (L.eyes.length >= 12) {
          S.toast('Не больше 12 глаз');
          return;
        }
        remember();
        var lastE = L.eyes[L.eyes.length - 1] || {x: 0, y: 0};
        L.eyes.push({x: Math.min(120, lastE.x + st.eye.w + 2), y: lastE.y, mirror: false, scale: 1});
        changed(true);
      }}));
      lb.appendChild(h('small', {class: 'muted', text: 'x и y это левый верхний угол глаза в пикселях. Всё вместе не больше 128 на 64. ' +
        'Подмигивает последний глаз в списке.'}));
    }

    // характер
    var B = st.beh;
    var bb = els.behOpts;
    bb.innerHTML = '';
    bb.appendChild(check('Не моргают совсем', B.never, function (v) {
      B.never = v;
      renderSide();
    }));
    if (!B.never) {
      bb.appendChild(field('Моргают раз в, секунд: от', range(B.blink[0], 0.5, 15, 0.5, function (v) {
        B.blink[0] = v;
      })));
      bb.appendChild(field('до', range(B.blink[1], 0.5, 20, 0.5, function (v) {
        B.blink[1] = v;
      })));
    }
    bb.appendChild(field('Взгляд (style)', select([['smooth', 'Плавный'], ['snap', 'Резкий'], ['twitch', 'Дёрганый, дрожит']],
      B.style, function (v) {
        B.style = v;
      })));
    bb.appendChild(field('Скорость взгляда (speed)', range(B.speed, 0.2, 4, 0.1, function (v) {
      B.speed = v;
    })));
    bb.appendChild(field('Смотрят на вас без дела (stare)', range(B.stare, 0, 1, 0.05, function (v) {
      B.stare = v;
    })));
    bb.appendChild(field('Подёргивания (twitch)', range(B.twitch, 0, 1, 0.05, function (v) {
      B.twitch = v;
    })));
    bb.appendChild(field('Открыты в покое (open)', range(B.open, 0.3, 1, 0.05, function (v) {
      B.open = v;
    }), 'Меньше 1: сонные или хитрые'));
    bb.appendChild(field('Наклон век (slant)', range(B.slant, -1, 1, 0.05, function (v) {
      B.slant = v;
    }), 'Больше нуля злые, меньше грустные'));
    bb.appendChild(check('Каждый глаз сам по себе (independent)', B.independent, function (v) {
      B.independent = v;
    }));
    bb.appendChild(check('💀 Жуткие (creepy): дольше смотрят и не моргают', B.creepy, function (v) {
      B.creepy = v;
    }));
    renderLayers();
  }

  function renderAll() {
    els.name.value = st.meta.name;
    renderSide();
    renderTools();
    changed();
    syncUndo();
  }

  // ---- загрузка --------------------------------------------------------------------

  function load(obj, label) {
    try {
      var s = fromObj(obj);
      if (st) {
        remember();
      }
      st = s;
      cur = Math.min(cur, Math.max(0, st.pal.length - 1));
      layer = 'eye';
      renderAll();
      if (label) {
        S.toast(label);
      }
      return true;
    } catch (e) {
      S.toast('Не открылось: ' + e.message);
      return false;
    }
  }

  function loadText(text, label) {
    var clean;
    try {
      clean = PE.cleanPasted(text);
    } catch (e) {
      return 'не похоже на JSON с глазами: нет фигурных скобок';
    }
    var obj;
    try {
      obj = PE.parseJson(clean);
    } catch (e) {
      return 'ошибка в JSON: ' + e.message;
    }
    return load(obj, label) ? '' : 'не открылось';
  }

  function starter() {
    return {
      format: 1, id: 'my_eyes', name: 'Мои глаза', author: '',
      palette: {'#': '#1b1b22', 'w': '#ffffff', 's': '#d9deea', 'i': '#3d7bff', 'p': '#101018', 'h': '#ffffff'},
      eye: ['...####...', '.##wwww##.', '#wwwwwwww#', '#wwwwwwww#', '#wwwwwwww#', '#swwwwwws#', '.##ssss##.',
        '...####...'],
      sclera: 'ws', iris: ['.ii.', 'ippi', 'ippi', '.ii.'], pupil: 'p', highlight: ['h'], highlight_offset: [-1, -1],
      lid: '#f2c9b0', lash: '#1b1b22', layout: {count: 2, gap: 3, mirror: true},
      behavior: {blink: [2.5, 6], style: 'smooth', stare: 0.15}
    };
  }

  function fromHash() {
    var hsh = location.hash.substring(1);
    if (!hsh) {
      return false;
    }
    var p = hsh.split('=');
    var key = p[0];
    var val = decodeURIComponent(p.slice(1).join('='));
    history.replaceState(null, '', location.pathname);
    if (key === 'json') {
      var err = loadText(val, 'Открыто из инструкции');
      return !err;
    }
    if (key === 'skin') {
      S.library().then(function (lib) {
        var hit = lib.filter(function (x) {
          return x.id === val;
        })[0];
        if (hit) {
          var obj = JSON.parse(hit.text);
          if (hit.source === 'builtin') {
            obj.id = 'my_' + obj.id;
            obj.name = obj.name + ' (моя копия)';
            obj.author = '';
          }
          load(obj, 'Открыты "' + (hit.raw.name || hit.id) + '"' + (hit.source === 'builtin' ? ' копией' : ''));
        }
      });
      return true;
    }
    return false;
  }

  // ---- запуск ------------------------------------------------------------------------

  function setBg(v) {
    bg = v;
    els.stage.dataset.bg = v;
    eyes.opts.dark = v === 'dark' || v === 'chat-dark';
    eyes.lastSig = '';
    els.bgBox.querySelectorAll('.chip').forEach(function (c) {
      c.classList.toggle('on', c.dataset.v === v);
    });
  }

  function init() {
    var $ = function (id) {
      return document.getElementById(id);
    };
    els = {
      canvas: $('ed-canvas'), canvasHolder: $('ed-canvas-holder'), layers: $('ed-layers'), tools: $('ed-tools'),
      palette: $('ed-palette'), colorBox: $('ed-color'), colorInput: $('ed-color-input'), alpha: $('ed-alpha'),
      alphaVal: $('ed-alpha-val'), hexInput: $('ed-hex'), charInput: $('ed-char'), isSclera: $('ed-is-sclera'),
      isPupil: $('ed-is-pupil'), sizeLabel: $('ed-size'), emptyLayer: $('ed-empty'), emptyText: $('ed-empty-text'),
      createBtn: $('ed-create'), removeBtn: $('ed-remove'), layerHint: $('ed-layer-hint'), stage: $('ed-stage'),
      status: $('ed-status'), json: $('ed-json'), size: $('ed-json-size'), meta: $('ed-meta'),
      eyeOpts: $('ed-eye-opts'), layoutOpts: $('ed-layout-opts'), behOpts: $('ed-beh-opts'), name: $('ed-name'),
      undo: $('ed-undo'), redo: $('ed-redo'), showSclera: $('ed-show-sclera'), bgBox: $('ed-bg'),
      emos: $('ed-emos'), unit: $('ed-unit')
    };
    eyes = new PE.Eyes({skin: PE.BLANK, fit: els.stage, fitPad: 24, fitHeight: 210, maxUnit: 10, minUnit: 3});
    els.stage.appendChild(eyes.canvas);
    setBg(S.isDark() ? 'dark' : 'light');
    els.bgBox.querySelectorAll('.chip').forEach(function (c) {
      c.addEventListener('click', function () {
        setBg(c.dataset.v);
      });
    });
    EMOS.forEach(function (e) {
      els.emos.appendChild(chip(e[1], false, function () {
        if (e[0] === 'ouch') {
          eyes.brain.poke();
        } else if (e[0] === 'asleep' && eyes.brain.napping) {
          eyes.brain.toggleNap();
        } else if (e[0] === 'sleepy') {
          eyes.brain.feel(14, 2.5);
        } else {
          eyes.event('emo:' + e[0]);
        }
      }, e[0]));
    });
    els.unit.addEventListener('input', function () {
      eyes.opts.maxUnit = Number(els.unit.value);
      eyes.refit();
    });

    bindCanvas();
    els.showSclera.addEventListener('change', drawGrid);
    els.name.addEventListener('input', function () {
      st.meta.name = els.name.value;
      validateSoon();
    });
    els.name.addEventListener('change', function () {
      remember();
      changed();
    });
    els.createBtn.addEventListener('click', createLayer);
    els.removeBtn.addEventListener('click', removeLayer);
    $('ed-w-').addEventListener('click', function () {
      resize(-1, 0);
    });
    $('ed-w+').addEventListener('click', function () {
      resize(1, 0);
    });
    $('ed-h-').addEventListener('click', function () {
      resize(0, -1);
    });
    $('ed-h+').addEventListener('click', function () {
      resize(0, 1);
    });
    $('ed-left').addEventListener('click', function () {
      shift(-1, 0);
    });
    $('ed-right').addEventListener('click', function () {
      shift(1, 0);
    });
    $('ed-up').addEventListener('click', function () {
      shift(0, -1);
    });
    $('ed-down').addEventListener('click', function () {
      shift(0, 1);
    });
    $('ed-flip').addEventListener('click', flip);
    $('ed-clear').addEventListener('click', clearLayer);
    els.undo.addEventListener('click', doUndo);
    els.redo.addEventListener('click', doRedo);
    document.addEventListener('keydown', function (e) {
      var tag = (document.activeElement || {}).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
        return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) {
          doRedo();
        } else {
          doUndo();
        }
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        doRedo();
      } else if (!e.ctrlKey && !e.metaKey) {
        var map = {b: 'brush', e: 'erase', f: 'fill', i: 'pick'};
        if (map[e.key]) {
          tool = map[e.key];
          renderTools();
          refreshPalette();
        } else if (e.key === 'm') {
          mirror = !mirror;
          renderTools();
          drawGrid();
        }
      }
    });

    // палитра
    PRESETS.forEach(function (c) {
      $('ed-presets').appendChild(h('button', {type: 'button', class: 'preset', title: c,
        style: 'background:' + c, onclick: function () {
          if (!st.pal[cur]) {
            addColor();
          }
          remember();
          setColor(c);
        }}));
    });
    var colorStarted = false;
    els.colorInput.addEventListener('input', function () {
      if (!colorStarted) {
        remember();
        colorStarted = true;
      }
      setColor(joinColor(els.colorInput.value, Number(els.alpha.value)));
    });
    els.colorInput.addEventListener('change', function () {
      colorStarted = false;
    });
    els.alpha.addEventListener('input', function () {
      if (!colorStarted) {
        remember();
        colorStarted = true;
      }
      setColor(joinColor(els.colorInput.value, Number(els.alpha.value)));
    });
    els.alpha.addEventListener('change', function () {
      colorStarted = false;
    });
    els.hexInput.addEventListener('change', function () {
      var c = splitColor(els.hexInput.value);
      if (!c) {
        S.toast('Цвет пишется как #RRGGBB или #AARRGGBB');
        refreshPalette();
        return;
      }
      remember();
      setColor(joinColor(c.rgb, c.a));
    });
    els.charInput.addEventListener('input', function () {
      renameChar(els.charInput.value);
    });
    els.isSclera.addEventListener('change', function () {
      var p = st.pal[cur];
      if (!p) {
        return;
      }
      remember();
      if (els.isSclera.checked) {
        if (st.sclera.indexOf(p.ch) < 0) {
          st.sclera += p.ch;
        }
      } else {
        st.sclera = st.sclera.split(p.ch).join('');
      }
      changed();
    });
    els.isPupil.addEventListener('change', function () {
      var p = st.pal[cur];
      if (!p) {
        return;
      }
      remember();
      st.pupil = els.isPupil.checked ? p.ch : '';
      changed();
    });
    $('ed-del-color').addEventListener('click', deleteColor);

    // файл и JSON
    $('ed-copy').addEventListener('click', function () {
      S.copy(lastJson, 'JSON скопирован. В плагине: "Из буфера обмена"');
    });
    $('ed-download').addEventListener('click', function () {
      S.download(PE.cleanId(st.meta.id) + '.json', lastJson);
    });
    $('ed-share').addEventListener('click', function () {
      S.share(PE.cleanId(st.meta.id) + '.json', lastJson);
    });
    $('ed-edit-json').addEventListener('click', function () {
      jsonEditing = !jsonEditing;
      els.json.readOnly = !jsonEditing;
      $('ed-apply-json').style.display = jsonEditing ? '' : 'none';
      $('ed-edit-json').textContent = jsonEditing ? 'Отменить правку' : 'Править JSON';
      if (!jsonEditing) {
        els.json.value = lastJson;
        $('ed-json-err').textContent = '';
      } else {
        els.json.focus();
      }
    });
    $('ed-apply-json').addEventListener('click', function () {
      var err = loadText(els.json.value, 'JSON применён');
      var out = $('ed-json-err');
      if (err) {
        out.textContent = err;
        var m = /строка (\d+)/.exec(err);
        if (m) {
          selectLine(els.json, Number(m[1]));
        }
        return;
      }
      out.textContent = '';
      jsonEditing = false;
      els.json.readOnly = true;
      $('ed-apply-json').style.display = 'none';
      $('ed-edit-json').textContent = 'Править JSON';
      validate();
    });
    $('ed-paste').addEventListener('click', function () {
      var dlg = $('ed-paste-box');
      dlg.hidden = !dlg.hidden;
      if (!dlg.hidden) {
        $('ed-paste-text').focus();
      }
    });
    $('ed-paste-apply').addEventListener('click', function () {
      var text = $('ed-paste-text').value;
      var err = loadText(text, 'Глаза открыты');
      var out = $('ed-paste-err');
      if (err) {
        out.textContent = err;
        var m = /строка (\d+)/.exec(err);
        if (m) {
          selectLine($('ed-paste-text'), Number(m[1]));
        }
        return;
      }
      out.textContent = '';
      $('ed-paste-box').hidden = true;
      $('ed-paste-text').value = '';
    });
    $('ed-file').addEventListener('change', function () {
      var f = this.files && this.files[0];
      if (!f) {
        return;
      }
      if (f.size > 300000) {
        S.toast('Файл слишком большой для глаз');
        return;
      }
      var r = new FileReader();
      r.onload = function () {
        var err = loadText(String(r.result), 'Открыт файл ' + f.name);
        if (err) {
          S.toast(err);
        }
      };
      r.readAsText(f);
      this.value = '';
    });
    $('ed-new').addEventListener('click', function () {
      if (confirm('Начать с чистых глаз? Текущие можно вернуть кнопкой "Отменить".')) {
        load(starter(), 'Новые глаза');
      }
    });
    var tpl = $('ed-template');
    S.library().then(function (lib) {
      var g1 = h('optgroup', {label: 'Встроенные'});
      var g2 = h('optgroup', {label: 'Каталог'});
      lib.forEach(function (it) {
        (it.source === 'builtin' ? g1 : g2).appendChild(h('option', {value: it.id, text: it.raw.name || it.id}));
      });
      tpl.appendChild(g1);
      tpl.appendChild(g2);
      tpl.addEventListener('change', function () {
        var hit = lib.filter(function (x) {
          return x.id === tpl.value;
        })[0];
        tpl.value = '';
        if (!hit) {
          return;
        }
        var obj = JSON.parse(hit.text);
        if (hit.source === 'builtin') {
          obj.id = 'my_' + obj.id;
          obj.name = obj.name + ' (моя копия)';
          obj.author = '';
        }
        load(obj, 'Открыты "' + (hit.raw.name || hit.id) + '". Отменить: ↶');
      });
    });

    var fitPreview = function () {
      var narrow = window.innerWidth < 900;
      eyes.opts.fitHeight = narrow ? 96 : 210;
      eyes.refit();
    };
    fitPreview();
    window.addEventListener('resize', function () {
      fitPreview();
      drawGrid();
    });
    window.addEventListener('hashchange', fromHash);

    if (!fromHash()) {
      var draft = S.store(DRAFT);
      var ok = false;
      if (draft) {
        try {
          st = fromObj(JSON.parse(draft));
          ok = true;
        } catch (e) {
          ok = false;
        }
      }
      if (!ok) {
        st = fromObj(starter());
      }
      renderAll();
    } else if (!st) {
      st = fromObj(starter());
      renderAll();
    }
  }

  function doUndo() {
    if (!undo.length) {
      return;
    }
    redo.push(snapshot());
    restore(undo.pop());
    syncUndo();
  }

  function doRedo() {
    if (!redo.length) {
      return;
    }
    undo.push(snapshot());
    restore(redo.pop());
    syncUndo();
  }

  function selectLine(ta, line) {
    var lines = ta.value.split('\n');
    var start = 0;
    for (var i = 0; i < line - 1 && i < lines.length; i++) {
      start += lines[i].length + 1;
    }
    var end = start + (lines[line - 1] || '').length;
    ta.focus();
    ta.setSelectionRange(start, end);
    var lh = parseFloat(getComputedStyle(ta).lineHeight) || 18;
    ta.scrollTop = Math.max(0, (line - 4) * lh);
  }

  document.addEventListener('DOMContentLoaded', init);
})();
