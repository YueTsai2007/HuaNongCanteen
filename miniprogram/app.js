const api = require('./utils/api');
const sync = require('./utils/sync');
const store = require('./utils/store');

App({
  globalData: {
    status: '仅保存在本机',
    conflict: false,
    account: null
  },
  _listeners: [],
  _syncing: false,

  onLaunch() {
    store.init();
    sync.onStatus((payload) => this.setStatus(payload));
    this.refreshAccount();
    wx.onNetworkStatusChange((res) => {
      if (res.isConnected) {
        this.autoSync();
      } else if (this.globalData.account) {
        this.setStatus({ text: '离线使用 · 本机数据可用' });
      }
    });
    this.autoSync();
  },

  onShow() {
    this.autoSync();
  },

  setStatus(payload) {
    if (typeof payload === 'string') payload = { text: payload };
    this.globalData.status = payload.text;
    this.globalData.conflict = !!payload.conflict;
    this.notify();
  },

  refreshAccount() {
    const session = api.getSession();
    this.globalData.account = session ? { id: session.id, email: session.email } : null;
    this.notify();
  },

  subscribe(fn) {
    this._listeners.push(fn);
    return () => {
      const i = this._listeners.indexOf(fn);
      if (i >= 0) this._listeners.splice(i, 1);
    };
  },

  notify() {
    this._listeners.slice().forEach((fn) => {
      try { fn(); } catch (e) { /* 页面已销毁时忽略 */ }
    });
  },

  autoSync() {
    if (this._syncing) return;
    const session = api.getSession();
    if (!session || !session.token) return;
    this._syncing = true;
    sync.syncNow()
      .then(() => { this._syncing = false; })
      .catch(() => { this._syncing = false; });
  }
});
