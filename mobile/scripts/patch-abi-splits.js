// Splits the build into one small APK per CPU architecture (armeabi-v7a,
// arm64-v8a, x86, x86_64) instead of one universal APK bundling all four —
// this is what makes each individual download ~50-70MB instead of ~187MB.
// A universal APK (all architectures, works on literally anything) is
// still produced too, for manual first-time installs and as an automatic
// fallback if a device's specific-architecture download ever fails.
// Run BEFORE the Gradle build.

const fs = require('fs');
const path = require('path');

const gradlePath = path.join(__dirname, '..', 'android', 'app', 'build.gradle');
let gradle = fs.readFileSync(gradlePath, 'utf8');

if (!gradle.includes('netgoAbiSplits')) {
  gradle = gradle.replace(
    /android\s*\{/,
    `android {\n    // netgoAbiSplits: one small APK per CPU architecture, plus a\n    // universal one as a fallback for manual installs.\n    splits {\n        abi {\n            enable true\n            reset()\n            include 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'\n            universalApk true\n        }\n    }`
  );
  fs.writeFileSync(gradlePath, gradle);
  console.log('✓ ABI splits enabled — the build will now produce one small APK per architecture, plus a universal one.');
} else {
  console.log('ABI splits already configured — nothing to do.');
}
