// Installs the native libVLC player plugin into the Android project that
// `npx cap add android` just generated. Run this AFTER `cap add android`
// and BEFORE `cap sync android` / building the APK.
//
// It: 1) copies the Java plugin/activity into the right package folder,
//     2) copies the player layout XML,
//     3) registers the fullscreen player Activity in AndroidManifest.xml,
//     4) adds the libVLC dependency to app/build.gradle.

const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');
const nativeDir = path.join(projectRoot, 'native');
const androidAppDir = path.join(projectRoot, 'android', 'app');

const config = JSON.parse(fs.readFileSync(path.join(projectRoot, 'capacitor.config.json'), 'utf8'));
const appId = config.appId;
const packagePath = appId.split('.').join(path.sep);

// 1) Java sources
const javaDir = path.join(androidAppDir, 'src', 'main', 'java', packagePath);
fs.mkdirSync(javaDir, { recursive: true });
['VlcPlayerPlugin.java', 'VlcPlayerActivity.java', 'InlineVlcPlayerPlugin.java', 'CastOptionsProvider.java', 'MainActivity.java'].forEach(file => {
  fs.copyFileSync(path.join(nativeDir, file), path.join(javaDir, file));
});

// 2) Layout XML
const layoutDir = path.join(androidAppDir, 'src', 'main', 'res', 'layout');
fs.mkdirSync(layoutDir, { recursive: true });
fs.copyFileSync(path.join(nativeDir, 'activity_vlc_player.xml'), path.join(layoutDir, 'activity_vlc_player.xml'));

// 3) Register the player Activity in AndroidManifest.xml
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

// 4) Add libVLC dependency (published on Maven Central by VideoLAN)
const gradlePath = path.join(androidAppDir, 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');
if (!gradle.includes('libvlc-all')) {
  gradle = gradle.replace(
    /dependencies\s*\{/,
    "dependencies {\n    implementation 'org.videolan.android:libvlc-all:3.5.1'\n    implementation 'com.google.android.gms:play-services-cast-framework:22.3.1'\n    implementation 'androidx.mediarouter:mediarouter:1.7.0'"
  );
  fs.writeFileSync(gradlePath, gradle);
}

console.log('✓ Native libVLC player plugin installed (package: ' + appId + ').');
