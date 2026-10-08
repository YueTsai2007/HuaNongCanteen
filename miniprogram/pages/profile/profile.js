const sync = require('../../utils/sync');
const config = require('../../config');

Page({
  data: {
    account: null,
    status: '',
    conflict: false,
    version: config.VERSION,
    busy: false
  },

  onShow() {
    this.refresh();
    this._unsub = getApp().subscribe(() => this.refresh());
  },

  onHide() { this.unsubscribe(); },
  onUnload() { this.unsubscribe(); },

  unsubscribe() {
    if (this._unsub) {
      this._unsub();
      this._unsub = null;
    }
  },

  refresh() {
    const app = getApp();
    this.setData({
      account: app.globalData.account,
      status: app.globalData.status,
      conflict: app.globalData.conflict
    });
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/login' });
  },

  async syncTapped() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const r = await sync.syncNow();
      if (r.ok) wx.showToast({ title: '已同步', icon: 'success' });
    } finally {
      this.setData({ busy: false });
    }
  },

  async keepLocal() {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const r = await sync.resolveKeepLocal();
      if (r.ok) wx.showToast({ title: '已保留本机数据', icon: 'success' });
    } finally {
      this.setData({ busy: false });
    }
  },

  useCloud() {
    wx.showModal({
      title: '使用云端数据',
      content: '本机当前的店铺、菜品、购物车和订单将被云端快照覆盖。如有未同步的重要修改，请先确认。继续吗？',
      confirmText: '使用云端',
      success: async (res) => {
        if (!res.confirm) return;
        this.setData({ busy: true });
        try {
          const r = await sync.resolveUseCloud();
          if (r.ok) wx.showToast({ title: '已使用云端数据', icon: 'success' });
        } finally {
          this.setData({ busy: false });
        }
      }
    });
  },

  logoutTapped() {
    wx.showModal({
      title: '退出登录',
      content: '退出会清空本机的店铺、菜品和订单数据（云端已同步的不受影响）。',
      confirmText: '退出',
      success: async (res) => {
        if (!res.confirm) return;
        this.setData({ busy: true });
        try {
          const r = await sync.logout();
          getApp().refreshAccount();
          if (!r.ok) {
            wx.showModal({ title: '暂时无法退出', content: r.message || '', showCancel: false });
          } else {
            wx.showToast({ title: '已退出', icon: 'success' });
          }
        } finally {
          this.setData({ busy: false });
        }
      }
    });
  }
});
