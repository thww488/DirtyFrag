DirtyFrag (CVE-2026-43284)

DirtyFrag 是 diabl0w/DFRoot 的加固分支。它利用 CVE-2026-43284（DirtyFrag）——一个内核页缓存写入原语——在不解锁引导加载程序、不刷自定义内核的情况下加载内核模块。本分支针对三星手机（OneUI、RKP 与 KDP 加固）进行了适配，并保留了上游不绑定特定 SU 管理器的设计。

[!IMPORTANT]
Root 权限是临时的。内核模块、su 以及任何已加载的模块都会在设备重启的瞬间消失。重启后手机总会回到干净的、未 root 的状态——这同样也是某次运行出错后的恢复途径。

安装

1. 从 Releases 安装 DirtyFrag APK。
2. 安装一个自带 libksud.so 的 SU 管理器：
   · 三星设备——必需：
     · diabl0w 的 KernelSU
   · 其他设备：
     · KernelSU
     · KernelSU-Next
     · KowSU
     · BakaSU（原 ReSukiSU）
3. 打开 DirtyFrag，在 SU Manager 卡片中选择你的管理器，然后点击 Run exploit。

[!WARNING]
自 1.10 版本（上游 3.2）起，DirtyFrag 不再内置 ksud——它改从所选管理器自带的 libksud.so 中暂存。不包含该库的管理器无法为你提供 root。以前“安装管理器的 CI / Actions 构建版本”的建议已不再适用。

全新安装 DirtyFrag 会获得一个新的 Android UID，因此每次重装后都需要在管理器中重新授予权限。

<img width="540" height="1170" alt="Screenshot_20261015_220718" src="https://github.com/user-attachments/assets/bd727962-a520-4820-a91a-90147aa84faa" />

功能

· 在应用内选择你的 SU 管理器
· Autorun（开机自启），受专家模式管控，并带有失败运行联锁：一次失败尝试后会自动禁用，因此一次糟糕的运行不会让你的设备陷入启动循环
· Auto reboot（软重启）——自 1.11 版本（上游 4.1）起，该功能适用于所有 KernelSU 衍生版：dfroot.ko 在晚期加载后会被卸载，然后执行 ksud 自带的 soft-reboot
· KernelSU 模块开/关——无需 root 即可生效，由内核模块在 ksud 启动前应用
· RO 分区保护
· 高级日志，一键保存到 Downloads
· 工具栏中的 Odin 胶囊按钮：其圆点指示当前固件是否允许设备进入下载模式，点击它会打开做出该判断所依据的三项事实
· 发布新版本时，标题本身会变成一个青柠色的 Update 胶囊按钮——一键跳转到发布页
· 无需 Shizuku

常见问题

问：日志显示修补失败，并提示我的设备不受影响

答：抱歉，没有办法解决。可能是：

· 你的设备内核太新
· 你的设备厂商已回移植官方缓解措施
· 意外缓解（通常出现在内核 6.1 上）

问：日志显示 "SUCCESS" 或 "ksud exited with error"，但我没有 root

答：如果 Autorun 卡片中的 Auto reboot 已启用，请将其关闭——管理器无法完成的软重启会导致已加载的模块再次被卸载。否则请参阅上游讨论 69。

问：我安装了一个模块，现在一启动 root 就崩溃

答：将 KernelSU 模块卡片设为 Disabled，然后重新 root。该开关无需 root 即可工作，这正是它的意义所在——当某个模块就是导致运行崩溃的元凶时，它是你的逃生通道。

问：模块 + Autorun，现在设备陷入启动循环

答：Android 通常能检测到启动循环并提供安全模式；否则在启动时按住音量下键。进入安全模式后关闭 Autorun，并将 KernelSU 模块设为 Disabled，或者直接卸载 DirtyFrag 直到设备稳定。如果你无法进入系统足够深，在任何 PC 上执行 adb uninstall df.root 即可在用户版本上卸载（无需 root）。

问：进度条停在 0%

答：这是应用的日志标记映射，不是漏洞利用本身。运行窗口为 70 秒——不要重跑一次“缓慢”的运行，守护进程可能仍在工作。

问：如何干净地移除 root？

答：在取消 root 之前，使用具有 root 权限的文件管理器删除 /data/adb/KernelSU 以及 KSUD 残留（不要删除 ADB 文件夹本身）

构建

```sh
JAVA_HOME="<Android Studio jbr>" ./gradlew assembleRelease
```

APK 输出到 app/build/outputs/apk/release/dirtyfrag.apk。

发布构建使用 app/keystore.jks 签名，该文件已被 gitignore（签名配置期望别名为 dirtyfrag，两个密码均为 dirtyfrag）。构建前请生成你自己的密钥库：

```sh
keytool -genkeypair -v -keystore app/keystore.jks -alias dirtyfrag \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass dirtyfrag -keypass dirtyfrag
```

八个预构建内核模块已提交在 app/src/main/jni/ko/ 下。上游将这些文件 gitignore，并在 CI 中按 KMI 构建；本分支将它们提交，以便直接克隆即可构建，无需 DDK 工具链。它们由本仓库的 Build 工作流（LKMs / <kmi> 任务）根据当前的 lkm/dfroot.c 构建，因此每当该文件变更时都必须重新构建：运行工作流，下载 df-lkm-<kmi> 构件并放入此目录。若想自行构建，请使用上游的 make / podman DDK 流程。

工作原理

Android 内核会直接将 AES-CBC ESP 数据包解密到已打开供 splice() 使用的文件的页缓存中。通过构造 IV = AES_ECB_DEC(key, current_content) XOR desired_content，可以在没有写权限、不触发写时复制的情况下覆盖映射共享库中任意 16 字节对齐的块。

漏洞利用使用该原语将 shellcode 修补到内核页缓存中的 libc++.so。下一次对被挂钩函数的特权调用会执行该 shellcode，从而通过 insmod 加载内核模块。

利用链

1. IpSec transform——应用分配一个 UdpEncapsulationSocket 和一个 SPI，并通过 IpSecManager 构建 AES-CBC/HMAC-SHA256 ESP 变换。
2. splicehelper -> crash_dump64——使用 CBC 原语将 splicehelper 拼接进 crash_dump64。crash_dump64 运行在 crash_dump SELinux 域中，该域可以打开 untrusted_app 无法读取的 vendor_file 标签文件——这正是我们需要的桥梁。
3. dfroot.ko -> vendor_file——内核模块通过该桥梁写入一个带 vendor_file 标签的文件。
4. libc++ 钩子（在 init 中触发，uid=0，tid=1）——shellcode 被修补到 std::ostream::sentry::sentry() 处。它会检查 uid 和 tid，创建 /dev/df 作为一次性互斥锁以防重入，然后在 vendor_modprobe 域中执行 insmod。
5. dfroot.ko init——将 SELinux 设为宽容模式，在存在的情况下通过 kprobes 绕过 DEFEX，然后使用 call_usermodehelper 运行引导程序。
6. bootstrap——读取应用的设备保护设置，接管 zygote 环境，将分区设为只读，可选地禁用所有模块，最后从你安装的 SU 管理器启动 SU 守护进程。

致谢

· 上游项目与利用链：https://github.com/diabl0w/DFRoot
· 原始 PoC 及各类代码：https://github.com/lsposed/lspromise
· SELinux 宽容内核模块及各类代码：https://github.com/polygraphene/DFReroot
· 非特权 XFRM socket 方法：https://github.com/combeng6th/DirtyInit