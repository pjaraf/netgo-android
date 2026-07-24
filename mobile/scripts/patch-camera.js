const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('android.permission.CAMERA')) {
  xml = xml.replace(
    '<application',
    '<uses-permission android:name="android.permission.CAMERA" />\n' +
    '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n' +
    '    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />\n' +
    '    <application'
  );
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ Camera permission added (needed for the in-app QR scanner).');
} else {
  console.log('Camera permission already present — nothing to do.');
}
