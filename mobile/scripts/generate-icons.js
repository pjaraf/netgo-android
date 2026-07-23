const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const projectRoot = path.join(__dirname, '..');
const assetsDir = path.join(projectRoot, 'assets');
const resDir = path.join(projectRoot, 'android', 'app', 'src', 'main', 'res');

const ICON_DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const ADAPTIVE_DENSITIES = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };

async function circleMask(size) {
  const svg = `<svg width="${size}" height="${size}"><circle cx="${size/2}" cy="${size/2}" r="${size/2}" fill="#fff"/></svg>`;
  return Buffer.from(svg);
}

async function main() {
  const legacy = path.join(assetsDir, 'icon-legacy.png');
  const foreground = path.join(assetsDir, 'icon-foreground.png');
  const background = path.join(assetsDir, 'icon-background.png');
  const banner = path.join(assetsDir, 'tv_banner.png');

  for (const [density, size] of Object.entries(ICON_DENSITIES)) {
    const dir = path.join(resDir, `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });

    await sharp(legacy).resize(size, size).toFile(path.join(dir, 'ic_launcher.png'));

    const squareBuf = await sharp(legacy).resize(size, size).toBuffer();
    await sharp(squareBuf)
      .composite([{ input: await circleMask(size), blend: 'dest-in' }])
      .png()
      .toFile(path.join(dir, 'ic_launcher_round.png'));
  }

  for (const [density, size] of Object.entries(ADAPTIVE_DENSITIES)) {
    const dir = path.join(resDir, `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    await sharp(foreground).resize(size, size).toFile(path.join(dir, 'ic_launcher_foreground.png'));
    await sharp(background).resize(size, size).toFile(path.join(dir, 'ic_launcher_background.png'));
  }

  const anydpiDir = path.join(resDir, 'mipmap-anydpi-v26');
  fs.mkdirSync(anydpiDir, { recursive: true });
  const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
`;
  fs.writeFileSync(path.join(anydpiDir, 'ic_launcher.xml'), adaptiveXml);
  fs.writeFileSync(path.join(anydpiDir, 'ic_launcher_round.xml'), adaptiveXml);

  const drawableDir = path.join(resDir, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  await sharp(banner).resize(320, 180).toFile(path.join(drawableDir, 'tv_banner.png'));

  const drawableXhdpi = path.join(resDir, 'drawable-xhdpi');
  fs.mkdirSync(drawableXhdpi, { recursive: true });
  await sharp(banner).resize(640, 360).toFile(path.join(drawableXhdpi, 'tv_banner.png'));

  console.log('✓ App icons (phone + adaptive) and TV banner generated for all densities.');
}

main().catch(err => { console.error(err); process.exit(1); });
