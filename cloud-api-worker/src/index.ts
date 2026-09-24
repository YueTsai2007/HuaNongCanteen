type D1Result<T> = { results?: T[]; success?: boolean; meta?: unknown };
type D1Statement = {
  bind(...values: unknown[]): D1Statement;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  run<T = unknown>(): Promise<D1Result<T>>;
};
type D1Database = {
  prepare(sql: string): D1Statement;
  batch<T = unknown>(statements: D1Statement[]): Promise<D1Result<T>[]>;
};
type R2ObjectBody = {
  body: ReadableStream;
  httpEtag: string;
  httpMetadata?: { contentType?: string };
};
type R2Bucket = {
  put(key: string, value: Uint8Array, options?: { httpMetadata?: { contentType?: string; cacheControl?: string } }): Promise<unknown>;
  get(key: string): Promise<R2ObjectBody | null>;
};
type Env = { DB: D1Database; BUCKET: R2Bucket };

type Account = { id: string; email: string };
type DbUser = Account & { password_salt: string; password_hash: string };

const SESSION_DAYS = 30;
const PBKDF2_ITERATIONS = 100_000;
const RATE_WINDOW_MS = 15 * 60 * 1000;
const RATE_LIMIT = 12;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_STATE_BYTES = 1_000_000;
const encoder = new TextEncoder();

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Max-Age": "86400",
  "Cache-Control": "no-store",
};

function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: corsHeaders });
}

function options(): Response {
  return new Response(null, { status: 204, headers: corsHeaders });
}

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

async function allowAuthAttempt(env: Env, request: Request, email: string): Promise<boolean> {
  const address = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const keyHash = await digestHex(`${address}\n${email}`);
  const now = Date.now();
  const boundary = now - RATE_WINDOW_MS;
  const row = await env.DB.prepare(`
    INSERT INTO auth_rate_limits (key_hash, window_started_at, attempts)
    VALUES (?, ?, 1)
    ON CONFLICT(key_hash) DO UPDATE SET
      attempts = CASE WHEN auth_rate_limits.window_started_at <= ? THEN 1 ELSE auth_rate_limits.attempts + 1 END,
      window_started_at = CASE WHEN auth_rate_limits.window_started_at <= ? THEN ? ELSE auth_rate_limits.window_started_at END
    RETURNING attempts
  `).bind(keyHash, now, boundary, boundary, now).first<{ attempts: number }>();
  await env.DB.prepare("DELETE FROM auth_rate_limits WHERE window_started_at < ?")
    .bind(now - 24 * 60 * 60 * 1000).run();
  return (row?.attempts ?? 1) <= RATE_LIMIT;
}

async function createSession(env: Env, account: Account) {
  const token = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenHash = await digestHex(token);
  const createdAt = Date.now();
  const expiresAt = createdAt + SESSION_DAYS * 24 * 60 * 60 * 1000;
  await env.DB.prepare(
    "INSERT INTO sessions (token_hash,user_id,created_at,expires_at,revoked_at) VALUES (?,?,?,?,NULL)",
  ).bind(tokenHash, account.id, createdAt, expiresAt).run();
  return { token, expiresAt };
}

async function readJson(request: Request): Promise<{ value?: unknown; error?: Response }> {
  try {
    return { value: await request.json() };
  } catch {
    return { error: json({ error: "请求内容无效" }, 400) };
  }
}

async function register(env: Env, request: Request): Promise<Response> {
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  if (!parsed.value || typeof parsed.value !== "object" || Array.isArray(parsed.value))
    return json({ error: "请求内容无效" }, 400);

  const data = parsed.value as Record<string, unknown>;
  const email = typeof data.email === "string" ? data.email.trim().toLowerCase() : "";
  const password = typeof data.password === "string" ? data.password : "";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254)
    return json({ error: "请输入有效邮箱" }, 400);
  if (password.length < 8 || password.length > 128)
    return json({ error: "密码需为 8 到 128 个字符" }, 400);
  if (!(await allowAuthAttempt(env, request, email)))
    return json({ error: "操作太频繁，请 15 分钟后再试" }, 429);

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const passwordHash = await passwordDigest(password, salt);
  const account = { id: crypto.randomUUID(), email };
  const token = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenHash = await digestHex(token);
  const now = Date.now();
  const expiresAt = now + SESSION_DAYS * 24 * 60 * 60 * 1000;
  try {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO users (id,email,password_salt,password_hash,created_at) VALUES (?,?,?,?,?)")
        .bind(account.id, account.email, base64url(salt), base64url(passwordHash), now),
      env.DB.prepare("INSERT INTO sessions (token_hash,user_id,created_at,expires_at,revoked_at) VALUES (?,?,?,?,NULL)")
        .bind(tokenHash, account.id, now, expiresAt),
    ]);
  } catch (error) {
    const existing = await env.DB.prepare("SELECT id FROM users WHERE email=? LIMIT 1")
      .bind(email).first<{ id: string }>();
    if (existing) return json({ error: "该邮箱可能已注册，请尝试登录" }, 409);
    throw error;
  }
  return json({ account, token, expiresAt }, 201);
}

async function login(env: Env, request: Request): Promise<Response> {
  const parsed = await readJson(request);
  if (parsed.error) return parsed.error;
  if (!parsed.value || typeof parsed.value !== "object" || Array.isArray(parsed.value))
    return json({ error: "请求内容无效" }, 400);

  const data = parsed.value as Record<string, unknown>;
  const email = typeof data.email === "string" ? data.email.trim().toLowerCase() : "";
  const password = typeof data.password === "string" ? data.password : "";
  if (!email || email.length > 254 || !password || password.length > 128)
    return json({ error: "邮箱或密码错误" }, 401);
  if (!(await allowAuthAttempt(env, request, email)))
    return json({ error: "操作太频繁，请 15 分钟后再试" }, 429);

  const user = await env.DB.prepare(
    "SELECT id,email,password_salt,password_hash FROM users WHERE email=? LIMIT 1",
  ).bind(email).first<DbUser>();
  const salt = user ? fromBase64url(user.password_salt) : new Uint8Array(16).fill(0x5a);
  const expected = user ? fromBase64url(user.password_hash) : new Uint8Array(32).fill(0x33);
  const actual = await passwordDigest(password, salt);
  if (!user || !constantTimeEqual(actual, expected))
    return json({ error: "邮箱或密码错误" }, 401);

  const account = { id: user.id, email: user.email };
  const session = await createSession(env, account);
  return json({ account, ...session });
}

async function accountFromRequest(env: Env, request: Request): Promise<Account | null> {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(authorization);
  if (!match) return null;
  const tokenHash = await digestHex(match[1]);
  return await env.DB.prepare(`
    SELECT users.id, users.email FROM sessions
    INNER JOIN users ON users.id = sessions.user_id
    WHERE sessions.token_hash = ? AND sessions.revoked_at IS NULL AND sessions.expires_at > ?
    LIMIT 1
  `).bind(tokenHash, Date.now()).first<Account>();
}

async function revokeSession(env: Env, request: Request): Promise<void> {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/.exec(authorization);
  if (!match) return;
  await env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL")
    .bind(Date.now(), await digestHex(match[1])).run();
}

function validSnapshot(value: unknown): value is Record<string, unknown[]> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const data = value as Record<string, unknown>;
  const limits: Record<string, number> = { halls: 32, shops: 2000, dishes: 10000, cart: 1000, orders: 50000 };
  for (const [key, limit] of Object.entries(limits)) {
    if (!Array.isArray(data[key]) || data[key].length > limit) return false;
  }
  return true;
}

async function stateRoute(env: Env, request: Request, account: Account): Promise<Response> {
  if (request.method === "GET") {
    const row = await env.DB.prepare(
      "SELECT revision,payload_json,updated_at FROM user_state WHERE user_id=?",
    ).bind(account.id).first<{ revision: number; payload_json: string; updated_at: number }>();
    return json(row
      ? { revision: row.revision, payload: JSON.parse(row.payload_json), updatedAt: row.updated_at }
      : { revision: 0, payload: null, updatedAt: null });
  }
  if (request.method !== "PUT") return json({ error: "不支持的请求方法" }, 405);
  const raw = await request.text();
  if (encoder.encode(raw).byteLength > MAX_STATE_BYTES) return json({ error: "数据包过大" }, 413);
  let body: { revision?: unknown; payload?: unknown };
  try {
    body = JSON.parse(raw) as typeof body;
  } catch {
    return json({ error: "请求内容无效" }, 400);
  }
  if (!Number.isSafeInteger(body.revision) || (body.revision as number) < 0 || !validSnapshot(body.payload))
    return json({ error: "数据格式不正确" }, 400);
  const payload = JSON.stringify(body.payload);
  const now = Date.now();
  const result = await env.DB.prepare(`
    INSERT INTO user_state (user_id,revision,payload_json,updated_at)
    VALUES (?,1,?,?)
    ON CONFLICT(user_id) DO UPDATE SET
      revision = user_state.revision + 1,
      payload_json = excluded.payload_json,
      updated_at = excluded.updated_at
    WHERE user_state.revision = ?
    RETURNING revision, updated_at
  `).bind(account.id, payload, now, body.revision).first<{ revision: number; updated_at: number }>();
  if (!result) {
    const current = await env.DB.prepare("SELECT revision FROM user_state WHERE user_id=?")
      .bind(account.id).first<{ revision: number }>();
    return json({ error: "云端数据已更新，请先同步后再保存", revision: current?.revision ?? 0 }, 409);
  }
  return json({ revision: result.revision, updatedAt: result.updated_at });
}

function detectImage(bytes: Uint8Array): string | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return "image/png";
  if (bytes.length >= 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" &&
      String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") return "image/webp";
  return null;
}

async function imageRoute(env: Env, request: Request, account: Account): Promise<Response> {
  if (request.method === "POST") {
    const declared = Number(request.headers.get("Content-Length") ?? 0);
    if (declared > MAX_IMAGE_BYTES) return json({ error: "图片不能超过 5 MB" }, 413);
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) return json({ error: "图片不能超过 5 MB" }, 413);
    const contentType = detectImage(bytes);
    if (!contentType) return json({ error: "仅支持 JPEG、PNG 或 WebP 图片" }, 415);
    const imageId = crypto.randomUUID();
    await env.BUCKET.put(`users/${account.id}/images/${imageId}`, bytes, {
      httpMetadata: { contentType, cacheControl: "private, max-age=86400" },
    });
    return json({ imageId });
  }
  if (request.method === "GET") {
    const imageId = new URL(request.url).searchParams.get("id") ?? "";
    if (!/^[0-9a-f-]{36}$/i.test(imageId)) return json({ error: "图片不存在" }, 404);
    const object = await env.BUCKET.get(`users/${account.id}/images/${imageId}`);
    if (!object) return json({ error: "图片不存在" }, 404);
    return new Response(object.body, { headers: {
      "Content-Type": object.httpMetadata?.contentType ?? "application/octet-stream",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "private, max-age=86400",
      ETag: object.httpEtag,
    } });
  }
  return json({ error: "不支持的请求方法" }, 405);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return options();
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (path === "/api/v1/health" && request.method === "GET") {
        await env.DB.prepare("SELECT 1 AS ready").first();
        return json({ ok: true, database: "connected" });
      }
      if (path === "/api/v1/auth/register" && request.method === "POST") return await register(env, request);
      if (path === "/api/v1/auth/login" && request.method === "POST") return await login(env, request);
      if (path === "/api/v1/auth/me" && request.method === "GET") {
        const account = await accountFromRequest(env, request);
        return account ? json({ account }) : json({ error: "请先登录" }, 401);
      }
      if (path === "/api/v1/auth/logout" && request.method === "POST") {
        await revokeSession(env, request);
        return json({ ok: true });
      }
      if (path === "/api/v1/state") {
        const account = await accountFromRequest(env, request);
        if (!account) return json({ error: "请先登录" }, 401);
        return await stateRoute(env, request, account);
      }
      if (path === "/api/v1/images") {
        const account = await accountFromRequest(env, request);
        if (!account) return json({ error: "请先登录" }, 401);
        return await imageRoute(env, request, account);
      }
      return json({ error: "接口不存在" }, 404);
    } catch (error) {
      console.error("[huanong-api] request failed", error instanceof Error ? error.message : error);
      return json({ error: "云端服务暂时不可用，请稍后重试" }, 503);
    }
  },
};

