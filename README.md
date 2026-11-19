# RikkaHub Plugin Host

RikkaHub 插件宿主维护仓库：固定上游源码、重放适配补丁、导入插件平台，构建独立发行版。

插件平台、派生发行与上游跟随已实现；P2/P3、P4/P5、P6/P7 均已接受。最终 `host-v5` 固定维护输入 `3035774`：干净重放、canonical 回归、三 ABI R8/签名、完整对应源码和独立复审已核验，API26/API36 的真实 updater 与同签升级证据保留。成品位于 `.work/releases/host-v5`；正式发布等待用户确认长期 APK 密钥的可恢复离机备份。详见 [接受出口](docs/changes/p6-p7-acceptance.md)、[发行手册](docs/state/host-release-runbook.md) 和 [实施状态](docs/implementation-status.md)。

## 开发

需要 Git、Node 24.15.0、pnpm 11.28.11；Android 使用 profile 指定的 JDK/SDK 和上游 Gradle wrapper。

```powershell
pnpm install --frozen-lockfile
pnpm test
node tools/hostctl/cli.mjs doctor
node tools/hostctl/cli.mjs observe-upstream --branch master
node tools/hostctl/cli.mjs prepare --profile rikka-github --out .work/integration
```

hostctl 接口随 P0 实现。固定的构建输入、命令与验证结果会记录在工作区和 docs/changes；未验证能力不计作阶段完成。

## 来源与许可

上游为 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，初始基线为 bfac2440c8b4dfad97be0a20e4b3e6709fdd6d88。保留上游 AGPLv3 许可；本仓库原创代码同样按根 LICENSE 分发。
