import {
  Vibrance,
  parseHexColor,
  formatHexColor,
  renderGradientCanvas,
} from './vibrance.js';

const vibrance = new Vibrance();
const midBuffer = new Float32Array(3);

const state = {
  startHex: '#FFFF00',
  endHex: '#0000FF',
  startRgb: [1.0, 1.0, 0.0],
  endRgb: [0.0, 0.0, 1.0],
  geometry: 'linear',
};

const form = document.getElementById('playground-form');
const startPicker = document.getElementById('start-picker');
const endPicker = document.getElementById('end-picker');
const startHexInput = document.getElementById('start-hex');
const endHexInput = document.getElementById('end-hex');
const startHexError = document.getElementById('start-hex-error');
const endHexError = document.getElementById('end-hex-error');
const swapButton = document.getElementById('swap-colors');
const geometryRadios = Array.from(document.querySelectorAll('input[name="geometry"]'));
const presetChips = Array.from(document.querySelectorAll('.preset-chip'));
const inspectButtons = Array.from(document.querySelectorAll('[data-load-start]'));

const playgroundVibranceCanvas = document.getElementById('playground-vibrance');
const playgroundSrgbCanvas = document.getElementById('playground-srgb');
const vibranceMidSwatch = document.getElementById('vibrance-mid-swatch');
const vibranceMidHex = document.getElementById('vibrance-mid-hex');
const srgbMidSwatch = document.getElementById('srgb-mid-swatch');
const srgbMidHex = document.getElementById('srgb-mid-hex');

const exampleGroups = Array.from(document.querySelectorAll('[data-example]')).map((container) => {
  const startHex = container.dataset.start;
  const endHex = container.dataset.end;
  return {
    container,
    startRgb: parseHexColor(startHex),
    endRgb: parseHexColor(endHex),
    geometry: container.dataset.geometry || 'linear',
    vibranceCanvas: container.querySelector('[data-role="vibrance"]'),
    srgbCanvas: container.querySelector('[data-role="srgb"]'),
  };
});

function normalizeHexShorthand(raw) {
  const cleaned = String(raw).trim().replace(/^#/, '');
  if (/^[0-9a-fA-F]{3}$/.test(cleaned)) {
    return (
      '#' +
      cleaned
        .split('')
        .map((ch) => ch + ch)
        .join('')
        .toUpperCase()
    );
  }
  if (/^[0-9a-fA-F]{6}$/.test(cleaned)) {
    return '#' + cleaned.toUpperCase();
  }
  return null;
}

function setFieldError(inputEl, errorEl, message) {
  if (message) {
    inputEl.setAttribute('aria-invalid', 'true');
    errorEl.textContent = message;
    errorEl.hidden = false;
  } else {
    inputEl.removeAttribute('aria-invalid');
    errorEl.textContent = '';
    errorEl.hidden = true;
  }
}

function syncPresetPressedState() {
  for (const chip of presetChips) {
    const matches =
      chip.dataset.start.toUpperCase() === state.startHex &&
      chip.dataset.end.toUpperCase() === state.endHex;
    chip.setAttribute('aria-pressed', matches ? 'true' : 'false');
  }
}

let playgroundFramePending = false;

function renderPlaygroundNow() {
  playgroundFramePending = false;

  renderGradientCanvas(playgroundVibranceCanvas, vibrance, state.startRgb, state.endRgb, {
    mode: 'vibrance',
    geometry: state.geometry,
  });
  renderGradientCanvas(playgroundSrgbCanvas, vibrance, state.startRgb, state.endRgb, {
    mode: 'srgb',
    geometry: state.geometry,
  });

  vibrance.colorsMix(
    state.startRgb[0],
    state.startRgb[1],
    state.startRgb[2],
    state.endRgb[0],
    state.endRgb[1],
    state.endRgb[2],
    0.5,
    midBuffer
  );
  const vibHex = formatHexColor(midBuffer[0], midBuffer[1], midBuffer[2]);
  vibranceMidSwatch.style.backgroundColor = vibHex;
  vibranceMidHex.textContent = vibHex;

  const srgbHex = formatHexColor(
    0.5 * (state.startRgb[0] + state.endRgb[0]),
    0.5 * (state.startRgb[1] + state.endRgb[1]),
    0.5 * (state.startRgb[2] + state.endRgb[2])
  );
  srgbMidSwatch.style.backgroundColor = srgbHex;
  srgbMidHex.textContent = srgbHex;

  playgroundVibranceCanvas.setAttribute(
    'aria-label',
    `Vibrance pigment-mixed ${state.geometry} gradient from ${state.startHex} to ${state.endHex}`
  );
  playgroundSrgbCanvas.setAttribute(
    'aria-label',
    `Standard sRGB ${state.geometry} gradient from ${state.startHex} to ${state.endHex}`
  );
}

function schedulePlaygroundRender() {
  if (!playgroundFramePending) {
    playgroundFramePending = true;
    requestAnimationFrame(renderPlaygroundNow);
  }
}

function applyColors(startHex, endHex, geometry = state.geometry) {
  const parsedStart = parseHexColor(startHex);
  const parsedEnd = parseHexColor(endHex);
  if (!parsedStart || !parsedEnd) {
    return;
  }

  state.startHex = startHex.toUpperCase();
  state.endHex = endHex.toUpperCase();
  state.startRgb = parsedStart;
  state.endRgb = parsedEnd;
  state.geometry = geometry;

  startPicker.value = state.startHex.toLowerCase();
  endPicker.value = state.endHex.toLowerCase();
  startHexInput.value = state.startHex;
  endHexInput.value = state.endHex;

  for (const radio of geometryRadios) {
    radio.checked = radio.value === state.geometry;
  }

  setFieldError(startHexInput, startHexError, '');
  setFieldError(endHexInput, endHexError, '');
  syncPresetPressedState();
  schedulePlaygroundRender();
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
});

startPicker.addEventListener('input', () => {
  const hex = startPicker.value.toUpperCase();
  const rgb = parseHexColor(hex);
  if (!rgb) return;
  state.startHex = hex;
  state.startRgb = rgb;
  startHexInput.value = hex;
  setFieldError(startHexInput, startHexError, '');
  syncPresetPressedState();
  schedulePlaygroundRender();
});

endPicker.addEventListener('input', () => {
  const hex = endPicker.value.toUpperCase();
  const rgb = parseHexColor(hex);
  if (!rgb) return;
  state.endHex = hex;
  state.endRgb = rgb;
  endHexInput.value = hex;
  setFieldError(endHexInput, endHexError, '');
  syncPresetPressedState();
  schedulePlaygroundRender();
});

function wireHexInput(inputEl, pickerEl, errorEl, side) {
  inputEl.addEventListener('input', () => {
    setFieldError(inputEl, errorEl, '');
    const rgb = parseHexColor(inputEl.value);
    if (!rgb) return;
    const normalized = '#' + inputEl.value.trim().replace(/^#/, '').toUpperCase();
    if (side === 'start') {
      state.startHex = normalized;
      state.startRgb = rgb;
    } else {
      state.endHex = normalized;
      state.endRgb = rgb;
    }
    pickerEl.value = normalized.toLowerCase();
    syncPresetPressedState();
    schedulePlaygroundRender();
  });

  inputEl.addEventListener('blur', () => {
    const normalized = normalizeHexShorthand(inputEl.value);
    if (!normalized) {
      setFieldError(inputEl, errorEl, 'Enter a hex color such as #FFAA00.');
      return;
    }
    const rgb = parseHexColor(normalized);
    inputEl.value = normalized;
    pickerEl.value = normalized.toLowerCase();
    setFieldError(inputEl, errorEl, '');
    if (side === 'start') {
      state.startHex = normalized;
      state.startRgb = rgb;
    } else {
      state.endHex = normalized;
      state.endRgb = rgb;
    }
    syncPresetPressedState();
    schedulePlaygroundRender();
  });
}

wireHexInput(startHexInput, startPicker, startHexError, 'start');
wireHexInput(endHexInput, endPicker, endHexError, 'end');

swapButton.addEventListener('click', () => {
  applyColors(state.endHex, state.startHex, state.geometry);
});

for (const radio of geometryRadios) {
  radio.addEventListener('change', () => {
    if (radio.checked) {
      state.geometry = radio.value;
      schedulePlaygroundRender();
    }
  });
}

for (const chip of presetChips) {
  chip.addEventListener('click', () => {
    applyColors(chip.dataset.start, chip.dataset.end, state.geometry);
  });
}

for (const button of inspectButtons) {
  button.addEventListener('click', () => {
    applyColors(
      button.dataset.loadStart,
      button.dataset.loadEnd,
      button.dataset.loadGeometry || 'linear'
    );
    const playgroundSection = document.getElementById('playground');
    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    playgroundSection.scrollIntoView({ behavior: prefersReducedMotion ? 'auto' : 'smooth' });
    startHexInput.focus({ preventScroll: true });
  });
}

function renderExampleGroup(group) {
  if (!group.startRgb || !group.endRgb) return;
  renderGradientCanvas(group.vibranceCanvas, vibrance, group.startRgb, group.endRgb, {
    mode: 'vibrance',
    geometry: group.geometry,
  });
  renderGradientCanvas(group.srgbCanvas, vibrance, group.startRgb, group.endRgb, {
    mode: 'srgb',
    geometry: group.geometry,
  });
}

// Initial paint
renderPlaygroundNow();
for (const group of exampleGroups) {
  renderExampleGroup(group);
}

// Re-render affected canvases cleanly when layout dimensions change
if ('ResizeObserver' in window) {
  const groupByContainer = new Map(exampleGroups.map((g) => [g.container, g]));
  const dirtyGroups = new Set();
  let resizeFramePending = false;

  const flushResizes = () => {
    resizeFramePending = false;
    for (const item of dirtyGroups) {
      if (item === 'playground') {
        renderPlaygroundNow();
      } else {
        renderExampleGroup(item);
      }
    }
    dirtyGroups.clear();
  };

  const observer = new ResizeObserver((entries) => {
    for (const entry of entries) {
      if (
        entry.target === playgroundVibranceCanvas ||
        entry.target === playgroundSrgbCanvas
      ) {
        dirtyGroups.add('playground');
      } else {
        const group = groupByContainer.get(entry.target);
        if (group) dirtyGroups.add(group);
      }
    }
    if (dirtyGroups.size > 0 && !resizeFramePending) {
      resizeFramePending = true;
      requestAnimationFrame(flushResizes);
    }
  });

  observer.observe(playgroundVibranceCanvas);
  observer.observe(playgroundSrgbCanvas);
  for (const group of exampleGroups) {
    observer.observe(group.container);
  }
}

// Accessible copy buttons (Invoker Commands + click fallback)
const tipTimers = new WeakMap();

function showCopyTip(tip, message) {
  if (!tip) return;
  clearTimeout(tipTimers.get(tip));
  // Alternate trailing NBSP when repeating the identical string so VoiceOver announces repeats
  tip.textContent = tip.textContent === message ? `${message}\u00A0` : message;
  tip.dataset.visible = '';
  const timer = setTimeout(() => {
    delete tip.dataset.visible;
  }, 1800);
  tipTimers.set(tip, timer);
}

async function handleCopyAction(button, targetEl) {
  if (!button || !targetEl) return;
  const tip = button.closest('.copy')?.querySelector('.tip');
  try {
    await navigator.clipboard.writeText(targetEl.textContent ?? '');
    showCopyTip(tip, 'Copied');
  } catch {
    showCopyTip(tip, 'Copy failed');
  }
}

document.addEventListener(
  'command',
  (event) => {
    if (event.command === '--copy') {
      handleCopyAction(event.source, event.target);
    }
  },
  true
);

if (!('CommandEvent' in window)) {
  document.addEventListener('click', (event) => {
    const button = event.target.closest('.copy-button[command="--copy"]');
    if (!button) return;
    const targetId = button.getAttribute('commandfor');
    const targetEl = targetId ? document.getElementById(targetId) : null;
    handleCopyAction(button, targetEl);
  });
}
