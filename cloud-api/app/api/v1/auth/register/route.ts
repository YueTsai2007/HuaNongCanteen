import { registerAccount } from "@/lib/auth";
import { json, options } from "@/lib/http";

export async function OPTIONS() { return options(); }

export async function POST(request: Request) {
  try {
    const result = await registerAccount(request, await request.json());
    return json(result.response, result.status);
  } catch {
    return json({ error: "暂时无法注册，请稍后再试" }, 503);
  }
}
