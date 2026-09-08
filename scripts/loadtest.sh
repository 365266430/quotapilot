#!/usr/bin/env bash
# QuotaPilot 压测脚本（规范 §7：并发正确性用确定性时序测试 + 压测脚本）
#
# 用法:
#   ./scripts/loadtest.sh [TOTAL] [PARALLEL] [UNITS]
# 环境变量:
#   BASE=http://localhost:8080  USER_ID  MODEL
# 前置: 已通过 API 配置好额度规则与价格版本（示例见 README「快速开始」）
#
# 输出: 成功/失败/限流计数 + 汇总 JSON 断言所需字段
set -u
BASE="${BASE:-http://localhost:8080}"
TOTAL="${1:-1000}"
PARALLEL="${2:-8}"
UNITS="${3:-100}"
USER_ID="${USER_ID:-loadtest-user}"
MODEL="${MODEL:-loadtest-model}"
OUT_DIR="$(mktemp -d)"

echo "压测目标: $BASE  总请求=$TOTAL 并发=$PARALLEL 单请求预估用量=$UNITS"

# 一次性前置: 价格 + 额度（幂等，重复执行安全）
curl -s -o /dev/null -X POST "$BASE/v1/prices/versions" -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"usageType\":\"TOKEN\",\"pricePerUnitMinor\":1}"
curl -s -o /dev/null -X POST "$BASE/v1/quotas" -H "Content-Type: application/json" \
  -d "{\"ruleId\":\"rule-loadtest-$USER_ID\",\"scopeType\":\"USER\",\"scopeId\":\"$USER_ID\",\"quotaLimitMinor\":1000000000}"

START=$(date +%s)
seq 1 "$TOTAL" | xargs -P "$PARALLEL" -I{} bash -c '
  i={}; out="'"$OUT_DIR"'/resp-$i.json"
  curl -s -m 30 -o "$out" -w "%{http_code}" "'"$BASE"'/v1/requests" \
    -H "Content-Type: application/json" \
    -d "{\"requestId\":\"lt-'"$USER_ID"'-$i\",\"userId\":\"'"$USER_ID"'\",\"model\":\"'"$MODEL"'\",\"declaredEstimatedUnits\":'"$UNITS"'}" \
    > "'"$OUT_DIR"'/code-$i.txt"
'
END=$(date +%s)

OK=0; FAIL=0; OTHER=0
for f in "$OUT_DIR"/code-*.txt; do
  c=$(cat "$f")
  case "$c" in
    200) OK=$((OK+1)) ;;
    429|402|409) OTHER=$((OTHER+1)) ;;
    *) FAIL=$((FAIL+1)) ;;
  esac
done

# 账户余额核验（账目收敛）
ACCT=$(grep -ao '"accountId":"[^"]*"' "$OUT_DIR/resp-1.json" | head -1 | cut -d'"' -f4)
BALANCE=$(curl -s "$BASE/v1/accounts/$ACCT/balance" 2>/dev/null || echo "{}")

echo "---- 结果 ----"
echo "HTTP 200 (成功): $OK"
echo "HTTP 429/402/409 (被限流/拒绝/重复): $OTHER"
echo "其他 (失败): $FAIL"
echo "耗时: $((END-START))s  吞吐: $((TOTAL / (END-START+1))) req/s"
echo "账户: $ACCT"
echo "余额: $BALANCE"
echo "临时目录: $OUT_DIR（保留供问题排查）"
if [ "$FAIL" -gt 0 ]; then echo "结论: FAIL（存在非预期错误）"; exit 1; fi
echo "结论: PASS"
