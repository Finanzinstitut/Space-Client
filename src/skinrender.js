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
 * Note that the source rectangles are built from `w/h/d`, the box's *texture*
 * size, never from its geometric size. Those two are not the same thing: an
 * overlay layer is drawn slightly larger than the part it covers while still
 * reading the same 8x8 patch of the sheet. Mixing them up means reading nine
 * texels where there are eight - or half a texel, for the 0.5-unit layers -
 * and the box picks up stray pixels from whatever sits next to it in the sheet.
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
 * Builds one box.
 *
 * `grow` inflates the geometry without touching the texture size, which is what
 * the overlay layers need. `pivot` is the point the box rotates around, given
 * relative to its own centre - the cape hangs from its top edge, not its middle.
 */
function makeBox(opts) {
  const grow = opts.grow || 0;
  return {
    name: opts.name,
    // Texture size, in texels.
    w: opts.w,
    h: opts.h,
    d: opts.d,
    // Geometric size, in model units.
    gw: opts.w + grow,
    gh: opts.h + grow,
    gd: opts.d + grow,
    pos: opts.pos,
    uv: opts.uv,
    mirror: opts.mirror || false,
    layer: opts.layer || 0,
    tex: opts.tex || "skin",
    rotX: opts.rotX || 0,
    rotY: opts.rotY || 0,
    /** Sideways swing. An arm lifts away from the body around this one. */
    rotZ: opts.rotZ || 0,
    pivot: opts.pivot || [0, 0, 0],
  };
}

/**
 * Builds the body out of boxes. Sizes are Minecraft units with the origin
 * between the feet, so the figure stands from y = 0 to y = 32.
 */
function buildModel(slim, legacy, hasCape) {
  const armW = slim ? 3 : 4;
  const armX = 4 + armW / 2;

  const parts = [
    { name: "head", w: 8, h: 8, d: 8, pos: [0, 28, 0], uv: UV.head },
    { name: "body", w: 8, h: 12, d: 4, pos: [0, 18, 0], uv: UV.body },
    { name: "armR", w: armW, h: 12, d: 4, pos: [-armX, 18, 0], uv: UV.armR },
    {
      name: "armL",
      w: armW, h: 12, d: 4,
      pos: [armX, 18, 0],
      uv: legacy ? UV.armR : UV.armL,
      mirror: legacy,
    },
    { name: "legR", w: 4, h: 12, d: 4, pos: [-2, 6, 0], uv: UV.legR },
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
  const overlays = [{ base: "head", uv: UV.hat, grow: 1 }];
  if (!legacy) {
    overlays.push(
      { base: "body", uv: UV.bodyOver, grow: 0.5 },
      { base: "armR", uv: UV.armROver, grow: 0.5 },
      { base: "armL", uv: UV.armLOver, grow: 0.5 },
      { base: "legR", uv: UV.legROver, grow: 0.5 },
      { base: "legL", uv: UV.legLOver, grow: 0.5 }
    );
  }

  const boxes = parts.map((p) => makeBox(p));

  for (const over of overlays) {
    const base = parts.find((p) => p.name === over.base);
    if (!base) continue;
    boxes.push(
      makeBox({
        ...base,
        name: base.name + "Over",
        uv: over.uv,
        grow: over.grow,
        // Overlays of a mirrored limb read from the mirrored slot too, except
        // on legacy skins, which have no overlay for those limbs at all.
        mirror: base.mirror,
        layer: 1,
      })
    );
  }

  if (hasCape) {
    // 10x16x1, hanging off the back of the torso and tilted away from it.
    // Turned 180 degrees so the printed side of the sheet faces outwards, and
    // pivoted at its top edge so the tilt swings the hem out rather than
    // pushing the shoulders through the body.
    boxes.push(
      makeBox({
        name: "cape",
        w: 10, h: 16, d: 1,
        pos: [0, 16, -2.5],
        uv: [0, 0],
        tex: "cape",
        rotY: Math.PI,
        rotX: 0.17,
        pivot: [0, 8, 0],
        layer: 0,
      })
    );
  }

  return boxes;
}


// ---------------- idle animations ----------------

/**
 * Where each limb turns from.
 *
 * A box rotates around its own pivot, and a pivot left at the centre makes an
 * arm swing from its middle - the elbow ends up somewhere near the ear. These
 * are the joints: the neck under the head, the shoulders and hips at the top
 * of their limbs. Given relative to each box's own centre, which is what
 * placeCorner expects.
 */
const JOINTS = {
  head: [0, -4, 0],
  armR: [0, 6, 0],
  armL: [0, 6, 0],
  legR: [0, 6, 0],
  legL: [0, 6, 0],
};

/**
 * The animations, as functions of their own progress from 0 to 1.
 *
 * Each returns the angles for whichever parts it moves, in radians, and says
 * nothing about the rest - so two animations never fight over a limb neither
 * of them is using, and anything unmentioned simply rests.
 *
 * Written as pure functions of t on purpose: there is no state to get out of
 * step, an animation can be cut off at any point without leaving a limb
 * somewhere odd, and the whole set can be stepped through frame by frame to
 * look at, which is how these were checked.
 */
const ANIMATIONS = {
  /** Always running underneath: the small motion of somebody simply standing. */
  breathe(t) {
    const s = Math.sin(t * Math.PI * 2);
    return {
      armR: { rotX: s * 0.05 },
      armL: { rotX: -s * 0.05 },
      head: { rotX: s * 0.02 },
    };
  },

  /**
   * The arm lifts out to the side and the hand rocks.
   *
   * Sideways, around Z, which is what a wave actually is - swinging the arm
   * forward around X reads as pointing at something. The rocking is on the same
   * axis so it looks hinged at the shoulder rather than twisting in the socket.
   */
  wave(t) {
    const lift = Math.min(1, t * 4);
    const drop = t > 0.8 ? (t - 0.8) * 5 : 0;
    const raise = Math.max(0, lift - drop);
    const rock = t > 0.2 && t < 0.85 ? Math.sin(t * Math.PI * 10) * 0.3 : 0;
    // Positive on the left arm, negative on the right. The signs were the
    // other way round and every raised arm swung across the chest and through
    // the head instead of out to the side - which is what a wave looked like
    // from the front: the figure putting its hand inside itself.
    return {
      armL: { rotZ: raise * 2.5 + rock, rotX: -raise * 0.25 },
      head: { rotY: 0.2 * raise },
    };
  },

  /** Looks left, then right, then front again. */
  lookAround(t) {
    return { head: { rotY: Math.sin(t * Math.PI * 2) * 0.6 } };
  },

  /**
   * A nod, with a lean in it.
   *
   * A pure nod turns the head around the axis the camera is looking down, so
   * almost nothing about the silhouette changes. The lean is what makes it
   * legible from the front; the nod is what stops the lean looking like a
   * twitch.
   */
  nod(t) {
    const beat = Math.abs(Math.sin(t * Math.PI * 2));
    // No sideways lean, however much better it would read. The model has no
    // neck between head and torso, so tilting the head swings its lower corner
    // clear of the shoulder and opens a gap - at which point it stops looking
    // like a nod and starts looking like a bug. A small turn carries the
    // movement instead: rotating about the upright axis keeps the head's base
    // flat on the body whatever it does.
    return { head: { rotX: beat * 0.45, rotY: Math.sin(t * Math.PI * 2) * 0.12 } };
  },

  /** Both arms up and out, the way you do after sitting too long. */
  stretch(t) {
    const up = Math.sin(Math.min(1, t) * Math.PI);
    return {
      armR: { rotZ: -up * 2.45, rotX: -up * 0.3 },
      armL: { rotZ: up * 2.45, rotX: -up * 0.3 },
      head: { rotX: -up * 0.25 },
    };
  },

  /**
   * A shrug: both arms lift away from the sides, the head sinks between them.
   *
   * This replaced a weight shift that swung the limbs forward and back. It was
   * perfectly correct and nearly invisible - the camera looks straight at the
   * figure and the projection is orthographic, so motion along the view axis
   * barely changes the outline. Anything meant to be noticed here has to move
   * sideways or up.
   */
  shrug(t) {
    const up = Math.sin(Math.min(1, t) * Math.PI);
    return {
      armR: { rotZ: -up * 0.45, rotX: -up * 0.15 },
      armL: { rotZ: up * 0.45, rotX: -up * 0.15 },
      head: { rotX: up * 0.12 },
    };
  },

  /** Turns to look over one shoulder and back. */
  glance(t) {
    const s = Math.sin(t * Math.PI);
    return { head: { rotY: -s * 0.9, rotX: s * 0.12 } };
  },
};

/** The ones picked at random. breathe is not among them; it always runs. */
const IDLE_PICKS = ["wave", "lookAround", "nod", "stretch", "shrug", "glance"];

/** Roughly how long each takes, in seconds. */
const DURATIONS = {
  wave: 2.6,
  lookAround: 4.0,
  nod: 1.6,
  stretch: 3.2,
  shrug: 2.2,
  glance: 2.4,
};

/**
 * Eases the ends of an animation so a limb is never snatched into position.
 *
 * Without this every animation begins and ends on a hard cut, which reads as a
 * glitch rather than a movement - most visibly on stretch, where the arms would
 * appear already halfway up.
 */
function blendIn(t) {
  const edge = 0.15;
  if (t < edge) return t / edge;
  if (t > 1 - edge) return (1 - t) / edge;
  return 1;
}

// ---------------- projection ----------------

/**
 * Places a corner in world space: rotate it around the box's own pivot, then
 * move it to where the box sits.
 */
function placeCorner(local, box) {
  let [x, y, z] = [
    local[0] - box.pivot[0],
    local[1] - box.pivot[1],
    local[2] - box.pivot[2],
  ];

  if (box.rotY) {
    const c = Math.cos(box.rotY);
    const s = Math.sin(box.rotY);
    [x, z] = [x * c + z * s, -x * s + z * c];
  }
  if (box.rotX) {
    const c = Math.cos(box.rotX);
    const s = Math.sin(box.rotX);
    [y, z] = [y * c - z * s, y * s + z * c];
  }
  // Last on purpose. The cape was placed with a Y turn and an X tilt long
  // before this axis existed, and rotations do not commute - putting Z
  // anywhere earlier would have quietly re-hung the cape.
  if (box.rotZ) {
    const c = Math.cos(box.rotZ);
    const s = Math.sin(box.rotZ);
    [x, y] = [x * c - y * s, x * s + y * c];
  }

  return [
    x + box.pivot[0] + box.pos[0],
    y + box.pivot[1] + box.pos[1],
    z + box.pivot[2] + box.pos[2],
  ];
}

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
/**
 * @param options.spin  Idle rotation in radians per second. 0 keeps the figure
 *                      still, which is what the skin editor wants - you are
 *                      inspecting a texture there, and a model that turns while
 *                      you look at a seam is a nuisance rather than a flourish.
 */
export function createSkinViewer(canvas, options = {}) {
  const spin = options.spin || 0;

  /**
   * How often the idle turn is allowed to draw a frame.
   *
   * Not every frame the screen offers. This figure is rasterised in software,
   * face by face, on the same thread as everything else - measured, it roughly
   * halves what the page has left over. A slow turn does not need sixty frames
   * a second to read as smooth, and the launcher sits open beside a running
   * game, where the frames belong to the game.
   */
  const minFrameMs = options.minFrameMs || 40;

  /**
   * Three separate reasons the figure might hold still, kept apart because they
   * answer to different people: two are preferences, one is the launcher
   * refusing to take frames from a running game. Dragging always works.
   */
  let allowSpin = options.spin !== 0;
  let allowAnimations = options.animations !== false;
  let quiet = false;

  const moving = () => !quiet && (allowSpin || allowAnimations);

  const state = {
    images: { skin: null, cape: null },
    /** Cape sheets come in multiples of 64x32, so UVs scale with the file. */
    texScale: { skin: 1, cape: 1 },
    model: [],
    // Facing the viewer, tilted a few degrees so the figure does not read flat.
    yaw: 0,
    pitch: 0.12,
    dragging: false,
    lastX: 0,
    lastY: 0,
    frame: null,
    idleTimer: null,
    lastTime: 0,
    /** The gesture playing right now, if any, and when the next one is due. */
    anim: null,
    nextAnimAt: 3,
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

    // Idle rotation.
    //
    // Scheduled here rather than after the drawing below, because the early
    // return for "no skin loaded yet" sits between the two - and a loop that
    // gave up during the moments before the texture arrived would never turn
    // at all. Dragging wins: the hand beats the animation while it is held.
    if (spin) {
      const now = performance.now();
      const step = state.lastTime ? Math.min(0.1, (now - state.lastTime) / 1000) : 0;
      state.lastTime = now;
      if (!state.dragging && allowSpin && !quiet) state.yaw += spin * step;
      applyPose(now);

      // A canvas in a hidden view or a minimised window animates nothing and
      // costs a whole core doing it, so the loop stops and the clock with it.
      const visible = !document.hidden && canvas.isConnected && canvas.offsetParent !== null;
      if (visible && moving()) {
        scheduleIdle();
      } else {
        state.lastTime = 0;
      }
    }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!state.images.skin) return;

    ctx.imageSmoothingEnabled = false;

    // 32 units tall plus a little air above and below.
    const scale = canvas.height / 38;
    const cx = canvas.width / 2;
    const cy = canvas.height / 2 + 16 * scale;

    const quads = [];
    for (const box of state.model) {
      const image = state.images[box.tex];
      if (!image) continue;
      const ts = state.texScale[box.tex];

      for (const face of boxFaces(box, box.mirror)) {
        const corner = (fu, fv) =>
          placeCorner(
            [
              (face.o[0] + face.du[0] * fu + face.dv[0] * fv) * box.gw,
              (face.o[1] + face.du[1] * fu + face.dv[1] * fv) * box.gh,
              (face.o[2] + face.du[2] * fu + face.dv[2] * fv) * box.gd,
            ],
            box
          );

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
          image,
          // Scaled here rather than at draw time, so a 128x64 cape sheet works
          // without every consumer knowing about it.
          s: face.s.map((n) => n * ts),
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
      ctx.drawImage(quad.image, sx, sy, sw, sh, 0, 0, sw, sh);
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
  }


  /** Strips the overlay suffix, so a sleeve turns with the arm inside it. */
  function baseName(name) {
    return name.endsWith("Over") ? name.slice(0, -4) : name;
  }

  /** Puts every animatable part back to rest and onto its joint. */
  function restJoints() {
    for (const box of state.model) {
      const joint = JOINTS[baseName(box.name)];
      if (!joint) continue;
      box.rotX = 0;
      box.rotY = 0;
      box.rotZ = 0;
      box.pivot = joint;
    }
  }

  /** Writes a finished pose onto the boxes. */
  function writePose(pose) {
    for (const box of state.model) {
      const angles = pose[baseName(box.name)];
      if (!angles) continue;
      box.rotX = angles.rotX;
      box.rotY = angles.rotY;
      box.rotZ = angles.rotZ;
    }
  }

  function addPose(into, from, weight) {
    for (const part of Object.keys(from)) {
      const target = into[part] || (into[part] = { rotX: 0, rotY: 0, rotZ: 0 });
      target.rotX += (from[part].rotX || 0) * weight;
      target.rotY += (from[part].rotY || 0) * weight;
      target.rotZ += (from[part].rotZ || 0) * weight;
    }
  }

  /**
   * Works out where every limb is this frame and writes it onto the boxes.
   *
   * Rest is applied first, every frame, to every part an animation could have
   * touched. Without that a gesture that ends - or is switched off midway -
   * leaves an arm wherever it happened to be, and the figure quietly collects
   * a new deformity for each one.
   */
  function applyPose(nowMs) {
    restJoints();

    if (quiet || !allowAnimations) return;

    const seconds = nowMs / 1000;
    const pose = {};

    // Always underneath: standing is not standing perfectly still.
    addPose(pose, ANIMATIONS.breathe((seconds / 4) % 1), 1);

    if (state.anim) {
      const t = (seconds - state.anim.start) / state.anim.dur;
      if (t >= 1) {
        state.anim = null;
        // A gap afterwards, so gestures do not run back to back.
        state.nextAnimAt = seconds + 4 + Math.random() * 7;
      } else {
        addPose(pose, ANIMATIONS[state.anim.name](t), blendIn(t));
      }
    } else if (seconds >= state.nextAnimAt) {
      const name = IDLE_PICKS[Math.floor(Math.random() * IDLE_PICKS.length)];
      state.anim = { name, start: seconds, dur: DURATIONS[name] || 2.5 };
    }

    for (const box of state.model) {
      const angles = pose[baseName(box.name)];
      if (!angles) continue;
      box.rotX = angles.rotX;
      box.rotY = angles.rotY;
      box.rotZ = angles.rotZ;
    }
  }

  /** One last frame with everything at rest, after movement is switched off. */
  function redrawRest() {
    state.anim = null;
    schedule();
  }

  function schedule() {
    if (state.frame === null) state.frame = requestAnimationFrame(draw);
  }

  /** The idle turn's own pacing, so it does not ask for every frame going. */
  function scheduleIdle() {
    if (state.idleTimer !== null || state.frame !== null) return;
    state.idleTimer = setTimeout(() => {
      state.idleTimer = null;
      schedule();
    }, minFrameMs);
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

  /**
   * Wakes the idle loop when the window comes back.
   *
   * The loop stops itself whenever the page is hidden, which is right - and
   * nothing started it again, which is why the figure sometimes simply stood
   * there. Alt-tab away and back and it was frozen until something else
   * happened to redraw it. Both events, because a window can be visible again
   * without a visibilitychange and focused again without being hidden first.
   */
  function wake() {
    if (moving()) {
      state.lastTime = 0;
      schedule();
    }
  }
  document.addEventListener("visibilitychange", wake);
  window.addEventListener("focus", wake);

  canvas.addEventListener("pointerdown", onDown);
  canvas.addEventListener("pointermove", onMove);
  canvas.addEventListener("pointerup", onUp);
  canvas.addEventListener("pointercancel", onUp);
  canvas.addEventListener("pointerleave", onUp);
  canvas.addEventListener("dblclick", () => {
    state.yaw = 0;
    state.pitch = 0.12;
    schedule();
  });

  const onResize = () => schedule();
  window.addEventListener("resize", onResize);

  return {
    /**
     * Points the viewer at a skin, and optionally at the cape that is active.
     * Rotation survives the change, so switching cape does not snap the model
     * back to front-on while the user is looking at the back of it.
     */
    async setSkin(imageUrl, slim = false, capeUrl = "") {
      const skin = await loadImage(imageUrl);
      state.images.skin = skin;
      state.texScale.skin = skin.width / 64;

      // A cape that fails to load is not worth failing the whole preview over.
      let cape = null;
      if (capeUrl) {
        cape = await loadImage(capeUrl).catch(() => null);
      }
      state.images.cape = cape;
      state.texScale.cape = cape ? cape.width / 64 : 1;

      state.model = buildModel(slim, skin.height === 32, !!cape);
      schedule();
    },
    reset() {
      state.yaw = 0;
      state.pitch = 0.12;
      schedule();
    },
    redraw: schedule,
    /**
     * Starts or stops the idle turn.
     *
     * Stopped while a game is running: a launcher quietly animating in the
     * background is taking frames from the thing it was opened to start.
     */
    setSpinning(on) {
      if (allowSpin === on) return;
      allowSpin = on;
      state.lastTime = 0;
      if (moving()) schedule(); else redrawRest();
    },

    setAnimations(on) {
      if (allowAnimations === on) return;
      allowAnimations = on;
      if (moving()) schedule(); else redrawRest();
    },

    /** Everything off while a game runs, whatever the preferences say. */
    setQuiet(on) {
      if (quiet === on) return;
      quiet = on;
      state.lastTime = 0;
      if (moving()) schedule(); else redrawRest();
    },

    /**
     * Holds one gesture at one point in its run, for looking at.
     *
     * Here for the same reason the mod has a diagnostics screen: a movement
     * that can only be judged by staring at it in motion cannot really be
     * judged at all. This pins a single frame so each pose can be rendered and
     * inspected - which is how the joints below were placed, and how the arm
     * that rotated from its middle instead of its shoulder was caught.
     */
    previewPose(name, t) {
      if (!ANIMATIONS[name]) return false;
      restJoints();
      const pose = {};
      addPose(pose, ANIMATIONS[name](t), 1);
      writePose(pose);
      schedule();
      return true;
    },

    destroy() {
      window.removeEventListener("resize", onResize);
      document.removeEventListener("visibilitychange", wake);
      window.removeEventListener("focus", wake);
      if (state.frame !== null) cancelAnimationFrame(state.frame);
      if (state.idleTimer !== null) clearTimeout(state.idleTimer);
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
