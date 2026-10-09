// Те же кадры, что JsParity.java, но переносом на JS. Запуск: node eyes/test/parity.js skin1.json ...
'use strict';
const fs = require('fs');
const path = require('path');
const ctx = {};
// в проекте сайт лежит в repo/, в опубликованном репозитории исходники в source/ внутри сайта
const site = fs.existsSync(path.join(__dirname, '..', 'repo')) ? path.join(__dirname, '..', 'repo') : path.join(__dirname, '..', '..');
new Function(fs.readFileSync(path.join(site, 'assets', 'eyes.js'), 'utf8')).call(ctx);
const PE = ctx.PixelEyes;

const STATES = ['rest', 'look', 'half', 'closed', 'happy', 'wide', 'narrow', 'heart', 'spiral', 'ouch', 'angry', 'sad',
  'frame_happy', 'frame_closed', 'particles', 'shake'];

function f32(v) {
  return Math.fround(v);
}

function setup(f, st, n) {
  for (let i = 0; i < n; i++) {
    switch (st) {
      case 'look': f.gx[i] = 0.7; f.gy[i] = -0.45; break;
      case 'half': f.openTop[i] = 0.5; f.gx[i] = -0.3; break;
      case 'closed': f.openTop[i] = 0; break;
      case 'happy': f.openTop[i] = 0; f.arcBot[i] = 1; break;
      case 'angry': f.openTop[i] = 0.62; f.openBot[i] = 0.95; break;
      case 'sad': f.openTop[i] = 0.72; f.openBot[i] = 0.92; f.gy[i] = 0.5; break;
      default: break;
    }
  }
  switch (st) {
    case 'wide': f.pupil = f32(1.3); break;
    case 'narrow': f.pupil = f32(0.55); break;
    case 'heart': f.pupilMode = 1; break;
    case 'spiral': f.pupilMode = 2; f.time = f32(0.37); break;
    case 'ouch': f.pupilMode = 3; break;
    case 'angry': f.slant = f32(0.85); f.pupil = f32(0.75); break;
    case 'sad': f.slant = f32(-0.75); f.pupil = f32(1.15); break;
    case 'frame_happy': f.frame = 'happy'; break;
    case 'frame_closed': f.frame = 'closed'; break;
    case 'shake': f.shakeX = 1; f.shakeY = -1; break;
    case 'particles':
      for (let t = 0; t < 8; t++) {
        f.particles.push({type: t, x: f32(4 + f32(t * f32(3.3))), y: f32(3.6), age: f32(t * f32(0.1)), life: f32(1.2), vx: 0, vy: 0});
      }
      break;
    default: break;
  }
}

function hash(px) {
  let h = 1469598103934665603n;
  const M = (1n << 64n) - 1n;
  for (const c of px) {
    h = ((h ^ BigInt(c >>> 0)) * 1099511628211n) & M;
  }
  return h.toString(16);
}

for (const file of process.argv.slice(2)) {
  const s = PE.parseSkin(fs.readFileSync(file, 'utf8'));
  const r = new PE.Renderer(s);
  const name = path.basename(file);
  for (const st of STATES) {
    for (let edge = 0; edge <= 2; edge++) {
      const f = new PE.Face(s.places.length);
      setup(f, st, s.places.length);
      r.edge = edge;
      const out = r.render(f);
      console.log(`${name} ${st} ${edge} ${r.cw}x${r.ch} ${hash(out)}`);
    }
  }
}
