# Spec: Long-term & Hybrid Memory for HmDianPing
# Format: YAML-inspired human-readable spec for spec-driven development

meta:
	name: hmdp-hybrid-memory-system
	version: 1.4.0
	description: |
		Adds a hybrid memory system to the HmDianPing AI Agent, optimized for the project's
		existing tech stack (Aliyun Dashscope, MinIO). This system uses Redis for short-term
		memory and a vector database (Qdrant) for long-term semantic memory. This spec
		outlines the final architecture, data schemas, and implementation strategy.

architecture:
	summary: |
		A two-tiered memory architecture:
		1. Short-Term Memory (STM): Redis-backed, stores recent conversation history for exact recall.
		   - Handled by the existing `RedisChatMemory` component.
		2. Long-Term Memory (LTM): Vector DB-backed, stores embedded conversational summaries, user
		   preferences, and key facts for semantic retrieval.
		   - This spec focuses on building the LTM component.
	multi_user_isolation:
		strategy: "Strict data partitioning by `user_id` at the application layer."
		details: |
			- All API endpoints (`/api/memory/*`) MUST require a `user_id`.
			- All vector database queries (read, write, delete) MUST include a `WHERE user_id = ?` or equivalent filter clause.
			- This prevents any possibility of cross-user data leakage at the storage level.
			- User IDs themselves are managed by the main application's authentication system (Sa-Token).

agent_strategy_layer:
	summary: "Defines the agent's high-level decision-making logic for interacting with the memory system."
	read_trigger:
		strategy: "Pre-computation on every user turn."
		description: "Before the agent processes the user's input, a query is sent to the LTM to fetch potentially relevant memories. This ensures context is available from the start."
	write_trigger:
		strategy: "Delegated to ingestion write strategy."
		description: "The agent itself doesn't decide when to write. It produces events (e.g., 'session_ended', 'fact_identified') that trigger the appropriate write strategy defined in `components.long_term_memory.ingestion.write_strategy`."
	usage_policy:
		strategy: "Confidence-based inclusion."
		description: "The agent will only use retrieved memories if their final computed score (from `retrieval.scoring`) exceeds a configurable threshold (e.g., 0.7). This prevents low-relevance or low-confidence memories from polluting the prompt."
		confidence_threshold: 0.7

components:
	long_term_memory:
		summary: Persist conversational and contextual memories as vectors + metadata for semantic search.
		storage:
			type: vector_db
			providers: [qdrant, milvus, weaviate]
			default: qdrant
			connection:
				- name: endpoint
					description: "HTTP/gRPC endpoint for the vector database (e.g., http://localhost:6333 for Qdrant)"
				- name: api_key
					description: "Optional API key for cloud services"
		embedding:
			default: tongyi_text_embedding_v2
			providers:
				- name: tongyi_text_embedding_v2
					type: alibaba
					model: "text-embedding-v2"
					dims: 1536
					normalize: true
				- name: openai_text_embedding_3_small
					type: openai
					model: text-embedding-3-small
					dims: 1536
					normalize: true
			batch_size: 32

		schema:
			collection_name: "hmdp_long_term_memories"
			fields:
				- name: memory_id
					type: string # UUID, ideally derived from content hash for idempotency
					primary: true
				- name: user_id
					type: string
					indexed: true # For filtering memories by user
				- name: session_id
					type: string
					indexed: true # For grouping memories by conversation
				- name: text
					type: text # The original text content of the memory
				- name: vector
					type: float[]
					dims: ${components.long_term_memory.embedding.providers[0].dims} # Dims from the default provider
					storage_dtype: float32
				- name: metadata
					type: map
					keys: [source, type, importance_score, tags, lang, source_hash, status, last_accessed_at] # Added status for lifecycle
				- name: created_at
					type: timestamp
				- name: ttl_days
					type: integer

		ingestion:
			description: "Pipeline for processing and storing new memories."
			write_strategy:
				summary: "Defines the triggers for writing information to the Long-Term Memory."
				triggers:
					- name: post_conversation_analysis
						type: async
						description: "After a user session concludes (e.g., 30 minutes of inactivity), a background job analyzes the transcript, extracts key facts, summarizes the conversation, and ingests them into LTM."
					- name: explicit_fact_extraction
						type: sync
						description: "During a conversation, if the agent identifies a clear, user-stated fact, it can be immediately ingested."
					- name: tool_result_caching
						type: sync
						description: "When a tool is executed, the result can be cached in LTM for a short TTL."

			sources:
				- name: chat_message
					extract: [text, user_id, session_id]
				- name: explicit_fact
					extract: [text, user_id]
			processors:
				- name: chunker
					strategy: token_window
					window_size_tokens: 256
					overlap_tokens: 64
				- name: embedder
					model: ${components.long_term_memory.embedding.default}
					batching: true
				- name: metadata_enricher
					steps: [importance_scoring_llm, entity_extraction]
			upsert_strategy: "upsert (create or update on conflict)"
			deduplication:
				enabled: true
				similarity_threshold: 0.95

		retrieval:
			description: "Multi-stage retrieval and scoring pipeline for finding relevant memories."
			pipeline:
				- stage: 1_vector_search
					description: "Fast initial candidate retrieval from the vector DB."
					top_k: 50
					score_threshold: 0.3
					filterable_metadata: [user_id, session_id, type, tags, status: "active"]
				- stage: 2_rerank
					description: "Refine candidates using a more powerful but slower method."
					method: cross-encoder
					final_top_k: 5
			scoring:
				summary: "A weighted model to calculate a final score for each retrieved memory."
				formula: "FinalScore = (w_relevance * Relevance) + (w_recency * Recency) + (w_importance * Importance)"
				weights:
					w_relevance: 0.5
					w_recency: 0.25
					w_importance: 0.25
				components:
					- name: Relevance
						source: "Rerank score from pipeline stage 2."
					- name: Recency
						source: "Calculated based on `last_accessed_at` or `created_at`. A time-decay function is applied."
					- name: Importance
						source: "The `importance_score` value from the memory's metadata, assigned during ingestion."

		conflict_resolution:
			summary: "Mechanism to handle contradictory information in memories."
			strategy: "Recency and Explicit Confirmation."
			steps:
				- "1. Prioritize Newer Information: When a new memory contradicts an existing one, the newer memory is generally assumed to be more accurate."
				- "2. Require Explicit Confirmation: If a high-importance fact is about to be overwritten, the agent should ask for confirmation."
				- "3. Source of Truth: Memories from explicit user statements have higher trust than inferred memories."

		lifecycle_management:
			summary: "A state machine to manage the lifecycle of a memory, optimizing storage and relevance."
			states:
				- name: active
					description: "Memory is new, frequently accessed, and included in all searches."
				- name: cooling
					description: "Memory has not been accessed for a while. It might be excluded from high-priority searches."
				- name: archived
					description: "Memory is old and rarely relevant. Its vector is deleted and the content is moved to cold storage (MinIO)."
			transitions:
				- from: active
					to: cooling
					trigger: "No access for `N` days (e.g., 90 days)."
					action: "Update status in metadata."
				- from: cooling
					to: active
					trigger: "Memory is accessed/retrieved."
					action: "Update status and `last_accessed_at` timestamp."
				- from: cooling
					to: archived
					trigger: "No access for `M` days (e.g., 365 days)."
					action: "Run background job to export content to MinIO and delete from vector DB."

		retention_and_compression:
			summary: "Strategies to manage memory size, cost, and relevance over time."
			retention:
				default_ttl_days: 365
				per_user_limit_entries: 10000
				archive_strategy: "Use the `lifecycle_management` state machine to move old vectors to the project's existing MinIO instance."
				minio_archive_path: "/memory-archive/{user_id}/{memory_id}.json"
			compression:
				- name: summarization
					strategy: "Periodically use an LLM to summarize related conversation chunks into a single, dense memory entry."
					trigger: "On session end, or when a topic cluster reaches a certain size."
				- name: quantization
					strategy: "Reduce vector precision (e.g., from float32 to int8) to save storage."
					note: "This is a feature of the vector DB (e.g., Qdrant's scalar/product quantization)."

		operational:
			backup: { enabled: true, schedule: daily, target: blob_store }
			monitoring:
				metrics: [ingestion_rate, query_latency_p95, vector_index_size, recall_at_k, state_transition_counts]
			alerts:
				- on: "ingestion_failure_rate > 1%"
					severity: high
				- on: "query_latency_p95 > 500ms"
					severity: warning

prompt_engineering:
	summary: "Defines how retrieved memories are integrated into the agent's prompt."
	prompt_template: |
		System: You are a helpful assistant for HmDianPing.
		Your task is to assist the user with their requests based on the tools and information provided.

		# Tools
		You have access to the following tools:
		<tool_definitions>

		# Short-term Memory (Conversation History)
		This is the recent conversation history.
		<chat_history>

		# Long-term Memory
		Here are some relevant facts and past conversations we've had that might be useful (confidence score > ${agent_strategy_layer.usage_policy.confidence_threshold}).
		<long_term_memory_results>

		User: {user_input}
		Agent:
	retrieval_format:
		- "Each retrieved memory from the LTM will be formatted as a simple key-value or sentence."
		- "Example: 'Fact: The user's favorite shop is The Grand Cafe.'"

api:
	- path: /api/memory/ingest
		method: POST
		desc: "Ingest raw text into long-term memory."
		request:
			body: { user_id: string, session_id: string, text: string, type: string, metadata?: map }
		response: { status: ok, memory_ids: [string] }

	- path: /api/memory/query
		method: POST
		desc: "Retrieve relevant memories for a given text query."
		request:
			body: { user_id: string, session_id?: string, text: string, top_k?: int, filters?: map }
		response: { results: [{memory_id, score, text, metadata}] }

	- path: /api/memory/forget
		method: DELETE
		desc: "Delete specific memories or all memories for a user (for privacy)."
		request:
			body: { user_id: string, memory_ids?: [string] } # If memory_ids is null, delete all for user
		response: { status: ok, deleted_count: int }

integration:
	java_integration:
		summary: "Implementation plan for integrating LTM into the Spring Boot application."
		maven_dependencies:
			- group: "io.qdrant"
				artifact: "qdrant-client"
				version: "1.9.0"
			- group: "org.springframework.ai"
				artifact: "spring-ai-alibaba"
				version: "1.1.2"
		config_example: |
			# application.yaml
			memory:
			  long-term:
			    provider: qdrant
			    qdrant:
			      host: localhost
			      port: 6333
			      collection-name: "hmdp_long_term_memories"
			  embedding:
			    provider: tongyi
			    tongyi:
			      api-key: ${DASHSCOPE_API_KEY}
			      options:
			        model: text-embedding-v2

		java_interfaces:
			- name: VectorStore
				methods:
					- "upsert(List<MemoryRecord> records): List<String>"
					- "query(VectorSearchQuery query): List<MemorySearchResult>"
					- "delete(List<String> memoryIds): boolean"
			- name: EmbeddingService
				methods:
					- "embed(List<String> texts): List<List<Float>>"
			- name: LongTermMemoryService
				methods:
					- "ingest(IngestRequest request): List<String>"
					- "search(SearchRequest request): List<MemorySearchResult>"
					- "archive(List<String> memoryIds): boolean"

implementation_notes:
	- "Create a `MemoryRecord` DTO that maps to the vector DB schema."
	- "Implement a `MinioArchiver` service to handle moving memory content to/from MinIO."
	- "Run memory compression and lifecycle tasks as asynchronous background jobs (`@Scheduled`)."
	- "Add a feature flag in `application.yaml` to enable/disable LTM reads/writes during rollout."
	- "Ensure all database interactions within `VectorStore` implementations are strictly filtered by `user_id`."

testing:
	- unit: "Mock EmbeddingService and VectorStore (e.g., using an in-memory list)."
	- integration: "Use Testcontainers to run Qdrant and MinIO instances for end-to-end tests."

migration:
	- step1: "Implement `VectorStore` interface with an in-memory mock for initial development."
	- step2: "Add `EmbeddingService` using `spring-ai-alibaba` and the `/api/memory/ingest` endpoint."
	- step3: "Implement the `QdrantVectorStore` and switch the configuration."
	- step4: "Integrate LTM retrieval into `CustomerServiceAgent` to augment prompts as defined in `prompt_engineering`."
	- step5: "Implement and schedule background jobs for memory lifecycle management (archiving) and compression."

qdrant_specifics:
	summary: "Qdrant-specific settings and operational guidance."
	quickstart_docker: |
		docker run -p 6333:6333 -p 6334:6334 \
		    -v $(pwd)/qdrant_storage:/qdrant/storage \
		    qdrant/qdrant
	collection_config:
		name: ${components.long_term_memory.schema.collection_name}
		vectors:
			size: ${components.long_term_memory.embedding.providers[0].dims}
			distance: Cosine
		hnsw_config:
			m: 16
			ef_construct: 200
		quantization_config:
			scalar:
				type: int8
				quantile: 0.99
				always_ram: true
		on_disk: true # Ensure data is persisted to disk

zh_explanations:
	meta:
		description: "中文说明：spec v1.4.0，此版本为最终优化版，深度结合了项目现有的技术栈（阿里云通义千问、MinIO），使其更具落地性和可操作性。"

	architecture:
		summary: |
			定义了一个混合记忆架构：
			1. 短期记忆 (STM): 使用已有的 Redis，存储近期对话，用于精确、快速地查找上下文。
			2. 长期记忆 (LTM): 使用向量数据库，存储经过 embedding 的对话摘要、用户偏好等，用于语义搜索。
			本 spec 聚焦于构建 LTM。
	architecture.multi_user_isolation:
		summary: "定义了严格的多用户数据隔离策略，确保用户数据在应用层和存储层都完全隔离，防止数据泄露。"

	agent_strategy_layer:
		summary: "新增的顶层策略层，定义了 Agent 何时、以及如何决策使用长期记忆，通过置信度阈值来控制 Agent 的行为，使其更智能、更可控。"

	components.long_term_memory.embedding:
		summary: "中文说明：将默认的 Embedding 模型从 OpenAI 切换为阿里云的 `text-embedding-v2`，以统一技术栈并简化 API Key 管理。"

	components.long_term_memory.retrieval.scoring:
		summary: "新增的显式评分模型，它将相关性、新近度和重要性三个维度进行加权计算，得出一个最终分数，用于更精确地评估每条记忆的价值。"

	components.long_term_memory.lifecycle_management:
		summary: "新增的记忆生命周期状态机，定义了记忆从'活跃'到'冷却'再到'归档'的状态流转，实现了更精细化的存储成本和性能管理。"

	components.long_term_memory.retention_and_compression:
		summary: "中文说明：明确了归档策略将与项目中已有的 MinIO 实例深度整合，作为记忆的冷存储后端。"

	integration.java_integration:
		summary: "为 Java/Spring Boot 项目提供了具体的实现规划，包括推荐的 Maven 依赖、`application.yaml` 配置示例以及核心的 `VectorStore`, `EmbeddingService` 等接口定义。"

	qdrant_specifics:
		summary: "提供了针对 Qdrant 的具体配置和操作指南，包括 Docker 启动命令（带持久化卷）、集合（Collection）的最佳实践配置，以及开启磁盘存储和量化以优化性能和成本。"



