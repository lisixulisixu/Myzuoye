# Myzuoy

Android Wi-Fi Direct（P2P）点对点文件传输。

## 进度

- M1 ✅ 授权 → 设备发现 → 建立连接 → 显示角色（组主 / 客户端）与组主地址
- M2 ✅ TCP 文本通道：组主监听 `8988`、客户端带重试连接，双向互发 UTF-8 文本
- M3 ✅ 单向文件传输：选择文件 → 文件头（文件名/大小/MD5）→ 8KB 分片 → 落盘 + 进度 + MD5 校验
- M4 ✅ 前台服务保活：传输期间通知栏进度，切后台/锁屏不中断
- 附加 ✅ 应用内查看已接收文件、封面特效、传输期间的小游戏、带天气特效的桌面小组件
- M7 ✅ 断点续传、多选队列、实时速度、局域网兜底、深色流动界面、Release 构建与 CI

## 功能速览

- **附近设备 / P2P 连接**：搜索附近设备，点击建立 Wi-Fi Direct 连接，显示角色与组主地址
- **文本聊天**：组网完成后自动建立 TCP 通道，双向互发文本
- **文件传输**：选择文件发送，接收方自动落盘到「下载/WiFi直传」，MD5 校验完整性
- **查看已收文件**：主界面「已收文件」按钮进入列表页，点击用系统应用打开
- **传输小游戏**：发送前询问是否要玩，玩则弹出跑酷小游戏；也可点「玩游戏」随时玩（见下文）
- **桌面小组件**：按定位显示当地温度与天气，并按天气画太阳/月亮/云/雨雪/雷电特效；
  同时显示连接状态与已接收文件数，点按直达应用（见下文）
- **传输体验**：多选文件排队发送、实时速度与剩余时间、可随时取消、**8MB 以上文件支持断点续传**
- **局域网兜底**：Wi-Fi Direct 被 ROM 限制时可改用「同一 Wi-Fi 输入对方 IP」直连（见下文）
- **界面**：深色流动背景（多段渐变 + 极光光带 + 漂浮光点），支持系统深色模式

## 安装包

直接下载安装（Android 7.0 及以上，`minSdk 24`）：

- 仓库内：[`apk/WiFi快传-release.apk`](apk/WiFi快传-release.apk) —— 点右上角 Download 即可
- 本地：`WiFi快传-1.1-release.apk`（构建产物同步副本）

安装时如提示「未知来源」，在系统设置里允许该来源安装即可。这是用本仓库 `release.keystore` 签名的正式包（R8 压缩后约 2.7MB），可直接安装。

## 构建

```bash
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 发布包（R8 + 资源压缩，约 2.7MB）
./gradlew :app:testDebugUnitTest # 纯 JVM 单测，不需要模拟器
```

Release 签名信息从项目根目录的 `keystore.properties` 读取（**已在 .gitignore 里，不要提交**）：

```properties
storeFile=release.keystore
storePassword=***
keyAlias=wifitransfer
keyPassword=***
```

文件不存在时会退化成未签名构建，别人 clone 下来也能直接编译。
`proguard-rules.pro` 里必须保留自定义 View（`MascotView`、`FlowBackgroundView` 等）——
它们在 XML 里以全限定名出现，由 LayoutInflater 反射创建，不 keep 会在 release 包里白屏。

CI：`.github/workflows/android.yml` 在每次 push / PR 时跑单测 + 编译 debug 包。

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
| `app/src/main/java/com/example/myzuoye/transfer/ReceivedFileStore.kt` | 接收文件落盘：Android 10+ 写 MediaStore，9 及以下写 Download；重名加序号 |
| `app/src/main/java/com/example/myzuoye/ReceivedFilesActivity.kt` | 应用内查看已接收文件列表 |
| `app/src/main/java/com/example/myzuoye/TransferGame.kt` | 传输小游戏窗口：①传输信息 ②游戏 ③按钮 |
| `app/src/main/java/com/example/myzuoye/ui/PixelRunnerView.kt` | 像素跑酷游戏视图（Choreographer 帧同步） |
| `app/src/main/java/com/example/myzuoye/ui/CoverOverlayView.kt` | 封面装饰层（渐变 + 光斑） |
| `app/src/main/java/com/example/myzuoye/ui/ReceivedFileAdapter.kt` | 已收文件列表适配器 |
| `app/src/main/java/com/example/myzuoye/widget/TransferWidgetProvider.kt` | 桌面小组件：渲染天气/状态、点击进入应用 |
| `app/src/main/java/com/example/myzuoye/widget/WeatherSceneRenderer.kt` | 离屏绘制小组件的天气背景与天体图层 |
| `app/src/main/java/com/example/myzuoye/transfer/SendQueue.kt` | 多选文件的发送队列（顺序发送、断连收敛） |
| `app/src/main/java/com/example/myzuoye/transfer/PartialStore.kt` | 大文件半成品存储，断点续传的基础 |
| `app/src/main/java/com/example/myzuoye/net/LanAddress.kt` | 枚举本机局域网 IPv4，局域网直连用 |
| `app/src/main/java/com/example/myzuoye/ui/AppDialog.kt` | 统一的深色对话框 |
| `app/src/main/java/com/example/myzuoye/util/Formatters.kt` | 容量 / 速度 / 时长的统一格式化 |
| `app/src/main/java/com/example/myzuoye/weather/WeatherRepository.kt` | 定位 → Open-Meteo 天气 → 城市反查 → 落盘 + 刷新小组件 |
| `app/src/main/java/com/example/myzuoye/weather/WeatherCondition.kt` | WMO 天气代码 → 天气分类 / 中文短名 |
| `app/src/main/java/com/example/myzuoye/widget/WidgetState.kt` | 小组件状态快照（SharedPreferences），供跨进程读取 |
| `app/src/main/res/layout/widget_transfer.xml` | 小组件布局（RemoteViews 白名单内的 View） |
| `app/src/main/res/xml/widget_transfer_info.xml` | 小组件配置：4×2、可缩放、预览布局 |

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

- **保存位置**：`下载/WiFi直传/`，重名自动加 `(1)(2)`，校验通过前用 `IS_PENDING` 隐藏
- **分片 64KB**：每帧一次 flush，分片太小系统调用开销占比高；64KB 在本机链路上吞吐最好
- **只读一遍**：文件头先写 MD5 占位符，边读边算 digest，算出的 MD5 放进结束帧回传核对。
  早期实现是「先整读一遍算 MD5、再读一遍发送」，1GB 文件等于读两次、慢一倍
- **实时速度**：400ms 滑动平均 + 80ms 节流回调，界面显示 `12.4 MB/s · 剩 48 秒`
- **多选队列**：一次选多个文件按顺序发；断连时清空队列并如实回报成功/失败数
- **取消**：发送中可取消，会给对端发 `TYPE_FILE_CANCEL`，对端不会一直卡在「接收中」

### 断点续传（协议版本 2）

8MB 以上的文件先写到应用专属目录的半成品（`Android/data/<包名>/files/partial`），
收完再复制进下载目录；失败或断连时半成品保留，下次同名同大小再传时：

1. 接收端在文件头之后回一个 `TYPE_FILE_RESUME`，内容是「我已经有 N 字节」
2. 发送端读取文件时，断点之前的部分只算 MD5、不发送，之后继续发
3. 接收端把已落盘的 N 字节重新喂给 digest，保证最终 MD5 仍覆盖整份文件

几个刻意的设计：

- **版本协商**：只有文件头版本 ≥ 2 才会回续传应答。老客户端（版本 0）不会等这个帧，
  给它发反而会导致「它从头发、这边从中间追加」把文件写坏
- **超时兜底**：发送端最多等 600ms，等不到就从头传，绝不因为对端没应答卡死
- **校验优先**：MD5 对不上就丢弃半成品，宁可重传也不留坏文件
- **自动清理**：半成品超过 7 天在下次启动时清掉

已知限制：半成品的 key 是「文件名 + 字节数」，两台不同设备发同名同大小的不同文件
会共用同一个半成品；这种情况最后 MD5 校验一定失败、半成品被丢弃，不会产生坏文件，
只是会多传一次。

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

## 局域网直连（Wi-Fi Direct 的兜底通道）

国产 ROM 对 Wi-Fi Direct 限制很重（搜不到、建组失败、连上分不到 192.168.49.x 地址）。
这时两台设备连同一个 Wi-Fi，用局域网直连即可——走的还是同一套 TCP + 帧协议，
文本、文件、断点续传全都照旧可用。

**长按「文本通道」这一行** → 选「局域网直连（同一 Wi-Fi）」：

- 对话框会显示本机地址（如 `192.168.1.23:8988`）
- 一台点「作为服务端监听」，另一台在输入框填对方地址后点「连接」
- 不需要 Wi-Fi Direct 权限，只要两台在同一个网段（路由器开了 AP 隔离则不行）

## 应用内查看已接收文件

主界面「已收文件」进入，列表支持：

- **缩略图**：图片与视频显示封面（`contentResolver.loadThumbnail`，API 29 以下自己算采样率），
  其他类型保持纯色块；贴图前校验 ViewHolder 的 tag，避免列表滑动时把图贴到错的
- **类型筛选**：全部 / 图片 / 视频 / 文档 / 其他
- **分享**：调系统分享面板发给别的应用
- **删除**：二次确认后删除（本应用创建的 MediaStore 条目无需额外权限）
- 点整行用系统应用打开

## 日志导出

**长按底部日志区** →「导出日志」。会把设备型号、系统版本、连接状态与最近 200 条日志
写成 txt，走 FileProvider 分享出来。真机出问题时不用连电脑，一次点击就能把日志发出来。

## Q 版人物

封面右下角的猫耳女仆（`ui/MascotView.kt`），点一下会弹跳、换表情，并在头顶的气泡里说一句话；
开始传输 / 传输成功 / 传输失败也会自动换表情提示。

- **气泡排版**：气泡画在人物上方的独立区域，按控件宽度自动折行。两行放不下就逐级缩小字号
  （21dp → 13dp），仍然超长才截断加省略号——任何长度的提示语都不会被控件边缘裁掉
- **人物让位**：气泡变高时人物区域等比缩小并保持贴底，两者不会叠在一起
- 台词停留时长跟长度走（1.8s 起，最长 4.2s），长句子来不及看完就消失会显得很敷衍

## 传输小游戏

发送文件时会先问一句「要玩小游戏吗？」，选「要玩」才弹出跑酷小游戏，边传边玩；
主界面顶部「玩游戏」按钮也可随时调出。

- **询问窗口**：每次传输开始后弹出。选「不玩」会记住偏好，之后传输不再弹游戏窗口也不再询问；
  想恢复提示，**长按「玩游戏」按钮**即可
- **玩法**：点屏幕跳跃躲避障碍物，右上角显示跳过计数，撞上则本局结束、点画面重开
- **开场倒计时**：每局开始前有 3 秒 3-2-1 倒计时，倒计时结束才出障碍物
- **角色**：Q 版猫耳女仆立绘，撞到障碍物即本局结束
- **分区**：①区显示传输文件名与进度；②区游戏主体；③区「返回」「确定」按钮
- **中断**：点「返回」或按返回键只退出游戏，后台传输不受影响
- **完成**：传输完成后①区显示「已发送」，可继续玩或点「确定」关闭
- 平时（非传输）点「玩游戏」进入休闲模式，不显示进度条，纯娱乐

## 桌面小组件

长按桌面空白处 →「Widgets」→ 找到「Myzuoye / WiFi 快传」→ 拖到桌面即可添加，默认 4×2，可横向/纵向缩放。

### 天气与温度

- **数据来源**：按设备定位（大致位置即可）请求 Open-Meteo 当前天气（温度、天气代码、昼夜），
  再用 BigDataCloud 反查城市名。两者都免 API Key，失败时保留上一次结果，不会突然变成 `--°`
- **刷新节奏**：系统每 30 分钟回调一次 `onUpdate` 时顺带刷新；应用每次进入前台也刷一次，
  内部按 15 分钟节流，城市名 6 小时内不重复反查，省流量也省电
- **权限**：Android 13+ 只需要「大致位置」（`ACCESS_COARSE_LOCATION`），首次进应用时询问一次；
  拒绝不影响任何传输功能，卡片上会提示「需开启定位」。Android 12 及以下为了搜设备本来就要定位权限，直接复用
- **显示**：第一行温度（如 `23°`），第二行天气短名（晴 / 多云 / 阴 / 雨 / 雷阵雨 / 雪…），
  右下角「城市 · 更新时间」

### 天气特效

卡片背景不是纯色，而是按控件实际尺寸**离屏绘制成位图**再交给 ImageView：

| 天气 | 画面 |
| --- | --- |
| 晴 | 暖色天空 + 太阳光晕 + 旋转光芒 + 星芒 |
| 多云 / 阴 | 淡蓝或灰蓝天空 + 分层柔和云团（越靠上越大越淡） |
| 雾 | 灰白天空 + 五道横向雾带 + 整体薄雾 |
| 雨 / 毛毛雨 / 阵雨 | 冷色天空 + 低垂云 + 斜向雨丝 + 地面溅点 |
| 雷阵雨 | 雨 + 云下闪电（外发光描边） |
| 雪 | 冷白天空 + 大小不一的雪花（带冷色外晕） |
| 夜间 | 深蓝渐变 + 月亮 / 星空，**文字自动切成浅色** |

实现上有两个坑：

- **不要让正圆被拉伸**：背景用 `fitXY` 铺满卡片，会被卡片比例拉成椭圆。
  所以太阳/月亮单独画在**正方形位图**里、用固定 dp 的正方形 ImageView 承载，任何尺寸下都是正圆；
  光晕半径也压在 `2.6r` 以内，否则渐变会在方形位图边缘被切断，桌面上会看到一个方框
- **位图大小有上限**：RemoteViews 走 Binder 传输，渲染时按 480px 宽 / 22 万像素封顶，
  超出的部分靠 `fitXY` 放大（渐变和云都是模糊的，放大看不出来）

### 状态与交互

- **显示内容**：应用图标与名称、连接状态（绿点=已连接 / 灰点=未连接 / 蓝点=传输中）、
  已接收文件数；传输过程中会换成「正在接收：文件名」
- **点按直达**：点卡片任意位置直接打开应用
- **实时刷新**：应用内连接状态变化、开始/结束传输、收到文件时主动推送更新
  （`updatePeriodMillis` 的 30 分钟系统轮询只是兜底）
- **状态来源**：小组件跑在桌面进程，读不到应用内存态，因此由应用侧把状态写进
  `SharedPreferences`（`WidgetState` / `WeatherRepository`），小组件渲染时再读出来
- 布局只用 RemoteViews 白名单内的 View。**不要用 `Space`**——它不在白名单里，
  会导致整张卡片显示「Can't load widget」；弹性留白用空 `TextView` + `layout_weight` 实现

## 验收用的对照图

`androidTest` 里有两个「肉眼验收」测试，不参与断言，只把画面拼成一张图导出来看：

```bash
adb shell am instrument -w -e class com.example.myzuoye.WeatherScenePreviewTest \
  com.example.myzuoye.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.example.myzuoye.MascotBubblePreviewTest \
  com.example.myzuoye.test/androidx.test.runner.AndroidJUnitRunner

# 导出（写到应用私有目录，用 run-as 取）
adb exec-out run-as com.example.myzuoye cat files/weather_preview/weather_sheet.png > weather_sheet.png
adb exec-out run-as com.example.myzuoye cat files/mascot_preview/bubble_sheet.png > bubble_sheet.png
```
