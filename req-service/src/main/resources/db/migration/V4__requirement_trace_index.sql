-- AC-20 追溯矩阵分页：租户过滤 + id 逆序分页的覆盖索引。
-- 没有它时优化器会选 uk_req_code(tenant_id) + Sort，先扫完本租户全部需求再算 8 个相关子查询
-- （10 万需求实测扫 1300 万行 / 2.5s）；列序 (tenant_id, id) 正好等于「过滤 + 排序」，
-- 逆序扫描可以扫满一页即停。索引名在 TraceabilityQuery.PAGE_FROM_WHERE 的 FORCE INDEX 里引用。
CREATE INDEX idx_req_trace_tenant_id ON requirement (tenant_id, id);
