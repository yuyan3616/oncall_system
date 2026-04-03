# SuperBizAgent

> 基于 Spring Boot + Spring AI 的智能问答与运维 Agent 系统

## 项目简介

SuperBizAgent 是一个企业级智能业务代理系统，基于 Spring Boot 3.2.0 和 Spring AI 框架构建，集成阿里云 DashScope 大语言模型和 Milvus 向量数据库，提供两大核心能力：

- **RAG 智能问答** - 基于向量检索和知识库的智能问答系统
- **AIOps 智能运维** - 基于 AI Agent 的自动化运维诊断系统

## 核心特性

- **RAG 问答系统** - 向量检索 + 多轮对话 + 流式输出 + 查询重写
- **AIOps 运维** - 智能告警分析 + 日志查询 + 多 Agent 协作
- **会话管理** - 上下文记忆 + 历史管理 + 自动压缩
- **工具集成** - 文档检索、告警查询、日志分析、时间工具
- **高可用保障** - 本地限流 + 模型路由 + 熔断保护
- **可观测性** - 全链路追踪 + 请求日志

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | 1.1.0 | AI 框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | 阿里云 AI 适配器 |
| Milvus | 2.6.10 | 向量数据库 |
| DashScope | - | 阿里云大模型服务 |

## 项目结构

```
OnCall-Agent/
├── src/main/java/org/example/
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java        # 时间工具
│   │   ├── InternalDocsTools.java    # 文档检索工具
│   │   ├── QueryMetricsTools.java    # 指标查询工具
│   │   └── QueryLogsTools.java       # 日志查询工具
│   ├── client/                       # 客户端
│   │   └── MilvusClientFactory.java  # Milvus 客户端工厂
│   ├── config/                       # 配置类
│   │   ├── DashScopeConfig.java      # DashScope 配置
│   │   ├── MilvusConfig.java         # Milvus 配置
│   │   ├── DocumentChunkConfig.java  # 文档分片配置
│   │   └── ...
│   ├── controller/                   # 控制器
│   │   ├── ChatController.java       # 对话控制器
│   │   ├── FileUploadController.java # 文件上传控制器
│   │   └── MilvusCheckController.java
│   ├── service/                      # 服务层
│   │   ├── ChatService.java          # 对话服务
│   │   ├── AiOpsService.java         # AIOps 服务
│   │   ├── RagService.java           # RAG 服务
│   │   ├── VectorSearchService.java   # 向量搜索服务
│   │   └── ...
│   ├── rag/                          # RAG 核心
│   │   ├── retrieval/                # 检索模块
│   │   │   ├── MultiChannelRetrievalService.java
│   │   │   └── RetrievalPostProcessor.java
│   │   └── rewrite/                 # 查询重写
│   │       └── DefaultQueryRewriteService.java
│   ├── memory/                       # 会话记忆
│   │   ├── ConversationMemoryService.java
│   │   └── DefaultMemoryCompressionService.java
│   ├── stability/                   # 高可用保障
│   │   ├── model/                   # 模型路由
│   │   ├── queue/                   # 限流队列
│   │   └── trace/                   # 链路追踪
│   └── Main.java                    # 启动类
├── src/main/resources/
│   ├── static/                      # Web 界面
│   │   ├── index.html
│   │   ├── app.js
│   │   └── styles.css
│   └── application.yml              # 应用配置
├── src/test/                         # 测试
├── aiops-docs/                       # AIOps 知识库文档
│   ├── cpu_high_usage.md
│   ├── memory_high_usage.md
│   ├── disk_high_usage.md
│   ├── slow_response.md
│   └── service_unavailable.md
├── pom.xml
├── Makefile
└── vector-database.yml              # Milvus Docker 配置
```

## 核心配置

### application.yml

```yaml
server:
  port: 9900

milvus:
  host: localhost
  port: 19530

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}

rag:
  top-k: 3
  model: "qwen3-max"
  rewrite:
    enabled: true
  search:
    channels:
      original-enabled: true
      rewritten-enabled: true
      subquestion-enabled: true

chat:
  memory:
    enabled: true
    recent-pairs: 6

ai:
  routing:
    chat:
      models:
        - qwen-plus
    aiops:
      models:
        - qwen-plus
```

### 环境变量

```bash
# 阿里云 DashScope API Key
export DASHSCOPE_API_KEY=your-api-key
```

## 快速开始

### 1. 环境准备

- Java 21+
- Maven 3.8+
- Docker & Docker Compose

### 2. 启动服务

**方法一：Makefile 一键启动**

```bash
# 一键初始化（启动 Docker → 启动服务 → 上传文档）
make init
```

**方法二：手动启动**

```bash
# 启动 Milvus 向量数据库
docker compose up -d -f vector-database.yml

# 编译并启动应用
mvn clean install
mvn spring-boot:run
```

### 3. 访问服务

- Web 界面: http://localhost:9900
- Attu (Milvus Web UI): http://localhost:8000

## API 接口

### 1. 智能问答

```bash
# 流式对话（推荐）
POST /api/chat_stream
Content-Type: application/json

{
  "id": "session-123",
  "question": "什么是向量数据库？"
}
```

```bash
# 普通对话
POST /api/chat
Content-Type: application/json

{
  "id": "session-123",
  "question": "什么是向量数据库？"
}
```

### 2. AIOps 智能运维

AIOps 分析支持两种触发方式：

**方式一：手动触发（基础能力）**

```bash
POST /api/ai_ops
Content-Type: application/json

{
  "id": "session-123",
  "question": "服务器 CPU 使用率过高怎么处理？"
}
```

典型场景：运维人员发现异常后，在系统界面输入故障描述，点击"智能诊断"触发分析。

**方式二：自动触发（扩展能力）**

1. **Webhook 自动触发（推荐）**
   
   对接 Prometheus AlertManager，实现告警自动触发分析：
   
   ```bash
   # Prometheus AlertManager 配置
   receivers:
     - name: 'aiops-webhook'
       webhook_configs:
         - url: 'http://localhost:9900/api/webhook/prometheus'
   ```
   
   查询分析结果：
   ```bash
   GET /api/webhook/status/{jobId}
   ```

2. **定时任务触发**
   
   可基于 Spring Boot `@Scheduled` 扩展周期性自动分析。

### 3. 文件管理

```bash
# 上传文档并向量化
POST /api/upload
Content-Type: multipart/form-data

curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.md"
```

### 4. 会话管理

```bash
# 清空会话历史
POST /api/chat/clear

# 获取会话信息
GET /api/chat/session/{sessionId}
```

### 5. 健康检查

```bash
# Milvus 健康检查
GET /milvus/health
```

## 可用 Makefile 命令

| 命令 | 说明 |
|------|------|
| `make init` | 一键初始化 |
| `make up` | 启动 Milvus |
| `make down` | 停止 Milvus |
| `make start` | 启动服务 |
| `make stop` | 停止服务 |
| `make upload` | 上传 AIOps 文档 |
| `make check` | 检查服务状态 |

## 许可证

MIT