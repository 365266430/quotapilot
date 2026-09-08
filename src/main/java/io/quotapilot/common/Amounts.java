package io.quotapilot.common;

import java.math.BigInteger;

/**
 * [M0/公共] 金额与安全运算工具。
 * 全项目金额统一使用 long 最小货币单位 = 千分之一分（1e-5 元），禁止 double（见规范 §7）。
 */
public final class Amounts {

    private Amounts() {}

    /** 精确乘法：units × unitPrice，溢出时抛出以触发估算上限拒绝。 */
    public static long exactMultiply(long units, long unitPriceMinor) {
        try {
            return Math.multiplyExact(units, unitPriceMinor);
        } catch (ArithmeticException e) {
            throw new ArithmeticOverflowException(units, unitPriceMinor);
        }
    }

    /** 精确加法。 */
    public static long exactAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new ArithmeticOverflowException(a, b);
        }
    }

    /** 金额均值（向上取整），用于估算历史均值 × 安全系数。 */
    public static long avgCeil(long total, long count) {
        if (count <= 0) {
            return 0;
        }
        return (total + count - 1) / count;
    }

    /** 乘法溢出异常（调用方应拒绝请求，不允许静默截断）。 */
    public static final class ArithmeticOverflowException extends RuntimeException {
        public ArithmeticOverflowException(long a, long b) {
            super("金额乘法溢出: " + a + " x " + b + " (BigInteger=" + BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)) + ")");
        }
    }
}
