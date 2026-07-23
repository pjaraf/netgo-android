const fs = require('fs');
const path = require('path');

const version = process.env.APP_VERSION || '0';
const content = `window.NETGO_APP_VERSION = ${version};\n`;
fs.writeFileSync(path.join(__dirname, '..', 'www', 'version.js'), content);
console.log('✓ App version stamped: ' + version);
