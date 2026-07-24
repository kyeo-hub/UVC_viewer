# PureView · 纯视

一个极简的 Android 应用：插上 USB HDMI 视频采集卡即可在手机/平板上预览画面。
纯粹、无广告、无多余功能，把你的 Android 设备变成便携显示器。

## 特性

- ✅ 支持 USB UVC（USB Video Class）HDMI 采集卡
- ✅ 自动检测设备插入并打开预览
- ✅ 暂停 / 继续、分辨率切换
- ❌ 无广告、无网络上传、无多余功能

## 技术栈

- Kotlin + AndroidX
- [UVCCamera](https://github.com/saki4510t/UVCCamera)（libusb + libuvc）
- Gradle 8.9 + AGP 8.5.2
- 最低 Android 7.0（API 24），目标 Android 14（API 34）

## 项目结构

```
uvc-viewer/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/uvcviewer/
│       │   ├── MainActivity.kt
│       │   ├── camera/UvcCameraManager.kt
│       │   └── widget/CameraSurfaceView.kt
│       └── res/
├── gradle/libs.versions.toml
├── settings.gradle.kts
└── .github/workflows/build.yml
```

## 构建

### 本地构建

1. 安装 Android Studio + JDK 17
2. 打开项目根目录，等待 Gradle 同步
3. `Build → Build APK(s)` 或命令行 `./gradlew assembleRelease`

### GitHub Actions 构建

项目自带 workflow，触发条件：

| 触发方式 | 动作 |
|---------|------|
| `git tag v1.0.0` 推送 | 编译 release APK，签名，创建 GitHub Release 并上传 APK |
| `workflow_dispatch`（手动） | 仅编译 + 上传 artifact，不发布 Release |

**首次发布前需配置以下 GitHub Secrets**（用于 APK 签名）：

| Secret 名 | 说明 |
|-----------|------|
| `SIGNING_KEY_BASE64` | keystore 文件的 base64 编码：`base64 -i release.keystore` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

> 没有签名密钥？生成方式：
> ```bash
> keytool -genkeypair -v -keystore release.keystore -alias uvc-viewer \
>   -keyalg RSA -keysize 2048 -validity 10000
> ```

### 发布流程

```bash
# 1. 改 app/build.gradle.kts 里的 versionCode / versionName
# 2. 提交并打 tag
git commit -am "release v1.0.0"
git tag v1.0.0
git push origin v1.0.0
# 3. GitHub Actions 自动编译并在 Releases 页发布 APK
```

## 使用

1. 用 OTG 线把 USB HDMI 采集卡接到 Android 设备
2. 系统弹出"打开 UVC Viewer"或直接打开 App
3. 同意 USB 权限请求
4. 画面开始预览

## License

MIT
