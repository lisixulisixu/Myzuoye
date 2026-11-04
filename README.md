# Myzuoy

Android Wi-Fi Direct（P2P）点对点文件传输。

## 进度

- M1 ✅ 授权 → 设备发现 → 建立连接 → 显示角色（组主 / 客户端）与组主地址
- M2 ✅ TCP 文本通道：组主监听 `8988`、客户端带重试连接，双向互发 UTF-8 文本
- M3 ✅ 单向文件传输：选择文件 → 文件头（文件名/大小/MD5）→ 8KB 分片 → 落盘 + 进度 + MD5 校验
- M4 ✅ 前台服务保活：传输期间通知栏进度，切后台/锁屏不中断

## 环境

- AGP 9.3.0（内置 Kotlin 支持，无需单独的 kotlin 插件）
- compileSdk / targetSdk 37，minSdk 24
- 依赖：appcompat、material、recyclerview、kotlinx-coroutines

## 代码结构

| 路径 | 说明 |
| --- | --- |
| `app/src/main/java/com/example/myzuoye/MainActivity.kt` | 主界面、权限流程、状态展示 |
| `app/src/main/java/com/example/myzuoye/p2p/P2pController.kt` | Wi-Fi Direct 控制层：搜索 / 连接 / 建组 / 取组主地址 |
| `app/src/main/java/com/example/myzuoye/p2p/WifiDirectReceiver.kt` | 系统广播接收与翻译 |
| `app/src/main/java/com/example/myzuoye/ui/DeviceAdapter.kt` | 附近设备列表 |
| `app/src/main/java/com/example/myzuoye/transfer/TransferProtocol.kt` | 帧协议：1 字节类型 + 4 字节长度 + 负载 |
| `app/src/main/java/com/example/myzuoye/transfer/TransferManager.kt` | TCP 通道：服务端监听 / 客户端重试连接 / 收发与状态回调 |
| `app/src/main/java/com/example/myzuoye/transfer/Checksum.kt` | MD5 校验工具，用于文件完整性核对 |
| `app/src/main/java/com/example/myzuoye/service/TransferService.kt` | 前台服务（dataSync）：传输期间保活 + 通知进度 |

## 文本通道（M2）

P2P 组网完成后自动建立：组主（`192.168.49.1`）作服务端监听 `8988`，客户端带重试（12 次 × 1 秒）连接，
避免刚建组时对方还没开始监听的竞态。协议帧结构：

```
+--------+----------+----------------+
| type   | length   | payload        |
| 1 字节 | 4 字节   | length 字节    |
+--------+----------+----------------+
```

## 文件传输（M3）

帧类型：

| type | 含义 | payload |
| --- | --- | --- |
| `0x01` | 文本 | UTF-8 字符串 |
| `0x02` | 文件头 | 版本(1) + 文件名长度(2) + 文件名 + 大小(8) + MD5(32) |
| `0x03` | 分片 | 原始文件字节（8KB） |
| `0x04` | 结束 | 已发送字节总数(long) |
| `0x05` | 取消 | 空 |

发送方用系统文件选择器（SAF）选文件，先算 MD5 再逐片发送；接收方落盘到系统「下载/WiFi直传」
目录（Android 10+ 走 MediaStore，9 及以下写外部存储 Download），重名自动加 `(1)`、`(2)` 不覆盖。
接收时边收边算 MD5，结束后核对大小与 MD5，不一致则删除（MediaStore 的 IS_PENDING 保证
半截文件不会露出来）。当前为单向发送，双向/多文件待后续迭代。

## 前台服务保活（M4）

文件传输期间，`TransferService` 以前台服务（`FOREGROUND_SERVICE_TYPE_DATA_SYNC`）运行，
通知栏展示传输进度，切到后台或锁屏也不会被系统回收连接。传输结束自动停止服务。

## 真机验证

两台手机都安装本应用并打开 Wi-Fi（不需要连同一个路由器），各自点一次「搜索设备」，
应能在列表中看到对方；点击设备即可建立 P2P 连接，界面会显示当前角色和组主地址 `192.168.49.1`。

> 部分国产 ROM 即使 Android 13 以上也要求打开系统「定位服务」，否则搜索不到设备。
>
> ⚠️ Android 模拟器不支持真正的 Wi-Fi Direct 组协商（GO Negotiation 在虚拟网卡上无法完成），
> 所以 **P2P 连接**必须在真机上验证。

## 在模拟器上验证文本通道 / 文件传输

P2P 组不起来，但**传输层可以单独验证**：长按「文本通道」状态文字会打开调试菜单，
可以绕过 Wi-Fi Direct 直接起监听或连一个地址。

以两台模拟器为例：

```bash
# 1) A 机长按通道状态 → 选「仅本地监听」，此时监听 8988
adb -s emulator-5554 forward tcp:18988 tcp:8988

# 2) B 机需要能访问宿主机 18988，用 reverse 把它映射进 B 机内部
adb -s emulator-5556 reverse tcp:18989 tcp:18988

# 3) B 机长按通道状态 → 「手动连接」，输入 127.0.0.1:18989
```

两端状态变成「文本通道：已连接」后即可互发文本、互传文件。
> 注意：`10.0.2.2` 在部分环境下不可用，优先用上面的 `adb reverse` 方案。

验证完整性可以直接比对两端的 MD5：

```bash
adb -s emulator-5556 shell md5sum /sdcard/Pictures/xxx.jpg
adb -s emulator-5554 shell md5sum "/sdcard/Download/WiFi直传/xxx.jpg"
```
