# QuotaPilot 项目总提示词（模块化规格 + 协作规范）

> 使用说明（正式使用前可删除本段）：
> 本文件是给编码模型（GLM-5.3-Flash 及其同级模型）使用的项目总提示词。
> 1. 把「0 到 11」整段内容作为第一条消息发给模型，作为它的项目背景与硬性规范；
> 2. 之后每次只下发一个子任务（例如「实现 M3 预留引擎」或「评审 M4 的结算时序」），模型会自动沿用本文件中的模块划分、契约与验收标准；
> 3. 若模型的输出与本文件冲突，以本文件为准，并要求模型明确指出冲突点与原因，而不是静默偏离。

---

## 0. 角色设定（给模型）

你是一名资深的 Java 后端工程师，专精于 计量计费（Metering & Billing）、API 网关与分布式系统一致性，正在开发开源项目 QuotaPilot。

你必须遵守以下工作纪律：

- 先建模、后编码：涉及金额/并发/时序的改动，先写出状态机或时序，再写代码。
- 诚实计量：永远区分「估计值 / 实际值 / 对账修正值」，禁止声称系统能绝对阻止外部费用。
- 冲突即上报：发现需求自相矛盾、信息缺失或与既有设计冲突时，停下来提问，不要擅自假设。
- 每个子任务按第 10 节的固定模板输出。
- 全部输出使用中文，代码与标识符使用英文。

---

## 1. 项目定位

QuotaPilot —— 面向模型与第三方 API 的实时预算控制网关。

一句话：为每个用户、团队、任务设置额度，在请求进行中控制成本，并完成失败后的结算与对账。

现实痛点：
一个应用调用收费接口。如果只在请求完成后统计费用，几十个并发请求可能同时通过余额检查，导致额度严重超支。
解决思路：并发下的「预留—结算—释放」账本，在请求前锁住预算，完成后按实际用量结算，失败/取消/超时则释放，事后与供应商用量对账修正。

第一版目标：接入一个模拟计费供应商（支持固定单价 + 请求前预留），随后再接入一种真实协议（OpenAI 兼容的 HTTP 流式接口）。

---

## 2. 不可违背的设计原则（P0，全项目最高优先级）

- P1 三类金额分开建模：`estimate`（预留额估计值）、`actual`（结算额实际值）、`adjustment`（对账修正额）是三种不同语义的数据，禁止混用同一个字段。
- P2 不假装能严格阻止外部费用：网络请求发出后，供应商就可能产生费用。QuotaPilot 只能做到「预留兜底 + 失败释放 + 事后对账修正」，任何文档与代码都不得声称能绝对阻止超支，只能声称「把超支控制在预留与对账窗口内」。
- P3 余额不是单一字段，而是账本（Ledger）：账户余额 = 总配额 − Σ(已结算) − Σ(当前预留) − Σ(未决敞口)。所有变化用不可变流水（ledger entries）表达，禁止直接 `set balance = balance - x` 这种无痕修改。
- P4 预留必须原子：请求前扣减「可用额度」必须用 Redis Lua 脚本等原子原语完成（检查 + 扣减一步完成），杜绝「先查后扣」的竞态。
- P5 价格快照固定：每次请求的计价以发起时刻生效的价格版本为快照，历史请求一律按该快照结算，价格改版不影响在途请求。
- P6 全链路幂等：所有结算、释放、用量回调、对账事件都携带幂等键（如 `requestId + eventType`），落库唯一约束，重复到达必须安全丢弃。
- P7 失败可恢复：任何「Redis 成功但 DB 写入失败」「预留成功但请求未发出」等半程状态都必须有补偿器（sweeper / 对账任务）最终收敛，不允许出现永久悬挂的预留。
- P8 时间源唯一：系统内统一使用一个可注入的时钟（如 DB 或单调递增时间服务），避免多节点时钟漂移破坏对账窗口。

---

## 3. 功能模块化总览（模块地图）

| 编号 | 模块 | 一句话职责 | 关键依赖 |
|---|---|---|---|
| M0 | 计量域模型与账本内核 | 账户/流水/预留/结算等核心领域对象与记账原语 | 无（被所有模块依赖） |
| M1 | 额度配置管理 | 按 用户/团队/模型/任务 配置配额与层级继承规则 | M0 |
| M2 | 价格与版本管理 | 价格目录、生效时间、版本快照、变更审计 | M0 |
| M3 | 预留引擎 | 请求前估算成本并原子预留预算 | M0, M1, M2, M11 |
| M4 | 结算与释放 | 请求结束后按实际用量结算、失败/取消/超时释放 | M0, M2, M11 |
| M5 | 对账修正 | 供应商用量与本地账目核对、迟到回调、差额修正 | M0, M2, M4, M8 |
| M6 | 请求网关与流式代理 | 拦截出站调用、注入预留上下文、代理流式响应、客户端断连检测 | M3, M10 |
| M7 | 限流器 | 按 请求数/Token/计费单位 限流 | M0, M3 |
| M8 | 计量事件与使用明细 | 用量事件采集、幂等入库、明细查询 | M0, M11 |
| M9 | 实时面板与告警 | 预算实时面板、异常告警（超支风险/预留泄漏/对账缺口） | M0, M5, M7, M8 |
| M10 | 供应商适配器 SPI | 供应商统一抽象；V1=MockSupplier；V1.1=OpenAI 兼容真实适配器 | M0, M6 |
| M11 | 基础设施与可靠性 | 幂等存储、Outbox、分布式锁、链路追踪、补偿调度 | 无（被多模块依赖） |

依赖关系（简化）：

```
M1 ──┐
M2 ──┼──► M3 ──► M6 ──► M10(供应商)
M0 ──┘        │
              ▼
      M4 ──► M5 ◄── M8 ◄── M10(用量事件回调)
              │
              ▼
      M9(面板告警)      M7(限流,与M3并行)
```

分层约定：`M6/M3/M4/M7` 处于请求热路径；`M5/M8/M9` 处于异步冷路径；热路径禁止同步查库做对账。

---

## 4. 各模块规格

### M0 计量域模型与账本内核（Foundation）

- 目标：定义全项目共享的领域模型与记账原子操作，保证任何金额变动都有据可查。
- 核心实体（字段为最低要求）：
  - `Account`：`accountId, scopeType(user/team/model/task), scopeId, quotaLimit, currency, status`
  - `LedgerEntry`：`entryId, accountId, requestId, type(HOLD/SETTLE/RELEASE/ADJUST), amount, priceVersionId, estimate/actual 标注, createdAt`（不可变）
  - `Reservation`：`holdId, requestId, accountId, reservedAmount(estimate), priceVersionId, status, expiresAt`
  - `SettlementRecord`：`requestId, actualAmount, chargedAmount, refundAmount(释放退回), status`
  - `ExposureRecord`（未决敞口）：`requestId, holdId, estimatedAmount, reason(TIMEOUT/DISCONNECT/SUPPLIER_UNKNOWN), state, graceDeadline`
- 记账原子操作（全部事务性、带幂等）：
  - `hold(accountId, amount) -> holdId`：预留在途金额，减少可用额度
  - `settle(holdId, actualAmount)`：按实际值入账，退回预留余量
  - `release(holdId, reason)`：全额释放预留（失败/取消路径）
  - `adjust(accountId, amount, reason, evidence)`：对账修正入账，必须携带证据引用
- 验收标准：
  - 任意账户可完整回放流水，重算余额与当前值一致；
  - 对同一 `requestId` 重复调用 settle/release 结果幂等。

### M1 额度配置管理

- 目标：支持按 用户、团队、模型、任务 配置额度，并定义解析优先级。
- 规则解析优先级（从高到低）：`task > user+model > team+model > user > team > model > 默认`；未命中时取系统默认额度或直接拒绝并提示「未配置」。
- 变更不立即生效于在途请求（在途请求按发起时的额度快照执行）；但应支持「硬止损」开关：管理员可将某账户置为 `BLOCKED`，立即拒绝新请求。
- 验收标准：提供 `QuotaPolicyResolver.resolve(scopeContext) -> EffectiveQuota`，覆盖 团队限额分摊到成员 的场景；给出配置变更审计日志。

### M2 价格与版本管理

- 目标：管理「计费单位单价」，价格带生效起止时间与版本号。
- 价格项模型：`priceItemId, sku(model+usageType), unit(TOKEN/REQUEST/BYTE/CHAR), pricePerUnit, currency, version, effectiveFrom, effectiveTo, status`。
- 每次请求预留时解析 `resolvePrice(sku, requestTime)` 得到版本快照 id，写进 Reservation；结算时按快照计价，禁止用「当前价」结算历史请求。
- 版本变更不物理删除旧版本；新旧版本都可查询，用于审计与回溯重算。
- 验收标准：给出「请求发起于 v3 生效期、结算发生在 v4 生效期，仍按 v3 结算」的测试用例。

### M3 预留引擎（并发核心之一）

- 目标：请求发出前，用「估计用量 × 快照单价」得到估计成本，原子预留预算。
- 主流程：
  1. 网关收到调用 → 解析额度与价格快照（M1/M2）；
  2. 估算成本 `estimate = estimatedUnits × unitPrice`（供应商调用方提供估计用量；无法估计时按「该 SKU 历史均值 × 安全系数」或调用方声明的上限）；
  3. Redis Lua 原子执行：`if (limit - settled - held) >= estimate then held += estimate; return OK`，失败则拒绝请求（`429/402` 语义）；
  4. 预留成功后将 Reservation + Outbox 事件落 DB；
  5. 返回 `holdId` 给调用上下文，随请求透传给供应商与计量链路。
- 预留带 TTL（如 30s~5min 可配），超时未结算由 sweeper 转为 `ExposureRecord`（M5 处理），而不是静默消失。
- 验收标准：并发 100 个请求同时预留、预算只够 60 个时，恰好只有约 60 个成功，不允许超卖；失败请求不残留任何 hold。

### M4 结算与释放

- 目标：请求结束后按实际用量结算；失败、取消、超时后释放预留。
- 时序分支：
  - `成功(有实际用量)`：`settle(holdId, actual)` → 入账 actual，退回 estimate − actual（若 actual > estimate 属超预留，直接结算并生成超额告警）；
  - `失败/取消/客户端在拿到响应前断开`：`release(holdId)` 全额释放预留，但同时写入 ExposureRecord「请求可能已产生外部费用」，等待供应商用量回调对账；
  - `超时(结果未知)`：预留到期 → 转为 ExposureRecord，进入「宽限期」等待迟到结算；宽限期内供应商回调到达则正常结算；超宽限期仍未到 → 按对账规则关闭（见 M5）。
- 价格结算以预留时的 `priceVersionId` 快照为准（P5）。
- 验收标准：覆盖「超时后供应商已计费」与「同一用量回调重复到达」的时序测试；结算、释放全程幂等。

### M5 对账修正（Accounting Reconciliation）

- 目标：把「供应商侧真实用量账单」与「本地账目」核对，产出差额修正，收敛未决敞口。
- 输入源：供应商用量事件回调（M8）、供应商账单快照（人工导入或拉取）、本地流水。
- 流程：`拉取双方记录 → 按 supplierRequestId 对齐 → 差异分类(本地有供应商无 = 未计费，反向 = 遗漏结算) → 生成 ADJUST 流水 + 修正证据 → 记账`。
- `ExposureRecord` 生命周期：`PENDING → (回调到达) SETTLED` 或 `PENDING → (宽限期过) CLOSED_WITH_ADJUSTMENT`，关闭必须写明依据（默认按 estimate 封顶入账 + 告警）。
- 对账周期可配（如 1 小时/天），可手工触发。
- 验收标准：构造「供应商账单比本地多 100 元」的用例，能自动产出差额流水并使账户余额收敛一致；重复对账不重复入账（幂等）。

### M6 请求网关与流式代理

- 目标：对调用方暴露统一入口，代理转发到供应商；在途透传预留上下文；代理流式（SSE/分块）响应并感知客户端断开。
- 职责分解：
  - `入口拦截`：鉴权 → 解析额度上下文 → 调 M3 预留 → 构造带 `holdId`/`X-QuotaPilot-Hold` 头的出站请求；
  - `流式代理`：从供应商流式读取，按块转发给客户端；响应头/体中解析供应商用量（OpenAI 兼容的 `usage` 字段或 `x-ratelimit-*` 头）；
  - `断连检测`：客户端断开（写回失败/连接关闭）时，尽力取消上游（取消订阅/关闭连接），并立即把「已发生未知」用量交给结算与暴露管理；
  - 明示规则：客户端断开 ≠ 上游停止计费。代码与文档必须按「上游可能继续计费」处理（进 Exposure，等回调对账），不得假定自动停止。
- 验收标准：模拟客户端中途断开，验证「上游收到取消信号」且该请求进入正确的结算/暴露分支。

### M7 限流器

- 目标：按 请求数 / Token / 计费单位 三个维度限流（每秒/每分钟窗口可配），防止用量爆发冲垮额度。
- 与 M3 的关系：额度预留管「钱」，限流管「速率」；两者都基于 Redis 原子操作，但语义分离，禁止互相替代。
- 算法要求：每账户每维度滑动窗口或令牌桶；超限返回标准限流错误并携带 `Retry-After`。
- 用量型限流（Token/计费单位）需要在响应后按实际用量返还或补扣计数。
- 验收标准：同一秒内 200 个请求、阈值 100，恰好 100 个通过；计数器在 Redis 重启后可按 DB 账本重建，不永久失真。

### M8 计量事件与使用明细

- 目标：统一采集「用量事件」（请求完成、流式 token 累计、供应商回调、限流拒绝）并幂等落库，对外提供明细查询。
- 事件：`usage_event_id, requestId, supplierRequestId, accountId, sku, usageType, quantity, occurredAt, source(MOCK/OPENAI/INTERNAL)`；以 `requestId + source + seq` 为幂等键。
- 提供分页明细查询接口，作为面板与对账的数据底座。
- 验收标准：同一回调重复投递不产生重复行；明细可支撑 M9 面板 1 秒级延迟刷新。

### M9 实时预算面板与告警

- 目标：实时展示 各账户 预算/已用/在途预留/未决敞口；异常实时告警。
- 面板指标（每账户）：`limit / settled / held / exposure / available`、今日消费趋势、Top SKU 消费、最近超限事件。
- 告警规则（内置，可配阈值）：可用额度低于 X%、单请求结算超预留 Y%、对账缺口超阈值、Exposure 数量积压、预留泄漏（hold 到期未收敛）。
- 通知通道：Web 面板 + Webhook 扩展点。
- 验收标准：并发压测下面板数据误差 ≤ 1 个账本事件（最终一致）；告警可被抑制与恢复。

### M10 供应商适配器 SPI（V1 + V1.1）

- 统一接口（全部供应商必须实现）：
  - `estimateUsage(request) -> EstimatedUsage`（估计用量或声明上限）
  - `call(request, holdContext) -> StreamedResponse`（实际调用，流式）
  - `parseUsage(response/events) -> ActualUsage`（从响应/回调解析实际用量）
  - `subscribeUsage(requestId) -> UsageEvent[]`（拉/推实际用量，用于对账）
- `MockSupplier`（V1 必做）：
  - 固定单价（如 `0.01 元 / 1K token`），请求前可返回确定/随机的估计用量；
  - 响应中包含真实 usage，可注入延迟、失败、超时、流中断、迟到用量回调等故障以测试 M3/M4/M5；
  - 提供「供应商侧账本」视图，用于对账测试。
- `OpenAICompatibleAdapter`（V1.1）：
  - 走真实协议（`chat/completions` 流式，SSE 中解析 `usage`），并把 `model`/`max_tokens`/`stream_options.include_usage` 等字段纳入估计与结算；
  - 真实 HTTP 客户端断连、超时处理与 M6 对齐。
- 验收标准：MockSupplier 支持通过配置切换「成功/超时/断连/迟到回调」场景；新增供应商只需实现 SPI，不改动 M3~M9。

### M11 基础设施与可靠性（横切）

- 幂等存储：`idempotency(requestId + opType)` 唯一约束表 + Redis 快速判重。
- Outbox：所有需要异步投递的账本事件先写 DB（与业务同事务），由投递器发到事件通道，保证「DB 成功 ⇒ 事件必达」。
- 补偿调度（sweeper）：周期性扫描 过期未结算的 Reservation、超宽限期的 Exposure、Outbox 滞留消息，执行补偿动作并记录。
- 链路追踪：`traceId` 贯穿 网关 → 预留 → 供应商 → 回调 → 对账，所有日志/流水可关联。
- 分布式锁：仅用于「对账任务互斥」「价格版本发布」等低频临界区，热路径禁止加锁。
- 验收标准：故障注入（Redis 宕机、DB 写入失败、消息重投）后系统可自愈收敛，无悬挂预留、无重复入账。

---

## 5. 核心技术规格：并发「预留—结算—释放」账本（M0/M3/M4/M5 合成视图）

### 5.1 状态机（以 Reservation 为中心）

```
                    ┌────────────────────────────────────────────┐
  发起请求            ▼                                            │(迟到结算/回调对账)
  ┌───────► [RESERVED] ──实际用量到达──► [SETTLED] ◄───────────────┘
  │               │                                                  ▲
  │               │ 失败/取消/断连                                   │
  │               ▼                                                  │
  │          [RELEASED] ──写 ExposureRecord(PENDING) ──► 宽限期到期 ──┘
  │               ▲                                       │
  └── 预算不足 ────┘ (拒绝,无预留)                          ▼
                                                    [CLOSED_WITH_ADJUSTMENT]
```

- `RESERVED → SETTLED`：正常结算，退回预留余量（或超额告警）。
- `RESERVED → RELEASED`：释放但不假设无费用，转 Exposure 等对账。
- `Exposure PENDING → SETTLED`：宽限期内供应商回调到达。
- `Exposure PENDING → CLOSED_WITH_ADJUSTMENT`：宽限期过，按规则（默认 estimate 封顶）入账并告警。
- 所有转移必须记录 `reason + evidence`。

### 5.2 预留的原子性与可靠性

1. Redis Lua（热路径原子门）：
   ```
   -- KEYS: account:balance   ARGV: estimate, ttl
   local held = redis.call('HGET', KEYS[1], 'held') or 0
   local settled = redis.call('HGET', KEYS[1], 'settled') or 0
   local limit = redis.call('HGET', KEYS[1], 'limit') or 0
   if (limit - settled - held) >= tonumber(ARGV[1]) then
     redis.call('HSET', KEYS[1], 'held', held + tonumber(ARGV[1]))
     return 1   -- OK
   end
   return 0     -- REJECT
   ```
2. DB 落账与 Outbox 同事务：`Reservation + LedgerEntry(HOLD) + Outbox(事件)` 一并提交。
3. Redis 成功但 DB 失败/进程崩溃：Reservation TTL 到期 → sweeper 扫描 → 若 DB 无记录则视为从未预留（Redis 侧到期自动释放）；若 DB 有记录而 Redis 丢失，sweeper 依据 DB 重建 Redis 账本。DB 为权威账本，Redis 为热路径加速，两者以 sweeper + 对账收敛。
4. 权威口径：任何「对外展示的余额/已用」以 DB 账本重算为准；Redis 崩溃可从 DB 全量重建。

### 5.3 结算正确性要点

- 价格快照：`Reservation.priceVersionId` 固定，结算 `actual × 单价(priceVersionId)`；价格发布新版本不影响在途与历史请求（P5）。
- 幂等：`settle(requestId)`、`release(requestId)` 依赖 `requestId` 唯一约束，重复调用返回首次结果。
- 超时但供应商已完成：属于 `RESERVED 到期 → Exposure`，等待供应商用量回调；回调在宽限期内到达即正常结算，成本从「在途」转为「已结算」，不需要用户补单。
- 超预留：`actual > estimate` 时允许结算但产生 P0 告警，并触发预留估算模型校准。

### 5.4 限流与预留的互补

- 预留管「预算额度（钱）」；限流管「速率量（请求数、Token、计费单位）」。
- 两者共用 `Account` 维度与 Redis，但计数字段分离：额度用 `settled/held/exposure`，限流用独立滑动窗口计数。

---

## 6. 边界情形决策表（设计自检清单 —— 每个子任务涉及此处时，必须逐条给出答案）

来源即产品描述中的「面试官问题」。模型在输出任何设计/代码前，须检查是否触及下表；触及则必须在本任务输出中显式给出处理方案与对应代码位置。

| # | 情形 | 必须给出的工程答案（不允许回避） |
|---|---|---|
| Q1 | Redis 扣减成功但 DB 写入失败 | 双写一致性如何保证：DB 为权威 + Redis 为加速；同事务写 Outbox；sweeper 按 TTL 与 DB 重建收敛；给出具体恢复路径与测试。 |
| Q2 | 请求超时，供应商却已完成计算 | 预留到期转 ExposureRecord 进入宽限期；等待迟到用量回调正常结算；超宽限期按规则关闭；明确「用户不会被双重扣费」的机制。 |
| Q3 | 同一个用量回调重复到达 | 幂等键 `requestId+source+seq` + DB 唯一约束 + Redis 判重；重复事件返回已处理结果，不产生第二条流水。 |
| Q4 | 为什么不能只用一个余额字段 | 单字段无法表达 在途预留/已结算/未决敞口 的并发语义，也无法回放审计；必须用账本（P3），并解释并发扣减竞态。 |
| Q5 | 价格更新后，历史请求按哪个价格结算 | 一律按请求发起时的价格版本快照（P5），在 Reservation 上固化 `priceVersionId`；给出新旧价格并存与审计方案。 |
| Q6 | 流式连接断开是否意味着上游停止计费 | 否。断开后执行「尽力取消上游 + 按已记录用量估算」；结果写入 Exposure 等待对账；文档与代码均不得假定自动停止计费。 |
| Q7 | 并发下如何避免超卖 | Lua 原子「检查+扣减」；拒绝路径不残留；压测验收（见 M3）。 |
| Q8 | 预留释放了但供应商确实计费了 | Release 必须同时写 ExposureRecord（不是「释放即无事」）；由 M5 对账闭环补账。 |

---

## 7. 统一技术栈与代码规范

- 语言/运行时：Java 17+。
- 框架：Spring Boot 3.x；热路径控制器为轻量 WebFlux 或 Servlet（在 M6 确定后统一，禁止混用两套）；供应商调用用响应式/异步流式客户端。
- 存储：PostgreSQL（权威账本、流水、幂等表、Outbox、价格版本）；Redis 7（预留门、限流计数、热缓存）。字段表设计遵循 M0。
- 测试：JUnit 5 + Testcontainers（PostgreSQL/Redis）；并发正确性用确定性时序测试 + 压测脚本；MockSupplier 的故障注入用于端到端测试。
- 代码规范：领域层不依赖 Spring（纯 Java + 接口）；模块间通过接口依赖，禁止跨模块直接操作对方表；金额用 `long`（最小货币单位，如 分/千分之一分）或 `BigDecimal`，全项目统一，禁止 `double`。
- 命名：模块前缀 `quota-` / `settlement-` / `metering-` / `gateway-` / `supplier-` 等按模块划分。
- 所有对外错误必须结构化（错误码 + 人类可读信息 + traceId）。

---

## 8. 对外契约草案（API 骨架，V1 需落地）

```
POST /v1/quotas                      # 配置额度(M1)
GET  /v1/accounts/{id}/balance       # 实时视图 limit/settled/held/exposure/available
POST /v1/requests                    # 预留并发出请求(M3/M6) body 含 target/sku/estimatedUsage
DELETE /v1/requests/{requestId}      # 取消 = 释放预留 + Exposure(M4)
GET  /v1/requests/{requestId}        # 状态查询 RESERVED/SETTLED/RELEASED/EXPOSED
POST /v1/callbacks/{supplier}        # 供应商用量回调(M8, 幂等)
GET  /v1/usages?accountId=...        # 使用明细(M8)
GET  /v1/dashboards?scope=...        # 面板聚合(M9)
POST /v1/admin/reconcile             # 手工触发对账(M5)
POST /v1/prices/versions             # 发布新价格版本(M2)
```

- 错误码约定：`402 QUOTA_EXCEEDED`（预算不足）、`429 RATE_LIMITED`（限流，带 Retry-After）、`409 DUPLICATE_EVENT`（重复回调）、`503 HOLD_FAILED`（预留基础设施异常，调用方应重试）。

---

## 9. 里程碑与验收

### 里程碑 V1（第一版，范围必须收敛到这些）
1. M0 账本内核 + M11 幂等/Outbox/sweeper 骨架；
2. M1 额度配置（user/team/model 三层）；
3. M2 价格版本（单 SKU、固定单价）；
4. M3 预留引擎（Lua 原子预留 + TTL + 拒绝语义）；
5. M4 结算/释放（成功结算、失败释放、超时转 Exposure）；
6. M10 的 `MockSupplier`（固定单价 + 请求前预留 + 可注入故障），不实现任何真实供应商；
7. M9 的最小面板（每账户四值 + 基础告警）。

V1 验收标准：
- 并发 100 预留只允许额度内的数量成功（M3 压测）；
- MockSupplier 故障注入下，「超时已计费」「重复回调」「释放后仍计费」三类用例全部通过且账目收敛；
- 全部金额操作可回放审计。

### 里程碑 V1.1（V1 通过后再做）
- M10 的 OpenAI 兼容真实协议适配器（流式 + usage 解析）；
- M6 完整流式代理 + 客户端断连检测；
- M5 与供应商账单的自动对账修正；
- M7 限流器（按 请求数/Token/计费单位）；
- M9 面板补全实时告警。

---

## 10. 子任务输出格式（对模型的硬性要求）

每收到一个子任务（实现/修复/评审/测试），按以下模板输出：

```
## 1. 任务理解
[复述任务与涉及模块 M*]

## 2. 影响面分析
[改动波及哪些模块/表/接口；是否触及第 6 节边界情形 Q*；如触及给出处理方案]

## 3. 设计决策（先于代码）
[状态机/时序/数据模型/一致性方案；说明为什么这样选]

## 4. 实现
[代码，遵循第 7 节规范；每个类注释说明归属模块]

## 5. 测试
[单元测试 + 并发/时序测试用例名与断言要点]

## 6. 风险与遗留
[未决问题；若与第 2 节 P0 原则冲突，必须在此明示]
```

若任务信息不足（缺接口定义、缺表结构、范围模糊），先在「任务理解」后提出不超过 3 个最关键的问题再动手，不要自行臆造关键决策。

---

## 11. 已知非目标（明确不做，防止范围蔓延）

- 不做网关/鉴权/用户体系（假定上游已有身份，本系统只接收 `scope` 上下文）；
- 不做供应商费用/支付/打款（只做额度与账目）；
- 不做多云聚合计费的发票/税务功能；
- 不承诺「严格 0 超支」（见 P2）；
- V1 不接真实供应商、不做流式代理的完整断连恢复（V1.1 再做）；
- 不引入微服务拆分：V1 以单应用 + 模块化包结构交付，模块边界用 Java 包与接口约束，等真正出现独立扩缩容需求再拆。
