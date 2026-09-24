import { getD1 } from "@/lib/db";

const encoder = new TextEncoder();
const SESSION_DAYS = 30;
// Cloudflare Workers rejects PBKDF2 work factors above 100,000.
const PBKDF2_ITERATIONS = 100_000;
const RATE_WINDOW_MS = 15 * 60 * 1000;
const RATE_LIMIT = 12;

export type Account = { id: string; email: string };

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/g, "");
}

function fromBase64url(value: string): Uint8Array {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(base64 + "=".repeat((4 - base64.length % 4) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function digestHex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function passwordDigest(password: string, salt: Uint8Array): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const saltBuffer = new ArrayBuffer(salt.byteLength);
  new Uint8Array(saltBuffer).set(salt);
  const result = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: saltBuffer, iterations: PBKDF2_ITERATIONS },
    key,
    256,
  );
  return new Uint8Array(result);
}

function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  let mismatch = a.length ^ b.length;
  const size = Math.max(a.length, b.length);
  for (let i = 0; i < size; i++) mismatch |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return mismatch === 0;
}

async function allowAuthAttempt(request: Request, email: string): Promise<boolean> {
  const db = getD1();
  const address = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const keyHash = await digestHex(`${address}\n${email}`);
  const now = Date.now();
  const boundary = now - RATE_WINDOW_MS;
  const row = await db.prepare(`
    INSERT INTO auth_rate_limits (key_hash, window_started_at, attempts)
    VALUES (?, ?, 1)
    ON CONFLICT(key_hash) DO UPDATE SET
      attempts = CASE WHEN auth_rate_limits.window_started_at <= ? THEN 1 ELSE auth_rate_limits.attempts + 1 END,
      window_started_at = CASE WHEN auth_rate_limits.window_started_at <= ? THEN ? ELSE auth_rate_limits.window_started_at END
    RETURNING attempts
  `).bind(keyHash, now, boundary, boundary, now).first<{ attempts: number }>();
  await db.prepare("DELETE FROM auth_rate_limits WHERE window_started_at < ?").bind(now - 24 * 60 * 60 * 1000).run();
  return (row?.attempts ?? 1) <= RATE_LIMIT;
}

async function createSession(user: Account): Promise<{ token: string; expiresAt: number }> {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const token = base64url(raw);
  const tokenHash = await digestHex(token);
  const createdAt = Date.now();
  const expiresAt = createdAt + SESSION_DAYS * 24 * 60 * 60 * 1000;
  await getD1().prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at, revoked_at) VALUES (?, ?, ?, ?, NULL)")
    .bind(tokenHash, user.id, createdAt, expiresAt).run();
  return { token, expiresAt };
}

export async function registerAccount(request: Request, body: unknown) {
  let stage = "validation";
  try {
    if (!body || typeof body !== "object") return { response: { error: "请求内容无效" }, status: 400 };
    const data = body as Record<string, unknown>;
    const email = typeof data.email === "string" ? data.email.trim().toLowerCase() : "";
    const password = typeof data.password === "string" ? data.password : "";
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
      return { response: { error: "请输入有效邮箱" }, status: 400 };
    }
    if (password.length < 8 || password.length > 128) {
      return { response: { error: "密码需为 8 到 128 个字符" }, status: 400 };
    }
    stage = "rate_limit";
    if (!(await allowAuthAttempt(request, email))) {
      return { response: { error: "操作太频繁，请 15 分钟后再试" }, status: 429 };
    }

    stage = "password_hash";
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const passwordHash = await passwordDigest(password, salt);
    stage = "session_token";
    const account = { id: crypto.randomUUID(), email };
    const tokenBytes = crypto.getRandomValues(new Uint8Array(32));
    const token = base64url(tokenBytes);
    const tokenHash = await digestHex(token);
    const now = Date.now();
    const expiresAt = now + SESSION_DAYS * 24 * 60 * 60 * 1000;
    stage = "database_batch";
    try {
      await getD1().batch([
        getD1().prepare("INSERT INTO users (id,email,password_salt,password_hash,created_at) VALUES (?,?,?,?,?)")
          .bind(account.id, account.email, base64url(salt), base64url(passwordHash), now),
        getD1().prepare("INSERT INTO sessions (token_hash,user_id,created_at,expires_at,revoked_at) VALUES (?,?,?,?,NULL)")
          .bind(tokenHash, account.id, now, expiresAt),
      ]);
    } catch {
      return { response: { error: "该邮箱可能已注册，请尝试登录" }, status: 409 };
    }
    return { response: { account, token, expiresAt }, status: 201 };
  } catch (error) {
    const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    console.error(`[auth/register] failed during ${stage}: ${detail}`);
    throw error;
  }
}

export async function loginAccount(request: Request, body: unknown) {
  if (!body || typeof body !== "object") return { response: { error: "请求内容无效" }, status: 400 };
  const data = body as Record<string, unknown>;
  const email = typeof data.email === "string" ? data.email.trim().toLowerCase() : "";
  const password = typeof data.password === "string" ? data.password : "";
  if (!email || email.length > 254 || !password || password.length > 128) {
    return { response: { error: "邮箱或密码错误" }, status: 401 };
  }
  if (!(await allowAuthAttempt(request, email))) {
    return { response: { error: "操作太频繁，请 15 分钟后再试" }, status: 429 };
  }

  const row = await getD1().prepare("SELECT id,email,password_salt,password_hash FROM users WHERE email=? LIMIT 1")
    .bind(email).first<{ id: string; email: string; password_salt: string; password_hash: string }>();
  const salt = row ? fromBase64url(row.password_salt) : new Uint8Array(16).fill(0x5a);
  const expected = row ? fromBase64url(row.password_hash) : new Uint8Array(32).fill(0x33);
  const actual = await passwordDigest(password, salt);
  if (!row || !constantTimeEqual(actual, expected)) return { response: { error: "邮箱或密码错误" }, status: 401 };
  const account = { id: row.id, email: row.email };
  const session = await createSession(account);
  return { response: { account, ...session }, status: 200 };
}

export async function accountFromRequest(request: Request): Promise<Account | null> {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(authorization);
  if (!match) return null;
  const tokenHash = await digestHex(match[1]);
  const row = await getD1().prepare(`
    SELECT users.id, users.email FROM sessions
    INNER JOIN users ON users.id = sessions.user_id
    WHERE sessions.token_hash = ? AND sessions.revoked_at IS NULL AND sessions.expires_at > ?
    LIMIT 1
  `).bind(tokenHash, Date.now()).first<Account>();
  return row ?? null;
}

export async function revokeSession(request: Request): Promise<void> {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(authorization);
  if (!match) return;
  await getD1().prepare("UPDATE sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL")
    .bind(Date.now(), await digestHex(match[1])).run();
}
