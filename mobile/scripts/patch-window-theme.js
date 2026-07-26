// Root-fixes the white flashes some devices showed on cold start and when
// returning from the fullscreen player: Android shows the *theme's own*
// windowBackground as a "starting window" during any Activity transition,
// before that Activity's views have drawn anything. If a theme doesn't
// explicitly set android:windowBackground, it inherits a light one from
// the base Material/AppCompat theme — which is the actual source of the
// white flash, separate from (and in addition to) the WebView's own
// default background and the launch splash image.
//
// This adds a dark color resource and forces android:windowBackground to
// use it on every theme in styles.xml, so there's never a light window
// behind any transition, no matter which Activity or theme is involved.
// Run AFTER `npx cap add android` and BEFORE `npx cap sync android`.

const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');
const resDir = path.join(projectRoot, 'android', 'app', 'src', 'main', 'res');
const stylesPath = path.join(resDir, 'values', 'styles.xml');
const colorsPath = path.join(resDir, 'values', 'colors.xml');

const DARK_COLOR_NAME = 'netgoWindowBg';
const DARK_COLOR_VALUE = '#0B1B26';

// 1) Make sure the dark color resource exists.
if (fs.existsSync(colorsPath)) {
  let colors = fs.readFileSync(colorsPath, 'utf8');
  if (!colors.includes(`name="${DARK_COLOR_NAME}"`)) {
    colors = colors.replace(
      '</resources>',
      `    <color name="${DARK_COLOR_NAME}">${DARK_COLOR_VALUE}</color>\n</resources>`
    );
    fs.writeFileSync(colorsPath, colors);
  }
} else {
  fs.mkdirSync(path.dirname(colorsPath), { recursive: true });
  fs.writeFileSync(
    colorsPath,
    `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="${DARK_COLOR_NAME}">${DARK_COLOR_VALUE}</color>\n</resources>\n`
  );
}

// 2) Force android:windowBackground on every <style> block in styles.xml.
if (!fs.existsSync(stylesPath)) {
  console.log('No styles.xml found — skipping (nothing to patch).');
  process.exit(0);
}

let styles = fs.readFileSync(stylesPath, 'utf8');

// Split into individual <style ...>...</style> blocks, patch each one, and
// reassemble — simplest reliable way to touch every theme regardless of
// how many there are or what they're named.
const styleBlockRegex = /<style\b[^>]*>[\s\S]*?<\/style>/g;
let changed = false;

styles = styles.replace(styleBlockRegex, (block) => {
  if (block.includes('android:windowBackground')) {
    // Already set — most likely by patch-splash.js pointing this theme at
    // our branded splash drawable. Leave it alone; only themes with no
    // windowBackground at all (which fall back to a light default) need
    // fixing here.
    return block;
  }
  changed = true;
  return block.replace(
    /(<style\b[^>]*>)/,
    `$1\n        <item name="android:windowBackground">@color/${DARK_COLOR_NAME}</item>`
  );
});

fs.writeFileSync(stylesPath, styles);
console.log(changed
  ? `✓ Dark windowBackground (@color/${DARK_COLOR_NAME}) applied to every theme in styles.xml.`
  : 'styles.xml already had windowBackground set everywhere — nothing to change.');
