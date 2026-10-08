const store = require('../../utils/store');
const sync = require('../../utils/sync');
const imagesUtil = require('../../utils/images');
const format = require('../../utils/format');

Page({
  data: {
    shopId: 0,
    shopName: '',
    shopDescription: '',
    shopImage: '',
    groups: [],
    showForm: false,
    form: { name: '', category: '', description: '', priceYuan: '', imagePath: '' },
    modal: null
  },

  onLoad(query) {
    const shopId = Number(query.shopId) || 0;
    const shopName = decodeURIComponent(query.name || '店铺');
    this.setData({ shopId, shopName });
    wx.setNavigationBarTitle({ title: shopName });
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
    const shop = d.shops.find((s) => s.id === this.data.shopId);
    const sorted = d.dishes
      .filter((x) => x.shopId === this.data.shopId)
      .sort((a, b) => b.id - a.id);
    const groups = [];
    sorted.forEach((x) => {
      const cat = x.category || '推荐';
      let g = groups.find((y) => y.category === cat);
      if (!g) {
        g = { category: cat, dishes: [] };
        groups.push(g);
      }
      const inCart = d.cart
        .filter((l) => l.dishId === x.id)
        .reduce((s, l) => s + l.quantity, 0);
      g.dishes.push({
        id: x.id,
        name: x.name,
        description: x.description || '',
        price: x.price,
        priceText: format.yuan(x.price),
        imageId: x.imageId,
        image: store.localImage('dishes', x.id),
        inCart
      });
    });
    this.setData({
      shopDescription: shop ? shop.description : '',
      shopImage: shop ? store.localImage('shops', shop.id) : '',
      groups
    });
    if (shop && shop.imageId && !this.data.shopImage) {
      imagesUtil.resolve('shops', shop).then((p) => {
        if (p) this.setData({ shopImage: p });
      });
    }
    groups.forEach((g) => {
      g.dishes.forEach((x, i) => {
        if (!x.image && x.imageId) {
          imagesUtil.resolve('dishes', { id: x.id, imageId: x.imageId }).then((p) => {
            if (p) this.setData({ ['groups[' + groups.indexOf(g) + '].dishes[' + i + '].image']: p });
          });
        }
      });
    });
  },

  /* ----- 加购弹窗 ----- */

  openDish(e) {
    const id = Number(e.currentTarget.dataset.id);
    const dish = this.findDish(id);
    if (!dish) return;
    this.setData({ modal: { id, name: dish.name, quantity: 1, note: '' } });
  },

  findDish(id) {
    let found = null;
    this.data.groups.forEach((g) => {
      g.dishes.forEach((x) => { if (x.id === id) found = x; });
    });
    return found;
  },

  closeModal() { this.setData({ modal: null }); },

  modalMinus() {
    const m = this.data.modal;
    if (m.quantity > 1) this.setData({ 'modal.quantity': m.quantity - 1 });
  },

  modalPlus() {
    this.setData({ 'modal.quantity': this.data.modal.quantity + 1 });
  },

  onNoteInput(e) { this.setData({ 'modal.note': e.detail.value }); },

  confirmAdd() {
    const m = this.data.modal;
    if (!m) return;
    sync.markLocalChange();
    store.changeCartQuantity(m.id, m.note.trim(), m.quantity);
    this.setData({ modal: null });
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已加入购物车', icon: 'success' });
  },

  quickAdd(e) {
    const id = Number(e.currentTarget.dataset.id);
    sync.markLocalChange();
    store.changeCartQuantity(id, '', 1);
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已加入购物车', icon: 'success' });
  },

  /* ----- 添加菜品 ----- */

  toggleForm() {
    this.setData({
      showForm: !this.data.showForm,
      form: { name: '', category: '', description: '', priceYuan: '', imagePath: '' }
    });
  },

  onFormInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ ['form.' + field]: e.detail.value });
  },

  chooseDishImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: (res) => {
        const tmp = res.tempFiles[0].tempFilePath;
        this.setData({ 'form.imagePath': store.persistTempImage(tmp) });
      }
    });
  },

  saveDish() {
    const f = this.data.form;
    const name = f.name.trim();
    const priceCents = Math.round(parseFloat(f.priceYuan) * 100);
    if (!name) {
      wx.showToast({ title: '请输入菜品名称', icon: 'none' });
      return;
    }
    if (!isFinite(priceCents) || priceCents <= 0) {
      wx.showToast({ title: '请输入有效价格', icon: 'none' });
      return;
    }
    sync.markLocalChange();
    const id = store.addDish(this.data.shopId, name, f.category.trim(), f.description.trim(), priceCents);
    if (f.imagePath) store.setLocalImage('dishes', id, f.imagePath);
    this.setData({ showForm: false, form: { name: '', category: '', description: '', priceYuan: '', imagePath: '' } });
    this.refresh();
    sync.scheduleSync();
    wx.showToast({ title: '已添加', icon: 'success' });
  }
});
