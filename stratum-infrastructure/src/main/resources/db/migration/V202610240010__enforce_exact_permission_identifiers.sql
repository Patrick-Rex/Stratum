-- MariaDB字符串比较可能忽略尾随空格，正则$允许末尾换行；权限标识必须与领域完全一致。
ALTER TABLE auth_permission
    ADD CONSTRAINT ck_auth_permission_type_exact CHECK (BINARY type IN (BINARY 'API', BINARY 'FEAT')),
    ADD CONSTRAINT ck_auth_permission_code_chars CHECK (code NOT REGEXP '[^a-z0-9.:-]');

ALTER TABLE auth_role
    ADD CONSTRAINT ck_auth_role_code_chars CHECK (code NOT REGEXP '[^a-z0-9.:-]');
