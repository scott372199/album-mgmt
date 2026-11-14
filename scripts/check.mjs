#!/usr/bin/env node

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const IGNORED = new Set(['.git', '.gradle', 'build', 'node_modules', 'dist']);
const TEXT_EXTENSIONS = new Set([
  '.java', '.json', '.js', '.jsx', '.kts', '.md', '.properties', '.ts', '.tsx', '.yml', '.yaml',
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

/* ---------- 结构断言 / structural assertions ---------- */
// 2026-11-14：生产镜像只打包 jar，导致 cx.chenyouyi.top 没有登录页与七域 UI。
// 这里把「前端必须进镜像」「CI 必须构建前端」「公网登录页不得印旧口令」钉死。
// The image used to ship only the jar, so the dashboard was missing in production.
// Pin the frontend build, the CI build step, and the absence of retired credentials.
const dockerfile = await readFile(path.join(ROOT, 'Dockerfile'), 'utf8');
const workflow = await readFile(path.join(ROOT, '.github/workflows/container.yml'), 'utf8');
const loginUi = await readFile(path.join(ROOT, 'web/src/main.jsx'), 'utf8');
let checks = 0;

function check(label, fn) {
  checks += 1;
  const before = problems.length;
  fn();
  if (problems.length > before) problems.push(`  ↳ ${label}`);
}

check('生产镜像打包前端控制台 / image ships the web console', () => {
  for (const needle of ['AS web', 'npm ci', 'npm run build', 'COPY --from=web', '/app/static/', 'static-locations']) {
    if (!dockerfile.includes(needle)) problems.push(`Dockerfile 缺少前端打包关键步骤：${needle}`);
  }
});

check('登录页不出现已废弃的公开口令 / no retired public key in the login UI', () => {
  if (/album-mgmt-demo/.test(loginUi)) {
    problems.push('web/src/main.jsx 仍印着公开演示口令，生产已换 SSM 随机强值');
  }
});

check('前端改动触发 CI 且 PR 上会构建 / web changes trigger CI and build on PRs', () => {
  if (!workflow.includes('"web/**"')) problems.push('container.yml 的 paths 未包含 web/**');
  if (!workflow.includes('Build web console')) problems.push('container.yml 缺少 PR 前端构建步骤');
});

if (problems.length) {
  console.error('album-mgmt check failed:');
  for (const problem of problems) console.error(`  ✗ ${problem}`);
  process.exit(1);
}

console.log(`album-mgmt check passed — ${checks} 项结构断言 / structural assertions`);
