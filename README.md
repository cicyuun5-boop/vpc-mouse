# VPC Mouse（安卓端：手机当蓝牙 HID 鼠标，可由 PC 控制）

> 目标：手机注册成蓝牙 HID 鼠标，PC 通过 Wi-Fi 发 `dx dy`，手机转成 HID 报告。
> 与 GhostBoard 的区别：GhostBoard 只接受手指，**PC 无法下发指令**；这个 App 开了 TCP 8089 给 PC 用。

---

## 一、云端编译（推荐：本机不用装 Android Studio）

你机器上没有 Android SDK，本地编译要下 500MB+。用 GitHub 免费 Action 编，5 分钟出 APK。

### 步骤

1. 注册/登录 GitHub（有账号跳过）
2. 右上角 **+** → **New repository** → 名字随便（如 `vpc-mouse`）→ **Public** → Create
3. 进仓库 → **Add file** → **Upload files**
4. 把 **`android/` 目录下的所有内容**拖进去（注意：是里面的文件，不是这个文件夹本身）
   - `app/`
   - `.github/`
   - `build.gradle.kts`
   - `settings.gradle.kts`
   - `gradle.properties`
5. 点 **Commit changes**
6. 顶部 **Actions** 标签 → 左侧 `build-apk` → 等 2–5 分钟（黄点转绿勾）
7. 点进那次运行 → 底部 **Artifacts** → 下载 `vpc-hid-mouse-apk.zip`
8. 解压得到 `app-debug.apk`，发到手机安装

> 如果 Actions 报错，把红色日志第一段发我，我改代码。

### 不想用 GitHub？

装 Android Studio（约 1.2 GB），用 **Open** 打开 `android/` 目录，等 Gradle 同步完，
菜单 **Build → Build Bundle(s)/APK(s) → Build APK(s)**，产物在
`app/build/outputs/apk/debug/app-debug.apk`。

---

## 二、手机端使用

1. 安装 APK（允许"未知来源"）
2. 打开 **VPC Mouse** → 授予「附近的设备」权限
3. 点 **启动 HID 鼠标服务**
4. 屏幕会显示 **本机 IP**（例如 `192.168.1.23`）——记下来
5. 手机蓝牙与电脑配对（若之前配过 GhostBoard，先删掉重配，保证 HID 服务在配对时已注册）

> 启动后通知栏会常驻一条「VPC Mouse 运行中」，这是前台服务，防止息屏被杀。

---

## 三、电脑端测试

```bash
python tools/phone_hid_test.py --ip 192.168.1.23 --demo square
```

预期：光标在屏幕上画出一个正方形并回到原点。

- 画不出来 → 检查 IP / 同一 Wi-Fi / 手机 App 是否显示已连接
- 画出来但很慢 → 正常（蓝牙 HID 约 100–125 Hz），后面靠参数补偿

---

## 四、协议

| 方向 | 格式 | 说明 |
|---|---|---|
| PC → 手机 | `"dx dy\n"` | 整数，建议 -127..127 |
| PC → 手机（带按键） | `"dx dy b\n"` | b: 1=左键 2=右键 4=中键，0=松开 |
| 端口 | 8089/TCP | 局域网 |

手机端会把高频指令**合并**后按 125 Hz 发出，避免排队导致的延迟——这是"卡"的主要来源。

---

## 五、文件结构

```
android/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ .github/workflows/build-apk.yml     # 云端编译
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      └─ java/cc/vpc/hidmouse/
         ├─ MainActivity.kt            # 权限 + 启动 + 显示 IP
         ├─ HidMouseService.kt         # HID 注册 + TCP + 合并发送
         └─ NetUtil.kt                 # 取本机 IPv4
```

---

## 六、已知坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `registerApp=false` | 权限没给 / ROM 限制 | 给「附近的设备」；换机型 |
| 电脑看不到鼠标 | 配对时 HID 服务没注册 | 手机 App 先启动，再在电脑上重新配对 |
| 连上但不动 | `host` 为空 | 断开重连蓝牙；重启服务 |
| 移动一顿一顿 | 蓝牙报告率低 + 队列堆积 | 已做合并发送；仍卡则降低 PC 发送频率 |
| 息屏后断开 | 后台被杀 | 已用前台服务；再把 App 加入电池白名单 |
