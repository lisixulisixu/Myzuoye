# Myzuoy

Android Wi-Fi Direct（P2P）点对点文件传输。

## 进度

- M1 ✅ 授权 → 设备发现 → 建立连接 → 显示角色（组主 / 客户端）与组主地址
- M2 ⏳ TCP 文本通道
- M3 ⏳ 文件传输（协议头 + 分片 + 进度 + 校验）
- M4 ⏳ 前台服务保活、断连恢复

## 环境

- AGP 9.3.0（内置 Kotlin 支持，无需单独的 kotlin 插件）
- compileSdk / targetSdk 37，minSdk 33（Android 13+）
- 依赖：appcompat、material、recyclerview、kotlinx-coroutines

## 代码结构

| 路径 | 说明 |
| --- | --- |
| `app/src/main/java/com/example/ptp/MainActivity.kt` | 主界面、权限流程、状态展示 |
| `app/src/main/java/com/example/ptp/p2p/P2pController.kt` | Wi-Fi Direct 控制层：搜索 / 连接 / 建组 / 取组主地址 |
| `app/src/main/java/com/example/ptp/p2p/WifiDirectReceiver.kt` | 系统广播接收与翻译 |
| `app/src/main/java/com/example/ptp/ui/DeviceAdapter.kt` | 附近设备列表 |

## 真机验证

两台手机都安装本应用并打开 Wi-Fi（不需要连同一个路由器），各自点一次「搜索设备」，
应能在列表中看到对方；点击设备即可建立 P2P 连接，界面会显示当前角色和组主地址 `192.168.49.1`。

> 部分国产 ROM 即使 Android 13 以上也要求打开系统「定位服务」，否则搜索不到设备。
