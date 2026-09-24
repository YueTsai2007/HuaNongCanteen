import { env } from "cloudflare:workers";

export function getD1(): D1Database {
  if (!env.DB) throw new Error("Cloud database is unavailable");
  return env.DB;
}

export function getBucket(): R2Bucket {
  if (!env.BUCKET) throw new Error("Image storage is unavailable");
  return env.BUCKET;
}
