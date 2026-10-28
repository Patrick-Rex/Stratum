package com.patrick.stratum.query.job;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.job.proxy.DeadLetterReplayEvidenceViewProxy;
import lombok.Getter;
import lombok.Setter;

/**
 * 单任务SQL只读投影，不映射消息正文、操作人、请求键或领取令牌。
 * <p>示例：查询处理器使用固定参数化SQL读取本投影。</p>
 */
@Getter
@Setter
@EntityProxy
@Table("dead_letter_replay_task")
public class DeadLetterReplayEvidenceView implements ProxyEntityAvailable<DeadLetterReplayEvidenceView, DeadLetterReplayEvidenceViewProxy> {
    @Column(primaryKey = true)
    private String taskId;
    private String state;
    private Long attemptNo;
    private String traceId;
    private String replayOutcome;
    private String deliveryState;
    private Integer targetMatches;
    private Integer consumptionProof;
}
