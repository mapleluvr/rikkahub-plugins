# RikkaHub Plugin SDK 0.1.0

SDK 提供 `definePlugin`、操作/工具/面板 DTO，以及包构建、发布索引、签名、校验和发布工具。插件运行在 QuickJS；没有 Node API。插件可读取授权的会话快照，返回请求投影或报告，工具可分别返回模型文本与 UI details。当前不暴露插件任意持久存储，因此本版升级没有用户自定义存储迁移代码；历史结果和操作回执由宿主保留。

维护者先在仓库执行 `pnpm install --frozen-lockfile`，再执行：

```powershell
pnpm --dir sdk/plugin pack --out ../../out/plugin-sdk.tgz
node sdk/plugin/create.mjs ../my-plugin out/plugin-sdk.tgz <已审查的40位宿主提交> dev.example.myplugin
Set-Location ../my-plugin
pnpm install
pnpm test
$env:RIKKA_RELEASE_TAG = "v0.1.0"
pnpm exec rikkaplug-release build . release
```

生成器拒绝覆盖已有目录。生成项目携带 SDK tarball，首次安装后应把 `vendor/plugin-sdk.tgz` 和 `pnpm-lock.yaml` 一并提交；CI 使用 frozen lockfile。宿主工具包仍为私有仓库，跨仓库 Actions 需要配置读取权限或 read-only token。模板中的 workflow 和校验器来源固定到提供的提交，不能填浮动分支。

`rikkaplug build <dir> <file.rikkaplug>` 生成确定性 ZIP 与 SHA-256 文件。`rikkaplug-release build <dir> <out-dir>` 还生成 `release.json`，要求环境变量 `RIKKA_RELEASE_TAG`、manifest.version、package.json.version 一致。输出文件必须不存在，避免覆盖旧发行字节。

`rikkaplug-release verify <out-dir>` 校验包字节和索引。用宿主的 `verification/quickjs-cli` 进一步执行相同的解包限制、真实 QuickJS 注册和无宿主访问面板预检：

```powershell
verification/quickjs-cli/gradlew.bat -p verification/quickjs-cli test installDist
verification/quickjs-cli/build/install/rikka-plugin-contract/bin/rikka-plugin-contract.bat <release-directory>
```

可选签名：把 P-256 PKCS#8 PEM 文件路径放在 `RIKKA_SIGNING_KEY_FILE`，执行 `rikkaplug-release sign <out-dir>`。签名覆盖索引的原始 UTF-8 字节，信封为 ES256、SPKI 公钥和 DER ECDSA 签名。宿主展示公钥指纹，首次确认后固定身份；验证签名本身不证明作者身份。

`rikkaplug-publish <out-dir>` 使用宿主的 `GH_TOKEN`、`GITHUB_REPOSITORY`、`GITHUB_SHA` 和 `RIKKA_RELEASE_TAG`。它先校验 tag 指向的提交，再建 draft、上传缺失资产、逐个下载比对，最后发布。已发布资产不会覆盖；重跑只接受相同包和索引。同一密钥对相同索引重新生成的有效 ECDSA 签名可复验服务器原签名，不能借此更换公钥。

复用工作流将无写权限的包测试/构建/QuickJS 校验与有写权限的签名/发布分成两个 job。发布 job 不 checkout 插件源、不运行插件脚本。详见 `../../.github/workflows/plugin-release.yml`。本地 CLI 通过不代表 GitHub Actions 已执行成功，也不代替 Android 生命周期验证。
