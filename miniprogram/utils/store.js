/**
 * 本地数据层：对应 Android 端 SqliteMenuRepository + OfflineSyncStore 的职责。
 * 数据结构与云端快照（schemaVersion 1）字段一一对应，可与 Android 端共用账号数据。
 * 金额一律为整数「分」。
 */
const IMAGES_KEY = 'hc_images';
const DATA_KEY = 'hc_data';
const EATLOG_KEY = 'hc_eatlog';
const seed = require('../data/seed');

let cache = null;

const DEFAULT_HALLS = [
  { id: 1, name: '荷园', sort: 0, floors: [1, 2, 3] },
  { id: 2, name: '芷园', sort: 1, floors: [1, 2, 3] },
  { id: 3, name: '莘园', sort: 2, floors: [1, 2] },
  { id: 4, name: '西园', sort: 3, floors: [1, 2, 3] },
  { id: 5, name: '稻香园', sort: 4, floors: [1] },
  { id: 6, name: '绿榕园', sort: 5, floors: [1, 2, 3] },
  { id: 7, name: '小吃街', sort: 6, floors: [1] },
  { id: 8, name: '外卖', sort: 7, floors: [] }
];

/** 旧数据迁移用：按食堂名补楼层配置。 */
const HALL_FLOORS = {};
DEFAULT_HALLS.forEach((h) => { HALL_FLOORS[h.name] = h.floors; });

function hallFloors(name) {
  const f = HALL_FLOORS[name];
  return Array.isArray(f) ? f.slice() : [1];
}

/** 给没有 floors 字段的旧食堂数据补上楼层配置。 */
function ensureFloors(d) {
  d.halls.forEach((h) => {
    if (!Array.isArray(h.floors)) h.floors = hallFloors(h.name);
  });
}

function seedHasMenu() {
  return (Array.isArray(seed.shops) && seed.shops.length) ||
    (Array.isArray(seed.dishes) && seed.dishes.length);
}

/** 把数据包内容套用到一份数据上：菜单以数据包为准，想吃清单保留仍存在的菜品。 */
function applySeedData(d) {
  const halls = (Array.isArray(seed.halls) && seed.halls.length) ? seed.halls : DEFAULT_HALLS;
  const shops = Array.isArray(seed.shops) ? seed.shops : [];
  const dishes = Array.isArray(seed.dishes) ? seed.dishes : [];
  d.halls = halls.map((h, i) => ({
    id: h.id, name: h.name, sort: typeof h.sort === 'number' ? h.sort : i,
    floors: Array.isArray(h.floors) ? h.floors : hallFloors(h.name)
  }));
  d.shops = shops.map((s) => ({
    id: s.id, hallId: s.hallId, name: s.name,
    description: s.description || '', imageId: s.imageId || null,
    floor: typeof s.floor === 'number' && s.floor > 0 ? s.floor : 1
  }));
  d.dishes = dishes.map((x) => ({
    id: x.id, shopId: x.shopId, name: x.name,
    category: x.category || '推荐', description: x.description || '',
    price: x.price, imageId: x.imageId || null
  }));
  d.seedVersion = seed.version;
  const alive = {};
  d.dishes.forEach((x) => { alive[x.id] = true; });
  d.cart = (d.cart || []).filter((l) => alive[l.dishId]);
  return d;
}

function freshData() {
  if (seedHasMenu()) {
    return applySeedData({
      cart: [],
      orders: []
    });
  }
  return {
    halls: DEFAULT_HALLS.map((h) => ({ id: h.id, name: h.name, sort: h.sort, floors: h.floors.slice() })),
    shops: [{ id: 1, hallId: 1, name: '荷园示例小炒', description: '示例店铺，可在本机继续添加', imageId: null, floor: 1 }],
    dishes: [{
      id: 1, shopId: 1, name: '招牌鸡腿饭', category: '推荐',
      description: '菜单示例，名称和价格均可扩展编辑', price: 1800, imageId: null
    }],
    seedVersion: seed.version,
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
  } else if (d.seedVersion !== seed.version && seedHasMenu()) {
    // 数据包版本升级：菜单整体替换为最新数据包，用户的想吃清单保留
    applySeedData(d);
  }
  ensureFloors(d);
  save(d);
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

function addShop(hallId, name, description, floor) {
  const d = load();
  const id = nextId(d.shops);
  d.shops.push({
    id, hallId, name, description: description || '', imageId: null,
    floor: typeof floor === 'number' && floor > 0 ? floor : 1
  });
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

/** 想吃清单：标记/取消「已吃过」。 */
function toggleCartDone(dishId, note) {
  const d = load();
  const key = dishId + ':' + (note || '');
  const line = d.cart.find((l) => (l.dishId + ':' + (l.note || '')) === key);
  if (line) {
    line.done = !line.done;
    if (line.done) {
      addEatLog(line);
    } else {
      // 取消打勾：删回对应最近一条还没评分的记录
      const logs = loadEatLogs();
      for (let i = logs.length - 1; i >= 0; i--) {
        const x = logs[i];
        if (x.dishId === dishId && (x.note || '') === (note || '') && !x.rating) {
          logs.splice(i, 1);
          break;
        }
      }
      wx.setStorageSync(EATLOG_KEY, logs);
    }
    save(d);
  }
}

/* ---------------- 饮食记录（统计） ---------------- */

function loadEatLogs() {
  const v = wx.getStorageSync(EATLOG_KEY);
  return Array.isArray(v) ? v : [];
}

function addEatLog(line) {
  const d = load();
  const shop = d.shops.find((s) => s.id === line.shopId);
  const hall = shop ? d.halls.find((h) => h.id === shop.hallId) : null;
  const now = new Date();
  const pad = (n) => (n < 10 ? '0' + n : '' + n);
  const logs = loadEatLogs();
  logs.push({
    id: Date.now() * 1000 + (logs.length % 1000),
    date: now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()),
    ts: Date.now(),
    dishId: line.dishId,
    name: line.dishName || '',
    note: line.note || '',
    shopName: line.shopName || '',
    hallName: hall ? hall.name : '',
    priceCents: (line.price || 0) * (line.quantity || 1),
    rating: 0
  });
  wx.setStorageSync(EATLOG_KEY, logs);
}

function rateEatLog(id, rating) {
  const logs = loadEatLogs();
  const x = logs.find((l) => l.id === id);
  if (x) {
    x.rating = x.rating === rating ? 0 : rating;
    wx.setStorageSync(EATLOG_KEY, logs);
  }
}

function deleteEatLog(id) {
  const logs = loadEatLogs();
  const i = logs.findIndex((l) => l.id === id);
  if (i >= 0) {
    logs.splice(i, 1);
    wx.setStorageSync(EATLOG_KEY, logs);
  }
}

/** 想吃清单：清掉所有已吃过的行。 */
function clearDoneCartLines() {
  const d = load();
  d.cart = d.cart.filter((l) => !l.done);
  save(d);
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

/** 导出菜单数据包（不含想吃清单/图片）：主人用它生成 data/seed.js 的内容。 */
function exportSeed() {
  const d = load();
  return {
    version: seed.version + 1,
    halls: d.halls.map((h) => ({
      id: h.id, name: h.name, sort: typeof h.sort === 'number' ? h.sort : 0,
      floors: Array.isArray(h.floors) ? h.floors : hallFloors(h.name)
    })),
    shops: d.shops.map((s) => ({
      id: s.id, hallId: s.hallId, name: s.name,
      description: s.description || '', imageId: null,
      floor: typeof s.floor === 'number' && s.floor > 0 ? s.floor : 1
    })),
    dishes: d.dishes.map((x) => ({
      id: x.id, shopId: x.shopId, name: x.name,
      category: x.category || '推荐', description: x.description || '',
      price: x.price, imageId: null
    }))
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
  changeCartQuantity, cartTotal, toggleCartDone, clearDoneCartLines,
  placeOrder,
  loadOrders, orderStats,
  loadEatLogs, rateEatLog, deleteEatLog,
  exportSeed,
  exportCloudSnapshot, replaceCloudSnapshot,
  pendingCloudImages, cloudImagesNeedingDownload, setImageId,
  resetForLogout
};
