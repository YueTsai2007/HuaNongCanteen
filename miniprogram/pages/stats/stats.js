const store = require('../../utils/store');
const format = require('../../utils/format');

const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

Page({
  data: {
    year: 0,
    month: 0,        // 1-12
    monthText: '',
    canNext: false,
    summary: null,   // { times, costText, avgText }
    days: [],        // [{ dateText, weekText, costText, items:[{id,name,noteText,place,priceText,rating,stars:[..]}] }]
    empty: false
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 });
    }
    const now = new Date();
    const keep = this.data.year && this.data.month;
    const year = keep ? this.data.year : now.getFullYear();
    const month = keep ? this.data.month : now.getMonth() + 1;
    this.setData({ year, month });
    this.refresh();
  },

  refresh() {
    const { year, month } = this.data;
    const logs = store.loadEatLogs()
      .filter((l) => {
        const p = (l.date || '').split('-');
        return Number(p[0]) === year && Number(p[1]) === month;
      })
      .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : b.ts - a.ts));

    const now = new Date();
    const canNext = year < now.getFullYear() || (year === now.getFullYear() && month < now.getMonth() + 1);

    const rated = logs.filter((l) => l.rating > 0);
    const summary = {
      times: logs.length,
      costText: format.yuan(logs.reduce((s, l) => s + (l.priceCents || 0), 0)),
      avgText: rated.length
        ? (rated.reduce((s, l) => s + l.rating, 0) / rated.length).toFixed(1) + ' 分'
        : '未评分'
    };

    const byDate = {};
    const order = [];
    logs.forEach((l) => {
      if (!byDate[l.date]) {
        byDate[l.date] = [];
        order.push(l.date);
      }
      byDate[l.date].push(l);
    });

    const days = order.map((date) => {
      const p = date.split('-');
      const d = new Date(date + 'T00:00:00');
      return {
        dateText: Number(p[1]) + '月' + Number(p[2]) + '日',
        weekText: '星期' + WEEK[d.getDay()],
        costText: format.yuan(byDate[date].reduce((s, l) => s + (l.priceCents || 0), 0)),
        items: byDate[date].map((l) => ({
          id: l.id,
          name: l.name || '未知菜品',
          noteText: l.note ? ' · ' + l.note : '',
          place: [l.hallName, l.shopName].filter(Boolean).join(' · '),
          priceText: format.yuan(l.priceCents || 0),
          rating: l.rating || 0,
          stars: [1, 2, 3, 4, 5].map((n) => n <= (l.rating || 0))
        }))
      };
    });

    this.setData({
      monthText: year + ' 年 ' + month + ' 月',
      canNext,
      summary,
      days,
      empty: !logs.length
    });
  },

  prevMonth() {
    let { year, month } = this.data;
    month--;
    if (month < 1) { month = 12; year--; }
    this.setData({ year, month });
    this.refresh();
  },

  nextMonth() {
    if (!this.data.canNext) return;
    let { year, month } = this.data;
    month++;
    if (month > 12) { month = 1; year++; }
    this.setData({ year, month });
    this.refresh();
  },

  tapStar(e) {
    const { id, star } = e.currentTarget.dataset;
    syncMark();
    store.rateEatLog(Number(id), Number(star));
    this.refresh();
  },

  removeLog(e) {
    const id = Number(e.currentTarget.dataset.id);
    const item = e.currentTarget.dataset.name || '这条记录';
    wx.showModal({
      title: '删除记录',
      content: '删掉「' + item + '」这条饮食记录？',
      confirmText: '删除',
      confirmColor: '#B64035',
      success: (res) => {
        if (!res.confirm) return;
        store.deleteEatLog(id);
        this.refresh();
      }
    });
  }
});

function syncMark() {
  try {
    require('../../utils/sync').markLocalChange();
  } catch (e) { /* 纯本地版无碍 */ }
}
