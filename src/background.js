/*
 * The moving background behind the launcher, one scene per colour theme.
 *
 * Drawn on a single canvas under everything else, and kept cheap on purpose:
 * a launcher is open next to a game, and a background that costs frames in
 * that game is a background nobody asked for. So it draws at most thirty
 * times a second, at no more than 1.5x the screen's pixel density, with a few
 * dozen shapes - and it stops entirely while the window is hidden, while a
 * game is running, while the system asks for reduced motion, and when it is
 * switched off in the settings.
 *
 * Colours come from the theme's own CSS variables (--anim-a and --anim-b), so
 * a scene never has to know which theme it is in.
 */

const FRAME_MS = 1000 / 24;

/** While the launcher is not the window in front: still alive, barely costing. */
const IDLE_FRAME_MS = 1000 / 8;

/*
 * The canvas is drawn at half the window's size and stretched. Everything in
 * these scenes is soft - clouds, glows, ribbons, specks - so it looks the same
 * and costs a quarter of the pixels, which matters most on the machines that
 * draw without a graphics card.
 */
const RESOLUTION = 0.5;

/** Except where the scene is made of hard points: stars must stay crisp. */
const SHARP = { space: 1 };

const rand = (a, b) => a + Math.random() * (b - a);
const TAU = Math.PI * 2;

/** "#rrggbb" or "rgb(r, g, b)" to [r, g, b]. */
function parseColor(text, fallback) {
  const value = (text || "").trim();
  let m = value.match(/^#([0-9a-f]{6})$/i);
  if (m) {
    const n = parseInt(m[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  m = value.match(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i);
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3])];
  return fallback;
}

const rgba = (c, a) => `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${a})`;

function mix(a, b, t) {
  return [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t));
}

/** A soft round light: a bright core fading to nothing. */
function glow(ctx, x, y, r, color, alpha) {
  const g = ctx.createRadialGradient(x, y, 0, x, y, r);
  g.addColorStop(0, rgba(color, alpha));
  g.addColorStop(1, rgba(color, 0));
  ctx.fillStyle = g;
  ctx.fillRect(x - r, y - r, r * 2, r * 2);
}

// ------------------------------------------------------------------ scenes

/** Space: stars in three depths, drifting slowly and twinkling. */
class Starfield {
  constructor(w, h) {
    this.stars = Array.from({ length: 150 }, () => ({
      x: rand(0, w), y: rand(0, h),
      depth: Math.random() < 0.6 ? 0.3 : Math.random() < 0.7 ? 0.6 : 1,
      phase: rand(0, TAU), speed: rand(0.6, 1.8),
    }));
  }
  step(dt, w, h) {
    for (const s of this.stars) {
      s.x -= dt * 6 * s.depth;
      s.phase += dt * s.speed;
      if (s.x < -2) { s.x = w + 2; s.y = rand(0, h); }
    }
  }
  draw(ctx, w, h, a, b) {
    for (const s of this.stars) {
      const alpha = (0.25 + 0.35 * s.depth) * (0.65 + 0.35 * Math.sin(s.phase));
      const size = 0.6 + s.depth * 1.1;
      ctx.fillStyle = rgba(mix(a, b, s.depth * 0.5), alpha);
      ctx.fillRect(s.x, s.y, size, size);
    }
  }
}

/** Nebula: large coloured clouds drifting over a thin field of stars. */
class Nebula {
  constructor(w, h) {
    this.clouds = Array.from({ length: 5 }, (_, i) => ({
      cx: rand(0.1, 0.9), cy: rand(0.1, 0.9),
      rx: rand(0.08, 0.2), ry: rand(0.06, 0.16),
      r: rand(0.35, 0.6), t: rand(0, TAU), speed: rand(0.03, 0.07),
      tone: i / 4,
    }));
    this.stars = new Starfield(w, h);
    this.stars.stars.length = 70;
  }
  step(dt, w, h) {
    for (const c of this.clouds) c.t += dt * c.speed;
    this.stars.step(dt * 0.5, w, h);
  }
  draw(ctx, w, h, a, b) {
    ctx.globalCompositeOperation = "lighter";
    const size = Math.max(w, h);
    for (const c of this.clouds) {
      const x = (c.cx + Math.sin(c.t) * c.rx) * w;
      const y = (c.cy + Math.cos(c.t * 0.8) * c.ry) * h;
      glow(ctx, x, y, c.r * size, mix(a, b, c.tone), 0.13);
    }
    ctx.globalCompositeOperation = "source-over";
    this.stars.draw(ctx, w, h, [255, 255, 255], b);
  }
}

/** Ocean: ribbons of light swaying like an aurora over deep water. */
class Aurora {
  constructor() {
    this.bands = Array.from({ length: 4 }, (_, i) => ({
      base: 0.18 + i * 0.13, amp: rand(0.03, 0.07), freq: rand(1.2, 2.4),
      speed: rand(0.15, 0.3), thick: rand(0.05, 0.1), t: rand(0, TAU), tone: i / 3,
    }));
    this.bubbles = [];
  }
  step(dt, w, h) {
    for (const band of this.bands) band.t += dt * band.speed;
    if (this.bubbles.length < 26 && Math.random() < dt * 3) {
      this.bubbles.push({ x: rand(0, w), y: h + 10, r: rand(1, 3), v: rand(12, 30), p: rand(0, TAU) });
    }
    for (const bub of this.bubbles) { bub.y -= bub.v * dt; bub.p += dt; }
    this.bubbles = this.bubbles.filter((bub) => bub.y > -10);
  }
  draw(ctx, w, h, a, b) {
    ctx.globalCompositeOperation = "lighter";
    const steps = 48;
    for (const band of this.bands) {
      const color = mix(a, b, band.tone);
      const top = [];
      for (let i = 0; i <= steps; i++) {
        const x = (i / steps) * w;
        const y = (band.base + Math.sin(i / steps * band.freq * TAU + band.t) * band.amp
          + Math.sin(i / steps * 7 + band.t * 1.7) * band.amp * 0.25) * h;
        top.push([x, y]);
      }
      const g = ctx.createLinearGradient(0, (band.base - band.amp) * h, 0, (band.base + band.amp + band.thick) * h);
      g.addColorStop(0, rgba(color, 0));
      g.addColorStop(0.5, rgba(color, 0.11));
      g.addColorStop(1, rgba(color, 0));
      ctx.fillStyle = g;
      ctx.beginPath();
      top.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      for (let i = top.length - 1; i >= 0; i--) ctx.lineTo(top[i][0], top[i][1] + band.thick * h);
      ctx.closePath();
      ctx.fill();
    }
    ctx.globalCompositeOperation = "source-over";
    for (const bub of this.bubbles) {
      ctx.strokeStyle = rgba(b, 0.25);
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(bub.x + Math.sin(bub.p) * 4, bub.y, bub.r, 0, TAU);
      ctx.stroke();
    }
  }
}

/** Ember: sparks rising from below, swaying and cooling as they go. */
class Embers {
  constructor(w, h) {
    this.sparks = Array.from({ length: 60 }, () => this.spawn(w, h, rand(0, h)));
  }
  spawn(w, h, y = h + 8) {
    return { x: rand(0, w), y, v: rand(18, 46), r: rand(0.8, 2.4), p: rand(0, TAU), heat: rand(0, 1) };
  }
  step(dt, w, h) {
    for (let i = 0; i < this.sparks.length; i++) {
      const s = this.sparks[i];
      s.y -= s.v * dt;
      s.p += dt * 1.6;
      s.x += Math.sin(s.p) * dt * 12;
      if (s.y < -10) this.sparks[i] = this.spawn(w, h);
    }
  }
  draw(ctx, w, h, a, b) {
    ctx.globalCompositeOperation = "lighter";
    // A low warm haze along the bottom edge
    const haze = ctx.createLinearGradient(0, h, 0, h * 0.55);
    haze.addColorStop(0, rgba(a, 0.12));
    haze.addColorStop(1, rgba(a, 0));
    ctx.fillStyle = haze;
    ctx.fillRect(0, h * 0.55, w, h * 0.45);
    for (const s of this.sparks) {
      const life = Math.max(0, Math.min(1, s.y / h));
      const color = mix(b, a, s.heat * 0.6 + (1 - life) * 0.4);
      glow(ctx, s.x, s.y, s.r * 6, color, 0.18 * life + 0.04);
      ctx.fillStyle = rgba(color, 0.55 * life + 0.15);
      ctx.fillRect(s.x - s.r / 2, s.y - s.r / 2, s.r, s.r);
    }
    ctx.globalCompositeOperation = "source-over";
  }
}

/** Forest: fireflies wandering in the dark and slowly pulsing. */
class Fireflies {
  constructor(w, h) {
    this.flies = Array.from({ length: 42 }, () => ({
      x: rand(0, w), y: rand(h * 0.2, h), a: rand(0, TAU), v: rand(8, 22),
      p: rand(0, TAU), ps: rand(0.6, 1.4), r: rand(1.2, 2.4),
    }));
  }
  step(dt, w, h) {
    for (const f of this.flies) {
      f.a += rand(-1.4, 1.4) * dt;
      f.x += Math.cos(f.a) * f.v * dt;
      f.y += Math.sin(f.a) * f.v * dt * 0.6;
      f.p += dt * f.ps;
      if (f.x < -20) f.x = w + 20;
      if (f.x > w + 20) f.x = -20;
      if (f.y < h * 0.1) f.a = Math.abs(f.a);
      if (f.y > h + 10) f.a = -Math.abs(f.a);
    }
  }
  draw(ctx, w, h, a, b) {
    ctx.globalCompositeOperation = "lighter";
    const ground = ctx.createLinearGradient(0, h, 0, h * 0.6);
    ground.addColorStop(0, rgba(a, 0.07));
    ground.addColorStop(1, rgba(a, 0));
    ctx.fillStyle = ground;
    ctx.fillRect(0, h * 0.6, w, h * 0.4);
    for (const f of this.flies) {
      const pulse = Math.max(0, Math.sin(f.p));
      glow(ctx, f.x, f.y, f.r * 9, b, 0.22 * pulse + 0.02);
      ctx.fillStyle = rgba(mix(a, b, 0.6), 0.3 + 0.6 * pulse);
      ctx.beginPath();
      ctx.arc(f.x, f.y, f.r * 0.7, 0, TAU);
      ctx.fill();
    }
    ctx.globalCompositeOperation = "source-over";
  }
}

/** Sakura: petals falling and turning in a light breeze. */
class Petals {
  constructor(w, h) {
    this.petals = Array.from({ length: 34 }, () => this.spawn(w, h, rand(-h, h)));
  }
  spawn(w, h, y = -12) {
    return {
      x: rand(-w * 0.2, w), y, vy: rand(14, 30), vx: rand(6, 16),
      rot: rand(0, TAU), spin: rand(-1.2, 1.2), sway: rand(0, TAU),
      size: rand(3, 6), tone: Math.random(),
    };
  }
  step(dt, w, h) {
    for (let i = 0; i < this.petals.length; i++) {
      const p = this.petals[i];
      p.sway += dt;
      p.y += p.vy * dt;
      p.x += (p.vx + Math.sin(p.sway) * 10) * dt;
      p.rot += p.spin * dt;
      if (p.y > h + 12 || p.x > w + 20) this.petals[i] = this.spawn(w, h);
    }
  }
  draw(ctx, w, h, a, b) {
    for (const p of this.petals) {
      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rot);
      // The turn of the petal, as a squash of its width
      ctx.scale(Math.abs(Math.cos(p.sway * 1.3)) * 0.7 + 0.3, 1);
      ctx.fillStyle = rgba(mix(a, b, p.tone), 0.42);
      ctx.beginPath();
      ctx.ellipse(0, 0, p.size * 0.6, p.size, 0, 0, TAU);
      ctx.fill();
      ctx.restore();
    }
  }
}

const SCENES = {
  space: Starfield,
  nebula: Nebula,
  ocean: Aurora,
  ember: Embers,
  forest: Fireflies,
  sakura: Petals,
};

// ------------------------------------------------------------------ runner

const state = {
  canvas: null,
  ctx: null,
  scene: null,
  theme: "",
  enabled: true,
  paused: false,
  running: false,
  last: 0,
  acc: 0,
  w: 0,
  h: 0,
  a: [255, 255, 255],
  b: [255, 255, 255],
};

const reducedMotion = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;

function resize() {
  const canvas = state.canvas;
  if (!canvas) return;
  const ratio = SHARP[state.theme] || RESOLUTION;
  state.w = window.innerWidth;
  state.h = window.innerHeight;
  canvas.width = Math.round(state.w * ratio);
  canvas.height = Math.round(state.h * ratio);
  canvas.style.width = state.w + "px";
  canvas.style.height = state.h + "px";
  state.ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  if (state.theme) state.scene = new SCENES[state.theme](state.w, state.h);
}

function readColors() {
  const css = getComputedStyle(document.documentElement);
  state.a = parseColor(css.getPropertyValue("--anim-a"), [255, 255, 255]);
  state.b = parseColor(css.getPropertyValue("--anim-b"), [200, 200, 210]);
}

function frame(now) {
  if (!state.running) return;
  requestAnimationFrame(frame);
  const dt = Math.min(0.1, (now - state.last) / 1000);
  const budget = document.hasFocus() ? FRAME_MS : IDLE_FRAME_MS;
  if (now - state.last < budget - 1) return;
  state.last = now;

  const { ctx, w, h, scene } = state;
  ctx.clearRect(0, 0, w, h);
  scene.step(dt, w, h);
  scene.draw(ctx, w, h, state.a, state.b);
}

function shouldRun() {
  return state.enabled && !state.paused && !document.hidden
    && !(reducedMotion && reducedMotion.matches) && !!state.scene;
}

function update() {
  const run = shouldRun();
  state.canvas.classList.toggle("on", run);
  if (run && !state.running) {
    state.running = true;
    state.last = performance.now();
    requestAnimationFrame(frame);
  } else if (!run && state.running) {
    state.running = false;
  }
}

/** Sets up the canvas once. */
export function initBackground() {
  if (state.canvas) return;
  const canvas = document.createElement("canvas");
  canvas.id = "bg-canvas";
  canvas.setAttribute("aria-hidden", "true");
  document.body.prepend(canvas);
  state.canvas = canvas;
  state.ctx = canvas.getContext("2d");
  resize();
  window.addEventListener("resize", resize);
  document.addEventListener("visibilitychange", update);
  if (reducedMotion && reducedMotion.addEventListener) reducedMotion.addEventListener("change", update);
}

/** Switches the scene to a theme, and turns it on or off. */
export function setBackground(theme, enabled) {
  initBackground();
  const name = SCENES[theme] ? theme : "space";
  state.enabled = enabled;
  if (name !== state.theme) {
    state.theme = name;
    resize();
  }
  readColors();
  update();
}

/** Holds the scene still - while a game is running, every frame belongs to it. */
export function pauseBackground(paused) {
  state.paused = paused;
  if (state.canvas) update();
}

export const THEMES = Object.keys(SCENES);
