# API 操作索引

机器契约：[openapi.json](openapi.json)。v0.3共72个操作，41个已实现、31个规划；已实现路由与Spring实际映射逐项核验。模块仍为partial/planned，不表示完整课程产品或LLM集群已完成。复杂跨表约束还需要领域服务、事务和授权校验。

当前完整能力边界与测试见 [运行验收](../evidence/runtime-review.md)。planned端点尚不能作为可调用API；`/capabilities` 明确未配置的LLM、媒体、沙箱、导出和前端。

| 方法 | 路径（前缀 /api/v1） | operationId | 模块 | 状态 |
|---|---|---|---|---|
| GET | `/health` | `getHealth` | M12 | implemented |
| GET | `/framework` | `getFramework` | M12 | implemented |
| GET | `/auth/csrf` | `getCsrf` | M01 | implemented |
| POST | `/auth/register` | `register` | M01 | implemented |
| POST | `/auth/session` | `login` | M01 | implemented |
| GET | `/auth/session` | `getSession` | M01 | implemented |
| DELETE | `/auth/session` | `logout` | M01 | implemented |
| GET | `/me/preferences` | `getPreferences` | M01 | planned |
| PUT | `/me/preferences` | `updatePreferences` | M01 | planned |
| GET | `/concepts` | `listConcepts` | M02 | implemented |
| GET | `/concepts/{id}` | `getConcept` | M02 | implemented |
| GET | `/exercises` | `listExercises` | M05 | implemented |
| GET | `/exercises/{id}` | `getExercise` | M05 | implemented |
| POST | `/attempts` | `submitAttempt` | M05 | implemented |
| GET | `/attempts/{id}` | `getAttempt` | M05 | implemented |
| POST | `/compile-jobs` | `submitCompile` | M04 | planned |
| GET | `/compile-jobs/{id}` | `getCompile` | M04 | planned |
| POST | `/compile-jobs/{id}/cancel` | `cancelCompile` | M04 | planned |
| POST | `/labs/sessions` | `createLab` | M03 | planned |
| GET | `/labs/sessions/{id}` | `getLab` | M03 | planned |
| POST | `/learning-events` | `recordReading` | M06 | planned |
| GET | `/me/progress` | `getProgress` | M06 | implemented |
| GET | `/me/reviews` | `listReviews` | M06 | implemented |
| GET | `/knowledge/search` | `searchKnowledge` | M08 | implemented |
| PATCH | `/me/reviews/{id}` | `updateReview` | M06 | implemented |
| POST | `/tutor/conversations` | `createConversation` | M09 | planned |
| GET | `/tutor/conversations` | `listConversations` | M09 | planned |
| GET | `/tutor/conversations/{id}` | `getConversation` | M09 | planned |
| POST | `/tutor/requests` | `createTutorRequest` | M09 | planned |
| GET | `/tutor/requests/{id}` | `getTutorRequest` | M09 | planned |
| POST | `/tutor/requests/{id}/cancel` | `cancelTutorRequest` | M09 | planned |
| POST | `/admin/sources` | `createSource` | M07 | planned |
| POST | `/admin/chunks` | `createChunkRevision` | M10 | planned |
| POST | `/admin/exercises` | `createExerciseRevision` | M10 | planned |
| POST | `/admin/reviews` | `reviewContent` | M10 | planned |
| POST | `/admin/releases` | `createRelease` | M10 | planned |
| GET | `/admin/releases/{id}` | `getRelease` | M10 | planned |
| POST | `/admin/releases/{id}/publish` | `publishRelease` | M10 | planned |
| POST | `/admin/releases/{id}/activate` | `activateHistoricalRelease` | M10 | planned |
| POST | `/workspaces` | `createWorkspace` | M15 | implemented |
| GET | `/workspaces` | `listWorkspaces` | M15 | implemented |
| GET | `/workspaces/{id}` | `getWorkspace` | M15 | implemented |
| PATCH | `/workspaces/{id}` | `updateWorkspace` | M15 | implemented |
| POST | `/workspaces/{id}/events` | `recordWorkspaceEvent` | M15 | implemented |
| GET | `/workspaces/{id}/events` | `listWorkspaceEvents` | M15 | implemented |
| POST | `/workspaces/{id}/context` | `createContextSnapshot` | M15 | implemented |
| POST | `/workspaces/{id}/goals` | `createLearningGoal` | M15 | implemented |
| GET | `/workspaces/{id}/memories` | `listWorkspaceMemories` | M15 | implemented |
| POST | `/workspaces/{id}/memories` | `stateMemory` | M15 | implemented |
| POST | `/memories/{id}/revoke` | `revokeMemory` | M15 | implemented |
| POST | `/workspaces/{id}/agent-runs` | `createAgentRun` | M13 | planned |
| GET | `/agent-runs/{id}` | `getAgentRun` | M13 | implemented |
| POST | `/agent-runs/{id}/commands` | `commandAgentRun` | M13 | implemented |
| GET | `/agent-runs/{id}/events` | `listRunEvents` | M13 | implemented |
| GET | `/workspaces/{id}/artifacts` | `listArtifacts` | M14 | implemented |
| POST | `/workspaces/{id}/artifacts` | `createArtifact` | M14 | implemented |
| GET | `/artifacts/{id}` | `getArtifact` | M14 | implemented |
| POST | `/artifacts/{id}/revisions` | `saveArtifactRevision` | M14 | implemented |
| GET | `/artifacts/{id}/revisions` | `getArtifactRevision` | M14 | implemented |
| POST | `/artifacts/{id}/exports` | `createExport` | M14 | planned |
| GET | `/export-jobs/{id}` | `getExport` | M14 | planned |
| POST | `/export-jobs/{id}/cancel` | `cancelExport` | M14 | planned |
| POST | `/workspaces/{id}/assets` | `createAssetUpload` | M14 | planned |
| POST | `/assets/{id}/complete` | `completeAssetUpload` | M14 | planned |
| GET | `/assets/{id}/download` | `getAssetDownload` | M14 | planned |
| POST | `/workspaces/{id}/notebooks` | `buildInteractiveNotebook` | M16 | planned |
| POST | `/notebooks/{id}/actions` | `dispatchNotebookAction` | M16 | planned |
| GET | `/capabilities` | `getCapabilities` | M12 | implemented |
| GET | `/releases/active` | `getActiveRelease` | M02 | implemented |
| GET | `/workspaces/{id}/projection` | `getWorkspaceProjection` | M15 | implemented |
| POST | `/workspaces/{id}/evidence-runs` | `createEvidenceRun` | M13 | implemented |
| POST | `/workspaces/{id}/notebook-drafts` | `createNotebookDraft` | M16 | implemented |
