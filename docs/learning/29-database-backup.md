# 第二十九阶段：MySQL备份与隔离恢复

本阶段解决“数据库数据丢失后，能否从备份恢复并让应用读出来”的问题。复用本机MySQL客户端/服务器8.0.45、JDK 21.0.10与已有Connector/J 8.4.0，没有安装组件或使用Docker。

## 新增文件的作用

| 文件 | 作用 |
|---|---|
| `scripts/database-backup.ps1` | 用户入口，定位组件、创建私有备份目录、核对ACL，再调用Java工具 |
| `scripts/DatabaseBackupTool.java` | 读取外部数据库配置，执行导出/导入，计算并比较结构、行数和数据摘要 |
| `scripts/verify-database-backup.py` | 显式运行的本阶段专项：正常恢复、拒绝覆盖/篡改/不安全目录、启动应用读取恢复库 |

没有修改Controller、Service、Mapper或前端，没有新增业务表，也没有重打JAR。Java工具是JDK源文件启动器执行的运维脚本，不参与应用启动。

## 整体流程

1. 从已忽略的`config/db.properties`读取当前连接，核对数据库只有带主键的InnoDB基础表、没有视图/触发器/存储过程/事件，也没有跨库外键。
2. 在可重复读的只读事务中，逐表读取`SHOW CREATE TABLE`、按主键排序的所有数据，得到结构SHA-256、行数和数据SHA-256。字段带长度前缀，NULL与空值分别编码。
3. 使用`mysqldump --single-transaction`导出结构和数据。SQL由客户端直接写文件，避免PowerShell重定向改变编码；不传`--databases`，不生成切换回开发库的`USE`或创建原库语句。
4. 再次计算源库摘要，前后相同才生成`manifest.properties`，记录源库、字符集/排序规则、各表摘要与SQL文件SHA-256。失败目录没有完整manifest，不作为成功备份。
5. 恢复时先检查SQL校验值，再使用不带`IF NOT EXISTS`的`CREATE DATABASE`创建全新目标。名称只接受`limited_trade_restore_`前缀，并显式拒绝源库；已有目标直接失败。
6. 将SQL导入新库，重新计算所有摘要并与清单比较。导入失败保留新库和诊断，不自动删库或覆盖重试。
7. 专项脚本让临时应用连接恢复库，验证真实HTTP登录、商品与本人订单查询，退出登录并停止本次JVM。原有开发服务继续运行。

`mysqldump`与Java摘要分别使用自己的事务，不共享同一快照。前后比较可以发现源库变化，恢复核对再确认SQL内容与清单相符；操作期间需要稳定的表结构，不能并行执行DDL。这里只支持当前项目结构，遇到工具未覆盖的数据库对象会直接拒绝。

## 如何使用

在项目根目录用PowerShell 7.4以上执行，MySQL需已运行：

```powershell
# 创建全新、自动命名的私有备份目录，控制台输出实际路径。
pwsh -NoProfile -File .\scripts\database-backup.ps1 -Action Backup

# 使用上一步输出的路径和一个从未使用过的新数据库名。
$backup = 'D:\codex\Java\limited-trade-platform\tools\mysql-backups\drill_8c5361809d65'
pwsh -NoProfile -File .\scripts\database-backup.ps1 -Action Restore -BackupDirectory $backup -TargetDatabase limited_trade_restore_manual_20261020

# 核对指定恢复库；不传TargetDatabase则核对当前开发库是否仍与备份相同。
pwsh -NoProfile -File .\scripts\database-backup.ps1 -Action Verify -BackupDirectory $backup -TargetDatabase limited_trade_restore_manual_20261020
```

`-MySqlBin`可以指定已有MySQL客户端目录；默认使用本机`C:\Program Files\MySQL\MySQL Server 8.0\bin`。所有操作复用外部配置里的数据库账号；恢复需要该账号有建库和导入权限。本工具不备份MySQL服务器账号与授权。

要重新执行完整专项，先保留已有可运行JAR与`config/local-accounts.txt`，确认8085空闲：

```powershell
$env:PYTHONUTF8 = '1'
python .\scripts\verify-database-backup.py
```

此脚本不会被普通Maven测试自动运行。每次会保留一个新备份及两个新恢复库；第二个库用于证明“篡改校验失败没有提前创建数据库”，随后也完成恢复与应用验证。不会自动清理这些数据库。

## 文件与凭据处理

备份放在Git忽略的`tools/mysql-backups/`下。每个新目录关闭ACL继承，只给当前用户和SYSTEM权限；Restore/Verify也检查目录权限，复制备份后权限不符会先拒绝执行。备份SQL含用户密码哈希等业务数据，仍属于私有文件。

MySQL连接密码只写入该私有目录下的短期`mysql-credentials-*.cnf`，通过第一个`--defaults-file`参数引用，原生进程完成或失败后在finally删除；密码不放命令行。诊断日志留在同一私有目录，控制台只输出安全的错误代码。正常失败可清理临时文件；强制终止整个JVM或断电不保证finally执行，此时目录ACL仍限制访问。

SHA-256用于发现文件变化，不是加密或可信签名。此阶段是同机逻辑全量备份与恢复，不包含异机备份、自动调度、binlog时间点恢复或完整灾备建设。

## 2026-10-20最小验证结果

最终证据：`tools/mysql-backups/drill_8c5361809d65/verification.json`和同目录manifest/SQL。恢复库：

- `limited_trade_restore_8c5361809d65`
- `limited_trade_restore_8c5361809d65_bad`：实际应用读取的恢复库，后缀仅来自校验拒绝场景，最后已正常恢复和核对。

实测通过：

- 开发库14张表的结构、全部行数和数据摘要在备份/恢复/最终源库复查时全部匹配。
- 当前数据包含3名用户、11个商品、4个分类、3个活动和3种活动券；应用公开列表返回其中10个在售商品。
- 已有目标、开发库名、被篡改SQL、不安全目录均被拒绝；篡改失败后同名目标仍能全新CREATE，证明之前未建库。
- 既有JAR在8085连接恢复库，登录、身份、商品、订单查询与退出成功。关闭定时任务、MQ消费和商品缓存，使用默认进程内Session；没有修改开发库或共享Redis数据。
- 源库订单为0，应用返回`[]`；本次没有验证非空订单的业务恢复，也没有声称做过完整交易回归。
- 临时JVM退出，8085不再监听，所有备份目录中无临时密码文件，最终目录ACL仅当前用户/SYSTEM。

开发库保持原样。早期编码问题复现过程保留了`drill_519da747dc95`、`drill_2b514b7f5285`及`drill_483b5fea2eb2`三个备份目录和相应恢复库，未自动删除，避免将保留证据误当作覆盖重试。前两次失败发生在Python读取Java本机编码输出，非SQL恢复失败；最终脚本分别明确工具UTF-8输出和JVM系统信息的本机编码。

一次独立只读复核发现“复制进来的目录权限可能过宽”，已先用空目录夹具复现拒绝缺失，再补ACL校验，随后完整本阶段专项通过。未全量回归、提交或推送。
