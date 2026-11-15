import {
  positionalEncodingFrequencies,
  net0Weight,
  net0Bias,
  net2Weight,
  net2Bias,
  net4Weight,
  net4Bias,
} from './weights.js';
import { pigmentsMixLinear } from './pigments-mix.js';

const PI = 3.1415927;

function clamp01(v) {
  return v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
}

function lerp(start, stop, fraction) {
  return (1.0 - fraction) * start + fraction * stop;
}

export function oetfSrgb(x) {
  return x > 0.0031308 ? 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055 : 12.92 * x;
}

export function eotfSrgb(x) {
  return x > 0.04045 ? Math.pow((x + 0.055) / 1.055, 2.4) : (1.0 / 12.92) * x;
}

class ColorToPigmentsModel {
  constructor() {
    this._frequencies = positionalEncodingFrequencies;
    this._encodedBuffer = new Float32Array(3 + 3 * 2 * this._frequencies.length);
    this._h1Buffer = new Float32Array(net0Bias.length);
    this._h2Buffer = new Float32Array(net2Bias.length);
  }

  _linearLayerReLU(input, weights, bias, output) {
    const inputSize = input.length;
    const outputSize = output.length;
    if (outputSize > bias.length || inputSize * outputSize > weights.length) {
      return;
    }

    for (let i = 0; i < outputSize; i++) {
      let sum = bias[i];
      const row = i * inputSize;
      for (let j = 0; j < inputSize; j++) {
        sum += input[j] * weights[row + j];
      }
      output[i] = sum > 0.0 ? sum : 0.0;
    }
  }

  _encodePosition(r, g, b, buffer) {
    buffer[0] = r;
    buffer[1] = g;
    buffer[2] = b;

    let index = 3;
    let frequency = PI;
    const count = this._frequencies.length;
    for (let k = 0; k < count; k++) {
      buffer[index++] = Math.sin(r * frequency);
      buffer[index++] = Math.sin(g * frequency);
      buffer[index++] = Math.sin(b * frequency);

      buffer[index++] = Math.cos(r * frequency);
      buffer[index++] = Math.cos(g * frequency);
      buffer[index++] = Math.cos(b * frequency);

      frequency *= 2.0;
    }
  }

  predict(r, g, b, concentrations) {
    this._encodePosition(r, g, b, this._encodedBuffer);
    this._linearLayerReLU(this._encodedBuffer, net0Weight, net0Bias, this._h1Buffer);
    this._linearLayerReLU(this._h1Buffer, net2Weight, net2Bias, this._h2Buffer);
    this._linearLayerReLU(this._h2Buffer, net4Weight, net4Bias, concentrations);

    concentrations[0] = Math.min(concentrations[0], 1.0);
    concentrations[1] = Math.min(concentrations[1], 1.0);
    concentrations[2] = Math.min(concentrations[2], 1.0);

    return concentrations;
  }
}

/**
 * JavaScript port of dev.romainguy.vibrance.Vibrance.
 * Mixes sRGB colors as physical paint pigments in a 7D latent space.
 */
export class Vibrance {
  constructor() {
    this._model = new ColorToPigmentsModel();
    this._pigmentsBuffer = new Float32Array(3);
  }

  colorToLatentColor(r, g, b, latentColor = new Float32Array(6)) {
    if (!latentColor || latentColor.length < 6) {
      throw new RangeError('latentColor must have a size >= 6');
    }

    const cr = clamp01(r);
    const cg = clamp01(g);
    const cb = clamp01(b);

    const buffer = this._pigmentsBuffer;
    this._model.predict(cr, cg, cb, buffer);

    const latent0 = buffer[0];
    const latent1 = buffer[1];
    const latent2 = buffer[2];

    pigmentsMixLinear(latent0, latent1, latent2, buffer);

    latentColor[0] = latent0;
    latentColor[1] = latent1;
    latentColor[2] = latent2;
    latentColor[3] = eotfSrgb(cr) - buffer[0];
    latentColor[4] = eotfSrgb(cg) - buffer[1];
    latentColor[5] = eotfSrgb(cb) - buffer[2];

    return latentColor;
  }

  latentColorToColor(latentColor, color = new Float32Array(3)) {
    if (!latentColor || latentColor.length < 6) {
      throw new RangeError('latentColor must have a size >= 6');
    }
    if (!color || color.length < 3) {
      throw new RangeError('color must have a size >= 3');
    }

    const blue = clamp01(latentColor[0]);
    const magenta = clamp01(latentColor[1]);
    const yellow = clamp01(latentColor[2]);

    pigmentsMixLinear(blue, magenta, yellow, color);

    color[0] = oetfSrgb(clamp01(color[0] + latentColor[3]));
    color[1] = oetfSrgb(clamp01(color[1] + latentColor[4]));
    color[2] = oetfSrgb(clamp01(color[2] + latentColor[5]));

    return color;
  }

  latentColorsMix(src, dst, amount, color = new Float32Array(3)) {
    if (!src || !dst || src.length < 6 || dst.length < 6) {
      throw new RangeError('src and dst must have a size >= 6');
    }
    if (!color || color.length < 3) {
      throw new RangeError('color must have a size >= 3');
    }

    const c0 = lerp(src[0], dst[0], amount);
    const c1 = lerp(src[1], dst[1], amount);
    const c2 = lerp(src[2], dst[2], amount);
    pigmentsMixLinear(c0, c1, c2, color);

    color[0] = oetfSrgb(clamp01(color[0] + lerp(src[3], dst[3], amount)));
    color[1] = oetfSrgb(clamp01(color[1] + lerp(src[4], dst[4], amount)));
    color[2] = oetfSrgb(clamp01(color[2] + lerp(src[5], dst[5], amount)));

    return color;
  }

  colorsMix(srcR, srcG, srcB, dstR, dstG, dstB, amount, color = new Float32Array(3)) {
    if (!color || color.length < 3) {
      throw new RangeError('color must have a size >= 3');
    }

    const sr = clamp01(srcR);
    const sg = clamp01(srcG);
    const sb = clamp01(srcB);

    this._model.predict(sr, sg, sb, color);
    const srcPigment0 = color[0];
    const srcPigment1 = color[1];
    const srcPigment2 = color[2];

    pigmentsMixLinear(srcPigment0, srcPigment1, srcPigment2, color);
    const srcRemainderR = eotfSrgb(sr) - color[0];
    const srcRemainderG = eotfSrgb(sg) - color[1];
    const srcRemainderB = eotfSrgb(sb) - color[2];

    const dr = clamp01(dstR);
    const dg = clamp01(dstG);
    const db = clamp01(dstB);

    this._model.predict(dr, dg, db, color);
    const dstPigment0 = color[0];
    const dstPigment1 = color[1];
    const dstPigment2 = color[2];

    pigmentsMixLinear(dstPigment0, dstPigment1, dstPigment2, color);
    const dstRemainderR = eotfSrgb(dr) - color[0];
    const dstRemainderG = eotfSrgb(dg) - color[1];
    const dstRemainderB = eotfSrgb(db) - color[2];

    const c0 = lerp(srcPigment0, dstPigment0, amount);
    const c1 = lerp(srcPigment1, dstPigment1, amount);
    const c2 = lerp(srcPigment2, dstPigment2, amount);
    pigmentsMixLinear(c0, c1, c2, color);

    color[0] = oetfSrgb(clamp01(color[0] + lerp(srcRemainderR, dstRemainderR, amount)));
    color[1] = oetfSrgb(clamp01(color[1] + lerp(srcRemainderG, dstRemainderG, amount)));
    color[2] = oetfSrgb(clamp01(color[2] + lerp(srcRemainderB, dstRemainderB, amount)));

    return color;
  }

  pigmentsMix(blue, magenta, yellow, white, color = new Float32Array(3)) {
    if (Math.abs(1.0 - (blue + magenta + yellow + white)) >= 1e-6) {
      throw new RangeError('The sum of the pigment concentrations must be equal to 1.0');
    }
    if (!color || color.length < 3) {
      throw new RangeError('color must have a size >= 3');
    }

    pigmentsMixLinear(blue, magenta, yellow, color);

    color[0] = oetfSrgb(color[0]);
    color[1] = oetfSrgb(color[1]);
    color[2] = oetfSrgb(color[2]);

    return color;
  }
}

export function parseHexColor(hex) {
  const cleaned = String(hex).trim().replace(/^#/, '');
  if (!/^[0-9a-fA-F]{6}$/.test(cleaned)) {
    return null;
  }
  const value = parseInt(cleaned, 16);
  return [
    ((value >> 16) & 0xff) / 255.0,
    ((value >> 8) & 0xff) / 255.0,
    (value & 0xff) / 255.0,
  ];
}

export function formatHexColor(r, g, b) {
  const toHex = (v) => Math.round(clamp01(v) * 255).toString(16).padStart(2, '0').toUpperCase();
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

// Precomputed 256x256 Gjøl 2016 triangle-PDF noise tile (ported from vibrance-compose shaders.kt)
const NOISE_SIZE = 256;
const NOISE_MASK = NOISE_SIZE - 1;
const NOISE_TILE = (() => {
  const tile = new Float32Array(NOISE_SIZE * NOISE_SIZE);
  const fract = (v) => v - Math.floor(v);
  for (let y = 0; y < NOISE_SIZE; y++) {
    for (let x = 0; x < NOISE_SIZE; x++) {
      let nx = fract((x / 1080.0) * 5.3987);
      let ny = fract((y / 1080.0) * 5.4421);
      const dot = ny * (nx + 21.5351) + nx * (ny + 14.3137);
      nx += dot;
      ny += dot;
      const xy = nx * ny;
      // Range [-1.0..1.0[ + 0.5 rounding offset for direct Uint8ClampedArray assignment
      tile[(y << 8) | x] = fract(xy * 95.4307) + fract(xy * 75.04961) - 0.5;
    }
  }
  return tile;
})();

const RAMP_STEPS = 2048;
const RAMP_MAX = RAMP_STEPS - 1;
const canvasStateCache = new WeakMap();

function getOrBuildGeometryCache(canvas, width, height, geometry) {
  let state = canvasStateCache.get(canvas);
  if (
    state &&
    state.width === width &&
    state.height === height &&
    state.geometry === geometry
  ) {
    return state;
  }

  const ctx = canvas.getContext('2d', { alpha: false });
  const imageData = ctx.createImageData(width, height);
  // Initialize alpha channel once to 255
  const data = imageData.data;
  for (let i = 3; i < data.length; i += 4) {
    data[i] = 255;
  }

  let tMap = null;
  if (geometry === 'radial') {
    tMap = new Uint16Array(width * height);
    const cx = width * 0.5;
    const cy = height * 0.5;
    const invRadius = 1.0 / Math.max(1.0, Math.max(width, height) * 0.5);
    let idx = 0;
    for (let y = 0; y < height; y++) {
      const dy = y - cy;
      for (let x = 0; x < width; x++) {
        const dx = x - cx;
        const t = clamp01(Math.hypot(dx, dy) * invRadius);
        tMap[idx++] = Math.round(t * RAMP_MAX);
      }
    }
  } else if (geometry === 'sweep') {
    tMap = new Uint16Array(width * height);
    const cx = width * 0.5;
    const cy = height * 0.5;
    const invTwoPi = 1.0 / (2.0 * Math.PI);
    let idx = 0;
    for (let y = 0; y < height; y++) {
      const dy = y - cy;
      for (let x = 0; x < width; x++) {
        const dx = x - cx;
        let t = Math.atan2(dy, dx) * invTwoPi;
        t = t - Math.floor(t);
        tMap[idx++] = Math.round(t * RAMP_MAX);
      }
    }
  } else {
    // Horizontal linear gradient only needs a 1D column-to-ramp mapping
    tMap = new Uint16Array(width);
    const denom = Math.max(1, width - 1);
    for (let x = 0; x < width; x++) {
      tMap[x] = Math.round((x / denom) * RAMP_MAX);
    }
  }

  state = {
    width,
    height,
    geometry,
    ctx,
    imageData,
    tMap,
    rampR: new Float32Array(RAMP_STEPS),
    rampG: new Float32Array(RAMP_STEPS),
    rampB: new Float32Array(RAMP_STEPS),
    latentStart: new Float32Array(6),
    latentEnd: new Float32Array(6),
    rgbOut: new Float32Array(3),
  };
  canvasStateCache.set(canvas, state);
  return state;
}

/**
 * Renders a Vibrance or standard sRGB gradient onto a 2D canvas with triangle-noise dithering.
 */
export function renderGradientCanvas(canvas, vibrance, startRgb, endRgb, options = {}) {
  const mode = options.mode || 'vibrance';
  const geometry = options.geometry || 'linear';

  const rect = canvas.getBoundingClientRect();
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = Math.max(1, Math.round(rect.width * dpr));
  const height = Math.max(1, Math.round(rect.height * dpr));

  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }

  const state = getOrBuildGeometryCache(canvas, width, height, geometry);
  const { rampR, rampG, rampB, latentStart, latentEnd, rgbOut } = state;

  if (mode === 'vibrance') {
    vibrance.colorToLatentColor(startRgb[0], startRgb[1], startRgb[2], latentStart);
    vibrance.colorToLatentColor(endRgb[0], endRgb[1], endRgb[2], latentEnd);
    for (let i = 0; i < RAMP_STEPS; i++) {
      vibrance.latentColorsMix(latentStart, latentEnd, i / RAMP_MAX, rgbOut);
      rampR[i] = rgbOut[0] * 255.0;
      rampG[i] = rgbOut[1] * 255.0;
      rampB[i] = rgbOut[2] * 255.0;
    }
  } else {
    const [sr, sg, sb] = startRgb;
    const [er, eg, eb] = endRgb;
    for (let i = 0; i < RAMP_STEPS; i++) {
      const t = i / RAMP_MAX;
      rampR[i] = lerp(sr, er, t) * 255.0;
      rampG[i] = lerp(sg, eg, t) * 255.0;
      rampB[i] = lerp(sb, eb, t) * 255.0;
    }
  }

  const data = state.imageData.data;
  const tMap = state.tMap;

  if (geometry === 'linear') {
    let p = 0;
    for (let y = 0; y < height; y++) {
      const noiseRow = (y & NOISE_MASK) << 8;
      for (let x = 0; x < width; x++) {
        const rIdx = tMap[x];
        const noise = NOISE_TILE[noiseRow | (x & NOISE_MASK)];
        data[p] = rampR[rIdx] + noise;
        data[p + 1] = rampG[rIdx] + noise;
        data[p + 2] = rampB[rIdx] + noise;
        p += 4;
      }
    }
  } else {
    let p = 0;
    let idx = 0;
    for (let y = 0; y < height; y++) {
      const noiseRow = (y & NOISE_MASK) << 8;
      for (let x = 0; x < width; x++) {
        const rIdx = tMap[idx++];
        const noise = NOISE_TILE[noiseRow | (x & NOISE_MASK)];
        data[p] = rampR[rIdx] + noise;
        data[p + 1] = rampG[rIdx] + noise;
        data[p + 2] = rampB[rIdx] + noise;
        p += 4;
      }
    }
  }

  state.ctx.putImageData(state.imageData, 0, 0);
}
