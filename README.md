# RikkaHub Plugin Host

RikkaHub 插件宿主维护仓库：固定上游源码、重放适配补丁、导入插件平台，构建独立发行版。

P2/P3 和 P4/P5 已通过独立审查。派生 APK 已通过签名校验和与官方 APK 并装验证，当前正在验证前版升级和上游跟随。详见 [实施状态](docs/implementation-status.md) 和 [仓库设计](docs/state/plugin-patch-repository.md)。

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
