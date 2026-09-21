# 熔痕谱系

“熔痕谱系”是一个本地 Spring Boot 服务，用 SQLite 保存增材设备的激光指令、扫描器位置、熔池传感器样本、机器事件、同步锦标、人工复核决策和版本化对齐结果。

## 运行

```bash
mvn -q -DskipTests package
mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5517
```

访问 <http://127.0.0.1:5517>，页面标题为“熔痕谱系”。首次启动会自动装载代码内置的固定 fixture，数据库位于 `data/fuse-lineage.db`。

## 数据口径

- 原始通道表只追加或整批导入：`scanner_point`、`laser_command`、`melt_sample`、`machine_event`、`calibration_marker`。
- 用户操作写入只追加的 `decision` 表，类型为 `CONFIRM_TROPHY`、`REVOKE_TROPHY`、`SPLIT_TRACK`、`KEEP_UNALIGNED`。
- 每次重算生成新的 `alignment_version`，再写入该版本的 `track_segment`、`aligned_sample`、`segment_residual`、`marker_residual` 和 `diagnostic`。
- 同一 `session_id` 内扫描器时钟和序号必须单调；`RESTARTED` 事件后的新会话从自己的零计数开始，算法不跨会话拟合。
- 扫描道时间窗采用左闭右开：`[start,end)`。恰好在边界的传感器样本只进入右侧窗口，不被两侧重复统计。
- 两个有效锦标按线性模型拟合传感器时钟到扫描器时钟。锦标区间内给精确对齐；向外不超过一个锦标间隔时保留近似位置并标记 `EXTRAPOLATION_RISK`；超过一个间隔只给 `OUT_OF_CALIBRATION` 风险，不伪造层、道次或坐标。
- 只剩一个有效锦标时不猜测漂移斜率，样本状态为 `UNCALIBRATED` 并带 `SINGLE_TROPHY`。
- 关激光移动仍生成路径段并用虚线显示，但熔池段统计只纳入 `ALIGNED` 且 `is_non_laser=0` 的样本。
- 每次拟合的斜率、截距、锦标区间和人工决策会写入证据字段；锦标残差按版本保留。

## 固定 fixture

代码中的固定 fixture 包含：

- 一次设备重启：`S1` 后进入 `S2`，事件计数器从 0 重新开始。
- 两个同步锦标：`T1` 位于第一层，`T2` 位于第二层；初始均已确认。
- 一段关激光移动：第二层 `2600` 到 `3600` 的扫描器时间窗。
- 精确命中激光/关激光边界的样本，用于验证左闭右开。
- 一个超过一个锦标间隔的样本 `S1-M011`，以及一个一间隔风险带内的样本 `S1-M008`。

## 页面操作

打开页面后可：

- 查看时线、层内 SVG 路径和信号热带联动结果。
- 撤销或恢复某个同步锦标后自动重算。
- 指定扫描器时钟拆分误连续的扫描道。
- 指定传感器时钟的半开区间，将信号保留为 `MANUAL_UNALIGNED`。
- 一键重装 fixture、清空数据库或导出 JSON 运行记录。

## API 与重放

- `GET /api/state`：读取原始数据、决策和最新版本。
- `POST /api/fixture/load`：清空后重新装载固定 fixture。
- `POST /api/reset`：清空所有表。
- `POST /api/decisions`：追加一条复核决策并重算。
- `POST /api/recompute`：按当前决策序列重放并重算。
- `GET /api/export`：导出含 `raw` 和最新状态的 JSON 运行记录。
- `POST /api/import`：请求体形如 `{"raw": {...}}`，仅重放原始通道和追加式决策。

命令行复核示例：

```bash
curl -s http://127.0.0.1:5517/api/export -o run.json
curl -s -X POST http://127.0.0.1:5517/api/reset
jq '{raw: .raw}' run.json > import.json
curl -s -X POST http://127.0.0.1:5517/api/import \
  -H 'Content-Type: application/json' \
  --data @import.json
```

导入会重新执行确定性算法，因此旧决策可重放，但不会更新或删除任何已经导出的原始采样。自动化测试覆盖锦标撤销、一间隔外推风险、边界不重复、重启隔离、道次拆分、清空后重导和页面标题。
