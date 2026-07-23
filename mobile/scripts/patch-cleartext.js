const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('usesCleartextTraffic')) {
  xml = xml.replace('<application', '<application\n        android:usesCleartextTraffic="true"');
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ AndroidManifest.xml patched to allow cleartext (http://) traffic.');
} else {
  console.log('AndroidManifest.xml already allows cleartext traffic.');
}
