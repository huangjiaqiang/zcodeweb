# ZCode 远程

一个单站点 Android WebView 容器：在后台和熄屏时保持 zcode.z.ai 远程会话连接，并把页面的任务通知转发为系统通知。

## 目录

- `app/` — 应用源码、资源、构建脚本（原生 Java + WebView，无 Gradle）
- `sim/` — 本地模拟页与 HTTP/WS 服务，用于验证可见性、连接保活、通知链路
- `probe/` — 探针 APK，用于在真机上验证注入时机与 WebView 可见性行为

## 构建与安装

```bash
cd app && ./build.sh
adb install -r app/out/ZCRemote.apk
```

依赖：JDK 17、Android SDK build-tools 35.0.0、platform android-29。SDK 路径默认 `~/workStation/android/sdk`，可用 `ANDROID_SDK` 环境变量覆盖。

默认打开 `https://zcode.z.ai/remote/v4`（登录后为设备选择页）。要直达指定会话，把带 `sid/mid` 参数的完整链接写入 `app/src/com/zcoderemote/app/Local.java`（该文件不入库，缺失时构建脚本会生成中性的桩文件），或在 App 设置里粘贴。

## 首次使用

1. 打开 App，在登录页选择与桌面端相同的平台（Z.ai / BigModel）完成 OAuth 登录。
2. 点右上角 ⚙ 进入设置，可修改会话地址、后台保活、熄屏唤醒、通知转发。
3. 华为设备额外配置：
   - 设置 → 应用 → 应用启动管理 → 本应用改为「手动管理」，三个开关全部打开。
   - 电池优化选择「不允许」。
   - 通知设置里把「需要你处理」调为重要并允许横幅。

## 行为

- 注入生命周期垫片：页面始终读到 `visibilityState='visible'`，不触发页面自身的 suspend，relay WS 在后台保持。
- 通知桥：页面调用 `new Notification(...)` 时转发为原生通知；前台不转发；交互类（确认/回复/计划）走高优先级通道，完成/出错走默认通道；同类通知只保留最新一条。
- 前台服务 + 常驻低优先级通知；熄屏时持有 partial wakelock（设置里可关）。
- 服务 `START_STICKY`，被系统重启后发出「远程会话已恢复」通知；`BOOT_COMPLETED` 自启。
- 页面终态失败（响应超时 / 连接超时 / 无法连接中转）自动 reload，10 分钟内最多 5 次；其它终态只发通知。
- 文件上传：`<input type="file">` 拉起系统文件选择器，支持单选与多选（`EXTRA_ALLOW_MULTIPLE`）。
- 文件下载：http(s) 走系统 DownloadManager（自动携带 Cookie 与 localStorage 的 Bearer token，落盘公共 Downloads）；blob:/data: 链接由注入脚本在页面内截获，base64 分块（400KB/块）经 JS 桥写入 MediaStore Downloads。两种下载完成后都由应用发出通知，点击通知以 `ACTION_VIEW` 直接打开文件（无默认打开方式时弹系统选择器；找不到可打开的应用则回落到打开 App）。http(s) 下载期间显示系统进度通知，完成后由应用通知接管。
- APK 下载：声明 `REQUEST_INSTALL_PACKAGES`（缺失时华为安装器静默退出，点击无反应）。点击完成通知 → 华为「是否允许安装应用」授权（可勾选不再提示）→ 应用市场安装器展示包信息与备案提示，按「安装」完成；服务端需返回 `application/vnd.android.package-archive`，octet-stream 则只弹选择器。
- 下载重名处理：两条下载路径都主动探测重名并按 `名称-N.扩展名` 命名（数字加在主文件名上，扩展名保持不变，不影响文件类型识别）。http(s) 路径按 DownloadManager 成功记录判重（记录在文件删除后仍存在，宁可改名也不覆盖）；blob 路径按 MediaStore 既有记录判重。
- 同一 relay 会话只允许一条连接，App 只使用一个 WebView。
- 调试钩子：`adb shell am start -n com.zcoderemote.app/.MainActivity --el openDownloadId <id>` 以应用身份执行与下载完成通知相同的打开动作，用于回归验证。

## 已知边界

- 熄屏后 Chromium 把页面定时器降到 1 分钟一次：心跳变慢，WS 不受影响，消息接收实测约 350ms。若中转服务严格要求 5 秒心跳，熄屏可能被判离线，需在真实会话上验证。
- blob/data 下载在内存中分块组装，超大文件（数百 MB 级）可能失败；DownloadManager 下载只附加 Authorization 与 Cookie 头，页面动态添加的其它请求头不会生效。
- 登录必须在设备上手动完成 OAuth；登录态存于 WebView 的 localStorage，卸载重装需要重新登录。
- 华为 EMUI 将「应用启动管理」保护在签名权限之后，第三方 App 无法直达，设置页只能打开应用详情并给出路径。
- 华为会把「需要你处理」通道的重要性降到默认，横幅提醒需在系统通知设置里手动调高。
- 页面自身升级若改变通知或可见性实现，注入脚本需要相应调整。

## 模拟验证

```bash
cd sim && python3 server.py
adb reverse tcp:8899 tcp:8899
adb shell am start -n com.zcoderemote.app/.MainActivity \
  --es url 'http://127.0.0.1:8899/?mode=observe&kind=status'
```

`mode=observe` 让模拟页按真实页面的逻辑在 `hidden` 时 close WS；`mode=shim` 让模拟页自带垫片。`kind=interaction` 让通知标题命中交互通道。
