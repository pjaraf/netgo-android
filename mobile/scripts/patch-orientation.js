// Ensures MainActivity does NOT get destroyed/recreated when the screen
// rotates (e.g. entering fullscreen video landscape mode). Without this,
// rotating could reload the whole WebView and kill the native video overlay
// mid-playback. Run AFTER `npx cap add android` and BEFORE building.

const fs = require('fs');
const path = require('path');

const manifestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifestPath, 'utf8');

// Find MainActivity's own <activity> tag specifically (there are several
// activity tags in this manifest — VlcPlayerActivity already declares its
// own configChanges separately).
const activityMatch = xml.match(/<activity\b[^>]*android:name="\.MainActivity"[^>]*>/);

if (!activityMatch) {
  console.log('Could not find the MainActivity <activity> tag — skipping.');
} else {
  const tag = activityMatch[0];
  const configChangesMatch = tag.match(/android:configChanges="([^"]*)"/);

  if (configChangesMatch && configChangesMatch[1].includes('orientation')) {
    console.log('MainActivity already handles orientation changes — nothing to do.');
  } else if (configChangesMatch) {
    // configChanges exists but doesn't cover orientation — a default
    // Capacitor template can look like this, which the old version of
    // this script mistook for "already handled" since it only checked
    // whether the attribute existed at all, not what it actually covered.
    const newValue = configChangesMatch[1] + '|orientation|screenSize|smallestScreenSize';
    const newTag = tag.replace(/android:configChanges="[^"]*"/, `android:configChanges="${newValue}"`);
    xml = xml.replace(tag, newTag);
    fs.writeFileSync(manifestPath, xml);
    console.log('✓ Added orientation to MainActivity\'s existing configChanges.');
  } else {
    const newTag = tag.replace(
      '<activity ',
      '<activity android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode" '
    );
    xml = xml.replace(tag, newTag);
    fs.writeFileSync(manifestPath, xml);
    console.log('✓ MainActivity now handles orientation changes without restarting.');
  }
}
