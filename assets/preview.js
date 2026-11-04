(function () {
  'use strict';
  var MAX = 65536;
  var status = document.getElementById('status');
  var text = '';
  async function load() {
    var match = /^#v1=([A-Za-z0-9_-]+={0,2})$/.exec(location.hash);
    if (!match || match[1].length > 90000) throw new Error('В ссылке нет допустимого рисунка');
    if (typeof DecompressionStream === 'undefined') throw new Error('Откройте ссылку в свежем Chrome, Firefox или Safari');
    var bytes = Uint8Array.from(atob(match[1].replace(/-/g, '+').replace(/_/g, '/')), function (c) { return c.charCodeAt(0); });
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate'));
    var reader = stream.getReader(), chunks = [], length = 0;
    try {
      while (true) {
        var part = await reader.read();
        if (part.done) break;
        length += part.value.length;
        if (length > MAX) throw new Error('Рисунок превышает допустимый размер');
        chunks.push(part.value);
      }
    } finally { await reader.cancel(); }
    var joined = new Uint8Array(length), offset = 0;
    chunks.forEach(function (c) { joined.set(c, offset); offset += c.length; });
    text = new TextDecoder('utf-8', {fatal:true}).decode(joined);
    var PE = window.PixelEyes;
    var skin = PE.parseSkin(text);
    document.getElementById('name').textContent = skin.name;
    document.getElementById('author').textContent = skin.author;
    document.getElementById('description').textContent = skin.description;
    document.getElementById('tags').textContent = skin.tags.join(' · ');
    document.getElementById('content').hidden = false;
    var stage = document.getElementById('stage');
    PE.setDark(true);
    var eyes = new PE.Eyes({skin:skin,fit:stage,fitPad:48,fitHeight:208,minUnit:1,maxUnit:12});
    stage.appendChild(eyes.canvas);
    [['neutral','Обычные'],['laugh','Радость'],['love','Любовь'],['incoming','Сообщение'],['sad_text','Грусть'],['insulted','Злость'],['shake','Встряска']].forEach(function (pair) {
      var button = document.createElement('button');
      button.type = 'button'; button.textContent = pair[1];
      button.addEventListener('click', function () {
        if (pair[0] === 'neutral') eyes.setSkin(skin);
        else eyes.event(pair[0]);
      });
      document.getElementById('emotions').appendChild(button);
    });
    document.getElementById('copy').addEventListener('click', async function () {
      try { await navigator.clipboard.writeText(text); status.textContent = 'JSON скопирован'; }
      catch (e) { status.textContent = 'Копирование недоступно в этом браузере'; }
    });
    document.getElementById('background').addEventListener('click', function () {
      var light = stage.classList.toggle('light'); PE.setDark(!light);
      this.textContent = light ? 'Тёмный фон' : 'Светлый фон';
    });
    status.textContent = '';
  }
  load().catch(function (e) { status.textContent = 'Не удалось открыть предпросмотр: ' + e.message; });
})();
