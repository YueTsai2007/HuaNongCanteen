const store = require('../../utils/store');
const format = require('../../utils/format');
const config = require('../../config');

Page({
  data: {
    version: config.VERSION,
    shopCount: 0,
    dishCount: 0,
    seedText: ''
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 });
    }
    this.refresh();
  },

  refresh() {
    const d = store.load();
    this.setData({
      shopCount: d.shops.length,
      dishCount: d.dishes.length
    });
  },

  /** 主人更新菜单：导出数据包 → 贴回 data/seed.js → 重新发布版本。 */
  exportSeed() {
    const seed = store.exportSeed();
    const text = 'module.exports = ' + JSON.stringify(seed, null, 2) + ';';
    this.setData({ seedText: text });
    wx.setClipboardData({
      data: text,
      success: () => {
        wx.showModal({
          title: '菜单数据包已复制',
          content: '共 ' + this.data.shopCount + ' 家店铺 / ' + this.data.dishCount +
            ' 道菜品，内容已复制到剪贴板。把它粘贴到小程序代码的 data/seed.js 里（version 已自动 +1），然后重新上传发布即可。',
          showCancel: false,
          confirmText: '知道了'
        });
      }
    });
  }
});
