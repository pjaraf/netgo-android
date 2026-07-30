// Ensures MainActivity does NOT get destroyed/recreated when the screen
// rotates (e.g. entering fullscreen video landscape mode). Without this,
// rotating could reload the whole WebView and kill the native video overlay
// mid-playback. Run AFTER `npx cap add android` and BEFORE building.

const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('android:configChanges')) {
  xml = xml.replace(
    /(<activity\b)(\s)/,
    '$1 android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode"$2'
  );
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ MainActivity now handles orientation changes without restarting.');
} else {
  console.log('MainActivity already declares configChanges — nothing to do.');
}
