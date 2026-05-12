/**
 * Einfache Auth:
 * - Geräte-Endpoints (/config, /ws): public
 * - Admin-Endpoints (/admin, /api): Bearer-Token im Header ODER Basic-Auth
 * - Admin-UI: gleiche Auth, plus Session-Cookie nach Login
 */
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

const sessions = new Map(); // token -> { user, expires }

let adminUser;
let adminPasswordHash;

export function initAuth({ user, password }) {
  adminUser = user;
  adminPasswordHash = hashPassword(password);
}

function hashPassword(pw) {
  return createHash('sha256').update(pw).digest('hex');
}

function safeEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a), Buffer.from(b));
}

export function verifyCredentials(user, password) {
  return safeEqual(user, adminUser) && safeEqual(hashPassword(password), adminPasswordHash);
}

export function createSession(user) {
  const token = randomBytes(32).toString('hex');
  sessions.set(token, { user, expires: Date.now() + 24 * 3600 * 1000 });
  return token;
}

export function validateSession(token) {
  if (!token) return null;
  const s = sessions.get(token);
  if (!s) return null;
  if (s.expires < Date.now()) {
    sessions.delete(token);
    return null;
  }
  return s.user;
}

export function destroySession(token) {
  sessions.delete(token);
}

/**
 * Hono-Middleware. Akzeptiert:
 *   1. Cookie session=<token>
 *   2. Authorization: Bearer <token>
 *   3. Authorization: Basic base64(user:pass)
 */
export async function requireAuth(c, next) {
  // Cookie
  const cookieHeader = c.req.header('Cookie') || '';
  const cookieMatch = cookieHeader.match(/(?:^|;\s*)session=([^;]+)/);
  if (cookieMatch) {
    const user = validateSession(cookieMatch[1]);
    if (user) {
      c.set('user', user);
      await next();
      return;
    }
  }
  // Authorization header
  const auth = c.req.header('Authorization') || '';
  if (auth.startsWith('Bearer ')) {
    const user = validateSession(auth.slice(7));
    if (user) {
      c.set('user', user);
      await next();
      return;
    }
  }
  if (auth.startsWith('Basic ')) {
    try {
      const decoded = Buffer.from(auth.slice(6), 'base64').toString('utf8');
      const [user, ...pwParts] = decoded.split(':');
      const password = pwParts.join(':');
      if (verifyCredentials(user, password)) {
        c.set('user', user);
        await next();
        return;
      }
    } catch {}
  }
  return c.json({ error: 'unauthorized' }, 401);
}
