# 插件 wire v1

本目录记录已经接入本地包、QuickJS 和 Android UI 的协议。Kotlin DTO 见 [PluginPackage.kt](../../../platform/protocol/src/main/kotlin/dev/rikkahub/plugins/protocol/PluginPackage.kt)，TS 作者接口见 [SDK](../../../sdk/plugin/index.ts)，宿主验证规则见 [PluginContract](../../../platform/core/src/main/kotlin/dev/rikkahub/plugins/core/PluginContract.kt)。跨端真实包 fixture 位于 `adapters/rikka/app/src/androidTest/assets/plugins`：SDK 测试逐字节比较构建产物，Android 直接安装同一包。

## 包与兼容性

`.rikkaplug` 是有界 ZIP，包含 UTF-8 `manifest.json`、一个打包后的 JavaScript 入口（默认 `main.js`）和显式 `resources` 列表。入口使用源码；在开发机 bundle 依赖。宿主接受协议 major `apiVersion: "1"`、`runtime: "quickjs-es2020"`。必需 feature 必须属于宿主公开集合：`operations.v1`、`tools.v1`、`ui.native.v1`、`ui.overlay.app.v1`。

manifest 包含必填 `id`、`name`、严格 SemVer `version`，以及 `capabilities`、`requiredFeatures`、`resources`、`operations`、`tools`、`panels`。各组贡献 ID 唯一，组间可以重名。宿主要求实际 factory 注册的集合与 manifest 完全相符。未知字段、能力和必需 feature 被拒绝，新增必需字段需要显式兼容演进。

| 约束 | 上限或规则 |
| --- | --- |
| ZIP 原字节 / 展开总字节 | 4 MiB / 8 MiB |
| 文件数 | 128 个显式普通文件 |
| manifest / JS 源码 | 64 Ki 字符 / 768 Ki 字符，SDK 对应使用更严格字节上限 |
| 路径 | 1–200 字符；ASCII 字母、数字、`_ . / -`；每个分段非空、非 `.`/`..`、不以点结束 |
| 贡献数 | 合计 1–64；局部 ID 为 `[a-z][a-z0-9_]{0,39}` |
| 工具输入 / 返回值 | 输入 256 Ki 字符；返回 envelope 512 Ki 字符 |
| UI | 最多 200 节点、深度 12、每节点文本 32000 字符 |

路径仅作为内存资源键；包存储以 SHA-256 寻址。重复及大小写冲突路径、目录、符号链接、加密、多卷、ZIP64、不一致目录和越界数据均拒绝。归档哈希覆盖最终 ZIP 原字节。

## 注册与调用

作者通过 `definePlugin(api => { ... })` 注册 `registerOperation`、`registerTool`、`registerPanel`。factory 登记函数，预检不可访问会话数据。宿主拥有序列化调用、权限检查、时间预算、取消及资源回收；Android 在 `isolatedProcess` Service 运行实际 JS。包没有 Node、文件系统或网络 API；可调用能力由宿主显式提供。

`PackageLease(pluginId, packageHash, activationEpoch, issuerId)` 由宿主签发和核验。issuerId 是授权目录创建时签发的 UUID，随目录持久化，而非会话备份中的授权。新安装即使重新导入相同包、产生相同 epoch，也不接受旧安装的 run/审批 lease。旧目录缺少该字段时保留空 issuerId 兼容身份；新目录绝不签发空身份。新运行捕获选定包列表，空列表也被保存。更新可以保留旧 lease，进行中的运行和审批继续解析原包；停用撤销该插件所有版本，重新启用使用新的 epoch。

当前 Rikka 宿主把整个安装目录（包字节、来源记录、issuer 和激活授权）放在 `Context.noBackupFilesDir/plugin-host`，不参加系统 cloud backup 或 device transfer；现代两种备份规则另显式排除旧 `filesDir/plugin-host`，旧系统保留原 upload-only 白名单。会话数据库备份保留包哈希选择、完整原 lease 和历史结果，但不携带可迁移的执行授权。新安装需要重新导入包并确认权限。

升级时若只有旧目录，宿主在目标同文件系统的临时目录中验证并复制全部保留包，保留来源/版本高水位，生成新 issuer，清空 grants/preferred 并停用所有记录，再原子发布。旧目录不被覆盖；目标已有安装时不再次迁移。缺包或损坏数据使迁移失败，不发布部分目录，也不以重置权限掩盖失败。旧活动 run/审批保留原 lease，不因同包重新授权而复活。

模型、工具、操作和恢复入口都核验 owning run 保存的完整原 lease 列表；无 gate 的旧 run 也必须持久化 `host.package-authorization` 并等待。异步准备之后以及实际 effect admission 时再次核验。只有原 lease 再次有效时该宿主 gate 才可清除，不能清除用户 pause、审批或其他独立 gate；另一安装签发的权限不满足这个条件。

操作和工具的 `Context.readMessages()` 返回授权快照中的 `{id, role, text}[]`。宿主在调用前后复核授权。面板渲染只能返回 UI 树。无效工具参数在 app adapter、隔离客户端和运行时三个入口校验，验证发生在宿主读取上下文之前。

## 输入与结果

工具声明 `id/title/description/parameters/needsApproval`；默认需要审批。参数为结构化 JSON Schema 子集：根对象支持 `type/properties/required/additionalProperties`；子节点支持 object、array（必须指定 items）、string、number、integer、boolean、null，以及 description、enum。根 description 应放入工具声明；不支持的 schema 关键字直接拒绝。定义限制为深度 12、256 个 schema 节点；实际参数先对全部值（包括 additionalProperties 子树）检查深度 16、10000 个节点，再序列化检查字符预算和验证 schema。

工具返回 `{content: string, details?: object}`。`content` 转为模型可见工具输出；`details` 与原包身份、工具 ID、标题一起进入消息确认事务。审批阶段已捕获显示身份。拒绝、人工回答、参数失败或 handler 异常没有返回新 details 时，结算保留原显示身份；插件的可选结果数据只能进入 data 区。Android 与 Web 展开历史结果只读取保存字段；Provider 请求不包含 UI details。

操作返回 `{projection?: {omit: string[]}, artifacts?: {type: string, content: Json}[]}`。projection 需要 `context.write`，artifact 需要 `artifacts.write`。宿主只允许省略本次来源中的消息，在 Session 同库事务确认来源及结果；历史消息保留。报告操作可在空闲会话运行，无需创建模型 run。

面板返回 `PluginUiNode`：`text`、`markdown`、`column`、`button`。只有 column 可含 children；button 的 operation 必须属于当前包声明，并附带 JSON object input。宿主把按钮绑定到实际渲染时的会话、分支、输入指纹和包 lease。点击前来源改变会拒绝。界面销毁回收渲染实例，已接收操作由 ChatService 管理，可通过宿主 Stop 取消。

## 权限与恢复

当前能力为 `context.read`、`context.write`、`artifacts.write`、`tools.register`、`ui.actions`、`ui.overlay.app`。包预检成功后才请求授权。激活目录原子替换确认后发布注册表；冷启动验证全部保留包与目录，损坏状态阻止激活。停用先撤权，再取消并等待宿主读取和 JS 实例清理。

操作记录 PREPARED、RUNNING、SETTLED、CANCELLED、UNKNOWN；进程恢复将未确认 RUNNING 转为 UNKNOWN，保存 attempt 身份供核对。操作与报告区域属于宿主，可从会话插件管理页直接进入；每页显示 10 条并提供前后分页，所有原分支可见记录均可访问。插件停用、授权失效或新版移除面板后仍可读取历史记录，不加载 JS。恢复数据库但缺少原包时，页面显示所需包哈希，允许重新导入后明确授权，或在无活动 run 时采用当前版本/清除选择。活动 run 保留原 lease 并等待，Stop 后才能修改选择。源内容变更显示提示；旧动作重新提交仍需通过来源验证。UNKNOWN 不自动执行或伪装成完成，Stop 保留已有执行证据。
