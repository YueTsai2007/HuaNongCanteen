# 华农食堂 · 微信小程序版

把 GitHub `YueTsai2007/HuaNongCanteen`（Android 原生客户端）的校园点单功能重写为**原生微信小程序**，与 Android 端**共用同一套云端 API、账号和数据快照**。

## 功能范围

- 首页 8 个食堂入口（荷园/芷园/莘园/西园/稻香园/绿榕园/小吃街/外卖）
- 店铺列表、店铺图片、菜品分类浏览；可自行添加店铺和菜品（含图片）
- 加购物车（支持口味备注，不同备注分开计价）、结算下单
- 历史订单（全部 / 近 7 天 / 近 30 天）、消费统计与热门菜品 TOP5
- 邮箱 + 密码注册登录；不登录可离线使用
- 云同步：整份快照上传（先传图片再写状态），修订号乐观锁，409 冲突时用户选择「保留本机 / 使用云端」
- 断网自动进入离线模式，恢复网络自动重连同步

## 目录结构

```
miniprogram/
├── app.js / app.json / app.wxss     入口与全局配置
├── config.js                        API 地址等配置（部署时改这里）
├── project.config.json              开发者工具项目配置
├── utils/
│   ├── api.js      云端 API 客户端（8 个端点，对齐 CloudApi.kt）
│   ├── store.js    本地数据层（快照结构与 Android 端 exportCloudSnapshot 同构）
│   ├── sync.js     云同步（代次标记 / revision 乐观锁 / 冲突处理）
│   ├── images.js   云端图片下载缓存（/images 需带令牌，先落盘再显示）
│   └── format.js   金额（整数分）与日期格式化
└── pages/
    ├── halls/    食堂入口（tab）
    ├── shops/    食堂下的店铺列表 + 添加店铺
    ├── shop/     菜品浏览 / 加购 / 添加菜品
    ├── cart/     购物车与结算（tab）
    ├── orders/   订单与统计（tab）
    ├── login/    登录注册 / 先离线使用
    └── profile/  账号、同步状态、冲突处理、退出登录（tab）
```

## 本地开发（无需 AppID）

1. 安装[微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)（稳定版）。
2. 「导入项目」→ 选择本 `miniprogram/` 目录 → AppID 选「测试号」。
3. 开发期调用后端需要在 **详情 → 本地设置** 勾选「不校验合法域名…」（`project.config.json` 已默认 `urlCheck: false`）。
4. 编译即可预览。默认数据指向旧 Sites API 域名；要指向自己的服务，改 `config.js` 的 `API_BASE`。

## 部署后端（Cloudflare Worker + D1 + R2）

仓库 `cloud-api-worker/` 的 Worker 已与小程序的 API 客户端完全兼容：

1. 在你的 Cloudflare 账号创建 D1 数据库和 R2 bucket（`huanongcanteen-images`）。
2. 修改 `cloud-api-worker/wrangler.jsonc` 里的 `database_name` / `database_id` / `bucket_name` 为**你自己**的资源值（原仓库里的 D1 ID 是上一任账号的资源，不能复用）。
3. 在 `cloud-api-worker/` 目录执行：

   ```bash
   npm install
   npm run typecheck
   npm run db:migrate   # 对远程 D1 应用迁移，执行前确认登录的是目标账号
   npx wrangler deploy
   ```

4. 访问 `https://<你的 Worker 域名>/api/v1/health`，返回 `{"ok":true,"database":"connected"}` 即部署成功（注意：这只代表 D1 连通，注册/同步/图片仍需按交接文档 P0 清单端到端验收）。

## 配置小程序的服务器域名

正式环境（真机预览/发布）必须在小程序管理后台配置，域名要求 HTTPS 且已备案：

- **request 合法域名**：`https://<你的 Worker 域名>`（覆盖 auth / state / 图片上传）
- 图片下载也走 `wx.request`（带 Authorization 头），同样由 request 域名覆盖，无需单独配置 downloadFile

配置位置：mp.weixin.qq.com → 开发管理 → 开发设置 → 服务器域名。

## 发布上线

1. 在 mp.weixin.qq.com 注册小程序（个人主体免费），拿到 AppID。
2. 把 AppID 填入 `project.config.json` 的 `appid` 字段。
3. 开发者工具里「上传」→ 后台设为体验版 → 真机验收（注册、离线下单、双端同步、冲突）。
4. 提交审核。**注意**：餐饮外卖/点单类目可能要求食品经营等相关资质，个人主体部分类目受限；若审核被拒，可考虑调整类目或服务范围（如仅做菜单浏览 + 自提备注，不做在线交易）。
5. 若做在线支付，还需接入微信支付并具备相应主体资质。

## 与 Android 端的兼容性说明

- 快照格式完全同构：`schemaVersion: 1`，数组键 `halls / shops / dishes / cart / orders`，字段名（`price`、`imageId`、`hallId` 等）与 Android 端 `exportCloudSnapshot()` 一致。
- 金额一律整数「分」；订单行保存下单时的店名/菜名/单价快照。
- 购物车键规则一致：`dishId:note`，不同备注分开计价。
- **会话令牌的差异**：Android 端用 Android Keystore AES-GCM 加密存储；小程序端受平台能力限制，令牌以明文存于小程序沙箱 storage。小程序与宿主隔离，风险可接受，但请知悉此差异。
- 暂未实现：ZIP/CSV 导入导出（小程序端无文件系统遍历能力，后续可导出 CSV 到剪贴板或文件）；微信一键登录（现有后端只有邮箱密码体系，接入 `wx.login` 需后端增加 code2Session 接口）。

## 已知边界

- 快照整包上传且服务端限制 1 MB，订单量极大后需做增量同步（与 Android 端相同的限制）。
- 两台设备同时离线修改会产生 409 冲突，需要手动选择保留哪份；覆盖前建议先在订单页核对数据。
