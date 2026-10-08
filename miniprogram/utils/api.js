/**
 * 云端 API 客户端：1:1 对齐 Android 端 CloudApi.kt 的 8 个端点。
 * 所有请求走 HTTPS + Bearer 会话令牌；无第三方依赖。
 */
const config = require('../config');

const SESSION_KEY = 'hc_session';

function getSession() {
  return wx.getStorageSync(SESSION_KEY) || null;
}

function saveSession(session) {
  wx.setStorageSync(SESSION_KEY, session);
}

function clearSession() {
  try { wx.removeStorageSync(SESSION_KEY); } catch (e) { /* ignore */ }
}

function rawRequest(path, options) {
  const opts = options || {};
  const session = getSession();
  const header = Object.assign({ Accept: 'application/json' }, opts.header || {});
  if (session && session.token) header.Authorization = 'Bearer ' + session.token;
  if (config.SITE_GATE_TOKEN) header['OAI-Sites-Authorization'] = 'Bearer ' + config.SITE_GATE_TOKEN;
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.API_BASE.replace(/\/+$/, '') + path,
      method: opts.method || 'GET',
      data: opts.data,
      header,
      responseType: opts.responseType || 'text',
      timeout: 20000,
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res);
        } else {
          let message = '云端请求失败 (' + res.statusCode + ')';
          if (res.data && typeof res.data === 'object' && res.data.error) message = res.data.error;
          reject({ statusCode: res.statusCode, message });
        }
      },
      fail() {
        reject({ statusCode: 0, message: '网络不可用或服务无法访问' });
      }
    });
  });
}

function json(path, method, data) {
  return rawRequest(path, { method, data }).then((res) => res.data);
}

function toSession(res) {
  return { token: res.token, id: res.account.id, email: res.account.email };
}

function register(email, password) {
  return json('/api/v1/auth/register', 'POST', { email, password }).then(toSession);
}

function login(email, password) {
  return json('/api/v1/auth/login', 'POST', { email, password }).then(toSession);
}

function checkSession() {
  return json('/api/v1/auth/me').then((x) => x.account);
}

function logout() {
  return json('/api/v1/auth/logout', 'POST', {});
}

function getState() {
  return json('/api/v1/state').then((x) => ({
    revision: x.revision || 0,
    payload: x.payload || null,
    updatedAt: x.updatedAt || null
  }));
}

function putState(revision, payload) {
  return json('/api/v1/state', 'PUT', { revision, payload }).then((x) => x.revision);
}

function uploadImage(arrayBuffer) {
  return rawRequest('/api/v1/images', {
    method: 'POST',
    data: arrayBuffer,
    header: { 'Content-Type': 'application/octet-stream' }
  }).then((res) => res.data.imageId);
}

function downloadImage(imageId) {
  return rawRequest('/api/v1/images?id=' + imageId, { responseType: 'arraybuffer' })
    .then((res) => res.data);
}

module.exports = {
  getSession,
  saveSession,
  clearSession,
  register,
  login,
  checkSession,
  logout,
  getState,
  putState,
  uploadImage,
  downloadImage
};
