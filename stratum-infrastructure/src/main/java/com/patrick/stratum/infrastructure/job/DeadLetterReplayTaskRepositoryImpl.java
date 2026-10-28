package com.patrick.stratum.infrastructure.job;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import com.patrick.stratum.domain.job.DeadLetterReplayTaskRepository;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.conflict;
import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.requireWrite;
import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.translate;

/**
 * 将重放请求和固定任务在同一个已有工作单元中持久绑定。
 * <p>示例：Application经{@code tasks.register(request)}登记，不启动处理器。</p>
 */
@Repository
public class DeadLetterReplayTaskRepositoryImpl implements DeadLetterReplayTaskRepository {
    private final JdbcTemplate jdbc;
    private final JobExecutionRepository executions;

    /**
     * 注入同一数据源和任务端口，复用外层事务且限制语句等待。
     * @param dataSource 应用事务数据源
     * @param executions 任务登记技术端口
     * @apiNote 示例：由Spring装配，不新建数据库连接池。
     */
    public DeadLetterReplayTaskRepositoryImpl(DataSource dataSource, JobExecutionRepository executions) {
        this.jdbc = new JdbcTemplate(dataSource); this.jdbc.setQueryTimeout(5); this.executions = executions;
    }

    /** {@inheritDoc} */
    @Override
    public UUID register(Request request) {
        requireWrite(); Objects.requireNonNull(request, "重放任务请求不能为空");
        UUID token = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO dead_letter_replay_task(request_id,archive_id,operator_id,registration_token)
                    VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE request_id=dead_letter_replay_task.request_id
                    """, request.requestId().toString(), request.archiveId().toString(), request.operatorId().toString(), token.toString());
            var rows = jdbc.query("""
                    SELECT archive_id,operator_id,registration_token,task_id
                    FROM dead_letter_replay_task WHERE request_id=? FOR UPDATE
                    """, (rs, index) -> new Registration(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            UUID.fromString(rs.getString(3)), rs.getString(4) == null ? null : UUID.fromString(rs.getString(4))),
                    request.requestId().toString());
            if (rows.size() != 1) throw conflict("重放任务请求登记失败");
            var row = rows.getFirst();
            if (!row.archiveId().equals(request.archiveId()) || !row.operatorId().equals(request.operatorId())) {
                throw conflict("重放任务请求身份已绑定其他归档或操作者");
            }
            if (row.taskId() != null) return row.taskId();
            if (!row.token().equals(token)) throw conflict("重放任务请求登记尚未完成");
            UUID task = executions.register(request.submission());
            if (!task.equals(request.submission().executionId())) throw conflict("重放任务身份已被其他登记占用");
            int updated = jdbc.update("UPDATE dead_letter_replay_task SET task_id=? WHERE request_id=? AND registration_token=? AND task_id IS NULL",
                    task.toString(), request.requestId().toString(), token.toString());
            if (updated != 1) throw conflict("重放任务绑定已变更");
            return task;
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 值快照：当前请求绑定，不暴露任务参数或敏感内容。
     * @param archiveId 首次归档身份
     * @param operatorId 首次操作者身份
     * @param token 登记竞争令牌
     * @param taskId 可空已绑定任务身份
     */
    private record Registration(UUID archiveId, UUID operatorId, UUID token, UUID taskId) {
        /**
         * 隐藏操作者和竞争令牌，只输出安全任务身份。
         * @return 受限摘要
         */
        @Override public String toString() { return "ReplayTaskRegistration[taskId=" + taskId + "]"; }
    }
}
