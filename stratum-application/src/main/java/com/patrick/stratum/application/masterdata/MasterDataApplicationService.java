package com.patrick.stratum.application.masterdata;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import com.patrick.stratum.application.cache.CacheUpdateCoordinator;
import com.patrick.stratum.application.event.OutboxEventWriter;
import com.patrick.stratum.application.service.IdempotentApplicationService;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.helper.ValidationHelper;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataChanged;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 主数据写用例编排，统一幂等、聚合写入、Outbox及提交后缓存，不依赖基础设施实现。 */
@Component
@RequiredArgsConstructor
public class MasterDataApplicationService {
    public static final VersionedCache.Policy CACHE_POLICY = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ofSeconds(30));
    private final MasterDataRepository repository;
    private final IdempotentApplicationService idempotency;
    private final OutboxEventWriter events;
    private final CacheUpdateCoordinator cache;
    private final JsonMapper json;

    /**
     * 创建主数据及同事务事件/回执，只有提交成功后才更新缓存。
     * @param command 已适配的创建命令，主体标识来自服务器
     * @return 新建或幂等重放的结果
     * @throws RuntimeException 校验、唯一约束或事务失败
     * @apiNote 示例：通过gateway.execute(CreateMasterDataCommand.builder()...build())调用。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MasterDataResult create(CreateMasterDataCommand command) {
        Objects.requireNonNull(command, "command");
        var key = key("create", command.getKind(), command.getSubjectId(), command.getIdempotencyKey());
        return idempotency.execute(key, command, MasterDataResult.class, () -> {
            var aggregate = MasterData.create(command.getKind(), command.getCode(), command.getName(), command.getBaseUnit());
            repository.save(aggregate);
            return publish(aggregate, MasterDataChanged.Change.CREATED);
        });
    }

    /**
     * 按观察版本修改名称，同键重放不重复更新聚合或发布事件。
     * @param command 主体由服务器提供的改名命令
     * @return 更新或重放的结果快照
     * @throws StratumException 不存在、类别不匹配或版本冲突
     * @apiNote 示例：通过gateway.execute(RenameMasterDataCommand.builder()...build())调用。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MasterDataResult rename(RenameMasterDataCommand command) {
        Objects.requireNonNull(command, "command");
        var key = key("rename", command.getKind(), command.getSubjectId(), command.getIdempotencyKey());
        return idempotency.execute(key, command, MasterDataResult.class, () -> {
            var aggregate = loadForWrite(command.getId(), command.getKind(), command.getVersion());
            aggregate.rename(command.getName());
            repository.save(aggregate);
            return publish(aggregate, MasterDataChanged.Change.RENAMED);
        });
    }

    /**
     * 在当前写事务中加载聚合，并委托领域对象核对类别和观察版本。
     * @param id 聚合标识
     * @param kind 用例类别
     * @param version 期望版本
     * @return 可执行领域行为的聚合
     * @throws StratumException 目标不存在或版本已过期
     */
    private MasterData loadForWrite(String id, MasterData.Kind kind, long version) {
        var aggregate = repository.findById(ValidationHelper.requireNonBlank(id, "id"))
                .orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
        aggregate.requireKind(kind);
        aggregate.requireVersion(version);
        return aggregate;
    }

    /**
     * 为读写双方提供同一缓存键，UUID维度避免不同主体的幂等键污染业务缓存。
     * @param id 主数据UUID
     * @return 主数据详情缓存键
     * @apiNote 示例：MasterDataApplicationService.cacheKey(materialId)。
     */
    public static String cacheKey(String id) { return VersionedCache.validateKey("biz:erp:master-data:" + id); }

    /**
     * 将业务变更及缓存补偿意图写入当前事务。
     * @param aggregate 已持久化聚合
     * @param change 已完成动作
     * @return 对外结果快照
     */
    private MasterDataResult publish(MasterData aggregate, MasterDataChanged.Change change) {
        var event = new MasterDataChanged(aggregate, change);
        events.append(event, "erp." + aggregate.getKind().name().toLowerCase(Locale.ROOT) + "." + change.name().toLowerCase(Locale.ROOT));
        var result = MasterDataResult.from(aggregate);
        cache.updateAfterCommit(cacheKey(aggregate.getId()),
                new VersionedCache.Value(aggregate.getVersion(), json.writeValueAsString(result)), CACHE_POLICY);
        return result;
    }

    /**
     * 在服务器内部绑定用例、版本、类别和主体，限制主体长度防止拼接歧义。
     * @param operation 固定操作名称
     * @param kind 服务端明确的类别
     * @param subjectId 服务器提供的主体
     * @param requestKey 客户端幂等键
     * @return 完整幂等键
     */
    private IdempotencyReceipt.Key key(String operation, MasterData.Kind kind, String subjectId, String requestKey) {
        Objects.requireNonNull(kind, "kind");
        String subject = ValidationHelper.requireNonBlank(subjectId, "subjectId");
        if (!subject.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("主体标识非法");
        return new IdempotencyReceipt.Key("master:" + operation + ":v1:" + kind.name() + ":" + subject, requestKey);
    }
}
