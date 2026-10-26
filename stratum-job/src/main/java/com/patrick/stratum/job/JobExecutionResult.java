package com.patrick.stratum.job;

/**
 * 单次执行调用的技术结果，不替代数据库任务状态。
 * <p>示例：STILL_RUNNING表示调用预算已结束而实际工作尚未退出，不能释放其容量或宣称成功。</p>
 */
public enum JobExecutionResult {
    SKIPPED, SUCCEEDED, FAILED, TIMED_OUT, INTERRUPTED, STATE_UNCERTAIN, STILL_RUNNING, CAPACITY_REJECTED, CLOSED
}
