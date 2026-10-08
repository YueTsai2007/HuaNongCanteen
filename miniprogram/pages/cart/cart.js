const store = require('../../utils/store');
const sync = require('../../utils/sync');
const format = require('../../utils/format');

Page({
  data: {
    cart: [],
    total: 0,
    totalText: '¥0.00'
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
    const d = store.load();
    const cart = d.cart
      .slice()
      .sort((a, b) => (a.shopName + a.dishName).localeCompare(b.shopName + b.dishName))
      .map((l) => ({
        dishId: l.dishId,
        note: l.note || '',
        shopName: l.shopName,
        dishName: l.dishName,
        quantity: l.quantity,
        noteText: l.note ? '(' + l.note + ')' : '',
        priceText: format.yuan(l.price),
        lineTotalText: format.yuan(l.price * l.quantity)
      }));
    const total = store.cartTotal();
    this.setData({ cart, total, totalText: format.yuan(total) });
  },

  adjust(e) {
    const { dishid, note, delta } = e.currentTarget.dataset;
    sync.markLocalChange();
    store.changeCartQuantity(Number(dishid), note, Number(delta));
    this.refresh();
    sync.scheduleSync();
  },

  placeOrder() {
    if (!this.data.cart.length) return;
    sync.markLocalChange();
    const id = store.placeOrder();
    if (id > 0) {
      this.refresh();
      sync.scheduleSync();
      wx.showToast({ title: '下单成功', icon: 'success' });
      setTimeout(() => wx.switchTab({ url: '/pages/orders/orders' }), 600);
    }
  }
});
