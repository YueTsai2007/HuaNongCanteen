/**
 * 本地数据层：对应 Android 端 SqliteMenuRepository + OfflineSyncStore 的职责。
 * 数据结构与云端快照（schemaVersion 1）字段一一对应，可与 Android 端共用账号数据。
 * 金额一律为整数「分」。
 */
const IMAGES_KEY = 'hc_images';
const DATA_KEY = 'hc_data';

let cache = null;

const DEFAULT_HALLS = [
  { id: 1, name: '荷园', sort: 0 },
  { id: 2, name: '芷园', sort: 1 },
  { id: 3, name: '莘园', sort: 2 },
  { id: 4, name: '西园', sort: 3 },
  { id: 5, name: '稻香园', sort: 4 },
  { id: 6, name: '绿榕园', sort: 5 },
  { id: 7, name: '小吃街', sort: 6 },
  { id: 8, name: '外卖', sort: 7 }
];

function freshData() {
  return {
    halls: DEFAULT_HALLS.map((h) => ({ id: h.id, name: h.name, sort: h.sort })),
    shops: [{ id: 1, hallId: 1, name: '荷园示例小炒', description: '示例店铺，可在本机继续添加', imageId: null }],
    dishes: [{
      id: 1, shopId: 1, name: '招牌鸡腿饭', category: '推荐',
      description: '菜单示例，名称和价格均可扩展编辑', price: 1800, imageId: null
    }],
    cart: [],
    orders: []
  };
}

function init() {
  if (!wx.getStorageSync(DATA_KEY)) {
    wx.setStorageSync(DATA_KEY, freshData());
  }
}

function load() {
  let d = wx.getStorageSync(DATA_KEY);
  if (!d || !Array.isArray(d.halls) || !d.halls.length) {
    d = freshData();
    save(d);
  }
  cache = d;
  return d;
}

function save(d) {
  cache = d;
  wx.setStorageSync(DATA_KEY, d);
}

function nextId(list) {
  let max = 0;
  list.forEach((x) => { if (x.id > max) max = x.id; });
  return max + 1;
}

/* ---------------- 本地图片 ---------------- */

function imagesMap() {
  return wx.getStorageSync(IMAGES_KEY) || {};
}

function saveImagesMap(m) {
  wx.setStorageSync(IMAGES_KEY, m);
}

function localImage(entity, id) {
  return imagesMap()[entity + '/' + id] || '';
}

function setLocalImage(entity, id, path) {
  const m = imagesMap();
  m[entity + '/' + id] = path;
  saveImagesMap(m);
}

/** 把临时图片复制到用户目录，避免临时文件被回收。 */
function persistTempImage(tempPath) {
  if (!tempPath) return '';
  const fs = wx.getFileSystemManager();
  const dir = wx.env.USER_DATA_PATH + '/menu-images';
  try { fs.mkdirSync(dir, true); } catch (e) { /* 已存在 */ }
  const target = dir + '/local-' + Date.now() + '-' + Math.floor(Math.random() * 1000);
  try {
    fs.copyFileSync(tempPath, target);
    return target;
  } catch (e) {
    return tempPath;
  }
}

/* ---------------- 店铺与菜品 ---------------- */

function addShop(hallId, name, description) {
  const d = load();
  const id = nextId(d.shops);
  d.shops.push({ id, hallId, name, description: description || '', imageId: null });
  save(d);
  return id;
}

function deleteShop(shopId) {
  const d = load();
  d.shops = d.shops.filter((s) => s.id !== shopId);
  d.dishes = d.dishes.filter((x) => x.shopId !== shopId);
  d.cart = d.cart.filter((l) => l.shopId !== shopId);
  save(d);
}

function addDish(shopId, name, category, description, priceCents) {
  const d = load();
  const id = nextId(d.dishes);
  d.dishes.push({
    id, shopId, name, category: category || '推荐',
    description: description || '', price: priceCents, imageId: null
  });
  save(d);
  return id;
}

/* ---------------- 购物车 ---------------- */

/** 对应 Android 的 cart_key = "${dishId}:${note}"。 */
function changeCartQuantity(dishId, note, delta) {
  const d = load();
  const key = dishId + ':' + (note || '');
  const idx = d.cart.findIndex((l) => (l.dishId + ':' + (l.note || '')) === key);
  const quantity = (idx >= 0 ? d.cart[idx].quantity : 0) + delta;
  if (quantity <= 0) {
    if (idx >= 0) d.cart.splice(idx, 1);
    save(d);
    return;
  }
  const dish = d.dishes.find((x) => x.id === dishId);
  const prev = idx >= 0 ? d.cart[idx] : null;
  const shop = dish ? d.shops.find((s) => s.id === dish.shopId) : null;
  d.cart[idx >= 0 ? idx : d.cart.length] = {
    dishId,
    shopId: dish ? dish.shopId : (prev ? prev.shopId : 0),
    shopName: shop ? shop.name : (prev ? prev.shopName : ''),
    dishName: dish ? dish.name : (prev ? prev.dishName : ''),
    price: dish ? dish.price : (prev ? prev.price : 0),
    quantity,
    note: note || ''
  };
  save(d);
}

function cartTotal() {
  const d = load();
  return d.cart.reduce((sum, l) => sum + l.price * l.quantity, 0);
}

/* ---------------- 订单 ---------------- */

function placeOrder() {
  const d = load();
  if (!d.cart.length) return -1;
  const total = d.cart.reduce((sum, l) => sum + l.price * l.quantity, 0);
  const count = d.cart.reduce((sum, l) => sum + l.quantity, 0);
  const id = nextId(d.orders);
  d.orders.push({
    id,
    createdAt: Date.now(),
    total,
    count,
    lines: d.cart.map((l) => ({
      shopId: l.shopId, shopName: l.shopName, dishId: l.dishId,
      dishName: l.dishName, price: l.price, quantity: l.quantity, note: l.note || ''
    }))
  });
  d.cart = [];
  save(d);
  return id;
}

function loadOrders(startAtTs) {
  const d = load();
  return d.orders
    .filter((o) => (startAtTs == null ? true : o.createdAt >= startAtTs))
    .sort((a, b) => (b.createdAt - a.createdAt) || (b.id - a.id))
    .slice(0, 200);
}

function orderStats(startAtTs) {
  const orders = loadOrders(startAtTs);
  const count = orders.length;
  const total = orders.reduce((s, o) => s + o.total, 0);
  const items = orders.reduce((s, o) => s + o.count, 0);
  const popularMap = {};
  orders.forEach((o) => {
    (o.lines || []).forEach((l) => {
      const k = l.shopName + '|' + l.dishName;
      if (!popularMap[k]) popularMap[k] = { shopName: l.shopName, dishName: l.dishName, quantity: 0 };
      popularMap[k].quantity += l.quantity;
    });
  });
  const popularDishes = Object.keys(popularMap)
    .map((k) => popularMap[k])
    .sort((a, b) => (b.quantity - a.quantity) || a.dishName.localeCompare(b.dishName))
    .slice(0, 5);
  return {
    orderCount: count,
    totalSpentCents: total,
    averageOrderCents: count ? Math.round(total / count) : 0,
    itemCount: items,
    popularDishes
  };
}

/* ---------------- 云端快照 ---------------- */

/** 导出与 Android 端 exportCloudSnapshot() 完全同构的快照对象。 */
function exportCloudSnapshot() {
  const d = load();
  return {
    schemaVersion: 1,
    halls: d.halls.map((h) => ({ id: h.id, name: h.name, sort: typeof h.sort === 'number' ? h.sort : 0 })),
    shops: d.shops.map((s) => ({
      id: s.id, hallId: s.hallId, name: s.name,
      description: s.description || '', imageId: s.imageId || null
    })),
    dishes: d.dishes.map((x) => ({
      id: x.id, shopId: x.shopId, name: x.name,
      category: x.category || '推荐', description: x.description || '',
      price: x.price, imageId: x.imageId || null
    })),
    cart: d.cart.map((l) => ({
      dishId: l.dishId, shopId: l.shopId, shopName: l.shopName,
      dishName: l.dishName, price: l.price, quantity: l.quantity, note: l.note || ''
    })),
    orders: d.orders.map((o) => ({
      id: o.id, createdAt: o.createdAt, total: o.total, count: o.count,
      lines: (o.lines || []).map((l) => ({
        shopId: l.shopId, shopName: l.shopName, dishId: l.dishId,
        dishName: l.dishName, price: l.price, quantity: l.quantity, note: l.note || ''
      }))
    }))
  };
}

function replaceCloudSnapshot(payload) {
  if (!payload || payload.schemaVersion !== 1) throw new Error('云端数据版本不兼容');
  const arr = (v) => (Array.isArray(v) ? v : []);
  save({
    halls: arr(payload.halls).map((h, i) => ({
      id: h.id, name: h.name, sort: typeof h.sort === 'number' ? h.sort : i
    })),
    shops: arr(payload.shops).map((s) => ({
      id: s.id, hallId: s.hallId, name: s.name,
      description: s.description || '', imageId: s.imageId || null
    })),
    dishes: arr(payload.dishes).map((x) => ({
      id: x.id, shopId: x.shopId, name: x.name,
      category: x.category || '推荐', description: x.description || '',
      price: x.price, imageId: x.imageId || null
    })),
    cart: arr(payload.cart).map((l) => ({
      dishId: l.dishId, shopId: l.shopId, shopName: l.shopName || '',
      dishName: l.dishName || '', price: l.price, quantity: l.quantity, note: l.note || ''
    })),
    orders: arr(payload.orders).map((o) => ({
      id: o.id, createdAt: o.createdAt, total: o.total, count: o.count,
      lines: arr(o.lines).map((l) => ({
        shopId: l.shopId, shopName: l.shopName || '', dishId: l.dishId,
        dishName: l.dishName || '', price: l.price, quantity: l.quantity, note: l.note || ''
      }))
    }))
  });
}

/** 有本地图片但还没上传过云端的实体。 */
function pendingCloudImages() {
  const d = load();
  const out = [];
  d.shops.forEach((s) => {
    const p = localImage('shops', s.id);
    if (p && !s.imageId) out.push({ entity: 'shops', id: s.id, path: p });
  });
  d.dishes.forEach((x) => {
    const p = localImage('dishes', x.id);
    if (p && !x.imageId) out.push({ entity: 'dishes', id: x.id, path: p });
  });
  return out;
}

/** 只有云端 imageId、本地还没有文件的实体。 */
function cloudImagesNeedingDownload() {
  const d = load();
  const out = [];
  d.shops.forEach((s) => {
    if (s.imageId && !localImage('shops', s.id)) out.push({ entity: 'shops', id: s.id, imageId: s.imageId });
  });
  d.dishes.forEach((x) => {
    if (x.imageId && !localImage('dishes', x.id)) out.push({ entity: 'dishes', id: x.id, imageId: x.imageId });
  });
  return out;
}

function setImageId(entity, id, imageId) {
  const d = load();
  const list = entity === 'shops' ? d.shops : d.dishes;
  const item = list.find((x) => x.id === id);
  if (item) {
    item.imageId = imageId;
    save(d);
  }
}

/** 退出登录时清空业务数据，仅保留食堂与示例店铺（与 Android resetForLogout 一致）。 */
function resetForLogout() {
  const d = load();
  const fresh = freshData();
  d.shops = fresh.shops;
  d.dishes = fresh.dishes;
  d.cart = [];
  d.orders = [];
  save(d);
  saveImagesMap({});
}

module.exports = {
  init, load,
  localImage, setLocalImage, persistTempImage,
  addShop, deleteShop, addDish,
  changeCartQuantity, cartTotal, placeOrder,
  loadOrders, orderStats,
  exportCloudSnapshot, replaceCloudSnapshot,
  pendingCloudImages, cloudImagesNeedingDownload, setImageId,
  resetForLogout
};
