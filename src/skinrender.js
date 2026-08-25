/**
 * Skin rendering.
 *
 * Two things live here:
 *
 *  - `createSkinViewer`, a small 3D viewer the user can spin with the mouse.
 *  - `renderSkinFlat`, the older flat body composition, still used for the
 *    small thumbnails in the skin library where a 3D render would be wasted.
 *
 * The 3D part is deliberately a few hundred lines of canvas 2D rather than a
 * WebGL library. The model is nothing but axis-aligned boxes, and under an
 * orthographic camera every face of a rotated box projects to a parallelogram -
 * which `ctx.setTransform` can map a texture rectangle onto exactly. So the
 * result is pixel-accurate with no perspective to fake, no shader pipeline, and
 * no dependency that would have to be bundled or fetched at runtime.
 *
 * Skin layout is the modern 64x64 format. Legacy 64x32 skins have no left arm
 * or leg of their own, so those are mirrored from the right side.
 */

// ---------------- model ----------------

/**
 * UV origins of each part in the 64x64 sheet, in Minecraft's own layout.
 * A box unwraps as top and bottom along the first row, then right, front, left
 * and back along the second.
 */
const UV = {
  head: [0, 0],
  hat: [32, 0],
  body: [16, 16],
  bodyOver: [16, 32],
  armR: [40, 16],
  armROver: [40, 32],
  armL: [32, 48],
  armLOver: [48, 48],
  legR: [0, 16],
  legROver: [0, 32],
  legL: [16, 48],
  legLOver: [0, 48],
};

/**
 * The six faces of a box.
 *
 * Each face is an origin corner plus the two directions that span it, in local
 * box space (-0.5 .. 0.5 per axis). That is exactly what the affine texture
 * mapping wants: the origin is where the top-left texel lands, and the two
 * directions say where the texture's u and v run.
 *
 * `mirror` flips every face horizontally and swaps left with right, which is
 * how Minecraft builds the missing limbs of a legacy skin.
 */
function boxFaces(box, mirror) {
  const { w, h, d, uv } = box;
  const [u, v] = uv;

  const faces = [
    // top (+y)
    { s: [u + d, v, w, d], o: [-0.5, 0.5, -0.5], du: [1, 0, 0], dv: [0, 0, 1] },
    // bottom (-y), read from the back edge to match the unwrap
    { s: [u + d + w, v, w, d], o: [-0.5, -0.5, 0.5], du: [1, 0, 0], dv: [0, 0, -1] },
    // the character's right side (-x)
    { s: [u, v + d, d, h], o: [-0.5, 0.5, -0.5], du: [0, 0, 1], dv: [0, -1, 0] },
    // front (+z), the face
    { s: [u + d, v + d, w, h], o: [-0.5, 0.5, 0.5], du: [1, 0, 0], dv: [0, -1, 0] },
    // the character's left side (+x)
    { s: [u + d + w, v + d, d, h], o: [0.5, 0.5, 0.5], du: [0, 0, -1], dv: [0, -1, 0] },
    // back (-z)
    { s: [u + 2 * d + w, v + d, w, h], o: [0.5, 0.5, -0.5], du: [-1, 0, 0], dv: [0, -1, 0] },
  ];

  if (!mirror) return faces;

  const flipped = faces.map((f) => ({
    s: f.s,
    o: [f.o[0] + f.du[0], f.o[1] + f.du[1], f.o[2] + f.du[2]],
    du: [-f.du[0], -f.du[1], -f.du[2]],
    dv: f.dv,
  }));
  const tmp = flipped[2];
  flipped[2] = flipped[4];
  flipped[4] = tmp;
  return flipped;
}

/**
 * Builds the body out of boxes. Sizes are Minecraft units with the origin
 * between the feet, so the figure stands from y = 0 to y = 32.
 */
function buildModel(slim, legacy) {
  const armW = slim ? 3 : 4;
  const armX = 4 + armW / 2;

  const boxes = [
    { name: "head", w: 8, h: 8, d: 8, pos: [0, 28, 0], uv: UV.head, mirror: false },
    { name: "body", w: 8, h: 12, d: 4, pos: [0, 18, 0], uv: UV.body, mirror: false },
    { name: "armR", w: armW, h: 12, d: 4, pos: [-armX, 18, 0], uv: UV.armR, mirror: false },
    {
      name: "armL",
      w: armW, h: 12, d: 4,
      pos: [armX, 18, 0],
      uv: legacy ? UV.armR : UV.armL,
      mirror: legacy,
    },
    { name: "legR", w: 4, h: 12, d: 4, pos: [-2, 6, 0], uv: UV.legR, mirror: false },
    {
      name: "legL",
      w: 4, h: 12, d: 4,
      pos: [2, 6, 0],
      uv: legacy ? UV.legR : UV.legL,
      mirror: legacy,
    },
  ];

  // Overlay layers sit slightly outside the base ones, the hat thickest of all
  // - the same proportions Minecraft itself uses.
  const overlays = [{ base: "head", uv: UV.hat, grow: 1, mirror: false }];
  if (!legacy) {
    overlays.push(
      { base: "body", uv: UV.bodyOver, grow: 0.5, mirror: false },
      { base: "armR", uv: UV.armROver, grow: 0.5, mirror: false },
      { base: "armL", uv: UV.armLOver, grow: 0.5, mirror: false },
      { base: "legR", uv: UV.legROver, grow: 0.5, mirror: false },
      { base: "legL", uv: UV.legLOver, grow: 0.5, mirror: false }
    );
  }

  const all = boxes.map((b) => ({ ...b, layer: 0 }));
  for (const over of overlays) {
    const base = boxes.find((b) => b.name === over.base);
    if (!base) continue;
    all.push({
      ...base,
      name: base.name + "Over",
      w: base.w + over.grow,
      h: base.h + over.grow,
      d: base.d + over.grow,
      uv: over.uv,
      mirror: over.mirror,
      layer: 1,
    });
  }
  return all;
}

// ---------------- projection ----------------

/** Orthographic: rotate around Y, then tilt around X, then drop the depth. */
function project(point, yaw, pitch, scale, cx, cy) {
  const [x, y, z] = point;

  const cosY = Math.cos(yaw);
  const sinY = Math.sin(yaw);
  const rx = x * cosY + z * sinY;
  const rz = -x * sinY + z * cosY;

  const cosP = Math.cos(pitch);
  const sinP = Math.sin(pitch);
  const ry = y * cosP - rz * sinP;
  const depth = y * sinP + rz * cosP;

  return { x: cx + rx * scale, y: cy - ry * scale, depth };
}

// ---------------- viewer ----------------

/**
 * Turns a canvas into a skin viewer that can be dragged to rotate.
 *
 * Returns a handle rather than drawing once, because the canvas has to be
 * redrawn on every mouse move and callers should not have to know that.
 */
export function createSkinViewer(canvas) {
  const state = {
    image: null,
    model: [],
    // Facing the viewer, tilted a few degrees so the figure does not read flat.
    yaw: 0,
    pitch: -0.12,
    dragging: false,
    lastX: 0,
    lastY: 0,
    frame: null,
  };

  const ctx = canvas.getContext("2d");

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const cssWidth = canvas.clientWidth || 200;
    const cssHeight = canvas.clientHeight || 260;
    const wantW = Math.round(cssWidth * dpr);
    const wantH = Math.round(cssHeight * dpr);
    if (canvas.width !== wantW || canvas.height !== wantH) {
      canvas.width = wantW;
      canvas.height = wantH;
    }
  }

  function draw() {
    state.frame = null;
    resize();

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!state.image) return;

    ctx.imageSmoothingEnabled = false;

    // 32 units tall plus a little air above and below.
    const scale = canvas.height / 38;
    const cx = canvas.width / 2;
    const cy = canvas.height / 2 + 16 * scale;

    const quads = [];
    for (const box of state.model) {
      for (const face of boxFaces(box, box.mirror)) {
        const corner = (fu, fv) => [
          box.pos[0] + (face.o[0] + face.du[0] * fu + face.dv[0] * fv) * box.w,
          box.pos[1] + (face.o[1] + face.du[1] * fu + face.dv[1] * fv) * box.h,
          box.pos[2] + (face.o[2] + face.du[2] * fu + face.dv[2] * fv) * box.d,
        ];

        const a = project(corner(0, 0), state.yaw, state.pitch, scale, cx, cy);
        const b = project(corner(1, 0), state.yaw, state.pitch, scale, cx, cy);
        const c = project(corner(1, 1), state.yaw, state.pitch, scale, cx, cy);
        const d = project(corner(0, 1), state.yaw, state.pitch, scale, cx, cy);

        // Back-face culling by winding order. Without it the inside of the far
        // side of every box gets drawn and then painted over, which costs twice
        // the work and shows through wherever an overlay is transparent.
        const area = (b.x - a.x) * (d.y - a.y) - (b.y - a.y) * (d.x - a.x);
        if (area <= 0) continue;

        quads.push({
          a, b, d,
          s: face.s,
          depth: (a.depth + b.depth + c.depth + d.depth) / 4,
          layer: box.layer,
        });
      }
    }

    // Painter's algorithm: far faces first. Overlays lose ties, so a hat never
    // sinks into the head it sits on.
    quads.sort((p, q) => p.depth - q.depth || p.layer - q.layer);

    for (const quad of quads) {
      const [sx, sy, sw, sh] = quad.s;

      // Map the source rectangle onto the projected parallelogram: local
      // (0,0) -> a, (sw,0) -> b, (0,sh) -> d.
      let ax = (quad.b.x - quad.a.x) / sw;
      let ay = (quad.b.y - quad.a.y) / sw;
      let bx = (quad.d.x - quad.a.x) / sh;
      let by = (quad.d.y - quad.a.y) / sh;

      // Grow each face by half a texel around its origin. Neighbouring faces
      // meet at exactly the same edge, and without this the seam between them
      // shows up as a hairline of background.
      const grow = 0.5;
      const ox = quad.a.x - ax * grow - bx * grow;
      const oy = quad.a.y - ay * grow - by * grow;
      const gx = (sw + grow * 2) / sw;
      const gy = (sh + grow * 2) / sh;

      ctx.setTransform(ax * gx, ay * gx, bx * gy, by * gy, ox, oy);
      ctx.drawImage(state.image, sx, sy, sw, sh, 0, 0, sw, sh);
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
  }

  function schedule() {
    if (state.frame === null) state.frame = requestAnimationFrame(draw);
  }

  // ---- mouse ----
  function onDown(event) {
    state.dragging = true;
    state.lastX = event.clientX;
    state.lastY = event.clientY;
    canvas.classList.add("grabbing");
    if (canvas.setPointerCapture) canvas.setPointerCapture(event.pointerId);
  }

  function onMove(event) {
    if (!state.dragging) return;
    const dx = event.clientX - state.lastX;
    const dy = event.clientY - state.lastY;
    state.lastX = event.clientX;
    state.lastY = event.clientY;

    state.yaw += dx * 0.01;
    // Clamped so the model can be viewed from above or below but never tipped
    // past vertical, where the controls would feel inverted.
    state.pitch = Math.max(-1.2, Math.min(1.2, state.pitch + dy * 0.01));
    schedule();
  }

  function onUp(event) {
    if (!state.dragging) return;
    state.dragging = false;
    canvas.classList.remove("grabbing");
    if (canvas.releasePointerCapture) {
      try {
        canvas.releasePointerCapture(event.pointerId);
      } catch (e) {
        /* the pointer was already released */
      }
    }
  }

  canvas.addEventListener("pointerdown", onDown);
  canvas.addEventListener("pointermove", onMove);
  canvas.addEventListener("pointerup", onUp);
  canvas.addEventListener("pointercancel", onUp);
  canvas.addEventListener("pointerleave", onUp);
  canvas.addEventListener("dblclick", () => {
    state.yaw = 0;
    state.pitch = -0.12;
    schedule();
  });

  const onResize = () => schedule();
  window.addEventListener("resize", onResize);

  return {
    /** Points the viewer at a texture. Rotation survives a skin change. */
    async setSkin(imageUrl, slim = false) {
      const image = await loadImage(imageUrl);
      state.image = image;
      state.model = buildModel(slim, image.height === 32);
      schedule();
    },
    reset() {
      state.yaw = 0;
      state.pitch = -0.12;
      schedule();
    },
    redraw: schedule,
    destroy() {
      window.removeEventListener("resize", onResize);
      if (state.frame !== null) cancelAnimationFrame(state.frame);
    },
  };
}

// ---------------- flat render ----------------

const PARTS_64 = {
  head:      { x: 8,  y: 8,  w: 8, h: 8 },
  headOver:  { x: 40, y: 8,  w: 8, h: 8 },
  body:      { x: 20, y: 20, w: 8, h: 12 },
  bodyOver:  { x: 20, y: 36, w: 8, h: 12 },
  armR:      { x: 44, y: 20, w: 4, h: 12 },
  armROver:  { x: 44, y: 36, w: 4, h: 12 },
  armL:      { x: 36, y: 52, w: 4, h: 12 },
  armLOver:  { x: 52, y: 52, w: 4, h: 12 },
  legR:      { x: 4,  y: 20, w: 4, h: 12 },
  legROver:  { x: 4,  y: 36, w: 4, h: 12 },
  legL:      { x: 20, y: 52, w: 4, h: 12 },
  legLOver:  { x: 4,  y: 52, w: 4, h: 12 },
};

/**
 * Draws a skin as a flat front-facing body. Cheap enough to run for every
 * thumbnail in the library grid.
 *
 * @param canvas   target canvas element
 * @param imageUrl skin texture URL
 * @param slim     true for the 3px-wide arm model
 * @param scale    canvas pixels per skin pixel
 */
export async function renderSkinFlat(canvas, imageUrl, slim = false, scale = 8) {
  const image = await loadImage(imageUrl);
  const legacy = image.height === 32;

  const SCALE = scale;
  const armWidth = slim ? 3 : 4;

  canvas.width = (armWidth * 2 + 8) * SCALE;
  canvas.height = 32 * SCALE;

  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  const originX = armWidth * SCALE;

  const draw = (part, dx, dy, dw, dh) => {
    if (!part) return;
    ctx.drawImage(image, part.x, part.y, part.w, part.h, dx, dy, dw, dh);
  };

  const p = PARTS_64;

  const armSrc = { ...p.armR, w: armWidth };
  draw(armSrc, 0, 8 * SCALE, armWidth * SCALE, 12 * SCALE);

  const armLeftSrc = legacy ? { ...p.armR, w: armWidth } : { ...p.armL, w: armWidth };
  draw(armLeftSrc, originX + 8 * SCALE, 8 * SCALE, armWidth * SCALE, 12 * SCALE);

  draw(p.body, originX, 8 * SCALE, 8 * SCALE, 12 * SCALE);
  draw(p.legR, originX, 20 * SCALE, 4 * SCALE, 12 * SCALE);
  draw(legacy ? p.legR : p.legL, originX + 4 * SCALE, 20 * SCALE, 4 * SCALE, 12 * SCALE);

  draw(p.head, originX, 0, 8 * SCALE, 8 * SCALE);

  if (!legacy) {
    draw(p.headOver, originX, 0, 8 * SCALE, 8 * SCALE);
    draw(p.bodyOver, originX, 8 * SCALE, 8 * SCALE, 12 * SCALE);
    draw({ ...p.armROver, w: armWidth }, 0, 8 * SCALE, armWidth * SCALE, 12 * SCALE);
    draw({ ...p.armLOver, w: armWidth }, originX + 8 * SCALE, 8 * SCALE, armWidth * SCALE, 12 * SCALE);
    draw(p.legROver, originX, 20 * SCALE, 4 * SCALE, 12 * SCALE);
    draw(p.legLOver, originX + 4 * SCALE, 20 * SCALE, 4 * SCALE, 12 * SCALE);
  }
}

/** Kept under the old name so existing callers keep working. */
export const renderSkin = renderSkinFlat;

/** Draws just the front face of a cape, which is the left third of the sheet. */
export async function renderCape(canvas, imageUrl) {
  const image = await loadImage(imageUrl);

  // Cape sheets are 64x32 with the front at (1,1) sized 10x16
  const scaleX = image.width / 64;
  const scaleY = image.height / 32;

  const SCALE = 6;
  canvas.width = 10 * SCALE;
  canvas.height = 16 * SCALE;

  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(
    image,
    1 * scaleX, 1 * scaleY, 10 * scaleX, 16 * scaleY,
    0, 0, canvas.width, canvas.height
  );
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("Could not load the skin texture"));
    image.src = url;
  });
}
