const store = require('../../utils/store');
const sync = require('../../utils/sync');
const format = require('../../utils/format');

Page({
  data: {
    groups: [],
    count: 0,
    doneCount: 0
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 });
    }
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
    const d = store.load();
    const lines = d.cart.slice().sort((a, b) => a.shopId - b.shopId || a.dishId - b.dishId);
    const byShop = {};
    const groups = [];
    lines.forEach((l) => {
      let g = byShop[l.shopId];
      if (!g) {
        g = { shopId: l.shopId, shopName: l.shopName, lines: [] };
        byShop[l.shopId] = g;
        groups.push(g);
      }
      g.lines.push({
        dishId: l.dishId,
        note: l.note || '',
        dishName: l.dishName,
        noteText: l.note ? '(' + l.note + ')' : '',
        quantity: l.quantity,
        priceText: format.yuan(l.price),
        done: !!l.done
      });
    });
    const count = d.cart.reduce((s, l) => s + l.quantity, 0);
    const doneCount = d.cart.filter((l) => l.done).reduce((s, l) => s + l.quantity, 0);
    this.setData({ groups, count, doneCount });
  },

  adjust(e) {
    const { dishid, note, delta } = e.currentTarget.dataset;
    sync.markLocalChange();
    store.changeCartQuantity(Number(dishid), note, Number(delta));
    this.refresh();
    sync.scheduleSync();
  },

  toggleDone(e) {
    const { dishid, note } = e.currentTarget.dataset;
    sync.markLocalChange();
    store.toggleCartDone(Number(dishid), note);
    this.refresh();
    sync.scheduleSync();
    const line = store.load().cart.find((l) =>
      l.dishId === Number(dishid) && (l.note || '') === (note || ''));
    if (line && line.done) {
      wx.showToast({ title: '已记入统计', icon: 'none' });
    }
  },

  clearDone() {
    if (!this.data.doneCount) return;
    sync.markLocalChange();
    store.clearDoneCartLines();
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已清理吃过的', icon: 'success' });
  },

  goBrowse() {
    wx.switchTab({ url: '/pages/halls/halls' });
  }
});
