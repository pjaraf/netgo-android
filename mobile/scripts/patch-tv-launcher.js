const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('android.software.leanback')) {
  xml = xml.replace(
    '</manifest>',
    '    <uses-feature android:name="android.software.leanback" android:required="false" />\n' +
    '    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n' +
    '</manifest>'
  );
}

if (!xml.includes('android:banner')) {
  xml = xml.replace('<application', '<application\n        android:banner="@drawable/tv_banner"');
}

if (!xml.includes('LEANBACK_LAUNCHER')) {
  xml = xml.replace(
    /(<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>\s*<\/intent-filter>)/,
    '$1\n        <intent-filter>\n' +
    '            <action android:name="android.intent.action.MAIN" />\n' +
    '            <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n' +
    '        </intent-filter>'
  );
}

fs.writeFileSync(manifestPath, xml);
console.log('✓ AndroidManifest.xml patched: this APK now shows on both the phone app drawer and the Android TV apps row.');
