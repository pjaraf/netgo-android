// Sets up what the app needs to download and install its own update APK
// directly (instead of handing the download off to the device's browser,
// which on some Android TV boxes truncates/corrupts the file):
//   1) the REQUEST_INSTALL_PACKAGES permission,
//   2) a FileProvider entry in the manifest (required to hand a downloaded
//      file to Android's package installer on modern Android),
//   3) the res/xml/file_paths.xml it points to.
// Run AFTER `npx cap add android` and BEFORE the Gradle build.

const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');
const androidAppDir = path.join(projectRoot, 'android', 'app');
const manifestPath = path.join(androidAppDir, 'src', 'main', 'AndroidManifest.xml');

let xml = fs.readFileSync(manifestPath, 'utf8');

if (!xml.includes('REQUEST_INSTALL_PACKAGES')) {
  xml = xml.replace(
    '<application',
    '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n    <application'
  );
}

if (!xml.includes('.fileprovider')) {
  xml = xml.replace(
    '</application>',
    '    <provider\n' +
    '            android:name="androidx.core.content.FileProvider"\n' +
    '            android:authorities="${applicationId}.fileprovider"\n' +
    '            android:exported="false"\n' +
    '            android:grantUriPermissions="true">\n' +
    '            <meta-data\n' +
    '                android:name="android.support.FILE_PROVIDER_PATHS"\n' +
    '                android:resource="@xml/file_paths" />\n' +
    '        </provider>\n' +
    '    </application>'
  );
}

fs.writeFileSync(manifestPath, xml);

const xmlResDir = path.join(androidAppDir, 'src', 'main', 'res', 'xml');
fs.mkdirSync(xmlResDir, { recursive: true });
fs.writeFileSync(
  path.join(xmlResDir, 'file_paths.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n' +
  '<paths>\n' +
  '    <external-files-path name="update" path="." />\n' +
  '</paths>\n'
);

console.log('✓ FileProvider + install permission configured for in-app updates.');
