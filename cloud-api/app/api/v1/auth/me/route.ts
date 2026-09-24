import { accountFromRequest } from "@/lib/auth";
import { json, options } from "@/lib/http";

export async function OPTIONS() { return options(); }

export async function GET(request: Request) {
  try {
    const account = await accountFromRequest(request);
    return account ? json({ account }) : json({ error: "请先登录" }, 401);
  } catch {
    return json({ error: "暂时无法验证登录状态" }, 503);
  }
}
