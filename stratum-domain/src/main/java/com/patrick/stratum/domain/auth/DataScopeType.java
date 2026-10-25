package com.patrick.stratum.domain.auth;

/** 值对象范围类型：组织、部门和本人保留通用契约，只有已确认的业务归属才能解释其含义。 */
public enum DataScopeType {
    ALL,
    ORG,
    ORG_AND_SUB,
    DEPT,
    DEPT_AND_SUB,
    SELF,
    CUSTOM
}
