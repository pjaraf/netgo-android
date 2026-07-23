const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');
const nativeDir = path.join(projectRoot, 'native');
const androidAppDir = path.join(projectRoot, 'android', 'app');

const config = JSON.parse(fs.readFileSync(path.join(projectRoot, 'capacitor.config.json'), 'utf8'));
const appId = config.appId;
const packagePath = appId.split('.').join(path.sep);

const javaDir = path.join(androidAppDir, 'src', 'main', 'java', packagePath);
fs.mkdirSync(javaDir, { recursive: true });
['VlcPlayerPlugin.java', 'VlcPlayerActivity.java', 'InlineVlcPlayerPlugin.java', 'MainActivity.java'].forEach(file => {
  fs.copyFileSync(path.join(nativeDir, file), path.join(javaDir, file));
});

const layoutDir = path.join(androidAppDir, 'src', 'main', 'res', 'layout');
fs.mkdirSync(layoutDir, { recursive: true });
fs.copyFileSync(path.join(nativeDir, 'activity_vlc_player.xml'), path.join(layoutDir, 'activity_vlc_player.xml'));

const manifestPath = path.join(androidAppDir, 'src', 'main', 'AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
if (!manifest.includes('VlcPlayerActivity')) {
  manifest = manifest.replace(
    '</application>',
    '    <activity android:name=".VlcPlayerActivity" android:exported="false" ' +
      'android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" ' +
      'android:configChanges="orientation|screenSize|keyboardHidden|screenLayout" />\n' +
      '</application>'
  );
  fs.writeFileSync(manifestPath, manifest);
}

const gradlePath = path.join(androidAppDir, 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');
if (!gradle.includes('libvlc-all')) {
  gradle = gradle.replace(
    /dependencies\s*\{/,
    "dependencies {\n    implementation 'org.videolan.android:libvlc-all:3.5.1'"
  );
  fs.writeFileSync(gradlePath, gradle);
}

console.log('✓ Native libVLC player plugin installed (package: ' + appId + ').');
