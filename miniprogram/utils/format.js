/** 金额一律用整数「分」存储与计算，与 Android 端一致。 */
function yuan(cents) {
  return '¥' + (cents / 100).toFixed(2);
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

function dateTime(ts) {
  const d = new Date(ts);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

/** period: 'all' | '7d' | '30d'，与 Android 端 OrderPeriod.startAt() 语义一致。 */
function startAt(period) {
  if (period === 'all') return null;
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + (period === '7d' ? -6 : -29));
  return d.getTime();
}

module.exports = { yuan, dateTime, startAt };
