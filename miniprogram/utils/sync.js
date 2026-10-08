/**
 * 云同步管理：移植 Android 端 MainViewModel + OfflineSyncStore 的策略。
 * - 本机修改先落本地（generation 代次 +1），再尝试整份快照上传；
 * - 远端 revision 与本机记录不一致 → 409 冲突，由用户选择保留本机 / 使用云端；
 * - 无本地待同步修改时直接拉取云端快照覆盖本机；
 * - 快照先传图片（/api/v1/images），再写状态（/api/v1/state）。
 */
const api = require('./api');
const store = require('./store');
const images = require('./images');

const SYNC_KEY = 'hc_sync';
let statusHandler = null;
let syncTimer = null;
let _busy = false;

function onStatus(fn) {
  statusHandler = fn;
}

function emit(text, conflict) {
  if (statusHandler) statusHandler({ text, conflict: !!conflict });
}

function state() {
  return wx.getStorageSync(SYNC_KEY) || {};
}

function saveState(s) {
  wx.setStorageSync(SYNC_KEY, s);
}

/* ----- OfflineSyncStore 对应实现 ----- */

function generation(accountId) {
  return state()['g.' + accountId] || 0;
}

function lastRevision(accountId) {
  return state()['r.' + accountId] || 0;
}

function syncedGeneration(accountId) {
  return state()['s.' + accountId] || 0;
}

function hasPending(accountId) {
  return generation(accountId) > syncedGeneration(accountId);
}

function markPending(accountId) {
  const s = state();
  s['g.' + accountId] = generation(accountId) + 1;
  saveState(s);
}

function markSyncedIfUnchanged(accountId, expectedGeneration, revision) {
  const s = state();
  const unchanged = generation(accountId) === expectedGeneration;
  s['r.' + accountId] = revision;
  if (unchanged) s['s.' + accountId] = expectedGeneration;
  saveState(s);
  return unchanged;
}

function acceptRemote(accountId, revision) {
  const s = state();
  s['r.' + accountId] = revision;
  s['s.' + accountId] = generation(accountId);
  saveState(s);
}

function setRevision(accountId, revision) {
  const s = state();
  s['r.' + accountId] = revision;
  saveState(s);
}

function markGuestPending() {
  const s = state();
  s.guestPending = true;
  saveState(s);
}

function hasGuestPending() {
  return !!state().guestPending;
}

function clearGuestPending() {
  const s = state();
  delete s.guestPending;
  saveState(s);
}

function offlineMode() {
  return !!state().offlineMode;
}

function setOfflineMode(enabled) {
  const s = state();
  s.offlineMode = !!enabled;
  saveState(s);
}

/** 任何本地修改前调用：登录用户记代次，游客记 guest_pending。 */
function markLocalChange() {
  const session = api.getSession();
  if (session && session.id) {
    markPending(session.id);
  } else {
    markGuestPending();
  }
}

/* ----- 图片与快照 ----- */

async function uploadLocalImages() {
  const pending = store.pendingCloudImages();
  const fs = wx.getFileSystemManager();
  for (let i = 0; i < pending.length; i++) {
    const item = pending[i];
    try {
      const buf = fs.readFileSync(item.path);
      if (buf && buf.byteLength > 0 && buf.byteLength <= 5 * 1024 * 1024) {
        const imageId = await api.uploadImage(buf);
        store.setImageId(item.entity, item.id, imageId);
      }
    } catch (e) {
      // 单张图片失败不阻塞整份快照同步，下次同步会重试
    }
  }
}

async function downloadCloudImages() {
  const need = store.cloudImagesNeedingDownload();
  for (let i = 0; i < need.length; i++) {
    const item = need[i];
    await images.resolve(item.entity, { id: item.id, imageId: item.imageId });
  }
}

async function pushLocal(accountId) {
  await uploadLocalImages();
  const payload = store.exportCloudSnapshot();
  const revision = await api.putState(lastRevision(accountId), payload);
  markSyncedIfUnchanged(accountId, generation(accountId), revision);
  clearGuestPending();
}

async function pullFromCloud(accountId) {
  const remote = await api.getState();
  if (remote.payload) {
    const generationBefore = generation(accountId);
    store.replaceCloudSnapshot(remote.payload);
    await downloadCloudImages();
    const clean = markSyncedIfUnchanged(accountId, generationBefore, remote.revision);
    emit(clean && !hasPending(accountId) ? '已同步' : '有新修改待同步');
  } else {
    // 云端为空：把本机数据作为初始快照上传
    await pushLocal(accountId);
    emit('已同步');
  }
}

/* ----- 对外主流程 ----- */

async function syncNow() {
  const session = api.getSession();
  if (!session || !session.token) {
    emit(offlineMode() ? '离线使用 · 本机保存' : '仅保存在本机');
    return { ok: false, offline: true };
  }
  if (_busy) return { ok: false, busy: true };
  _busy = true;
  emit('正在同步');
  try {
    await api.checkSession();
    const accountId = session.id;
    if (hasPending(accountId)) {
      const remote = await api.getState();
      if (remote.revision !== lastRevision(accountId)) {
        emit('同步冲突 · 本机数据已保留', true);
        return { ok: false, conflict: true };
      }
      await pushLocal(accountId);
      emit(hasPending(accountId) ? '有新修改待同步' : '已同步');
      return { ok: true };
    }
    await pullFromCloud(accountId);
    return { ok: true };
  } catch (e) {
    if (e && e.statusCode === 401) {
      api.clearSession();
      setOfflineMode(true);
      emit('登录已过期 · 本机数据保留');
      return { ok: false, authExpired: true };
    }
    if (e && e.statusCode === 409) {
      emit('同步冲突 · 本机数据已保留', true);
      return { ok: false, conflict: true };
    }
    const accountId = session.id;
    emit(hasPending(accountId) ? '离线使用 · 修改待同步' : '离线使用 · 本机数据可用');
    return { ok: false, offline: true };
  } finally {
    _busy = false;
  }
}

/** 本地修改后的防抖同步：1.5 秒内的连续修改合并成一次上传。 */
function scheduleSync() {
  if (syncTimer) clearTimeout(syncTimer);
  syncTimer = setTimeout(() => {
    syncTimer = null;
    const session = api.getSession();
    if (session && session.token) {
      syncNow().catch(() => {});
    }
  }, 1500);
}

/* ----- 冲突处理（对齐 Android resolveConflict*） ----- */

async function resolveKeepLocal() {
  const session = api.getSession();
  if (!session) return { ok: false };
  _busy = true;
  emit('正在同步');
  try {
    const remote = await api.getState();
    setRevision(session.id, remote.revision);
    if (!hasPending(session.id)) markPending(session.id);
    await pushLocal(session.id);
    emit('已同步');
    return { ok: true };
  } catch (e) {
    emit('同步失败 · 本机数据已保留', e && e.statusCode === 409);
    return { ok: false };
  } finally {
    _busy = false;
  }
}

async function resolveUseCloud() {
  const session = api.getSession();
  if (!session) return { ok: false };
  _busy = true;
  emit('正在同步');
  try {
    const remote = await api.getState();
    if (remote.payload) {
      store.replaceCloudSnapshot(remote.payload);
      await downloadCloudImages();
    } else {
      store.resetForLogout();
    }
    acceptRemote(session.id, remote.revision);
    clearGuestPending();
    emit('已同步');
    return { ok: true };
  } catch (e) {
    emit('同步失败 · 请稍后重试');
    return { ok: false };
  } finally {
    _busy = false;
  }
}

/* ----- 登录 / 退出 ----- */

async function afterLogin(session) {
  api.saveSession(session);
  setOfflineMode(false);
  if (hasGuestPending()) markPending(session.id);
}

async function logout() {
  const session = api.getSession();
  if (!session) return { ok: true };
  if (hasPending(session.id)) {
    await syncNow();
    if (hasPending(session.id)) {
      emit('还有未同步的本机修改，暂未退出');
      return { ok: false, message: '还有未同步的本机修改。恢复网络并完成同步后再退出，避免丢失数据。' };
    }
  }
  try { await api.logout(); } catch (e) { /* 服务端撤销失败也继续清理本机 */ }
  api.clearSession();
  store.resetForLogout();
  setOfflineMode(false);
  clearGuestPending();
  emit('仅保存在本机');
  return { ok: true };
}

module.exports = {
  onStatus,
  markLocalChange,
  scheduleSync,
  syncNow,
  resolveKeepLocal,
  resolveUseCloud,
  afterLogin,
  logout,
  hasPending,
  hasGuestPending,
  offlineMode,
  setOfflineMode,
  markGuestPending
};
