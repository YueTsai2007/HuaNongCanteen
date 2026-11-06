const store = require('../../utils/store');
const sync = require('../../utils/sync');
const imagesUtil = require('../../utils/images');

Page({
  data: {
    halls: [],
    hallId: 0,
    hallName: '',
    floors: [],
    showFloorBar: false,
    floorId: 0,
    shops: [],
    status: '仅保存在本机',
    conflict: false,
    showForm: false,
    form: { name: '', description: '', imagePath: '', floor: 1 },
    pending: null
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 });
    }
    const saved = getApp().globalData.selectedHallId || 0;
    if (saved && saved !== this.data.hallId) this.setData({ hallId: saved });
    this.refresh();
    this._unsub = getApp().subscribe(() => this.refresh());
  },

  onHide() { this.unsubscribe(); },
  onUnload() { this.unsubscribe(); },

  noop() {},

  unsubscribe() {
    if (this._unsub) {
      this._unsub();
      this._unsub = null;
    }
  },

  refresh() {
    const app = getApp();
    const d = store.load();
    const halls = d.halls.slice().sort((a, b) => a.sort - b.sort);
    let hallId = this.data.hallId;
    if (!halls.some((h) => h.id === hallId)) hallId = halls.length ? halls[0].id : 0;
    const hall = halls.find((h) => h.id === hallId);
    const floors = hall && Array.isArray(hall.floors) ? hall.floors : [];
    const showFloorBar = floors.length >= 2;
    const floorId = this.data.floorId || 0;
    const shops = d.shops
      .filter((s) => s.hallId === hallId)
      .filter((s) => !showFloorBar || !floorId || (s.floor || 1) === floorId)
      .sort((a, b) => (a.floor || 1) - (b.floor || 1) || b.id - a.id)
      .map((s) => ({
        id: s.id,
        name: s.name,
        description: s.description || '',
        imageId: s.imageId,
        floor: s.floor || 1,
        dishCount: d.dishes.filter((x) => x.shopId === s.id).length,
        image: store.localImage('shops', s.id)
      }));
    app.globalData.selectedHallId = hallId;
    this.setData({
      halls,
      hallId,
      hallName: hall ? hall.name : '',
      floors,
      showFloorBar,
      floorId,
      shops,
      status: app.globalData.status,
      conflict: app.globalData.conflict
    });
    shops.forEach((s, i) => {
      if (!s.image && s.imageId) {
        imagesUtil.resolve('shops', { id: s.id, imageId: s.imageId }).then((p) => {
          if (p) this.setData({ ['shops[' + i + '].image']: p });
        });
      }
    });
  },

  /* ----- 食堂侧栏 ----- */

  selectHall(e) {
    const id = Number(e.currentTarget.dataset.id);
    if (id === this.data.hallId) return;
    this.setData({ hallId: id, floorId: 0 });
    this.refresh();
  },

  /* ----- 楼层筛选 ----- */

  selectFloor(e) {
    const floor = Number(e.currentTarget.dataset.floor) || 0;
    if (floor === this.data.floorId) return;
    this.setData({ floorId: floor });
    this.refresh();
  },

  selectFormFloor(e) {
    const floor = Number(e.currentTarget.dataset.floor) || 1;
    this.setData({ 'form.floor': floor });
  },

  /* ----- 店铺 ----- */

  openShop(e) {
    const { id, name, floor } = e.currentTarget.dataset;
    const f = this.data.showFloorBar ? (Number(floor) || 1) : 0;
    wx.navigateTo({
      url: '/pages/shop/shop?shopId=' + id + '&name=' + encodeURIComponent(name) + '&floor=' + f
    });
  },

  askDelete(e) {
    const id = Number(e.currentTarget.dataset.id);
    const shop = this.data.shops.find((s) => s.id === id);
    if (shop) this.setData({ pending: shop });
  },

  cancelDelete() { this.setData({ pending: null }); },

  confirmDelete() {
    const shop = this.data.pending;
    if (!shop) return;
    sync.markLocalChange();
    store.deleteShop(shop.id);
    this.setData({ pending: null });
    this.refresh();
    sync.scheduleSync();
  },

  goList() { wx.switchTab({ url: '/pages/cart/cart' }); },
  goProfile() { wx.switchTab({ url: '/pages/profile/profile' }); },

  /* ----- 添加店铺弹窗 ----- */

  toggleForm() {
    this.setData({
      showForm: !this.data.showForm,
      form: { name: '', description: '', imagePath: '', floor: 1 }
    });
  },

  closeForm() { this.setData({ showForm: false }); },

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
    const id = store.addShop(this.data.hallId, name, this.data.form.description.trim(), this.data.form.floor);
    if (this.data.form.imagePath) {
      store.setLocalImage('shops', id, this.data.form.imagePath);
    }
    this.setData({ showForm: false, form: { name: '', description: '', imagePath: '', floor: 1 } });
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已添加', icon: 'success' });
  }
});
