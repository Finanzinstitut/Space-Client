/**
 * Draws a Minecraft skin as a flat 2D body onto a canvas.
 *
 * This replaces the earlier third-party render service: the skin PNG is already
 * available from Mojang, so composing the body locally means the preview works
 * even when that service is slow, blocked, or has not caught up with a fresh
 * upload.
 *
 * Skin layout is the modern 64x64 format. Legacy 64x32 skins have no left arm
 * or leg of their own, so those are mirrored from the right side.
 */

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
 * @param canvas   target canvas element
 * @param imageUrl skin texture URL
 * @param slim     true for the 3px-wide arm model
 */
export async function renderSkin(canvas, imageUrl, slim = false) {
  const image = await loadImage(imageUrl);
  const legacy = image.height === 32;

  // One skin pixel becomes SCALE canvas pixels
  const SCALE = 8;
  const armWidth = slim ? 3 : 4;

  // Body is 16 wide (arm + torso + arm) and 32 tall (head + torso + legs)
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

  // Right arm (viewer's left)
  const armSrc = { ...p.armR, w: armWidth };
  draw(armSrc, 0, 8 * SCALE, armWidth * SCALE, 12 * SCALE);

  // Left arm - mirrored from the right one on legacy skins
  const armLeftSrc = legacy ? { ...p.armR, w: armWidth } : { ...p.armL, w: armWidth };
  draw(armLeftSrc, originX + 8 * SCALE, 8 * SCALE, armWidth * SCALE, 12 * SCALE);

  // Torso and legs
  draw(p.body, originX, 8 * SCALE, 8 * SCALE, 12 * SCALE);
  draw(p.legR, originX, 20 * SCALE, 4 * SCALE, 12 * SCALE);
  draw(legacy ? p.legR : p.legL, originX + 4 * SCALE, 20 * SCALE, 4 * SCALE, 12 * SCALE);

  // Head last so the hat layer sits on top of everything around it
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
