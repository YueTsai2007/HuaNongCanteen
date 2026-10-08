const store = require('../../utils/store');

Page({
  data: {
    halls: [],
    status: '仅保存在本机',
    conflict: false,
    account: null
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
    const d = store.load();
    const halls = d.halls
      .slice()
      .sort((a, b) => a.sort - b.sort)
      .map((h) => ({
        id: h.id,
        name: h.name,
        shopCount: d.shops.filter((s) => s.hallId === h.id).length
      }));
    this.setData({
      halls,
      status: app.globalData.status,
      conflict: app.globalData.conflict,
      account: app.globalData.account
    });
  },

  openHall(e) {
    const { id, name } = e.currentTarget.dataset;
    wx.navigateTo({
      url: '/pages/shops/shops?hallId=' + id + '&name=' + encodeURIComponent(name)
    });
  }
});
