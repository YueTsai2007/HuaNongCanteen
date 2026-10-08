/**
 * 全局配置。
 *
 * API_BASE：云端 API 地址。
 *  - 开发期可暂时指向旧 Sites 域名（配合开发者工具“不校验合法域名”）
 *  - 正式使用请部署 cloud-api-worker 后替换为自己的 Worker 域名，
 *    并在小程序后台「开发设置 → 服务器域名 → request 合法域名」中登记。
 * SITE_GATE_TOKEN：仅旧 Sites 访客门禁需要；使用自建 Worker 时留空。
 */
module.exports = {
  API_BASE: 'https://huanong-canteen-api.martiansztu2007.chatgpt.site',
  SITE_GATE_TOKEN: '',
  VERSION: '0.1.0'
};
