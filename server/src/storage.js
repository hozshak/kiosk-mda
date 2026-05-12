/**
 * XML-Config Speicher als Dateien im data/configs/ Verzeichnis.
 * Hält in-memory die zuletzt geladene XML inkl. ETag für schnelle If-None-Match-Antworten.
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

let baseDir;
const cache = new Map(); // env -> { xml, etag, mtime }

export function initStorage(dataDir) {
  baseDir = join(dataDir, 'configs');
  mkdirSync(baseDir, { recursive: true });
}

function pathFor(env) {
  return join(baseDir, `${env}.xml`);
}

export function readConfig(env) {
  const p = pathFor(env);
  if (!existsSync(p)) return null;
  const stat = statSync(p);
  const cached = cache.get(env);
  if (cached && cached.mtime === stat.mtimeMs) return cached;

  const xml = readFileSync(p, 'utf8');
  const etag = `"${createHash('sha1').update(xml).digest('hex')}"`;
  const entry = { xml, etag, mtime: stat.mtimeMs };
  cache.set(env, entry);
  return entry;
}

export function writeConfig(env, xml) {
  const p = pathFor(env);
  writeFileSync(p, xml, 'utf8');
  const etag = `"${createHash('sha1').update(xml).digest('hex')}"`;
  const stat = statSync(p);
  const entry = { xml, etag, mtime: stat.mtimeMs };
  cache.set(env, entry);
  return entry;
}

export function listEnvs() {
  return ['test', 'prod'];
}
