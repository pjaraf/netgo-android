// Since we started building 5 APK variants per run (universal + one per
// CPU architecture), packaging them all in the same Gradle build needs
// much more memory than Gradle's default allocation — which was causing
// "java.lang.OutOfMemoryError" during the :app:packageDebug task. This
// raises Gradle's JVM heap size to a level that fits comfortably within a
// GitHub Actions runner's ~7GB of RAM.
// Run AFTER `npx cap add android` and BEFORE the Gradle build.

const fs = require('fs');
const path = require('path');

const propsPath = path.join(__dirname, '..', 'android', 'gradle.properties');
let props = fs.existsSync(propsPath) ? fs.readFileSync(propsPath, 'utf8') : '';

const jvmArgsLine = "org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8";

if (props.includes('org.gradle.jvmargs')) {
  props = props.replace(/org\.gradle\.jvmargs=.*/g, jvmArgsLine);
} else {
  props += `\n${jvmArgsLine}\n`;
}

fs.writeFileSync(propsPath, props);
console.log('✓ Increased Gradle memory allocation (org.gradle.jvmargs=-Xmx4096m).');
