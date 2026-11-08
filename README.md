# 进程

联想拯救者平板 Legion Y700 上的进程查看。桌面名称是「进程」，包名 `app.y700.proc`。

打开页面后，用 Magisk 的 `su` 采样内存和 CPU，大约每两秒一次。离开页面就停止。

## 界面

- 顶部是可用内存、总内存、整机 CPU、交换分区占用。
- 「按内存」「按 CPU」切换排序，最多显示 12 条。
- 驻留内存不到约 20 MB，并且 CPU 不到 1% 的进程不进列表。
- 同一个普通应用的多个进程按 uid 加在一起。CPU 按单核算，100% 表示占满一颗核。
- 「清理」会 `am force-stop` 关掉这个应用的后台。桌面、系统界面、设置、电话和本应用没有清理按钮。系统进程也不能清。
- 底部「日志」看最近 40 行。文件在平板上的 `/data/adb/y700-proc/proc.log`。

## 平板上要有

- 已安装 Magisk，并且允许「进程」使用 root。`su` 会依次试 `/product/bin/su`、`/debug_ramdisk/su`、`su`。
- Magisk 自带的 busybox：`/data/adb/magisk/busybox`。
- Android 8.0 及以上。

## 自己编译

不需要 Gradle。Mac 上 Android SDK 在 `~/Library/Android/sdk` 时直接：

```sh
./build.sh
```

也可以设置 `ANDROID_HOME`。SDK 里要有 `platforms;android-34` 和 `build-tools;36.0.0`，JDK 用 17（源码按 Java 11 编译）。

编完得到 `y700-proc.apk`。签名文件是仓库根目录的 `debug.keystore`，没有就自动生成，库密码和密钥密码都是 `android`。keystore、apk 和 `build/` 不进 git。

```sh
adb install -r y700-proc.apk
```

## 发布

推送 `v*` 标签，或在 Actions 里手动跑 Release。工作流在 Ubuntu 上编译，把 `y700-proc.apk` 传到 GitHub Release。

签名用仓库 secret `KEYSTORE_BASE64`（keystore 的 base64）。这样每次发布的签名一致，平板上可以覆盖安装。
