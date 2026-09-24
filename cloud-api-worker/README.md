# 华农食堂 Cloudflare Worker API

该 Worker 将账号、菜单、购物车、订单与统计快照存入你自己的 Cloudflare D1；店铺和菜品图片用 R2 保存。API 路径与 Android 客户端现有云同步接口兼容。

## 部署前

1. 在同一个 Cloudflare 账号创建 R2 bucket，名称必须为 `huanongcanteen-images`。Wrangler 配置已将它绑定为 `BUCKET`。
2. 确认 D1 数据库 `huanong` 的 ID 是 `afd8d1e9-8a26-4a10-ab2c-6144ac991cb7`。
3. 将 GitHub 仓库连接到 Cloudflare Workers Builds，Root directory 填 `cloud-api-worker`。
4. Deployment command 填 `npm run deploy`。此命令先对远程 D1 应用迁移，再部署 Worker。
5. 部署完成后访问 `https://<Worker 域名>/api/v1/health`，应返回 `{"ok":true,"database":"connected"}`。

## 本机命令

需要 Node.js 22.13 或更新版本，并通过 `npx wrangler login` 登录 Cloudflare：

```sh
npm install
npm run typecheck
npm run deploy
```

不要把 Cloudflare API Token 写入源码。Android 客户端切换到最终 Worker 域名后，用 Gradle 属性 `-PcloudApiBase=https://<Worker 域名>` 构建。

