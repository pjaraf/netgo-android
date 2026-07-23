const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('OPTIONS_PROVIDER_CLASS')) {
  xml = xml.replace(
    '</application>',
    '    <meta-data\n' +
    '        android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS"\n' +
    '        android:value="com.netgo.mobile.CastOptionsProvider" />\n' +
    '</application>'
  );
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ Google Cast (CastOptionsProvider) registered in AndroidManifest.xml.');
} else {
  console.log('Google Cast already registered — nothing to do.');
}
