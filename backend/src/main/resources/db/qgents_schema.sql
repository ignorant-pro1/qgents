-- Qgents 全新数据库建表脚本，MySQL 8.0.16+。
-- mysql -u <user> -p <database> < src/main/resources/db/qgents_schema.sql
SET
    NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS
    users (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
        email VARCHAR(320) NOT NULL COMMENT '归一化后的用户登录邮箱，用于大小写无关唯一校验',
        display_name VARCHAR(120) NOT NULL COMMENT '用户展示名称',
        avatar_url TEXT NULL COMMENT '用户头像URL',
        last_team_id BIGINT NULL COMMENT '用户最近选择的团队，失去权限后回退到首个可用团队',
        password_hash VARCHAR(255) NOT NULL COMMENT '密码单向哈希，禁止存储明文',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态枚举：ACTIVE/DISABLED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        UNIQUE KEY uk_users_email (email)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户账号与登录凭据';

CREATE TABLE IF NOT EXISTS
    teams (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '团队ID',
        owner_user_id BIGINT NOT NULL COMMENT '团队所有者用户ID',
        name VARCHAR(255) NOT NULL COMMENT '团队名称',
        description TEXT NULL COMMENT '团队简介',
        avatar_url TEXT NULL COMMENT '团队头像URL（OSS 公共读长期地址，可为空）',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '团队状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_team_owner (owner_user_id),
        CONSTRAINT fk_team_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队协作顶层边界';

CREATE TABLE IF NOT EXISTS
    team_members (
        team_id BIGINT NOT NULL COMMENT '团队ID',
        user_id BIGINT NOT NULL COMMENT '成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '团队角色枚举：TEAM_OWNER/TEAM_ADMIN/TEAM_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
        PRIMARY KEY (team_id, user_id),
        KEY idx_tm_user (user_id),
        CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队成员及角色关系';

CREATE TABLE IF NOT EXISTS
    team_invitations (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '团队邀请ID',
        team_id BIGINT NOT NULL COMMENT '目标团队ID',
        invited_by BIGINT NOT NULL COMMENT '邀请发起用户ID',
        email_normalized VARCHAR(320) NOT NULL COMMENT '被邀请邮箱归一化值',
        token_hash BINARY(32) NOT NULL COMMENT '邀请令牌SHA-256哈希',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '邀请状态枚举：PENDING/ACCEPTED/REVOKED；过期按 expires_at 动态判定',
        expires_at DATETIME (6) NOT NULL COMMENT '邀请过期时间（UTC）',
        accepted_at DATETIME (6) NULL COMMENT '接受邀请时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        UNIQUE KEY uk_invite_hash (token_hash),
        KEY idx_invite_team (team_id, status),
        KEY idx_invite_user (invited_by),
        CONSTRAINT fk_invite_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
        CONSTRAINT fk_invite_user FOREIGN KEY (invited_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队邮箱邀请';

CREATE TABLE IF NOT EXISTS
    projects (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '项目ID',
        team_id BIGINT NOT NULL COMMENT '所属团队ID',
        created_by BIGINT NOT NULL COMMENT '项目创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT '项目名称',
        description TEXT NULL COMMENT '项目说明',
        avatar_url TEXT NULL COMMENT '项目头像URL（OSS 公共读长期地址，可为空）',
        status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '项目状态枚举：ACTIVE/ARCHIVED',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_project_team (team_id, status),
        KEY idx_project_creator (created_by),
        CONSTRAINT fk_project_team FOREIGN KEY (team_id) REFERENCES teams (id),
        CONSTRAINT fk_project_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目隔离边界';

CREATE TABLE IF NOT EXISTS
    project_members (
        project_id BIGINT NOT NULL COMMENT '项目ID',
        user_id BIGINT NOT NULL COMMENT '项目成员用户ID',
        role VARCHAR(32) NOT NULL COMMENT '项目角色枚举：PROJECT_ADMIN/PROJECT_MEMBER',
        joined_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入项目时间（UTC）',
        PRIMARY KEY (project_id, user_id),
        KEY idx_pm_user (user_id),
        CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
        CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目成员及角色关系';

CREATE TABLE IF NOT EXISTS agents (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '项目内 Agent ID，自增且各项目互不复用',
    project_id BIGINT NOT NULL COMMENT '所属项目ID',
    name VARCHAR(120) NOT NULL COMMENT 'Agent 名称，在群成员与消息发送者中展示',
    description TEXT NULL COMMENT 'Agent 职责描述；默认 Agent 为空',
    system_prompt MEDIUMTEXT NULL COMMENT 'Agent 自有系统提示词，追加在内置提示词之后',
    avatar_url TEXT NULL COMMENT 'Agent 头像URL，可为空',
    default_agent TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否项目默认 Agent；每项目唯一',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态枚举：PENDING/ACTIVE/REJECTED；只有 ACTIVE 可邀请入群和运行',
    proposed_by BIGINT NULL COMMENT '提出申请的真实用户ID；默认 Agent 为空',
    approved_by BIGINT NULL COMMENT '审批的管理员用户ID',
    approved_at DATETIME(6) NULL COMMENT '审批完成时间（UTC）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    -- 生成列只对默认 Agent 取 project_id，其余为 NULL；唯一索引允许多个 NULL，保证每项目只有一个默认 Agent。
    default_slot BIGINT GENERATED ALWAYS AS (IF(default_agent = 1, project_id, NULL)) STORED,
    UNIQUE KEY uk_agent_default (default_slot),
    KEY idx_agent_project (project_id, status),
    CONSTRAINT fk_agent_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_agent_proposed_by FOREIGN KEY (proposed_by) REFERENCES users (id),
    CONSTRAINT fk_agent_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT chk_agent_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目内 Agent：默认 Agent 与经管理员审批的自定义 Agent';

CREATE TABLE IF NOT EXISTS
    chat_groups (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '项目群聊ID',
        project_id BIGINT NOT NULL COMMENT '所属项目ID',
        created_by BIGINT NOT NULL COMMENT '创建用户ID',
        default_group TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为项目自动创建的默认群',
        name VARCHAR(255) NOT NULL COMMENT '群聊名称',
        last_message_at DATETIME (6) NULL COMMENT '最近消息时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_chat_project (project_id),
        default_project_id BIGINT GENERATED ALWAYS AS (IF(default_group, project_id, NULL)) STORED,
        UNIQUE KEY uk_chat_default_project (default_project_id),
        KEY idx_rg_creator (created_by),
        CONSTRAINT fk_rg_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_rg_creator FOREIGN KEY (created_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目需求讨论与协作上下文群';

CREATE TABLE IF NOT EXISTS group_users (
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_id),
    CONSTRAINT fk_gu_group FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_gu_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '新建群聊的用户成员';

CREATE TABLE IF NOT EXISTS group_agents (
    group_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    PRIMARY KEY (group_id, agent_id),
    CONSTRAINT fk_ga_group FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_ga_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群聊的 Agent 成员；邀请即加入';

CREATE TABLE IF NOT EXISTS
    messages (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
        group_id BIGINT NOT NULL COMMENT '所属项目群聊ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '群内单调递增消息序号',
        sender_type VARCHAR(16) NOT NULL COMMENT '发送方 USER/AGENT/SYSTEM',
        author_user_id BIGINT NULL COMMENT '用户作者ID；Agent/系统消息时为空',
        sender_agent_id BIGINT NULL COMMENT '发送 Agent ID；AGENT 消息必填，其余为空',
        client_message_id VARCHAR(128) NULL COMMENT '客户端生成的消息幂等ID',
        message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型枚举：TEXT/CODE/IMAGE/FILE/SYSTEM',
        content JSON NOT NULL COMMENT '按消息类型校验的结构化内容JSON',
        mentions JSON NULL COMMENT '提及对象JSON数组，元素含type(USER/AGENT)和id',
        reply_to_message_id BIGINT NULL COMMENT '回复或引用的原消息ID',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
        UNIQUE KEY uk_msg_seq (group_id, sequence_no),
        UNIQUE KEY uk_msg_client (group_id, client_message_id),
        KEY idx_msg_user (author_user_id),
        KEY idx_msg_agent (sender_agent_id),
        KEY idx_msg_reply (reply_to_message_id),
        CONSTRAINT fk_msg_group FOREIGN KEY (group_id) REFERENCES chat_groups (id),
        CONSTRAINT fk_msg_user FOREIGN KEY (author_user_id) REFERENCES users (id),
        CONSTRAINT fk_msg_agent FOREIGN KEY (sender_agent_id) REFERENCES agents (id),
        CONSTRAINT fk_msg_reply FOREIGN KEY (reply_to_message_id) REFERENCES messages (id),
        CONSTRAINT chk_message_sender CHECK (
            (
                message_type = 'SYSTEM'
                AND sender_type = 'SYSTEM'
                AND author_user_id IS NULL
                AND sender_agent_id IS NULL
            )
            OR (
                message_type <> 'SYSTEM'
                AND
                ((sender_type = 'USER' AND author_user_id IS NOT NULL AND sender_agent_id IS NULL)
                 OR (sender_type = 'AGENT' AND author_user_id IS NULL AND sender_agent_id IS NOT NULL))
            )
        )
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目群聊有序消息，提及在MVP内以JSON保存';

CREATE TABLE IF NOT EXISTS
    attachments (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '附件ID',
        project_id BIGINT NOT NULL COMMENT '附件所属项目ID，用于上传前权限隔离',
        uploaded_by BIGINT NOT NULL COMMENT '上传用户ID',
        object_key VARCHAR(512) NULL COMMENT '对象存储内部键（含附件自增ID），不含临时访问凭证',
        file_name VARCHAR(512) NOT NULL COMMENT '原始文件名',
        media_type VARCHAR(255) NULL COMMENT 'MIME媒体类型',
        size_bytes BIGINT UNSIGNED NULL COMMENT '文件大小，单位字节',
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '附件上传状态：PENDING/READY',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '上传记录创建时间（UTC）',
        UNIQUE KEY uk_attachment_object (object_key),
        KEY idx_attachment_user (uploaded_by),
        CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_attachment_user FOREIGN KEY (uploaded_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息文件、图片和Diff附件元数据';

CREATE TABLE IF NOT EXISTS
    skills (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'Skill ID',
        project_id BIGINT NOT NULL COMMENT '所属项目ID',
        created_by BIGINT NOT NULL COMMENT '创建用户ID',
        name VARCHAR(255) NOT NULL COMMENT 'Skill名称',
        description TEXT NOT NULL COMMENT 'Skill用途描述',
        content MEDIUMTEXT NOT NULL COMMENT '完整SKILL.md内容',
        tags JSON NULL COMMENT '标签JSON字符串数组',
        visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性枚举：PRIVATE/PROJECT_SHARED',
        status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态枚举：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/ARCHIVED',
        submitted_by BIGINT NULL COMMENT '最近提交审核用户ID',
        submitted_at DATETIME (6) NULL COMMENT '最近提交审核时间（UTC）',
        reviewer_id BIGINT NULL COMMENT '最近审核用户ID',
        rejection_reason TEXT NULL COMMENT '最近驳回原因',
        reviewed_at DATETIME (6) NULL COMMENT '最近审核时间（UTC）',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
        updated_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        KEY idx_skill_project (project_id, status, updated_at),
        KEY idx_skill_creator (created_by),
        KEY idx_skill_reviewer (reviewer_id),
        CONSTRAINT fk_skill_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_skill_creator FOREIGN KEY (created_by) REFERENCES users (id),
        CONSTRAINT fk_skill_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id),
        CONSTRAINT fk_skill_submitter FOREIGN KEY (submitted_by) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目Skill，MVP标签内嵌JSON';

-- 聊天 Agent 执行记录；关联触发消息、实际 Agent 身份与工作区，子 Agent 关联父运行。
CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL COMMENT '执行该轮运行的项目内 Agent',
    trigger_message_id BIGINT NULL,
    parent_run_id BIGINT NULL,
    workspace_id BIGINT NULL COMMENT '本轮最近使用的工作区',
    created_by BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    instruction TEXT NOT NULL,
    result MEDIUMTEXT NULL,
    rounds INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    KEY idx_agent_run_group (group_id, created_at),
    KEY idx_agent_run_queue (status, id),
    KEY idx_agent_run_parent (parent_run_id),
    KEY idx_agent_run_message (trigger_message_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent loop 根运行及 subagent 运行';

-- 工具调用明细落库：运行结束或进程重启后，聊天里的工具列表仍可回看。
CREATE TABLE IF NOT EXISTS agent_run_tools (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL COMMENT '所属 agent_runs',
    tool_call_id VARCHAR(128) NOT NULL COMMENT '模型给出的工具调用 ID',
    tool VARCHAR(64) NOT NULL COMMENT '工具名',
    round INT NOT NULL COMMENT '开始时的模型轮次',
    state VARCHAR(16) NOT NULL COMMENT 'running/ok/failed/done',
    input TEXT NULL COMMENT '模型入参，超过 2000 字截断',
    output TEXT NULL COMMENT '工具结果，超过 2000 字截断',
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uk_agent_run_tool (run_id, tool_call_id),
    CONSTRAINT fk_agent_run_tool_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 运行内的工具调用明细';

CREATE TABLE IF NOT EXISTS workspaces (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否项目默认 Workspace；每个项目唯一',
    status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    write_lease_agent_run_id BIGINT NULL COMMENT '当前工作区写入租约所属根 AgentRun',
    write_lease_token VARCHAR(64) NULL COMMENT 'Opaque write lease fencing token',
    write_lease_expires_at DATETIME(6) NULL COMMENT 'UTC write lease expiry',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- 生成列只对默认记录取 project_id，其余为 NULL；MySQL 唯一索引允许多个 NULL，从而保证每项目唯一 default。
    default_slot BIGINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN project_id ELSE NULL END) STORED,
    UNIQUE KEY uk_workspace_default (default_slot),
    KEY idx_workspace_project_id (project_id, id),
    KEY idx_workspace_write_lease (write_lease_expires_at),
    CONSTRAINT fk_workspace_project FOREIGN KEY(project_id) REFERENCES projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent project development workspace';

CREATE TABLE IF NOT EXISTS
    events (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件ID',
        project_id BIGINT NOT NULL COMMENT '所属项目ID',
        group_id BIGINT NULL COMMENT '可选关联项目群聊ID',
        sequence_no BIGINT UNSIGNED NOT NULL COMMENT '项目内单调递增事件序号，作为SSE游标',
        event_type VARCHAR(64) NOT NULL COMMENT '事件类型，如message.created/agent-run.updated',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如agentRunId',
        payload JSON NOT NULL COMMENT '脱敏事件载荷JSON，禁止包含凭证',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        UNIQUE KEY uk_event_seq (project_id, sequence_no),
        KEY idx_event_type (event_type, created_at),
        CONSTRAINT fk_event_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_event_group FOREIGN KEY (group_id) REFERENCES chat_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目级实时事件存储，至少保留24小时';

CREATE TABLE IF NOT EXISTS
    notifications (
        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '通知ID',
        recipient_user_id BIGINT NOT NULL COMMENT '接收通知的用户ID',
        project_id BIGINT NULL COMMENT '关联项目ID；系统级通知为空',
        group_id BIGINT NULL COMMENT '关联项目群聊ID；非群通知为空',
        kind VARCHAR(64) NOT NULL COMMENT '通知类型：MESSAGE_MENTION/INVITED/TEAM_JOINED/PROJECT_ADDED',
        title VARCHAR(255) NOT NULL COMMENT '一行通知标题',
        description TEXT NULL COMMENT '通知描述正文',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如messageId',
        is_read TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已读：0未读1已读',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        read_at DATETIME (6) NULL COMMENT '已读时间（UTC），未读为空',
        KEY idx_notif_user (recipient_user_id, is_read, created_at),
        KEY idx_notif_project (project_id),
        KEY idx_notif_group (group_id),
        CONSTRAINT fk_notif_user FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT fk_notif_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_notif_group FOREIGN KEY (group_id) REFERENCES chat_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户通知中心';

CREATE TABLE IF NOT EXISTS push_devices (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '推送设备ID',
    user_id BIGINT NOT NULL COMMENT '设备所属用户ID',
    installation_id VARCHAR(128) NOT NULL COMMENT '客户端安装实例稳定ID',
    platform VARCHAR(16) NOT NULL COMMENT '平台：ANDROID/IOS',
    provider VARCHAR(16) NOT NULL DEFAULT 'FCM' COMMENT '推送提供方：FCM',
    token_hash CHAR(64) NOT NULL COMMENT '设备Token的SHA-256摘要',
    token_ciphertext TEXT NOT NULL COMMENT 'AES-GCM加密后的设备Token',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否接收离线推送',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_push_device_installation (user_id, installation_id),
    UNIQUE KEY uk_push_device_token (provider, token_hash),
    KEY idx_push_device_user_active (user_id, active),
    CONSTRAINT fk_push_device_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_device_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT chk_push_device_provider CHECK (provider IN ('FCM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移动端离线推送设备注册';

CREATE TABLE IF NOT EXISTS push_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '推送投递ID',
    notification_id BIGINT NOT NULL COMMENT '对应的持久通知ID',
    device_id BIGINT NOT NULL COMMENT '目标设备ID',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际投递尝试次数',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '下次允许重试时间（UTC）',
    provider_message_id VARCHAR(255) NULL COMMENT '提供方返回的真实消息ID',
    last_error_code VARCHAR(64) NULL COMMENT '稳定错误码',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL COMMENT '提供方受理时间（UTC）',
    UNIQUE KEY uk_push_delivery_notification_device (notification_id, device_id),
    KEY idx_push_delivery_due (status, next_attempt_at),
    CONSTRAINT fk_push_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT fk_push_delivery_device FOREIGN KEY (device_id) REFERENCES push_devices(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_delivery_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复、幂等的移动端推送投递Outbox';

CREATE TABLE IF NOT EXISTS notification_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件ID',
    recipient_user_id BIGINT NOT NULL COMMENT '接收通知的用户ID',
    sequence_no BIGINT NOT NULL COMMENT '用户内单调递增事件序号（SSE 游标）',
    notification_id BIGINT NULL COMMENT '关联通知ID',
    kind VARCHAR(32) NULL COMMENT '通知类型（MENTIONED/INVITED 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    UNIQUE KEY uk_ne_recipient_seq (recipient_user_id, sequence_no),
    CONSTRAINT fk_ne_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知级 SSE 事件（用户维度游标）';

CREATE TABLE IF NOT EXISTS team_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件ID',
    team_id BIGINT NOT NULL COMMENT '所属团队ID',
    sequence_no BIGINT NOT NULL COMMENT '团队内单调递增事件序号（SSE 游标）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（project.member.added/team.member.updated/activity.created）',
    resource_id VARCHAR(64) NULL COMMENT '关联资源ID（projectId 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    UNIQUE KEY uk_te_team_seq (team_id, sequence_no),
    CONSTRAINT fk_te_team FOREIGN KEY (team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队级 SSE 事件（团队维度游标）';

CREATE TABLE IF NOT EXISTS
    group_read_state (
        user_id BIGINT NOT NULL COMMENT '用户ID',
        group_id BIGINT NOT NULL COMMENT '项目群聊ID',
        last_read_sequence_no BIGINT UNSIGNED NULL COMMENT '已读游标（群内消息序号，NULL 视为 0）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        PRIMARY KEY (user_id, group_id),
        KEY idx_grs_group (group_id),
        CONSTRAINT fk_grs_group FOREIGN KEY (group_id) REFERENCES chat_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群成员已读游标（未读数=最新sequence-游标，排除本人）';

-- 群聊记忆后台提炼的进度与调度状态：一个群一行，消息事件更新活跃状态，定时任务扫描并异步提炼。
CREATE TABLE IF NOT EXISTS
    group_memory_state (
        group_id BIGINT NOT NULL PRIMARY KEY COMMENT '项目群聊ID',
        project_id BIGINT NOT NULL COMMENT '所属项目ID',
        last_processed_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已提炼到的群内消息序号（水位，成功后推进）',
        last_message_at DATETIME(6) NULL COMMENT '最近消息时间（UTC），用于闲置时长判定',
        status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING，用于互斥并发提炼',
        claim_token DATETIME(6) NULL COMMENT '当前领取标识；只有持有它的任务能推进进度或写入结果',
        attempt_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数；新消息清零',
        next_attempt_at DATETIME(6) NULL COMMENT '下次允许重试时间（UTC）',
        correction TEXT NULL COMMENT '用户通过对话提出的待应用纠错内容',
        last_error VARCHAR(500) NULL COMMENT '最近一次失败摘要',
        discovered_version VARCHAR(64) NULL COMMENT '最近一次项目记忆发现所依据的群记忆内容版本',
        discovery_attempt_count INT NOT NULL DEFAULT 0 COMMENT '项目记忆发现连续失败次数；成功后清零',
        discovery_next_attempt_at DATETIME(6) NULL COMMENT '下次允许发现项目记忆更新的时间（UTC）',
        discovery_error VARCHAR(500) NULL COMMENT '最近一次项目记忆发现失败摘要',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        CONSTRAINT fk_gms_group FOREIGN KEY (group_id) REFERENCES chat_groups (id) ON DELETE CASCADE,
        CONSTRAINT fk_gms_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT chk_gms_status CHECK (status IN ('IDLE', 'RUNNING'))
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群聊记忆提炼进度与调度状态';

-- 已存在的库不会重建表（README 第 2 步：数据卷初始化过的库不会重跑本脚本）；从上一个版本升级时手工执行：
-- 1. 先执行本文件中新增的建表语句（CREATE TABLE IF NOT EXISTS，已存在则跳过）：
--    agents、group_agents、group_memory_state、agent_run_tools。
-- 2. 再补已有表上新增的列、索引与约束；重复执行会报 Duplicate column/key，跳过即可：
-- ALTER TABLE messages
--     ADD COLUMN sender_agent_id BIGINT NULL COMMENT '发送 Agent ID；AGENT 消息必填，其余为空' AFTER author_user_id,
--     ADD KEY idx_msg_agent (sender_agent_id),
--     DROP CHECK chk_message_sender,
--     ADD CONSTRAINT fk_msg_agent FOREIGN KEY (sender_agent_id) REFERENCES agents (id),
--     ADD CONSTRAINT chk_message_sender CHECK (
--         (message_type = 'SYSTEM' AND sender_type = 'SYSTEM'
--             AND author_user_id IS NULL AND sender_agent_id IS NULL)
--         OR (message_type <> 'SYSTEM'
--             AND ((sender_type = 'USER' AND author_user_id IS NOT NULL AND sender_agent_id IS NULL)
--                 OR (sender_type = 'AGENT' AND author_user_id IS NULL AND sender_agent_id IS NOT NULL))));
-- -- 旧 agent_runs 行没有 Agent 身份，加列前先清空（MVP 不保留执行历史）：
-- DELETE FROM agent_runs;
-- ALTER TABLE agent_runs
--     ADD COLUMN agent_id BIGINT NOT NULL COMMENT '执行该轮运行的项目内 Agent' AFTER group_id;
-- -- 群不再有单独的启用开关，Agent 以群成员的身份存在：
-- ALTER TABLE chat_groups DROP COLUMN qgent_enabled;
-- -- 项目记忆改成管理员在默认群确认后由 Agent 直接写入，旧候选表不再使用：
-- DROP TABLE IF EXISTS project_memory_proposals;
-- -- 项目 Agent 审批改为按申请 ID 进行，不再把申请绑定到 Agent 公布消息：
-- ALTER TABLE agents DROP INDEX uk_agent_announced_message, DROP COLUMN announced_message_id;
-- 已经在这个分支的早期构建上跑过的库（group_memory_state 缺列）不单独补：
-- README 第 2 步的 docker compose down -v 重建就是支持路径。

-- 全新整库初始化时上方 users 建表已含该列，此处仅服务已存在的库；已存在该列的库自动跳过。
CREATE TABLE IF NOT EXISTS
    user_group_preference (
        user_id BIGINT NOT NULL COMMENT '用户ID',
        group_id BIGINT NOT NULL COMMENT '项目群聊ID',
        pinned TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶（0/1）',
        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
        PRIMARY KEY (user_id, group_id),
        KEY idx_ugp_group (group_id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户×群置顶偏好（跨设备同步）';

-- 团队 App 连接和项目仓库导入；不绑定 Workspace。
CREATE TABLE IF NOT EXISTS github_installations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    provider_installation_id BIGINT NOT NULL,
    account_login VARCHAR(255) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    suspended TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_github_installation (provider_installation_id),
    CONSTRAINT fk_github_installation_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队 GitHub App 安装授权';

CREATE TABLE IF NOT EXISTS github_installation_states (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    state_hash BINARY(32) NOT NULL,
    client VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    UNIQUE KEY uk_github_installation_state (state_hash),
    CONSTRAINT fk_github_state_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_github_state_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性 App 安装回调凭据';

CREATE TABLE IF NOT EXISTS project_repositories (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    installation_id BIGINT NOT NULL,
    provider_repository_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    html_url VARCHAR(1024) NOT NULL,
    private_repository TINYINT(1) NOT NULL,
    UNIQUE KEY uk_project_imported_repository (project_id, provider_repository_id),
    CONSTRAINT fk_import_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_import_installation FOREIGN KEY (installation_id) REFERENCES github_installations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目显式导入的 GitHub 仓库';

CREATE TABLE IF NOT EXISTS tool_executions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '执行编号',
    owner_instance_id VARCHAR(128) NOT NULL COMMENT '实际运行该执行的应用实例编号',
    sandbox_id CHAR(36) NOT NULL COMMENT '沙箱编号',
    agent_run_id BIGINT NULL COMMENT '实际调用的 AgentRun 编号',
    workspace_id BIGINT NULL COMMENT '实际使用的 Workspace 编号',
    tool_name VARCHAR(64) NOT NULL COMMENT '服务端注册的工具名称',
    arguments_json JSON NOT NULL COMMENT '工具结构化参数',
    status VARCHAR(24) NOT NULL COMMENT '执行状态',
    exit_code INT NULL COMMENT '进程类工具退出码',
    result_json JSON NULL COMMENT '结构化工具结果',
    failure_code VARCHAR(64) NULL COMMENT '稳定失败码',
    failure_reason VARCHAR(1024) NULL COMMENT '失败原因',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    started_at DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at DATETIME(6) NULL COMMENT '结束时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_tool_execution_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED')
    ),
    KEY idx_tool_execution_sandbox (sandbox_id, owner_instance_id, created_at),
    KEY idx_tool_execution_run (agent_run_id, created_at),
    KEY idx_tool_execution_workspace (workspace_id, created_at),
    KEY idx_tool_execution_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱工具执行记录';

CREATE TABLE IF NOT EXISTS tool_execution_logs (
    execution_id BIGINT NOT NULL COMMENT '工具执行编号',
    sequence_no BIGINT NOT NULL COMMENT '执行内递增序号',
    stream VARCHAR(16) NOT NULL COMMENT '日志流类型',
    content TEXT NOT NULL COMMENT '经过长度限制的日志内容',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT chk_tool_execution_log_stream CHECK (stream IN ('SYSTEM', 'STDOUT', 'STDERR')),
    PRIMARY KEY (execution_id, sequence_no),
    CONSTRAINT fk_tool_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES tool_executions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱工具执行日志';
