const store = require('../../utils/store');
const sync = require('../../utils/sync');
const imagesUtil = require('../../utils/images');

Page({
  data: {
    hallId: 1,
    hallName: '',
    shops: [],
    showForm: false,
    form: { name: '', description: '', imagePath: '' }
  },

  onLoad(query) {
    const hallId = Number(query.hallId) || 1;
    const hallName = decodeURIComponent(query.name || '店铺');
    this.setData({ hallId, hallName });
    wx.setNavigationBarTitle({ title: hallName });
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
    const list = d.shops
      .filter((s) => s.hallId === this.data.hallId)
      .sort((a, b) => b.id - a.id);
    const shops = list.map((s) => ({
      id: s.id,
      name: s.name,
      description: s.description || '',
      imageId: s.imageId,
      dishCount: d.dishes.filter((x) => x.shopId === s.id).length,
      image: store.localImage('shops', s.id)
    }));
    this.setData({ shops });
    shops.forEach((s, i) => {
      if (!s.image && s.imageId) {
        imagesUtil.resolve('shops', { id: s.id, imageId: s.imageId }).then((p) => {
          if (p) this.setData({ ['shops[' + i + '].image']: p });
        });
      }
    });
  },

  toggleForm() {
    this.setData({
      showForm: !this.data.showForm,
      form: { name: '', description: '', imagePath: '' }
    });
  },

  onNameInput(e) { this.setData({ 'form.name': e.detail.value }); },
  onDescInput(e) { this.setData({ 'form.description': e.detail.value }); },

  chooseImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: (res) => {
        const tmp = res.tempFiles[0].tempFilePath;
        this.setData({ 'form.imagePath': store.persistTempImage(tmp) });
      }
    });
  },

  saveShop() {
    const name = this.data.form.name.trim();
    if (!name) {
      wx.showToast({ title: '请输入店铺名称', icon: 'none' });
      return;
    }
    sync.markLocalChange();
    const id = store.addShop(this.data.hallId, name, this.data.form.description.trim());
    if (this.data.form.imagePath) {
      store.setLocalImage('shops', id, this.data.form.imagePath);
    }
    this.setData({ showForm: false, form: { name: '', description: '', imagePath: '' } });
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已添加', icon: 'success' });
  },

  openShop(e) {
    const { id, name } = e.currentTarget.dataset;
    wx.navigateTo({
      url: '/pages/shop/shop?shopId=' + id + '&name=' + encodeURIComponent(name)
    });
  }
});
