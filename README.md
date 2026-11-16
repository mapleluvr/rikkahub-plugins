# RikkaHub Plugin Host

RikkaHub 插件宿主维护仓库：固定上游源码、重放适配补丁、导入插件平台，构建独立发行版。

当前处于 P0 实施，尚无可安装的插件宿主发行版。实施和验证状态见 [工作入口](docs/OVERVIEW.md)，工程约定见 [仓库设计](docs/state/plugin-patch-repository.md)。

## 开发

需要 Git、Node 24.15.0、pnpm 11.28.11；Android 使用 profile 指定的 JDK/SDK 和上游 Gradle wrapper。

```powershell
pnpm install --frozen-lockfile
pnpm test
node tools/hostctl/cli.mjs doctor
node tools/hostctl/cli.mjs prepare --profile rikka-bfac2440 --source ../rikkahub --out .work/integration
```

hostctl 接口随 P0 实现。固定的构建输入、命令与验证结果会记录在工作区和 docs/changes；未验证能力不计作阶段完成。

## 来源与许可

上游为 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，初始基线为 bfac2440c8b4dfad97be0a20e4b3e6709fdd6d88。保留上游 AGPLv3 许可；本仓库原创代码同样按根 LICENSE 分发。
