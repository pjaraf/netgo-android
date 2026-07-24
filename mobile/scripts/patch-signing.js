// Pins the debug signing key directly inside android/app/build.gradle,
// instead of relying on Gradle finding it at the default global location
// (which Capacitor's generated project may not actually use). This is
// what guarantees every CI build produces an APK with the exact same
// signature, so "Actualizar" installs over the previous version instead
// of failing with "conflicto de paquete". Run AFTER `npx cap add android`
// and BEFORE the Gradle build.

const fs = require('fs');
const path = require('path');

const gradlePath = path.join(__dirname, '..', 'android', 'app', 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');

if (!gradle.includes('netgoDebugSigning')) {
  gradle = gradle.replace(
    /android\s*\{/,
    `android {\n    // netgoDebugSigning: pinned so every CI build produces the same\n    // signature, letting updates install over the previous version.\n    signingConfigs {\n        debug {\n            storeFile file("\${rootDir}/../keystore/debug.keystore")\n            storePassword 'android'\n            keyAlias 'androiddebugkey'\n            keyPassword 'android'\n        }\n    }`
  );

  if (/buildTypes\s*\{[\s\S]*?debug\s*\{/.test(gradle)) {
    gradle = gradle.replace(
      /(buildTypes\s*\{[\s\S]*?debug\s*\{)/,
      `$1\n            signingConfig signingConfigs.debug`
    );
  } else {
    gradle = gradle.replace(
      /buildTypes\s*\{/,
      `buildTypes {\n        debug {\n            signingConfig signingConfigs.debug\n        }`
    );
  }

  fs.writeFileSync(gradlePath, gradle);
  console.log('✓ Pinned debug signing key wired directly into build.gradle.');
} else {
  console.log('Debug signing already pinned in build.gradle — nothing to do.');
}
