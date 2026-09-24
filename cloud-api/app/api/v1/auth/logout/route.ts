import { revokeSession } from "@/lib/auth";
import { json, options } from "@/lib/http";

export async function OPTIONS() { return options(); }

export async function POST(request: Request) {
  try {
    await revokeSession(request);
    return json({ ok: true });
  } catch {
    return json({ error: "暂时无法退出登录" }, 503);
  }
}
