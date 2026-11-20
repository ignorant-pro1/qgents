<div align="center">

# Qgents

**在项目群聊里 @ 一个 Agent，它在 Docker 沙箱里把活干完，把分支推回 GitHub。**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1.svg)](https://www.mysql.com/)

[快速开始](#快速开始) · [功能](#功能) · [架构](#架构) · [配置 GitHub App](#配置-github-app) · [部署](#部署)

</div>

## 这是什么

多半的 AI 编码工具停在对话框里：你把上下文复制进去，把结果复制出来。Qgents 把这件事放进团队的群聊：Agent 是项目成员，有项目内独立 ID、独立记忆和权限，只在被 @ 的那条消息上运行，跑完给你一个真实的分支，而不是一段看起来对的文本。

层级是团队 → 项目 → 群聊。新建项目自动生成该项目自己的默认 Agent（名字 Qgent）和默认群；新群只在有人选择时才把 Agent 拉进来。上下文属于项目自己：共享的 `memory.md`、群记忆、Agent 私有记忆、附件、群历史，Agent 直接读，不用人搬。

后端是单个 Java 21 / Spring Boot 应用，同时承载群聊、Agent 运行和沙箱管理，依赖 MySQL 与 Redis。前端在相邻仓库 `qgents-frontend`（React 19）。

## 快速开始

前置：JDK 21、Maven 3.9+、Docker（含 Compose v2）。跑前端另需 Node.js。

```sh
# 1. 配环境：数据库密码、模型 key、JWT_SECRET、SMTP 都要填
cp .env.example .env

# 2. 起 MySQL 和 Redis（127.0.0.1:13306 / 16379）
docker compose up -d --wait

# 3. 放进登录私钥，并构建 Agent 执行命令用的沙箱镜像
cp /path/to/your/rsa/private_key.pem src/main/resources/rsa/private_key.pem
#   没有现成的就现生成一把：
#   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/rsa/private_key.pem
docker compose --profile images build
# 4. 启动后端
mvn spring-boot:run
```

看到 `Started QgentsApplication` 就好了。三个入口：

| 入口 | 地址 |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| 接口文档 | http://localhost:8080/swagger-ui.html |
| 运维端点 | http://127.0.0.1:8081/actuator/health |

前端：

```sh
git clone <前端仓库地址> qgents-frontend
cd qgents-frontend && cp .env.example .env && npm ci && npm run dev
```

`.env` 里 `VITE_USE_MOCK=false` 时请求经 Vite 代理转发到 `http://localhost:8080`，页面在 http://localhost:5173。

Docker 连不上时后端照样能启动，只有需要沙箱的工具会失败；聊天、附件、GitHub 只读工具不依赖沙箱。

## 功能

- 群聊：团队 → 项目 → 多个群，消息、提及、回复、已读、置顶；只有 @ 了群内 Agent 的消息才启动运行，普通聊天不调用模型。
- Agent 运行：群聊消息和 Agent 待运行记录同事务写入 MySQL，排队任务可在服务重启后继续领取。默认由主 Agent 执行，最多 30 个模型轮次，单步最多 4 个工具并行。设置 `APP_AGENT_MAX_SUBAGENTS` 为正数可开启委派。排队和运行期间都可以按消息取消。
- 单文件替换：默认 Agent 接到路径与新旧文本都明确的请求（如「把 `src/main.py` 里的 `hello world` 改成 `hello qgents`」）时，优先使用精简的文件工具。需要更多项目上下文时可以在同一次运行中恢复完整工作流。可用 `APP_AGENT_FOCUSED_EDIT_ENABLED=false` 关闭。
- 沙箱：用户命令跑在临时容器里，默认 2 核 / 4 GiB，空闲 30 分钟回收、最长闲置 2 小时，单条命令默认 15 分钟超时、上限 1 小时。
- 三层记忆：项目共享的 `memory.md`、群记忆 `groups/<groupId>/memory.md`、Agent 私有记忆 `agent-memory/<agentId>/<groupId>.md`，都存在项目数据目录里，不是数据库表单。
- 项目知识：项目 Skill、跨群历史检索、图片与文本附件（单个上限 50 MB，存本地磁盘或阿里云 OSS）。
- GitHub App（可选）：团队安装，项目管理员导入仓库；Agent 能读 PR 和 Issue、拉代码、推分支，不会自动创建或合并 PR。配置步骤见[配置 GitHub App](#配置-github-app)。
- 实时与通知：项目事件走 SSE（可用 `Last-Event-ID` 续传）与 WebSocket；站内通知可选接 FCM 推到手机。

## 架构

```text
浏览器（React 19 前端）
   │  HTTP · SSE · WebSocket   /api/v1
   ▼
Spring Boot 应用 ── MySQL 8.4   业务数据、实时事件
   │              └ Redis 7     刷新令牌、验证码、限流
   ├── DeepSeek（OpenAI 兼容协议，Spring AI）
   └── Docker Engine
         └── 沙箱容器 ── 项目数据目录：memory.md、groups/、agent-memory/、.git、workspaces/
```

业务端口 `8080`，运维端口 `8081` 默认只监听回环地址，暴露 `health`、`info`、`metrics`、`prometheus`。

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 运行时 | Java 21、Spring Boot 4.1 |
| 数据 | MyBatis-Plus 3.5、MySQL 8.4、Redis 7 |
| 模型 | Spring AI 2.0、DeepSeek（OpenAI 兼容接口） |
| 沙箱 | docker-java 3.7，用户命令在独立容器中执行 |
| 集成 | 阿里云 OSS、GitHub App（BouncyCastle 解析私钥）、SMTP、FCM |
| 前端 | React 19、TypeScript、Vite、Ant Design 6（独立仓库） |

## 目录结构

```text
src/main/java/qg/qgent/     业务代码，16 个领域包
src/main/resources/         配置、建表脚本、日志配置、登录公钥
src/test/java/              单元测试，其中集成测试默认跳过
sandbox-images/dev-tools/   Agent 命令沙箱镜像（JDK 21、Node 22、Maven、Gradle、Android SDK）
Dockerfile                  后端运行镜像
docker-compose.yml          本地依赖、生产部署与可选 profile
.env.example                环境变量模板
```

| 包 | 职责 |
| --- | --- |
| `auth` / `user` | 邮箱验证码注册、RSA + JWT 登录、资料与头像 |
| `team` / `project` | 团队与邀请、项目成员与权限 |
| `agent` | 项目内 Agent：默认 Agent、自定义 Agent 的申请与群内审批 |
| `chat` | 群聊、消息、提及、已读、群内历史检索 |
| `harness` | Agent 运行、上下文装配、工具循环、子 Agent |
| `workspace` / `sandbox` | Workspace 与仓库 worktree、容器命令执行、编码工具 |
| `skill` / `memory` | 项目 Skill；项目、群、Agent 三层记忆 |
| `attachment` / `github` | 附件上传预览、GitHub App 与仓库工具 |
| `notification` / `realtime` | 站内通知与推送设备、SSE / WebSocket 事件流 |
| `common` | 统一响应与异常、存储等基础设施 |

## 配置

| 要调的东西 | 文件 |
| --- | --- |
| 连接地址、密钥路径、可选服务开关 | `.env`（从 `.env.example` 复制） |
| Agent 轮次与并发、沙箱资源与超时、附件上限 | `src/main/resources/application.yaml` |
| 服务编排、端口、卷与 profile | `docker-compose.yml` |
| 日志文件、滚动与保留 | `src/main/resources/logback-spring.xml` |
| 数据库表结构 | `src/main/resources/db/qgents_schema.sql` |
| 沙箱镜像的构建与内置工具 | `sandbox-images/dev-tools/README.md` |

登录密码由前端用 RSA 公钥加密、后端用私钥解密。仓库里只有公钥；私钥自己生成或放置，并让 `RSA_KEY_ID`、前端 `.env` 的 `VITE_RSA_KEY_ID` 与 `VITE_RSA_PUBLIC_KEY` 三者对上。

Linux 和 macOS 要把 `.env` 的 `SANDBOX_DOCKER_HOST` 改成 `unix:///var/run/docker.sock`，并让 `PROJECT_DATA_ROOT` 与 `PROJECT_DATA_DOCKER_HOST_ROOT` 指向同一个宿主机目录。Windows 用默认的 named pipe，路径写正斜杠。

数据库表只在数据卷第一次初始化时创建。之后 `qgents_schema.sql` 新增了表，要么手动补进库，要么 `docker compose down -v` 重建（会清数据）。

已有数据库升级时，执行一次 `src/main/resources/db/migrations/001_agent_run_queue_index.sql`，为待执行任务的扫描添加索引。

## 配置 GitHub App

不配也能建项目、聊天、用沙箱，只是不能导入仓库、不能推分支。配一次，团队里所有的项目都在同一份授权下工作。

### 1. 建一个 GitHub App

到 `github.com/settings/apps` → New GitHub App（装在组织下就进组织设置建）。填三项：

- Homepage URL：你的站点地址
- Setup URL（Post installation）：`https://<你的域名>/api/integrations/github/callback`
- Webhook URL：`https://<你的域名>/api/integrations/github/webhook`，填一个自己生成的随机串做 Webhook secret

Setup URL 是安装完跳回来的地址，GitHub 只认 App 设置里的这个值，代码改不了。走 nginx 时外部路径是 `/api/...`（反代会改写成 `/api/v1/...`）；本地开发直连后端就写 `http://localhost:8080/api/v1/integrations/github/callback`。

Webhook 只用来同步安装、卸载和仓库授权变化。本地没法让 GitHub 访问到你的机器时，可以先不配，App 照样能用，只是这类变化要手动点「刷新授权」。

### 2. 开权限

Repository permissions 里勾这几个：

| 权限 | 级别 | 用来做什么 |
| --- | --- | --- |
| Contents | Read and write | 克隆、拉取、推送分支 |
| Pull requests | Read and write | 读 PR 列表与详情；`github_request` 建 PR、留评论 |
| Issues | Read and write | 读 Issue；建 Issue、改标签 |
| Metadata | Read-only | 必选，GitHub 自动勾上，改不掉 |

只想让 Agent 读不想让它写，把 Pull requests 和 Issues 设成 Read-only 就行，写操作会拿到 GitHub 的 403。

### 3. 订阅事件

Subscribe to events 只勾 `Installation` 和 `Installation repositories`。别的都不需要，这个 App 不接收 push、PR、Issue 事件。

### 4. 私钥和 .env

App 页面最下面点 Generate a private key，下载的 `.pem` 放到宿主机上（生产是 `$QGENTS_DATA_ROOT/github-app/private-key.pem`），然后把 `.env` 里这几项填上：

```sh
GITHUB_APP_ID=1234567                     # App 页面最上面的 App ID
GITHUB_APP_SLUG=your-app-slug             # App 地址 /apps/ 后面那段
GITHUB_APP_PRIVATE_KEY_PATH=/srv/qgents/github-app/private-key.pem
GITHUB_WEBHOOK_SECRET=你刚填的随机串
FRONTEND_URL_WEB=https://<你的域名>        # 安装完跳回的前端地址
```

三项缺一（App ID、slug、私钥）后端就当没配 GitHub，团队页面上的连接按钮会直接报未配置。

### 5. 用起来

团队页面 → GitHub → 连接，跳到 `github.com/apps/<slug>/installations/new`；选好仓库装完自动跳回前端。之后项目管理员在项目的「仓库」页导入需要的仓库。

Agent 拿到的安装令牌只对当前项目已导入的那几个仓库有效，不是整个安装范围。

## 测试

```sh
mvn test              # 单元测试，不依赖外部服务
mvn clean package     # 编译、测试并打包
```

需要真实 MySQL、Redis、RSA 私钥和模型配置的端到端测试默认跳过，用 `QGENTS_INTEGRATION_TESTS=true` 开启，同样读 `.env`。

## 部署

```sh
docker compose --profile images build
docker compose --profile production up -d --build
```

`production` profile 起 API 和前端，Web 发布到宿主机 80 端口并反代 `/api` 与 WebSocket，API 只发布到 `127.0.0.1:8080`。

部署前准备：

- `QGENTS_DATA_ROOT`：宿主机绝对数据目录，默认 `/srv/qgents`，先建好 `projects`、`workspace-metadata`、`github-app`，允许 UID/GID `10001` 写入
- `DOCKER_GID`：`stat -c '%g' /var/run/docker.sock` 的实际组 ID
- `RSA_PRIVATE_KEY_FILE`：宿主机上已有的登录私钥
- `FRONTEND_DIRECTORY`：前端仓库目录
- `FRONTEND_RSA_PUBLIC_KEY`：前端构建期写入，改了要重建 Web 镜像

可选 profile：`proxy` 起 Mihomo，`tunnel` 起 Cloudflare Tunnel（要填 `CLOUDFLARE_TUNNEL_TOKEN`）。

## 这个项目不是什么

- 不是通用聊天机器人：只有 @ 群内 Agent 才触发运行，普通聊天不会调模型。
- 不会自动合并代码：Agent 推分支，PR 由人决定。
- 还在 MVP 阶段：不兼容旧实现，接口和表结构会随实现直接改。

## License

[MIT](LICENSE)
