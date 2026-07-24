// Registers the Google Cast SDK configuration in AndroidManifest.xml.
// Run AFTER `npx cap add android` and BEFORE building.

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

// Cast device discovery uses multicast (mDNS) network packets — without this
// permission, some devices silently find zero Cast/Chromecast devices.
if (!xml.includes('CHANGE_WIFI_MULTICAST_STATE')) {
  xml = fs.readFileSync(manifestPath, 'utf8');
  xml = xml.replace(
    '<application',
    '<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />\n' +
    '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n' +
    '    <application'
  );
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ Multicast permission added (needed for Cast device discovery).');
}

// Android 13+ requires this specific runtime permission for the Cast SDK to
// find any devices at all — without it, discovery silently returns zero
// results even though everything else (network, device) works fine.
if (!xml.includes('NEARBY_WIFI_DEVICES')) {
  xml = fs.readFileSync(manifestPath, 'utf8');
  xml = xml.replace(
    '<application',
    '<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />\n' +
    '    <application'
  );
  fs.writeFileSync(manifestPath, xml);
  console.log('✓ NEARBY_WIFI_DEVICES permission added (required on Android 13+ for Cast discovery).');
}
