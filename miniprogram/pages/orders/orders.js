const store = require('../../utils/store');
const format = require('../../utils/format');

const TABS = [
  { key: 'all', label: '全部' },
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' }
];

Page({
  data: {
    tabs: TABS,
    period: 'all',
    stats: null,
    orders: [],
    detail: null
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

  setPeriod(e) {
    const period = e.currentTarget.dataset.period;
    if (period === this.data.period) return;
    this.setData({ period });
    this.refresh();
  },

  refresh() {
    const start = format.startAt(this.data.period);
    const orders = store.loadOrders(start).map((o) => ({
      id: o.id,
      count: o.count,
      timeText: format.dateTime(o.createdAt),
      totalText: format.yuan(o.total)
    }));
    const s = store.orderStats(start);
    this.setData({
      orders,
      stats: {
        orderCount: s.orderCount,
        itemCount: s.itemCount,
        totalText: format.yuan(s.totalSpentCents),
        avgText: format.yuan(s.averageOrderCents),
        popular: s.popularDishes
      }
    });
  },

  openOrder(e) {
    const id = Number(e.currentTarget.dataset.id);
    const all = store.loadOrders(null);
    const o = all.find((x) => x.id === id);
    if (!o) return;
    this.setData({
      detail: {
        timeText: format.dateTime(o.createdAt),
        totalText: format.yuan(o.total),
        count: o.count,
        lines: (o.lines || []).map((l) => ({
          shopName: l.shopName,
          dishName: l.dishName,
          note: l.note || '',
          quantity: l.quantity,
          priceText: format.yuan(l.price),
          lineTotalText: format.yuan(l.price * l.quantity)
        }))
      }
    });
  },

  closeDetail() { this.setData({ detail: null }); }
});
