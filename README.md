# 华农食堂 Android

原生 Kotlin + Jetpack Compose 校园点单应用。SQLite 保留离线可用能力，登录后与云端同步菜单、图片、购物车和完整订单档案。日常菜单与价格计算不需要 NDK；高频列表用 Compose/SQLite 原生实现，未来需要图像识别等重计算时可再用独立 JNI/C++ 模块。

## 功能

- 荷园、芷园、莘园、西园、稻香园、绿榕园、小吃街和外卖入口；店铺、菜品与图片可自行添加。
- 邮箱+密码注册和登录；会话令牌通过 Android Keystore AES-GCM 加密保存在本机。
- 账号云端保存店铺、菜品、图片、购物车及全部订单，支持换设备登录恢复；SQLite 保留离线缓存。
- 购物车结算、历史订单、日期筛选、统计、排行与 CSV/ZIP 导出。
- 页面过渡和购物车变化动画；店铺图片支持全屏查看和缩放。
- v0.4.0，Android 8.0+（API 26）。


## 构建

需要 JDK 17、Gradle 8.13、Android SDK 36。

```bash
./gradlew :app:assembleDebug
```

通过 Sites 管理界面取得该站点的 SIWC bypass token 后，把它作为 Gradle 属性提供给构建环境，勿写进源码仓库：

```bash
./gradlew :app:assembleDebug -PsiteGateToken=<站点令牌>
```

`siteGateToken` 只用于通过 Sites 的外层访客门禁；账户数据仍由用户自己的邮箱密码会话保护。公开发布 APK 时，令牌可以被提取，因此不能把它当成数据访问凭证。

Linux 初次安装 SDK 可运行 `./setup-android-env.sh`。命令行环境可运行 `source ./use-android-env.sh`。
