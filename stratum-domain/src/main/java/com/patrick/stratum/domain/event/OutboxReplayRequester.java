package com.patrick.stratum.domain.event;

/** 人工重放应用端口，只提交持久意图，不直接发布原消息。 */
public interface OutboxReplayRequester {
    /**
     * 从事务外提交人工重放，返回前已提交登记结果。
     * @param request 不含可替换业务正文的人工请求
     * @return 首次或幂等回执，不表示已投递或消费
     * @throws IllegalStateException 已处于外层事务
     * @throws com.patrick.stratum.common.error.StratumException 原事件/归档核验失败或当前状态冲突
     * @apiNote 示例：requester.request(new OutboxReplayRepository.Request(requestId, archiveId, traceId))。
     */
    OutboxReplayRepository.Receipt request(OutboxReplayRepository.Request request);
}
