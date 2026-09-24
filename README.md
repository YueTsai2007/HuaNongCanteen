# 华农食堂（Android）

原生 Android 点单应用首版。界面使用 Kotlin + Jetpack Compose；当前菜单、购物车和订单保存在本机 SQLite 数据库中。日常菜单浏览与金额计算不需要 NDK，后续若加入图像识别等高计算量能力，可在独立模块中加入 JNI/C++。

## 当前功能

- 荷园、芷园、莘园、西园、稻香园、绿榕园、小吃街和外卖入口。
- 在手机上添加店铺和菜品，可选择图片；图片复制到应用私有目录。
- 通过“备份”导出或恢复包含菜单图片的本地 ZIP；Android 云备份和设备迁移规则也包含菜单数据库与图片。
- 按店铺浏览菜品，菜品有分类、介绍、单价及简单口味备注。
- 跨店购物车，按店铺分组；本地计算总价并保存订单历史。
- 页面切换、购物车金额和数量变化带轻量动画；店铺照片支持全屏缩放查看。
- 订单档案支持全部/近 7 天/近 30 天统计、常点菜品排行、分页浏览和订单明细。
- 订单历史可导出为 UTF-8 CSV 表格；全量 ZIP 备份也会包含历史订单和菜品图片。
- 启动时带有一组可删除的示例店铺和菜品，便于体验。

卸载前请在首页点“备份”并导出文件，重装后选择“恢复备份”。Android 系统自动备份也已配置；是否自动恢复取决于设备账号、系统备份设置和厂商策略。

## 工程结构

```text
app/src/main/java/cn/huanong/canteen/
  MainActivity.kt              Compose 页面与交互
  data/Models.kt               菜单、购物车、订单模型
  data/MenuRepository.kt       数据仓库接口与 SQLite 实现
  ui/MainViewModel.kt          页面状态和业务操作
```

页面只通过 `MainViewModel` 调用 `MenuRepository`。后续接入云端时，可以增加远程数据源或替换仓库实现；数据库版本升级应在 `MenuDb.onUpgrade` 中追加迁移，保留用户数据。

## 构建

- Android Studio（支持 Android SDK 36）和 JDK 17。
- 使用 Gradle 8.13、Android Gradle Plugin 8.13.2。
- 打开本目录进行 Gradle 同步并运行 `app` 配置。

Linux 命令行首次初始化可运行 `./setup-android-env.sh`。脚本会把 Gradle 和 Android SDK 安装在项目的 `.tools/` 下，写入本地 `local.properties`，并生成标准 Gradle Wrapper；Android SDK 许可由运行者在提示时确认。之后可用 `./gradlew :app:assembleDebug` 构建 APK，也可在 Android Studio 中打开工程。运行 `source ./use-android-env.sh` 可为当前终端设置 SDK 与 Gradle 路径。

已在 JDK 17、Gradle 8.13、Android API 36 与 Build Tools 36.0.0 环境构建 Debug APK。Compose BOM 固定为 2025.12.00，以兼容 Android Gradle Plugin 8.13.2 与 API 36。

## 目前的边界

- 本机数据不在设备间同步；未接账号、支付、配送费或优惠券。
- 结算会创建本机订单，跨店商品在一张订单中按店铺分组。
- 菜品规格目前以口味备注为主；规格组合、库存、营业时间和后台管理可在后续迭代加入。
