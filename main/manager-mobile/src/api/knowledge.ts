import type { IResponse, PageResult } from '@/http/request/types'
import { http } from '@/http/request/alova'
import { getEnvBaseUrl } from '@/utils'
import { getStoredAccessToken } from '@/utils/auth'
import { getCurrentWorkspaceId } from '@/utils/workspace'

export interface KnowledgeBase {
  id: string
  datasetId: string
  name: string
  description?: string
  documentCount?: number
  chunkCount?: number
  tokenNum?: number
  status: number
  ragModelId?: string
  parserConfig?: string
  createdAt?: string
}

export interface RagModel {
  id: string
  modelName?: string
  name?: string
  providerCode?: string
}

export interface KnowledgeDocument {
  id: string
  documentId?: string
  name: string
  fileType?: string
  fileSize?: number
  run?: 'UNSTART' | 'RUNNING' | 'CANCEL' | 'DONE' | 'FAIL' | string
  progress?: number
  status?: string
  chunkCount?: number
  tokenCount?: number
  error?: string
  createdAt?: string
}

export interface KnowledgeChunk {
  id: string
  content: string
  documentId?: string
  docnmKwd?: string
  importantKeywords?: string[]
  questions?: string[]
  available?: boolean
}

export interface RetrievalHit extends KnowledgeChunk {
  documentName?: string
  similarity?: number
  vectorSimilarity?: number
  termSimilarity?: number
}

export interface ChunkPage {
  chunks: KnowledgeChunk[]
  total: number
}

export interface RetrievalResult {
  chunks: RetrievalHit[]
  total: number
}

export function getKnowledgeBases(params: { page?: number, pageSize?: number, name?: string } = {}) {
  return http.Get<PageResult<KnowledgeBase>>('/datasets', {
    params: {
      page: params.page || 1,
      page_size: params.pageSize || 50,
      name: params.name || '',
    },
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function getKnowledgeBase(datasetId: string) {
  return http.Get<KnowledgeBase>(`/datasets/${datasetId}`, {
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function getRagModels() {
  return http.Get<RagModel[]>('/datasets/rag-models', {
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function createKnowledgeBase(data: Pick<KnowledgeBase, 'name' | 'description' | 'status' | 'ragModelId' | 'parserConfig'>) {
  return http.Post<KnowledgeBase>('/datasets', data, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function updateKnowledgeBase(datasetId: string, data: Partial<KnowledgeBase>) {
  return http.Put<KnowledgeBase>(`/datasets/${datasetId}`, data, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function deleteKnowledgeBase(datasetId: string) {
  return http.Delete(`/datasets/${datasetId}`, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function getDocuments(datasetId: string, params: { page?: number, pageSize?: number, name?: string } = {}) {
  return http.Get<PageResult<KnowledgeDocument>>(`/datasets/${datasetId}/documents`, {
    params: {
      page: params.page || 1,
      page_size: params.pageSize || 50,
      name: params.name || '',
    },
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function parseDocuments(datasetId: string, documentIds: string[]) {
  return http.Post(`/datasets/${datasetId}/chunks`, { document_ids: documentIds }, {
    timeout: 120000,
    meta: { ignoreAuth: false, toast: false },
  })
}

export function deleteDocument(datasetId: string, documentId: string) {
  return http.Delete(`/datasets/${datasetId}/documents/${documentId}`, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function getChunks(datasetId: string, documentId: string, params: { page?: number, pageSize?: number, keywords?: string } = {}) {
  return http.Get<ChunkPage>(`/datasets/${datasetId}/documents/${documentId}/chunks`, {
    params: {
      page: params.page || 1,
      page_size: params.pageSize || 30,
      keywords: params.keywords || '',
    },
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function runRetrievalTest(datasetId: string, question: string) {
  return http.Post<RetrievalResult>(`/datasets/${datasetId}/retrieval-test`, {
    dataset_ids: [datasetId],
    question,
    page: 1,
    page_size: 10,
    top_k: 10,
  }, {
    timeout: 30000,
    meta: { ignoreAuth: false, toast: false },
  })
}

export function uploadKnowledgeDocument(datasetId: string, filePath: string, name: string) {
  return new Promise<KnowledgeDocument>((resolve, reject) => {
    const token = getStoredAccessToken()
    if (!token) {
      reject(new Error('登录状态已失效'))
      return
    }

    const workspaceId = getCurrentWorkspaceId()
    const header: Record<string, string> = {
      Authorization: `Bearer ${token}`,
    }
    if (workspaceId)
      header['X-Workspace-Id'] = workspaceId

    uni.uploadFile({
      url: `${getEnvBaseUrl()}/datasets/${datasetId}/documents`,
      filePath,
      name: 'file',
      formData: { name },
      header,
      timeout: 120000,
      success: (response) => {
        if (response.statusCode !== 200) {
          reject(new Error(`上传失败（HTTP ${response.statusCode}）`))
          return
        }
        try {
          const body = JSON.parse(response.data) as IResponse<KnowledgeDocument>
          if (Number(body.code) !== 0)
            throw new Error(body.msg || '上传失败')
          resolve(body.data)
        }
        catch (error) {
          reject(error instanceof Error ? error : new Error('上传响应格式错误'))
        }
      },
      fail: error => reject(new Error(error.errMsg || '上传失败')),
    })
  })
}
