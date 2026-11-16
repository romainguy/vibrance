import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distVibranceUrl = pathToFileURL(path.join(__dirname, 'dist', 'js', 'vibrance.js')).href;

const { Vibrance, parseHexColor, formatHexColor } = await import(distVibranceUrl);

function assertAlmostEquals(expected, actual, eps = 1e-4) {
  assert.equal(actual.length, expected.length);
  for (let i = 0; i < expected.length; i++) {
    const diff = Math.abs(expected[i] - actual[i]);
    assert.ok(
      diff <= eps,
      `Index ${i}: expected ${expected[i]}, got ${actual[i]} (diff ${diff} > ${eps})`
    );
  }
}

const vibrance = new Vibrance();

// 1. colorRoundTrip (mirrors VibranceTest.kt)
const testColors = [
  [0.0, 0.0, 0.0],
  [0.0, 0.0, 1.0],
  [1.0, 1.0, 0.0],
  [0.0, 0.0, 1.0],
  [1.0, 0.0, 0.0],
  [1.0, 0.0, 1.0],
  [0.1, 0.7, 0.1],
  [1.0, 1.0, 1.0],
  [0.0, 0.5, 1.0],
  [1.0, 1.0, 0.0],
  [0.9, 0.0, 0.1],
  [78 / 255, 113 / 255, 253 / 255],
  [72 / 255, 107 / 255, 249 / 255],
  [45 / 255, 81 / 255, 225 / 255],
  [21 / 255, 40 / 255, 192 / 255],
];

for (const color of testColors) {
  const latent = vibrance.colorToLatentColor(color[0], color[1], color[2]);
  for (let k = 0; k < 6; k++) {
    assert.ok(
      latent[k] >= 0.0 && latent[k] <= 1.0,
      `Expected latent[${k}] in [0, 1] for [${color.join(', ')}], got ${latent[k]}`
    );
  }
  const roundTripped = vibrance.latentColorToColor(latent);
  assertAlmostEquals(color, roundTripped);
}

// 2. latentColorsMix (mirrors VibranceTest.kt)
{
  const latent1 = vibrance.colorToLatentColor(1.0, 0.0, 0.0);
  const latent2 = vibrance.colorToLatentColor(0.0, 0.0, 1.0);

  assertAlmostEquals([1.0, 0.0, 0.0], vibrance.latentColorsMix(latent1, latent2, 0.0));
  assertAlmostEquals([0.0, 0.0, 1.0], vibrance.latentColorsMix(latent1, latent2, 1.0));
  assertAlmostEquals([1.0, 0.0, 0.0], vibrance.latentColorsMix(latent1, latent1, 0.5));
}

// 3. colorsMix (mirrors VibranceTest.kt + parity with latentColorsMix)
{
  assertAlmostEquals([1.0, 0.0, 0.0], vibrance.colorsMix(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0));
  assertAlmostEquals([0.0, 0.0, 1.0], vibrance.colorsMix(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0));
  assertAlmostEquals([1.0, 0.0, 0.0], vibrance.colorsMix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.5));

  // Verify Yellow + Blue produces rich leaf green around 25% and chromatic teal at 50%
  // (unlike standard sRGB, which collapses to neutral [0.5, 0.5, 0.5] gray at 50%).
  const yellowBlueQuarter = vibrance.colorsMix(1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.25);
  assert.ok(
    yellowBlueQuarter[1] > yellowBlueQuarter[0] && yellowBlueQuarter[1] > yellowBlueQuarter[2],
    `Expected green-dominant mix at 25% for Yellow + Blue, got [${Array.from(yellowBlueQuarter).join(', ')}]`
  );

  const yellowBlueMid = vibrance.colorsMix(1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.5);
  const lYellow = vibrance.colorToLatentColor(1.0, 1.0, 0.0);
  const lBlue = vibrance.colorToLatentColor(0.0, 0.0, 1.0);
  const latentMid = vibrance.latentColorsMix(lYellow, lBlue, 0.5);
  assertAlmostEquals(yellowBlueMid, latentMid, 1e-6);
  const midChroma = Math.max(...yellowBlueMid) - Math.min(...yellowBlueMid);
  assert.ok(
    midChroma > 0.3 && yellowBlueMid[1] > yellowBlueMid[0] && yellowBlueMid[1] > yellowBlueMid[2],
    `Expected green-dominant saturated midpoint for Yellow + Blue, got [${Array.from(yellowBlueMid).join(', ')}]`
  );

  // Verify out-of-gamut magenta -> dark blue avoids green floor-clamping Mach bands
  let prevGreen = 0.0;
  for (const t of [0.1, 0.25, 0.5, 0.75, 1.0]) {
    const mixed = vibrance.colorsMix(1.0, 0.0, 1.0, 21 / 255, 40 / 255, 192 / 255, t);
    assert.ok(
      mixed[1] > prevGreen,
      `Expected strictly increasing green > 0 at t=${t}, got ${mixed[1]} <= ${prevGreen}`
    );
    prevGreen = mixed[1];
  }

  // Verify valid pigmentsMix call (concentrations summing to 1.0)
  const pureWhite = vibrance.pigmentsMix(0.0, 0.0, 0.0, 1.0);
  assert.ok(pureWhite[0] > 0.9 && pureWhite[1] > 0.9 && pureWhite[2] > 0.9);
}

// 4. Preconditions (mirrors VibranceTest.kt)
assert.throws(() => vibrance.colorToLatentColor(0, 0, 0, new Float32Array(5)));
assert.throws(() => vibrance.latentColorToColor(new Float32Array(6), new Float32Array(2)));
assert.throws(() => vibrance.latentColorToColor(new Float32Array(5), new Float32Array(3)));
assert.throws(() => vibrance.latentColorsMix(new Float32Array(5), new Float32Array(6), 0));
assert.throws(() => vibrance.latentColorsMix(new Float32Array(6), new Float32Array(5), 0));
assert.throws(() =>
  vibrance.latentColorsMix(new Float32Array(6), new Float32Array(6), 0, new Float32Array(2))
);
assert.throws(() => vibrance.colorsMix(0, 0, 0, 0, 0, 0, 0, new Float32Array(2)));
assert.throws(() => vibrance.pigmentsMix(0.5, 0.5, 0.5, 0.5));
assert.throws(() => vibrance.pigmentsMix(1.0, 0.0, 0.0, 0.0, new Float32Array(2)));

// 5. Hex helpers
assertAlmostEquals([1.0, 1.0, 0.0], parseHexColor('#ffff00'));
assert.equal(formatHexColor(0.0, 0.0, 1.0), '#0000FF');
assert.equal(parseHexColor('invalid'), null);

console.log('✓ Vibrance JS verification passed (all VibranceTest.kt parity checks succeeded).');
