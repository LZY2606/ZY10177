# 熔痕谱系 (melt-lineage)

增材制造多通道信号的谱系对齐服务:把激光指令、扫描器位置、熔池传感器采样和机器事件
映射到构建层、扫描道与空间位置,并保留每一次时钟校正的依据。

技术栈: Java 17 · Spring Boot 3.5 · SQLite (xerial) · 原生 SVG 单页。

## 运行

```bash
mvn -q -DskipTests package          # 构建
mvn -q test                          # 自动化测试(11 个验收用例)
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5517
# 打开 http://127.0.0.1:5517 ,点击「导入 fixture」
```

数据库文件在 `./data/meltlineage.db`,删除即清空;页面「清空数据库」按钮等效。

## 数据口径

- **参考时钟**: 激光指令与机器事件同属控制器时钟域,定义为参考时间 `t_ref`。
- **漂移通道**: 熔池采样(自带 ADC 钟,fixture 中漂移约 1.002x + 3.0)与扫描器
  (自带钟,偏移 +0.5;`t=40` 设备重启后时钟归零,进入 epoch 1)。
- **锦标 (anchor)**: 已知同一物理时刻在两个时钟域的读数对 `(t_channel, t_ref)`。
  fixture 含两对锦标(熔池×2、扫描器×2,参考时刻 8 与 30)。每次 RESTART 事件为
  扫描器新 epoch 推导一个零点锦标,依据记为 `restart-event`。
- **对齐算法 `ridge-ls-v1`**: 每个 `(通道, epoch)` 对未撤销锦标做朝标称斜率 1.0
  岭正则化的最小二乘拟合(`segment` 表存斜率/偏移/残差 RMS/锦标依据)。
- **风险规则**: 样本落在锦标范围之外且超出距离 > 一个最大锦标间隔时,只标记
  `risk=1`,绝不给出精确对齐;单锦标时间隔未定义,任何外推均为风险。
- **单调性**: 扫描器对齐后时钟在每个 epoch 内必须单调,且拟合从不跨重启连接计数
  (epoch 分段拟合);违反时产生 `scanner-clock-non-monotonic` 诊断。
- **道次边界**: 样本按左闭右开 `[t_start, t_end)` 归入道次,边界样本只属于右侧道次,
  不会被两侧重复统计。
- **关激光移动**: `laser_on=0` 的道次在路径图中保留可见(灰虚线),但不计入熔池统计。
- **原始采样不可改写**: `sample`/`event` 表仅导入时写入;对齐结果按版本存放在
  `aligned_sample`/`segment`/`track_stat`/`diagnostic`,每次重算产生新版本,旧版本保留。

## 决策与重放

用户操作(确认/撤销锦标、新增锦标、拆分误连续道次、把一段信号保留为未对齐)全部
记录为有序 `decision` 日志。重算时先把锦标/道次表恢复到 fixture 快照,再按序重放
全部决策,然后运行对齐算法——因此新算法版本可以重放旧决定,且绝不触碰原始采样。

## 运行记录导出与复核

- `GET /api/export` 导出运行记录 JSON(决策日志 + 各对齐版本 + 段/诊断/统计)。
- `POST /api/import-record` 在清空后的数据库上重新导入:自动导入 fixture、重放记录
  中的决策并重算,供复核。流程: 导出 → 清空数据库 → 重新导入记录 → 对比状态。

## 固定 fixture

`src/main/resources/fixture/build-fixture.json`(确定性生成): 单层 7 段路径,含
一次设备重启(t=40,扫描器时钟归零)、两对同步锦标(t=8 与 t=30)、一段关激光移动
(t=32..34)。导入时由 RESTART 事件推导扫描器 epoch 1 的零点锦标。

## API 摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/import-fixture` | 清空并导入 fixture,产生首个对齐版本 |
| POST | `/api/reset` | 清空数据库 |
| POST | `/api/recompute` | 重放决策并重算(可传 `{"algo": "..."}`) |
| POST | `/api/anchors/{id}/confirm` `/revoke` | 确认/撤销锦标 |
| POST | `/api/tracks/{id}/split` | 在 `{"t": ...}` 处拆分道次 |
| POST | `/api/unaligned` | 保留未对齐段 `{"channel","tStart","tEnd"}` |
| GET  | `/api/state` `/api/timeline` | 页面数据 |
| GET  | `/api/export` · POST `/api/import-record` | 运行记录导出/复核 |

## 测试对应验收点

`AlignmentAcceptanceTest`: 撤销锦标后超出一间隔区域只给风险不伪造精确对齐、
道次边界样本左闭右开不被重复统计、重启前后计数不连接且时钟单调、关激光移动不计入
熔池统计但仍可见、非单调时钟诊断、未对齐段保留、重放不改写原始采样、导出-清空-
重放复核闭环。`WebSmokeTest`: 首页展示「熔痕谱系」与导入/状态接口。
