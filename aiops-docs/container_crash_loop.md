# 容器持续重启/崩溃循环告警处理方案

## 告警名称
- **告警名**: `ContainerCrashLoop`
- **告警级别**: 严重
- **触发条件**: 容器在5分钟内重启次数超过5次

## 问题描述
当容器频繁崩溃重启时，可能导致：
- 服务间歇性不可用
- 请求大量超时或失败
- 数据一致性风险
- 上下游依赖调用链故障

## 排查步骤

### 步骤1: 获取当前时间
**工具**: `get_current_time`
**目的**: 确定告警发生的时间范围，用于后续日志查询

### 步骤2: 查询容器事件日志
**工具**: `query_logs`
**参数要求**:
- **地域**: `ap-guangzhou`
- **日志主题**: `container-events`
- **时间范围**: 最近30分钟
- **查询条件**: `CrashLoopBackOff OR OOMKilled OR Error OR ExitCode:!=0`

**查询示例**:
```
地域: ap-guangzhou
日志主题: container-events
时间范围: 2024-01-20 14:00:00 到 2024-01-20 14:30:00
查询语句: pod_name:order-service AND (reason:CrashLoopBackOff OR reason:Error)
```

### 步骤3: 查询容器启动日志
**工具**: `query_logs`
**参数要求**:
- **地域**: `ap-guangzhou`
- **日志主题**: `container-logs`
- **时间范围**: 最近30分钟
- **查询条件**: `level:ERROR OR level:FATAL OR Exception OR Initialize_Failed`

### 步骤4: 查询系统资源指标
**工具**: `query_logs`
**参数要求**:
- **地域**: `ap-guangzhou`
- **日志主题**: `system-metrics`
- **时间范围**: 最近30分钟
- **查询条件**: `memory_usage:>90% OR cpu_usage:>90% OR disk_usage:>90%`

## 常见原因分析

### 原因1: 容器内存不足（OOMKilled）
**特征**:
- 容器退出码为137（SIGKILL）
- 重启前内存使用接近容器Limit
- 容器状态显示OOMKilled
- 系统日志有out_of_memory记录

**处理方案**:
1. 增加容器内存Limit和Request
2. 检查应用是否有内存泄漏
3. 调整JVM或运行时内存参数
4. 考虑增加副本数分散负载

### 原因2: 启动依赖未就绪
**特征**:
- 容器启动后几秒内退出
- 退出码通常为1或非0
- 日志显示连接数据库/中间件失败
- 容器启动时间短，无明显资源压力

**处理方案**:
1. 添加Init Container检查依赖服务
2. 配置Readiness Probe和Liveness Probe
3. 在应用启动逻辑中添加重试机制
4. 确保依赖服务先于当前服务启动

### 原因3: 健康检查探针配置不当
**特征**:
- 容器被探针杀死（退出码137或143）
- 容器实际运行正常但探针过于严格
- 探针超时时间设置过短
- 探针检查路径返回非200状态码

**处理方案**:
1. 检查Liveness Probe配置参数
2. 适当增加探针的超时时间和间隔
3. 确保探针检查路径不需要复杂计算
4. 分离Readiness和Liveness探针语义

### 原因4: 配置挂载或存储卷异常
**特征**:
- 容器启动时提示配置文件找不到或格式错误
- 挂载卷权限不足
- ConfigMap或Secret更新后格式不兼容
- PV/PVC绑定失败

**处理方案**:
1. 检查ConfigMap和Secret配置是否正确
2. 验证挂载卷的路径和权限
3. 确保PVC已绑定到可用PV
4. 回滚最近的配置变更

### 原因5: 镜像拉取失败或镜像问题
**特征**:
- 退出码为0或125
- 容器状态显示ImagePullBackOff
- 镜像标签不存在或仓库认证失败
- 新推送的镜像存在bug

**处理方案**:
1. 检查镜像仓库认证凭据
2. 确认镜像tag是否存在
3. 回滚到上一个已知正常版本
4. 清理本地镜像缓存后重试

## 紧急处理措施

### 立即操作（5分钟内）
1. **回滚**: 回滚到上一个稳定版本的镜像
2. **扩缩容**: 临时增加其他正常副本数量
3. **摘除**: 从负载均衡摘除异常实例

### 短期措施（30分钟内）
1. 查看容器日志定位崩溃原因
2. 检查资源配额和探针配置
3. 修复配置错误或回滚配置变更
4. 确认依赖服务状态正常

### 长期优化
1. 完善启动健康检查机制
2. 配置Pod Disruption Budget
3. 实施优雅关闭（Graceful Shutdown）
4. 增加容器启动失败监控告警

## 验证步骤
1. 确认容器状态为Running且稳定运行
2. 检查重启次数不再增长
3. 验证业务接口正常返回
4. 确认日志无新的FATAL或ERROR
5. 观察15分钟确保容器不再重启

## 相关告警
- `OOMKilled`: 内存溢出终止
- `ServiceUnavailable`: 服务不可用
- `HighErrorRate`: 错误率过高
- `ImagePullFailed`: 镜像拉取失败

## 联系方式
- **运维团队**: ops-team@company.com
- **容器平台团队**: k8s-team@company.com
- **开发团队**: dev-team@company.com
- **紧急电话**: 400-xxx-xxxx
