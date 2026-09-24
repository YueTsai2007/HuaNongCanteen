import { getD1 } from "@/lib/db";
import { json, options } from "@/lib/http";

export async function OPTIONS() { return options(); }

export async function GET() {
  try {
    await getD1().prepare("SELECT 1 AS ok").first();
    return json({ service: "huanong-canteen", status: "ok", apiVersion: 1 });
  } catch {
    return json({ service: "huanong-canteen", status: "unavailable" }, 503);
  }
}
