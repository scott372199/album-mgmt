#!/usr/bin/env node

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const IGNORED = new Set(['.git', '.gradle', 'build', 'node_modules', 'dist']);
const TEXT_EXTENSIONS = new Set([
  '.java', '.json', '.js', '.kts', '.md', '.properties', '.ts', '.tsx', '.yml', '.yaml',
]);
const FORBIDDEN = [
  /AKIA[0-9A-Z]{16}/,
  /BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY/,
  /\bgh[pousr]_[A-Za-z0-9]{20,}\b/,
  /\/Users\/[A-Za-z0-9._-]+\//,
];
const MANAGEMENT_ORIGIN = 'cx.chenyouyi.top';
const ALBUM_ORIGIN = 'photos.chenyouyi.top';
const WRITE_MARKERS = [
  /\bterraform\s+apply\b/i,
  /\biam:\*/,
  /\bDeleteResource\b/,
];

const problems = [];

async function walk(dir, relative = '') {
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (IGNORED.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    const fileRelative = path.join(relative, entry.name);
    if (entry.isDirectory()) {
      await walk(file, fileRelative);
      continue;
    }
    const extension = path.extname(entry.name).toLowerCase();
    if (!TEXT_EXTENSIONS.has(extension)) continue;
    const info = await stat(file);
    if (info.size > 512 * 1024) {
      problems.push(`${fileRelative}: text file exceeds 512KB`);
      continue;
    }
    const text = await readFile(file, 'utf8');
    for (const pattern of FORBIDDEN) {
      if (pattern.test(text)) problems.push(`${fileRelative}: forbidden sensitive pattern`);
    }
    if (text.includes(MANAGEMENT_ORIGIN) && text.includes(ALBUM_ORIGIN)) {
      problems.push(`${fileRelative}: management and album origins are mixed`);
    }
    if (fileRelative.startsWith('app/src/') && WRITE_MARKERS.some((pattern) => pattern.test(text))) {
      problems.push(`${fileRelative}: prototype contains a forbidden production write marker`);
    }
  }
}

await walk(ROOT);

if (problems.length) {
  console.error('album-mgmt check failed:');
  for (const problem of problems) console.error(`  ✗ ${problem}`);
  process.exit(1);
}

console.log('album-mgmt check passed');
