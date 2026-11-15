# Vibrance Website

Static documentation and interactive pigment-mixing playground for [Vibrance](https://github.com/romainguy/vibrance), published to the `gh-pages` branch for GitHub Pages.

## Structure

- `src/` — HTML, CSS, and client-side ES modules (`vibrance.js`, `app.js`, `.nojekyll`).
- `build.mjs` — Zero-dependency Node script that reads `VERSION_NAME` from `gradle.properties`, generates `dist/js/weights.js` from `scripts/data/pigments_mlp.json` and `dist/js/pigments-mix.js` from `Vibrance.kt`, copies `src/` into `dist/`, and runs `verify.mjs`.
- `verify.mjs` — Node test suite verifying numerical parity between the JavaScript port and `VibranceTest.kt`.
- `deploy.sh` — Builds the site and commits `dist/` to the `gh-pages` branch via a temporary `git worktree` (leaving your current working directory untouched).

## Build and preview locally

Requires Node.js 18+:

```bash
node website/build.mjs
python3 -m http.server 8000 --directory website/dist
```

Then open `http://localhost:8000`.

## Deploy to `gh-pages`

Build, commit to `gh-pages`, and push to `origin`:

```bash
./website/deploy.sh
```

To commit to the local `gh-pages` branch without pushing to `origin`:

```bash
./website/deploy.sh --local
```
