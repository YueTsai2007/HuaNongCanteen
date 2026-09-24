import { accountFromRequest } from "@/lib/auth";
import { getBucket } from "@/lib/db";
import { json, options } from "@/lib/http";

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function detectImage(bytes: Uint8Array): string | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return "image/png";
  if (bytes.length >= 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") return "image/webp";
  return null;
}

export async function OPTIONS() { return options(); }

export async function POST(request: Request) {
  try {
    const account = await accountFromRequest(request);
    if (!account) return json({ error: "请先登录" }, 401);
    const declared = Number(request.headers.get("Content-Length") ?? 0);
    if (declared > MAX_IMAGE_BYTES) return json({ error: "图片不能超过 5 MB" }, 413);
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (!bytes.length || bytes.length > MAX_IMAGE_BYTES) return json({ error: "图片不能超过 5 MB" }, 413);
    const contentType = detectImage(bytes);
    if (!contentType) return json({ error: "仅支持 JPEG、PNG 或 WebP 图片" }, 415);
    const imageId = crypto.randomUUID();
    const key = `users/${account.id}/images/${imageId}`;
    await getBucket().put(key, bytes, { httpMetadata: { contentType, cacheControl: "private, max-age=86400" } });
    return json({ imageId });
  } catch {
    return json({ error: "图片上传失败，请稍后重试" }, 503);
  }
}

export async function GET(request: Request) {
  try {
    const account = await accountFromRequest(request);
    if (!account) return json({ error: "请先登录" }, 401);
    const imageId = new URL(request.url).searchParams.get("id") ?? "";
    if (!/^[0-9a-f-]{36}$/i.test(imageId)) return json({ error: "图片不存在" }, 404);
    const object = await getBucket().get(`users/${account.id}/images/${imageId}`);
    if (!object) return json({ error: "图片不存在" }, 404);
    return new Response(object.body, { headers: {
      "Content-Type": object.httpMetadata?.contentType ?? "application/octet-stream",
      "Cache-Control": "private, max-age=86400",
      "Access-Control-Allow-Origin": "*",
      ETag: object.httpEtag,
    } });
  } catch {
    return json({ error: "图片暂时无法读取" }, 503);
  }
}
