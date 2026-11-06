Component({
  data: {
    selected: 0,
    color: '#7C867E',
    selectedColor: '#31845D',
    list: [
      { pagePath: '/pages/halls/halls', text: '食堂', icon: '🌿' },
      { pagePath: '/pages/cart/cart', text: '清单', icon: '🧾' },
      { pagePath: '/pages/stats/stats', text: '统计', icon: '📊' },
      { pagePath: '/pages/profile/profile', text: '我的', icon: '🙂' }
    ]
  },

  methods: {
    switchTab(e) {
      const path = e.currentTarget.dataset.path;
      const index = Number(e.currentTarget.dataset.index);
      wx.switchTab({ url: path });
      this.setData({ selected: index });
    }
  }
});
