# WxBridge

用 LSPosed 模块把**本机微信的聊天记录和图片**接进外部 LLM，做摘要、待办提取、风险扫描。

默认只读。自动回复是需要显式开启的写操作，有封号风险。不修改数据库，不碰别人的数据。

---

## 边界先说清楚

| 做什么 | 不做什么 |
| --- | --- |
| 读本机、本人账号的消息与图片 | 撤回 / 改库 |
| 图片走视觉模型识别（读字 + 看画面） | 导出他人聊天记录 |
| 脱敏后交给云端 LLM 分析 | 批量群发 / 营销 |
| 按规则自动回复（默认关，需显式开启） | 对外提供任何服务 |

聊天内容**会离开设备**（发给你配置的 LLM API），图片也一样。`Redactor` 做身份代号化和数字打码，但这只是降低暴露面，不等同于加密。敏感场景请把 Base URL 换成本地 ollama / vLLM。

---

## 架构

```
┌──────────────── 微信进程 com.tencent.mm ────────────────┐
│  WcdbKeyHook                                            │
│    hook com.tencent.wcdb.database.SQLiteDatabase        │
│         #openDatabase(path, password, …)                │
│         ↓                                               │
│  KeyBridge → shared_prefs/wxbridge.xml               │
└─────────────────────────────────────────────────────────┘
                          │  su cat
                          ▼
┌──────────────── 本 App 进程 io.wxbridge ────────────────────┐
│  WxKeyReader    读 XML，还原 SQLCipher 密码              │
│  WxDatabase     su cp 主库 + -wal 到私有目录             │
│  DbOpener       参数探测后解密（SQLCipher 3 / 4）        │
│  MainRepo       查 message / rcontact                   │
│  ImageResolver  imageinfo2.db + image2/ 定位图片         │
│  ImagePipeline  视觉模型识图（读字 + 看画面）            │
│  Redactor       wxid→U1、手机号/身份证/银行卡打码        │
│  LlmClient      OpenAI 兼容 chat/completions + 多模态    │
└─────────────────────────────────────────────────────────┘
```

### 三个关键设计决策

**1. hook 层只干一件事：偷密码。**

其余全部在主 App 进程。微信升级导致 hook 失效时，你只需要修 `WcdbKeyHook.kt` 一个文件。

**2. 密码走 shared_prefs，不走 socket。**

Android 11+ 跨 uid 的 abstract unix socket 在不同 ROM 上时通时不通（SELinux `unix_stream_socket connectto`），ContentProvider 要处理 URI 权限，广播在 8.0 之后基本废了。最稳的路是：微信进程写**它自己的** `shared_prefs`（100% 有权限），主 App 用 `su cat` 读（100% 能读）。零权限谈判。

**3. 复制库，而不是在微信进程里查。**

在微信进程跑查询确实更优雅（不用解密），但任何一次崩溃都连累微信，调试靠猜。复制 + 本地解密多花几百毫秒，换来完全隔离。

---

## 图片理解

图片不再是 `[图片]` 占位符。统一走视觉模型 —— 一条通路同时解决「读字」和「看画面」。

输出直接嵌进消息流：

```
[图片 · 快递面单。关键信息：顺丰 SF1234567890，运费 12 元，2026-11-04 发出]
```

提示词让模型按三段输出：图类型 / 关键文字（金额、单号、时间、地址）/ 画面要点。
纯文字截图靠第二段，照片靠第三段，一个请求搞定，不用维护两套识别链路。

细节：

- 结果按图片文件名缓存到 `cache/img/vlm/`，同一张图只算一次，重复摘要不重复花钱
- 每次摘要默认最多分析 **20 张**，超出的跳过（界面可改）
- 图片压到长边 1024、JPEG 75% 再发送，单张约 100~200KB，约 1024 tokens 封顶
- 识别结果同样过 `Redactor`，图里的手机号、身份证一样打码
- **必须配视觉模型**。`deepseek-flash`（V4.1-Flash）原生多模态，选 DeepSeek 预设会自动填好；
  选的模型不支持图片时，勾选框关掉即可，图片退化成 `[图片]` 占位符

> 早期版本用过「本地 ML Kit OCR + 视觉模型」双通路，后来去掉了 OCR：
> 多模态模型读字已经够用，省下 15MB 体积，也少一条要维护的链路。
> 代价是图片识别必须联网。

---

## 自动回复

**这是写操作，有真实的封号风险，默认关闭。**

| 能力 | 说明 |
| --- | --- |
| 私聊直接回 | 收到文本消息就生成回复 |
| 群聊仅 @ | 只在你被 @ 的群里回，其余不理 |
| 上下文 | 带最近 N 条（默认 10）对话喂给模型 |
| emoji | 文本消息原生支持，直接带字符就显示成表情 |
| 表情包 | 对方发表情时，按概率回一个本地表情包 |

整个引擎跑在**微信进程**里：主库实例已经是解密状态、发送接口也只能在那调用。
App 只负责写配置（用 root 写进微信的 `files/wxbridge/`）和读日志。

触发是**事件驱动**的：hook WCDB 的 `insert`，只认表名 `message`，消息一落库就处理。
没消息时模块完全静默 —— 不轮询、不唤醒、不耗电。只有 insert hook 没挂上时，
才降级成 60 秒一次的低频兜底。

这样做的还有个好处：不依赖微信那些混淆成单字母的消息回调类，
只依赖 `com.tencent.wcdb`（独立库，不参与微信混淆），和已有的密码 hook 同源。

### 防封措施（缺一不可）

- 全局开关默认关
- 默认只回白名单
- 回复前随机延迟 5~12 秒，模拟打字
- 同一会话冷却 180 秒
- 每日总量上限 100 条
- 只回文本消息和表情包，不回自己、不回系统
- 群里只回被 @ 的文本，**不自动回表情**（避免刷屏感）

这些不是建议，是底线。少任何一条，封号概率都会明显上升。

### 发送入口是最大的不确定点

微信发消息的接口在 `com.tencent.mm.model.*` 里被混淆成单字母静态方法，版本之间类名会变。
`WxSender` 的做法是「多候选类名 + `(String, String, int)` 签名匹配」，命中即用；
找不到会明确报出来，不去全量扫描 —— 乱调一个参数相同的方法可能触发完全无关的逻辑。

启动微信后看日志这一行：

```
AutoReply: 发送入口 = com.tencent.mm.model.bh#a(String, String, int)
```

显示「未找到发送入口」就是版本不兼容，把新类名加进 `WxSender.CANDIDATES` 即可。

### 第一次怎么试

1. 填好模型和 API Key（复用上面「模型」区）
2. 白名单里**只填你自己的小号**
3. 勾「启用自动回复」，保存
4. 用小号发一条消息，等 10 秒左右
5. 点「回复日志」看结果

别一上来就开全量。日志会写清楚每条是「已回复」还是「发送失败」。

### 表情包怎么发

本地表情包在 `MicroMsg/<md5>/emoji/`，文件名就是 md5。`EmojiRepo` 扫这个目录建清单，
发送时只需要 **md5 + 文件大小**，不用把文件读出来 —— 本体微信自己会从缓存或 CDN 取。

触发条件：**对方发的是表情包（type=47）且是私聊**，按概率回一个随机表情。

- 概率默认 50%，全量回很假
- 群里不自动回表情，太像刷屏
- 没有语义标签，所以是**随机挑**，不是「回一个合适的」。想要更准只能给每个表情手工打标签
- 发送入口同样靠候选匹配：找 `(String, EmojiInfo)` 的静态方法，再反射构造 `EmojiInfo`
  填 md5/size。命中不了就是版本不兼容，日志会写「表情发送入口不可用」

启动日志分两段告诉你结果：

```
AutoReply: 文本=com.tencent.mm.model.bh#a(String, String, int) | 表情=com.tencent.mm.model.bh#a(String, EmojiInfo)
```

任一段显示「未找到」，就把对应类名加进 `WxSender` 的候选列表。

## 模型配置

界面上选预设，自动填好 Base URL 和模型名，只需要贴 API Key。

| 预设 | 文本模型 | 视觉模型 | 备注 |
| --- | --- | --- | --- |
| DeepSeek | `deepseek-flash` | `deepseek-flash` | V4.1-Flash 原生多模态，一套模型通吃；旧的 `deepseek-chat` 仍可用 |
| 通义千问 | `qwen-plus` | `qwen-vl-plus` | 中文 + 看图，综合最平衡 |
| 智谱 GLM | `glm-4-flash` | `glm-4v-flash` | 免费额度大，适合先跑通 |
| Moonshot | `moonshot-v1-8k` | `moonshot-v1-8k-vision-preview` | 长文本友好 |
| OpenAI | `gpt-4o-mini` | `gpt-4o-mini` | 需要海外网络 |
| 本地 ollama / vLLM | 任意 | 视模型而定 | 记录不出内网，推荐 `qwen2.5:7b` + 局域网 IP |

全部走 OpenAI 兼容协议，自定义端点也能填。文本模型和视觉模型分开配置 —— 摘要用便宜的，看图为少数几张单独调视觉模型。

---

## 构建与发版

### 自动版本号

版本号不手写，由 CI 从 git 推导：

| 触发 | versionName | versionCode |
| --- | --- | --- |
| push 到 `main` | `0.0.0-<sha7>` | `git rev-list --count HEAD` |
| 打 tag `v1.2.3` | `1.2.3` | 同上 |

`versionCode` 用提交总数，单调递增，满足覆盖安装要求。本地不传参就用 `0.1.0 / 1`，Android Studio 直接点 Run 也能过。也可以手动指定：

```bash
gradle :app:assembleRelease -PversionName=0.3.0 -PversionCode=42
```

### 发一版

```bash
git tag v0.2.0
git push origin v0.2.0
```

CI 自动构建 release APK → 上传 artifact → **创建 GitHub Release 并附上 `wx-ai-0.2.0.apk`**，Release 说明带安装步骤。普通 push 只出 artifact，不发版。

### 本地

Android Studio 打开目录即可，或 `gradle :app:assembleRelease`。要求 JDK 17 + Android SDK 34。

> `de.robv.android.xposed:api:82` 来自 `https://api.xposed.info/`。该仓库不可达时，从任意 Xposed 模块工程取 `api-82.jar` 放进 `app/libs/`，把依赖换成 `compileOnly(files("libs/api-82.jar"))`。**绝不能改成 implementation**，否则 APK 会带一份 `de.robv.android.xposed`，和框架自身的类打架。

APK 体积约 10MB。没有打包任何端侧模型 —— 图片识别全部走云端视觉模型，
这是刻意的取舍：省体积、少一条维护链路，代价是图片识别需要联网。

---

## 应用内更新

App 底部「更新」一栏可以直接检查新版本：

1. 点「检查更新」→ 请求 `api.github.com/repos/fadeds/wxbridge/releases/latest`
2. 比对语义版本（`1.10.0` > `1.9.2`；`0.0.0-abc1234` 视为 `0.0.0`）
3. 有新版就显示更新说明，「下载并安装」拉到 `cacheDir/update/` 再拉起系统安装器

注意事项：

- 首次安装需要允许「安装未知应用」，App 会自动跳到设置页
- 仓库是私有的要填 GitHub token，否则 401；公开仓库留空即可（未认证限 60 次/小时）
- 国内访问 `api.github.com` 可能慢，超时给到 15 秒
- 更新走的是 Release，**得先打 tag 发一版**，检查更新才有东西可比

## 使用

1. 装 APK（debug 签名，随便装）
2. LSPosed → 模块 → 启用「WxBridge」→ 作用域勾选**微信**
3. **强制停止微信**，重新打开，进一次聊天列表（让 WCDB 真的打开库）
4. 回 App，依次点：

```
① 环境自检   → 确认 root、MicroMsg 可读、hook 是否已写密码
② 读取密钥   → 拿到 dbPath + password
③ 打开数据库 → 复制、解密、探测参数、打印表结构和图片数量
④ 生成摘要   → 跑
⑤ 库结构扫描 → 扫一遍所有 .db，看 rcontact / ImgInfo2 落在哪个库
```

第 ⑤ 步是库结构诊断：微信 8.x 后期把表从 `EnMicroMsg.db` 拆了出去，
`rcontact`、`ImgInfo2` 可能各在独立的库里。图片定位和联系人名解析都依赖这个判断，
先跑一次看清楚，调试会省很多时间。

### 主库是运行时探测出来的

**8.0.79 实测根本没有 `EnMicroMsg.db`。** 所以主库不能按文件名找，判定标准改成一条：
哪个库里含 `message` 表，哪个就是主库。

两条路径，互为兜底：

1. hook 在开库时顺手查一下 `sqlite_master`，命中就记进 `main_db`
2. 模块没记到时，App 侧自己把已抓到密码的库逐个解密检查（`MainDbFinder`），
   跳过 `WxCgiReport` / `AppBrandDB` / `FTS5Index` 这类明确无关的库，最多试 8 个

第二条不依赖 hook 的时机，所以即使微信换了开库路径也能work。

---

## 已知限制

**微信版本兼容性。** hook 打的是 `com.tencent.wcdb.*`，腾讯开源的独立库，不参与微信主包混淆，8.x 各版本基本稳定。日志里出现 `WARNING: no WCDB hook installed` 就是没挂上。

**这个坑踩了一整天，值得单独说：微信启用了 Tinker 热修复。**
实际运行的类由 `DelegateLastClassLoader` 从 `/data/user/0/com.tencent.mm/tinker/patch-xxx/dex/`
加载，和主 ClassLoader 里的同名类是**两个不同的 Class 对象**。
在 `lpparam.classLoader` 上 hook 等于挂了一份没人用的副本 ——
日志显示 "hooked"，但调用一次都不触发。
现在改成挂 `ClassLoader.loadClass`，等目标类真正加载时再 hook，
按「类名 + ClassLoader 身份」去重。改了这一点之后密码立刻就抓到了。

**SQLCipher 参数不确定。** WCDB 早期用 SQLCipher 3 参数（1024 / 64000 / SHA1），8.0.4x 之后部分版本迁到 SQLCipher 4（4096 / 256000 / SHA512）。`DbOpener` 依次试三组，报错会列出试过的组合。

**图片定位有启发式成分。** 优先级：`ImgInfo2.bigImgPath` → `message.imgPath` → 目录 mtime 匹配（±15s）。第三级会错配，日志里标 `guess image by mtime`。

**图片识别必须联网。** 没有端侧 OCR，图的文字和画面都靠视觉模型。断网、或所选模型不支持图片时，图片会退化成 `[图片]` 占位符。纯文字截图用多模态模型读，偶尔会有个别字识别错误，重要单号建议人工核对。

**语音没做。** `voice2/` 是 SILK 编码，要先转 PCM 再转文字，成本比收益高，这版跳过。

**只支持 Android 端微信。** PC 微信 4.0 是另一套 `db_storage` 结构。

---

## Plan B：三组参数全部探测失败

备选方案是**在微信进程里直接查**：hook 到 `SQLiteDatabase` 实例（`openDatabase` 的返回值），在微信进程内执行 `SELECT`，把结果写成 JSON 落到 `/data/data/com.tencent.mm/files/wxbridge_dump.json`，主 App 用 `su cat` 取走。

完全绕开密码和参数问题，用的就是微信自己的连接。代价是查询跑在微信进程、崩了连累微信，且增量时机不好控制（需要主 App 放请求文件、hook 侧轮询）。`WcdbKeyHook` 里已经能拿到 `param.result`，改造成本不高。

---

## 风控与安全

- **自动回复默认关闭**。开了之后封号概率显著上升，第一次务必只对自己的小号。
- 建议给微信开 **DenyList / Shamiko**，作用域只勾微信。
- 脱密库副本在 `/data/data/io.wxbridge/files/wxdb/`，卸载即清除。
- API Key 存在 SharedPreferences，`allowBackup=false` 已关云备份；别在截图里露出来。

## 合规

读自己账号在本机的数据、自己分析，属于个人使用。一旦涉及导出他人聊天记录、或做成对外服务，性质就变了 —— 需要对方知情同意，也涉及《个人信息保护法》。

自己用，别外传。
