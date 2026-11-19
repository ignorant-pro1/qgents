# Agent 开发沙箱镜像

此目录保留在仓库根目录的 `sandbox-images/` 下，作为独立 Docker 构建上下文。后端应用源码位于 `src/`；该镜像提供 Agent 执行用户命令的环境，由后端按需创建容器，不参与 Maven 模块构建。

内置 Git、JDK、Maven、Node、Python 和 ripgrep，完整工作区挂载到 `/workspace`。
Agent 通过参数数组命令选择仓库、创建分支和提交；GitHub 认证由 Agent 可选工具提供临时环境。
镜像不内置 token，也不依赖共享 Git Store 或宿主机 `.git` 标记。

```powershell
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
```

命令在仓库根目录执行。生产 Compose 的 `images` profile 使用相同目录和镜像标签。
