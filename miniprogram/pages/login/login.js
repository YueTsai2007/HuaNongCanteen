const api = require('../../utils/api');
const sync = require('../../utils/sync');

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

Page({
  data: {
    mode: 'login',
    email: '',
    password: '',
    busy: false,
    error: ''
  },

  setMode(e) {
    this.setData({ mode: e.currentTarget.dataset.mode, error: '' });
  },

  onEmailInput(e) { this.setData({ email: e.detail.value }); },
  onPasswordInput(e) { this.setData({ password: e.detail.value }); },

  async submit() {
    if (this.data.busy) return;
    const email = this.data.email.trim();
    const password = this.data.password;
    if (!EMAIL_RE.test(email) || email.length > 254) {
      this.setData({ error: '请输入有效邮箱' });
      return;
    }
    if (password.length < 8 || password.length > 128) {
      this.setData({ error: '密码需为 8 到 128 个字符' });
      return;
    }
    this.setData({ busy: true, error: '' });
    try {
      const session = this.data.mode === 'register'
        ? await api.register(email, password)
        : await api.login(email, password);
      await sync.afterLogin(session);
      const app = getApp();
      app.refreshAccount();
      app.setStatus({ text: '正在同步' });
      sync.syncNow();
      wx.showToast({ title: this.data.mode === 'register' ? '注册成功' : '登录成功', icon: 'success' });
      setTimeout(() => {
        wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/halls/halls' }) });
      }, 600);
    } catch (e) {
      this.setData({ error: (e && e.message) || '操作失败' });
    } finally {
      this.setData({ busy: false });
    }
  },

  continueOffline() {
    sync.setOfflineMode(true);
    getApp().setStatus({ text: '离线使用 · 本机保存' });
    wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/halls/halls' }) });
  }
});
