# QuotaPilot

面向模型与第三方 API 的**实时预算控制网关**：为每个用户/团队/模型/任务设置额度，在请求进行中控制成本，并完成失败后的结算与对账。

核心思路：并发下的「**预留 — 结算 — 释放**」账本。请求前用 Redis Lua 原子锁住预算，完成后按实际用量结算，失败/取消/超时则释放，事后与供应商用量对账修正。

> 项目规范见 [AGENTS.md](AGENTS.md)（模块化规格 + 协作规范，P0 原则 / 模块地图 / 边界决策表 / 里程碑验收）。

## 架构

```
调用方 → M6 网关编排 → M3 预留引擎(Redis Lua 原子门) → M10 供应商 SPI(MockSupplier)
                │                                      │
                ▼                                      ▼
        M4 结算/释放(成功/失败/超时三分支)         M8 用量事件(幂等)
                │                                      │
                ▼                                      ▼
        M0 账本内核(PostgreSQL 权威流水) ◄──── M5 对账修正(差额 ADJUST)
                │
                ▼
        M9 面板告警        M11 Outbox/幂等/sweeper/分布式锁(横切)
```

- **领域层纯 Java**（`io.quotapilot.<module>.domain`，零 Spring 依赖），基础设施以端口接口注入。
- 单应用 + 模块化包结构交付（规范 §11）；热路径 M6/M3/M4 统一 Servlet MVC。
- 金额单位全项目统一：`long` 最小货币单位 = **千分之一分（1e-5 元）**，禁止 double。
  例：0.01 元 / 1K token = 1 minorUnit / token。

## 模块地图（V1 已交付）

| 模块 | 职责 | 关键实现 |
|---|---|---|
| M0 | 账本内核 | Account / LedgerEntry(不可变流水) / Reservation / SettlementRecord / ExposureRecord；hold/settle/release/adjust 全事务幂等 |
| M1 | 额度配置 | `task > user+model > team+model > user > team > model > 默认` 七级解析；团队分摊；BLOCKED 硬止损；变更审计 |
| M2 | 价格版本 | 快照解析固化到 Reservation（P5）；发布自动关窗；新旧版本并存可查 |
| M3 | 预留引擎 | Redis Lua「检查+扣减」原子门 + TTL；DB 失败立即补偿释放（Q1）；DB 权威 / Redis 加速 |
| M4 | 结算与释放 | 成功结算退余量 / 超预留 P0 告警 / 失败释放转敞口 / 超时 sweeper 转敞口；全程幂等 |
| M5 | 对账修正 | 供应商账单按 supplierRequestId 对齐 → 差额 ADJUST（幂等键防重）→ 敞口收敛 |
| M6 | 网关编排 | 预留→调用→结算编排；失败语义按「是否已触达供应商」区分（诚实计量 P2） |
| M7 | 限流器 | 三维度：请求数(每秒滑动窗口)/Token/计费单位(每分钟令牌桶)，Redis Lua 原子；Redis 丢失按 DB 重建不永久失真；结算后按实际用量返还/补扣 |
| M8 | 计量事件 | `(requestId, source, seq)` 唯一约束幂等入库；分页明细 |
| M9 | 面板告警 | limit/settled/held/exposure/available 四值视图（DB 权威重算）+ 内置告警 + 抑制窗口 + Webhook + 周期评估调度 + **Web 面板**（`http://localhost:8080/` 单页，自动刷新） |
| M10 | 供应商 SPI | 统一 SPI（流式 StreamedResponse）+ 注册表；MockSupplier 故障注入 + 供应商侧账本；**OpenAICompatibleAdapter（V1.1）：真实 HTTP chat/completions 流式 + SSE usage 解析** |
| M11 | 基础设施 | 幂等表、Outbox（与业务同事务 + FAILED 滞留重驱动）、sweeper（过期预留→敞口→封顶关闭、Redis 对账重建）、traceId、分布式锁 |
| M5 自动化 | 对账调度 | 周期自动对账（可配，默认 1h）+ 手工触发；差额 ADJUST 幂等 |

## 快速开始

```bash
# 依赖：Java 17+、Maven 3.9+、Redis（默认 localhost:6379）
mvn test          # 全量自动化验收（40 用例，含真实 Redis Lua 并发压测）
mvn spring-boot:run
# Web 面板：http://localhost:8080/
```

默认使用 H2（PostgreSQL 兼容模式）；生产切换：

```bash
# 先执行 docs/schema-postgres.sql（参考 DDL），或依赖 ddl-auto 自动建表
mvn spring-boot:run -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.jvmArguments="-DQUOTAPILOT_PG_URL=jdbc:postgresql://host:5432/quotapilot ..."
```

真实 OpenAI 联调：设置 `QUOTAPILOT_OPENAI_API_KEY` 后运行 `OpenAiRealApiIT`（无凭据自动跳过）。

## 压测与浸泡验证（规范 §7 压测脚本）

```bash
./scripts/loadtest.sh 1000 8 100   # 总请求/并发/单请求预估用量
```

已完成的浸泡验证结果（应用带全部调度器真实运行）：
- 1000/1000 请求成功、零失败；吞吐 25 req/s（Windows 本机 curl 进程开销主导，非网关瓶颈）
- **账目精确收敛**：settled = 100,000 minor = 1000 × 100 × 1（分毫不差），held=0，敞口=0
- 对账 scanned=1000 全对齐、零差额；重复回调 409 幂等且金额不重复；sweep 零悬挂预留
- 应用日志零 ERROR；sweeper/outbox/告警调度周期运转无异常

## Docker 部署

```bash
cp .env.example .env   # 修改凭据
docker compose up -d   # app + PostgreSQL 16 + Redis 7（健康检查 + 自动初始化 DDL）
```

> 注意：本仓库开发环境无 Docker，镜像构建未实测；首次使用请先 `docker compose build` 验证。
> api.openai.com 已确认从本网络可达（401=正常未授权），真实联调仅需设置 API key。

## API 一览（V1）

```
POST   /v1/quotas                        # 配置额度(M1)；POST /v1/quotas/{teamId}/members 团队成员（分摊限额自动迁移）
POST   /v1/prices/versions               # 发布价格版本(M2)
POST   /v1/requests                      # 预留并发出请求(M3/M6)，reserveOnly=true 仅预留；supplier 选择适配器(mock/openai)
POST   /v1/requests/stream               # [V1.1] 流式代理(M6)：SSE 透传 + 断连检测 → 取消上游 + 敞口(Q6)
GET    /v1/requests/{id}                 # 状态 RESERVED/SETTLED/RELEASED(+Exposure 视图)
DELETE /v1/requests/{id}                 # 取消释放(M4)
POST   /v1/callbacks/{supplier}          # 供应商用量回调(M8，重复投递 → 409 DUPLICATE_EVENT)
GET    /v1/usages?accountId=             # 使用明细(M8)
GET    /v1/accounts/{id}/balance         # 实时余额(M9)
GET    /v1/dashboards?scope={accountId}  # 面板+告警(M9)
POST   /v1/admin/reconcile               # 手工对账(M5)；另有周期自动对账调度
POST   /v1/admin/sweep                   # 手工补偿(M11)
POST   /v1/admin/accounts/{id}/status    # BLOCKED 硬止损(M1)
POST   /v1/admin/rate-limits/{accountId} # [V1.1] 限流规则(M7，三维度)
GET    /v1/admin/supplier-ledger         # 供应商侧账本(M10，对账测试)
```

错误码：`402 QUOTA_EXCEEDED` / `402 ACCOUNT_BLOCKED`、`403 NO_QUOTA_CONFIGURED`、`409 REQUEST_STATE_CONFLICT`、`409 DUPLICATE_EVENT`、`429 RATE_LIMITED`(带 Retry-After)、`503 HOLD_FAILED`；所有错误结构化为 `{code, message, traceId}`。

## 自动化验收（全部通过，37 用例）

| 验收项 | 测试 |
|---|---|
| 并发 100 预留、预算仅够 60 → 恰好 60 成功不超卖、拒绝不残留 | `M3ReservationConcurrencyIT` |
| Q1 Redis 扣减成功但 DB 失败 → 补偿 + sweeper 按 DB 重建 Redis | `M3ReservationConcurrencyIT` + `M3M4FlowTest` |
| Q2 超时已计费：回调先到（结算获胜）/ 迟到回调宽限期内正常结算 | `FaultInjectionEndToEndIT` |
| Q3 重复回调安全丢弃、不重复入账 | `FaultInjectionEndToEndIT` + `M3M4FlowTest` |
| Q8 释放后仍计费 → 敞口宽限期过按 estimate 封顶关闭并告警 | `FaultInjectionEndToEndIT` |
| Q5 价格快照：v3 生效期发起、v4 生效期结算仍按 v3 | `M1M2DomainTest` |
| P3 账本可回放审计：流水重算余额与面板一致 | `FaultInjectionEndToEndIT` + `M3M4FlowTest` |
| M1 七级解析优先级 + 团队分摊（含成员变化限额迁移） | `M1M2DomainTest` + `V11OpsIT` |
| M7 同一秒 200 请求阈值 100 → 恰好 100 通过；Redis 丢失按 DB 重建；429+Retry-After | `M7RateLimitIT` |
| M10/M6 OpenAI 兼容真实 HTTP SSE：非流式结算 + 流式透传 + 元事件 | `M6OpenAIStreamIT` |
| Q6 客户端中途断连（真实 RST）：取消信号送达上游、上游照常计费、迟到回调收敛 | `M6OpenAIStreamIT` |
| M6 上游突断：协议语义判定提前中断 → 敞口 → 宽限期封顶关闭 | `M6OpenAIStreamIT` |
| API 契约全链路（含 402/404/409 语义） | `ApiContractIT` |
| 调度器冒烟（自动对账 / 告警评估）+ M9 阈值告警 | `V11OpsIT` |

## 边界情形决策表落地（规范 §6）

- **Q1** DB 权威 + Redis 加速；Outbox 同事务；`hold` 失败立即补偿 Redis；sweeper 周期对账重建。
- **Q2** 预留到期 → 敞口宽限期；迟到回调正常结算，不双重扣费（结算幂等）。
- **Q3** `requestId+source+seq` 唯一约束 + 预检查；重复返回已处理结果。
- **Q4** 余额 = limit − settled − held，全部由不可变流水回放表达。
- **Q5** `Reservation.priceVersionId` 固化快照；旧版本不删除。
- **Q6** 客户端断开 ≠ 上游停止计费：按「可能已计费」写敞口等待对账。
- **Q7** Lua 原子「检查+扣减」；压测验收。
- **Q8** Release 同事务写 ExposureRecord；M5 对账闭环补账。

## 环境偏差与已知限制

- **测试基础设施**：环境无 Docker，Testcontainers 不可用 → 集成测试采用 H2（PostgreSQL 兼容模式）+ 本机真实 Redis 3.0（Lua 脚本按 Redis 3.0 语法编写：多字段用 HMSET）。生产仍以 PostgreSQL 为权威账本（`postgres` profile）。
- **OpenAI 适配器**：真实协议已实现并以内嵌 OpenAI 兼容上游（真实 HTTP SSE）端到端验证；对真实 api.openai.com 的联调仅需配置 `quotapilot.suppliers.openai.enabled=true` + base-url/api-key。`subscribeUsage` 返回空（OpenAI 无按请求拉取接口），对账走账单导入/回调。
- **限流计数重建口径**：REQUESTS 用窗口内用量事件数、TOKENS 用用量合计、BILLING_UNITS 用账本结算额重建（DB 权威，近似窗口口径）。
- 团队分摊：`sharedAmongMembers=true` 按成员数向上取整分摊到成员独立账户；成员变化自动迁移存量成员账户限额（在途请求按快照不受影响）。
- V1 的同步取消（DELETE）按「未产生外部费用」处理；流式路径的断连已按 Q6 全语义处理（取消上游 + 敞口）。
