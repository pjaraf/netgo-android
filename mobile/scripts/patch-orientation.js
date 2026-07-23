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
