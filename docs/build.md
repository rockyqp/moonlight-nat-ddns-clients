# 编译说明

本仓库不提交本地构建产物。APK、Windows 压缩包等二进制文件建议放到 GitHub Releases。

## Android 推荐客户端

目录：

```powershell
cd clients\moonlight-android
```

准备 Android SDK、NDK 和 JDK 后执行：

```powershell
.\gradlew.bat assembleNonRootDebug
```

输出：

```text
app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

Debug 包名是：

```text
com.limelight.debug
```

它可以和官方 Moonlight `com.limelight` 共存。正式发布前应使用自己的 applicationId 和签名，避免和官方应用冲突。

## Android NAT Adapter PoC

目录：

```powershell
cd clients\android-moonlight-nat-adapter
```

Android Studio 打开目录后可直接构建，或使用 Gradle：

```powershell
gradle :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果本机没有全局 Gradle，可以先生成 wrapper，或通过 Android Studio 的 Gradle 面板构建。

## Windows 客户端

目录：

```powershell
cd clients\moonlight-qt
```

依赖：

- Qt 6.7 或更新版本，MSVC 组件。
- Visual Studio 2022。
- 7-Zip，用于生成可分发包。

推荐沿用 Moonlight-Qt 官方 Windows 构建流程：

```powershell
.\setup-deps.ps1
scripts\build-arch.bat Release x64
scripts\generate-bundle.bat
```

常见输出：

```text
build\build-x64-release\app\release\Moonlight.exe
build\deploy-x64-release\Moonlight.exe
```

`build\deploy-x64-release` 是更适合打包分发的运行目录，因为其中包含 Qt 运行文件和依赖库。
