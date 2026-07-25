// Replaces Android's default splash-screen placeholder (the one that was
// showing briefly on launch instead of our logo) with a proper full-bleed
// splash image: our dark brand background with the amber "N" mark
// centered. Whatever exact drawable name Capacitor's generated project
// references for the launch screen, this finds it (from styles.xml) and
// overwrites it — and also writes a plain "splash.png" as a safety net in
// case that lookup doesn't match.
// Run AFTER `npx cap add android` and BEFORE `npx cap sync android`
// (needs sharp, already a devDependency for generate-icons.js).

const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const projectRoot = path.join(__dirname, '..');
const assetsDir = path.join(projectRoot, 'assets');
const resDir = path.join(projectRoot, 'android', 'app', 'src', 'main', 'res');

async function main() {
  const size = 1200;
  const logoSize = 380;

  const logoBuf = await sharp(path.join(assetsDir, 'icon-foreground.png'))
    .resize(logoSize, logoSize)
    .toBuffer();

  const splashBuf = await sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0x0B, g: 0x1B, b: 0x26, alpha: 1 }
    }
  })
    .composite([{ input: logoBuf, gravity: 'center' }])
    .png()
    .toBuffer();

  // Figure out the drawable name the generated project actually references
  // for the launch window background, so we overwrite the right file.
  let splashName = 'splash';
  const stylesPath = path.join(resDir, 'values', 'styles.xml');
  if (fs.existsSync(stylesPath)) {
    const styles = fs.readFileSync(stylesPath, 'utf8');
    const match = styles.match(/windowBackground[^>]*@drawable\/(\w+)/);
    if (match) splashName = match[1];
  }

  const targets = new Set(['splash', splashName]);
  const drawableDirs = fs.existsSync(resDir)
    ? fs.readdirSync(resDir).filter(d => d.startsWith('drawable'))
    : [];
  if (!drawableDirs.length) drawableDirs.push('drawable');

  for (const dir of drawableDirs) {
    const fullDir = path.join(resDir, dir);
    fs.mkdirSync(fullDir, { recursive: true });
    for (const name of targets) {
      fs.writeFileSync(path.join(fullDir, `${name}.png`), splashBuf);
    }
  }

  // Also set the plain background color (shown for an instant before even
  // the image decodes) to our dark brand color instead of default white.
  const colorsPath = path.join(resDir, 'values', 'colors.xml');
  if (fs.existsSync(colorsPath)) {
    let colors = fs.readFileSync(colorsPath, 'utf8');
    if (colors.includes('name="splashBackground"')) {
      colors = colors.replace(
        /<color name="splashBackground">#[0-9A-Fa-f]+<\/color>/,
        '<color name="splashBackground">#0B1B26</color>'
      );
    } else {
      colors = colors.replace(
        '</resources>',
        '    <color name="splashBackground">#0B1B26</color>\n</resources>'
      );
    }
    fs.writeFileSync(colorsPath, colors);
  }

  console.log(`✓ Splash screen replaced with the NetGo logo (drawable: ${[...targets].join(', ')}).`);
}

main().catch(err => { console.error(err); process.exit(1); });
