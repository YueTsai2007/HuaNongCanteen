import { accountFromRequest } from "@/lib/auth";
import { getD1 } from "@/lib/db";
import { json, options } from "@/lib/http";

const MAX_STATE_BYTES = 1_000_000;

function validSnapshot(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const data = value as Record<string, unknown>;
  const limits: Record<string, number> = { halls: 32, shops: 2000, dishes: 10000, cart: 1000, orders: 50000 };
  for (const [key, limit] of Object.entries(limits)) {
    if (!Array.isArray(data[key]) || data[key].length > limit) return false;
  }
  return true;
}

export async function OPTIONS() { return options(); }

export async function GET(request: Request) {
  try {
    const account = await accountFromRequest(request);
    if (!account) return json({ error: "请先登录" }, 401);
    const row = await getD1().prepare("SELECT revision,payload_json,updated_at FROM user_state WHERE user_id=?")
      .bind(account.id).first<{ revision: number; payload_json: string; updated_at: number }>();
    return json(row
      ? { revision: row.revision, payload: JSON.parse(row.payload_json), updatedAt: row.updated_at }
      : { revision: 0, payload: null, updatedAt: null });
  } catch {
    return json({ error: "云端数据暂时无法读取" }, 503);
  }
}

export async function PUT(request: Request) {
  try {
    const account = await accountFromRequest(request);
    if (!account) return json({ error: "请先登录" }, 401);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_STATE_BYTES) return json({ error: "数据包过大" }, 413);
    const body = JSON.parse(raw) as { revision?: unknown; payload?: unknown };
    if (!Number.isSafeInteger(body.revision) || (body.revision as number) < 0 || !validSnapshot(body.payload)) {
      return json({ error: "数据格式不正确" }, 400);
    }
    const payload = JSON.stringify(body.payload);
    const now = Date.now();
    const result = await getD1().prepare(`
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
      const current = await getD1().prepare("SELECT revision FROM user_state WHERE user_id=?")
        .bind(account.id).first<{ revision: number }>();
      return json({ error: "云端数据已更新，请先同步后再保存", revision: current?.revision ?? 0 }, 409);
    }
    return json({ revision: result.revision, updatedAt: result.updated_at });
  } catch {
    return json({ error: "云端数据暂时无法保存" }, 503);
  }
}
