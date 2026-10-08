/**
 * 图片展示辅助：优先本地文件；只有云端 imageId 时下载到用户目录并缓存。
 * GET /api/v1/images 需要 Bearer 令牌，不能直接放进 <image src>，所以先落盘。
 */
const api = require('./api');
const store = require('./store');

const _inflight = {};

function ensureDir() {
  const fs = wx.getFileSystemManager();
  const dir = wx.env.USER_DATA_PATH + '/menu-images';
  try { fs.mkdirSync(dir, true); } catch (e) { /* 已存在 */ }
  return dir;
}

function downloadToCache(entity, id, imageId) {
  const key = entity + '/' + id;
  if (_inflight[key]) return _inflight[key];
  _inflight[key] = api.downloadImage(imageId)
    .then((buf) => {
      const dir = ensureDir();
      const path = dir + '/' + imageId;
      wx.getFileSystemManager().writeFileSync(path, buf);
      store.setLocalImage(entity, id, path);
      delete _inflight[key];
      return path;
    })
    .catch(() => {
      delete _inflight[key];
      return '';
    });
  return _inflight[key];
}

/** 返回可直接用于 <image> 的本地路径；失败返回 ''。 */
function resolve(entity, item) {
  if (!item) return Promise.resolve('');
  const local = store.localImage(entity, item.id);
  if (local) return Promise.resolve(local);
  if (item.imageId) return downloadToCache(entity, item.id, item.imageId);
  return Promise.resolve('');
}

module.exports = { resolve, ensureDir };
