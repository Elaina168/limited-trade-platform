# 第三十四阶段：规则RAG检索与评价

本阶段在既有Java只读Agent上增加真实规则检索：问题转为本地语义向量，从公开Markdown资料中取出片段，再交给DeepSeek回答。本人订单仍从原有业务工具实时读取，知识库不保存用户订单。沿用Boot3.5.16、Spring AI1.1.8和Java21。

## 加了什么及作用

新增6个生产类型，均在`src/main/java/com/elaina/limitedtrade/agent/rag/`：

| 类型 | 作用 |
|---|---|
| BgeEmbeddingModel | 实现Spring AI Embedding接口，调用本地ONNX模型，提取CLS向量并归一化 |
| RuleDocuments | 按Markdown标题与字符上限分块，保留原文行号和稳定片段ID |
| RuleChunk | 描述实际片段：ID、文档、标题、路径、行号、原文、相似度 |
| RuleKnowledgeBase | 建立或加载SimpleVectorStore，校验索引指纹，提供search_trade_rules工具 |
| RagProperties | 绑定独立开关、模型和索引路径、分块、TopK、阈值、重建配置 |
| RagConfiguration | 仅当Agent和RAG两个开关都打开时创建本地模型与知识库Bean |

新增5份公开资料：支付、主动取消、超时关闭、订单访问、发货收货，依据实际OrderService和Mapper条件编写。当前320字符上限生成17个片段，包含规则依据段落。路径均为classpath资源标识，不是向客户端暴露本机绝对路径。

修改现有AgentProperties以嵌套绑定并校验RagProperties；AgentConfiguration注入可选知识库。TradeAgentService在RAG开启时注册`get_my_order`、`list_my_orders`、`search_trade_rules`，移除旧`get_trade_rules`回调，避免模型绕过检索。关闭RAG则保持原三个工具。

响应新增`knowledgeSources`数组，由服务端从本次工具实际结果收集并按片段ID去重，字段与RuleChunk一致。旧`sources`字段保留兼容固定规则模式。该数组表示检索返回过的证据，不表示每个片段都被答案采用，也不保证答案逐句正确；正文引用仍须审阅。

## 实际调用链

`POST /api/agent/chat` → Session/CSRF和可信用户 → TradeAgentService → DeepSeek请求规则工具 → RuleKnowledgeBase.search → 查询前缀＋BGE向量 → SimpleVectorStore余弦TopK → 原始片段工具结果 → DeepSeek回答 → 后端附实际knowledgeSources。

订单工具继续通过可信ToolContext中的userId调用OrderService。知识库不接受userId，不查询数据库，不具备取消、支付、发货能力。无命中返回`NO_EVIDENCE`和空片段；本地推理或索引加载失败抛出错误，不伪装成无资料。提示词要求仅依据本次检索片段回答，资料内容不构成系统指令。

## 模型与资源

采用BAAI中文BGE small v1.5的第三方ONNX导出`Xenova/bge-small-zh-v1.5`，固定修订`75c43b069aac4d136ba6bc1122f995fedcfd2781`。模型94,851,877字节，tokenizer439,125字节，脚本核对长度和摘要，清单存本机模型目录。

`scripts/prepare-rag-model.py`是一次性准备脚本，使用已有Python标准库；运行应用不需要Python服务。Java依赖增加ONNX Runtime1.19.2、DJL tokenizer0.32.0和Spring AI vector-store模块，缓存仍在tools下。ONNX运行库jar约93.5MB，另有其他传递依赖和原生分词库，不把模型文件大小当作总磁盘占用。

Spring AI1.1.8的Transformers实现固定mean pooling，未提供本阶段所需的CLS配置，所以使用小型适配器。BGE取首个CLS token、512维、L2归一化；查询添加`为这个句子生成表示以用于检索相关文章：`，文档不添加。CPU运行，2个intra线程、1个inter线程，无GPU依赖。模型/session/tokenizer随Bean关闭释放。

## 开关、索引与复现

保留本机密钥文件`config/agent.properties`，本轮未改其中的密钥或enabled。示例在`config/agent.properties.example`。默认Agent与RAG均关闭，关闭时不要求模型文件。启用需要两个开关均为true：

```properties
app.agent.enabled=true
app.agent.rag.enabled=true
app.agent.rag.model-directory=tools/models/bge-small-zh-v1.5
app.agent.rag.index-file=tools/rag-index/rules.json
app.agent.rag.chunk-chars=320
app.agent.rag.top-k=3
app.agent.rag.similarity-threshold=0.55
app.agent.rag.rebuild-index=false
```

在项目根目录准备模型：`python scripts/prepare-rag-model.py`。下载自Hugging Face固定修订，网络失败会报错，不切换未知模型。首次启用创建索引，之后加载匹配的索引，避免重新计算文档向量。启动时仍需加载本地模型以向量化新问题。

索引指纹包含模型与分词器摘要、池化和查询前缀、资料内容与行号、分块上限。加载核对指纹、数量、片段ID、文本、向量维度。资料或模型改变时明确拒绝旧索引；设`rebuild-index=true`启动一次重建，随后改回false。生成过程先保存临时文件并核验，再原子替换，临时文件在finally清理。TopK、阈值仅影响查询，不要求重建。

SimpleVectorStore把所有向量加载到内存并做线性检索，保存为本机JSON。本阶段为小规模学习与评价基线，不是专业向量数据库，也没有多实例索引同步机制。

## 检索评价

固定数据集`src/test/resources/rag-evaluation.json`共20条：6条校准、14条留出。先固定样本，再检查模型实际结果；未为改善结果修改留出问题。

校准正例首名最低0.5913，负例最高0.4996；取中点并保留两位小数得到阈值0.55。相似度不是正确率或模型置信概率。暂定0.70会漏掉校准正例，因此没有直接沿用。

| 留出指标 | 本次结果 |
|---|---|
| 目标文档Hit@1 | 10/10 |
| 目标文档Hit@3 | 10/10 |
| 无资料返回空片段 | 4/4 |

指标按文档ID评价，不能证明首名片段覆盖问题全部信息。例如校准问题“MQ不可用还有其他关闭机制吗”首名为timeout文档的库存回补段，真正扫描机制在第三名，因此保留TopK=3。单个片段命中不代表答案完整，也不能把这组小样本写成总体100%准确率。

原始命中和得分在被忽略的`tools/rag-evaluation-raw.json`，报告在`tools/rag-evaluation-report.json`。自动化测试验证固定集结果，同时覆盖原文行号、稳定ID、真实向量维度与归一化、索引重载、损坏拒绝和显式重建。

## 真实DeepSeek联调

真实本地向量＋DeepSeek＋测试MySQL，3项通过，数据库夹具事务回滚：

| 场景 | 实际观察 |
|---|---|
| 到支付截止但状态未变、库存回补 | 2次模型、1次检索；引用实际payment/timeout片段，解释不能付款及一次回补 |
| 损坏退货邮费 | 2次模型、1次检索；NO_EVIDENCE，knowledgeSources为空，明确无法确定 |
| 本人订单9301＋支付期限规则 | 2次模型、2次工具；读取实际expiresAt，分别用订单事实和规则片段回答 |

当次耗时约2.0～3.4秒，累计7812Token，仅为3次本地观察，不是性能基准或泛化准确率。结果在被忽略的`tools/agent-rag-live-results.json`和`tools/agent-rag-live.log`。真实测试默认跳过，显式运行会访问DeepSeek并产生费用：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentRagDeepSeekLiveTest" "-Dagent.rag.live=true" test
```

最终关联回归60项通过，0失败、0错误、0跳过，离线打包成功。没有执行全量回归；命令如下（模型准备后执行，不进行真实DeepSeek调用）：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConfigurationTest,AgentToolsTest,TradeAgentProtocolTest,AgentApiTest,AgentOrderIntegrationTest,UserOrderPageApiTest,BgeEmbeddingModelTest,RuleDocumentsTest,RuleKnowledgeBaseTest,RuleRetrievalEvaluationTest" package
```

## 学习顺序与简历边界

先讲完整请求如何经过检索，再合并讲RuleChunk＋RagProperties＋RagConfiguration；之后讲RuleDocuments分块、BgeEmbeddingModel向量化、RuleKnowledgeBase索引与检索，最后讲TradeAgentService的回调注册、来源收集和测试。注解、参数、返回值、关键语句都以实际生产代码为准。

本阶段可表述为“基于Spring AI、DeepSeek与本地BGE实现交易规则RAG，结合可信用户订单工具，返回实际检索片段来源；通过固定检索集和真实模型样本验证”。尚未实现专业向量数据库、重排序、上传管理、多轮记忆、SSE或生产规模评测。本阶段完成后停下学习代码，不自动进入下一阶段，也不自动提交推送。

自行审阅检查了Bean开关、旧工具兼容、可信订单身份、真实来源收集、输入校验、资源关闭和持久化边界；未启动子代理。补充损坏索引条目ID测试后先观察失败，再增加ID、文本和维度校验并通过。Git差异空白检查通过，33个改动文件UTF-8可解码，未发现本机密钥；密钥、模型、索引仍由Git忽略。本轮未提交或推送。

依据：[BGE模型说明](https://huggingface.co/BAAI/bge-small-zh-v1.5)、[Spring AI1.1.8 Transformers源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-transformers/src/main/java/org/springframework/ai/transformers/TransformersEmbeddingModel.java)。
