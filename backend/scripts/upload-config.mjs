#!/usr/bin/env node
// Hilfsskript zum Upload einer XML-Datei via PUT /admin/config/:env
// Usage: node scripts/upload-config.mjs <env> <xml-file>
// Erwartet ENV-Vars: KIOSK_API_BASE und KIOSK_ADMIN_TOKEN

import { readFile } from 'node:fs/promises';

const [, , env, file] = process.argv;
if (!env || !file) {
  console.error('Usage: upload-config.mjs <env> <xml-file>');
  process.exit(1);
}

const base = process.env.KIOSK_API_BASE;
const token = process.env.KIOSK_ADMIN_TOKEN;
if (!base || !token) {
  console.error('Missing env: KIOSK_API_BASE and/or KIOSK_ADMIN_TOKEN');
  process.exit(1);
}

const xml = await readFile(file, 'utf8');

const res = await fetch(`${base}/admin/config/${env}`, {
  method: 'PUT',
  headers: {
    'Content-Type': 'application/xml',
    Authorization: `Bearer ${token}`,
  },
  body: xml,
});

const out = await res.text();
console.log(`HTTP ${res.status}\n${out}`);
process.exit(res.ok ? 0 : 1);
