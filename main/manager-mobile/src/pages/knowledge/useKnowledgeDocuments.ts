import type { ReplacementJob } from './documentWorkflow'
import type { KnowledgeChunk, KnowledgeDocument, RetrievalHit } from '@/api/knowledge'
import { computed, ref } from 'vue'
import { deleteDocument, getChunks, getDocuments, parseDocuments, runRetrievalTest, uploadKnowledgeDocument } from '@/api/knowledge'
import { toast } from '@/utils/toast'
import {
  completedDocumentTransitions,
  createDocumentPollScheduler,
  createLatestRequestGate,
  documentProgressPercent,
  documentStatusText,
  isDocumentProcessing,
  nextDocumentPollDelay,
  normalizeDocumentStatus,
  parseReplacementJobs,
  replacementDecision,
} from './documentWorkflow'

interface SelectedFile {
  path: string
  name: string
  size: number
}

const allowedExtensions = ['doc', 'docx', 'pdf', 'txt', 'md', 'mdx', 'csv', 'xls', 'xlsx', 'ppt', 'pptx', 'json', 'jsonl', 'qa']

export function useKnowledgeDocuments() {
  const datasetId = ref('')
  const documents = ref<KnowledgeDocument[]>([])
  const loading = ref(false)
  const uploading = ref(false)
  const busyIds = ref<Set<string>>(new Set())
  const selectedDocument = ref<KnowledgeDocument | null>(null)
  const chunks = ref<KnowledgeChunk[]>([])
  const retrievalQuestion = ref('')
  const retrievalHits = ref<RetrievalHit[]>([])
  const retrievalLoading = ref(false)
  const loadError = ref('')
  const workflowError = ref('')
  const reuploadMode = ref(false)
  const replacementJobs = ref<ReplacementJob[]>([])
  const loadGate = createLatestRequestGate()
  const chunkGate = createLatestRequestGate()
  let pageActive = false
  let pollFailures = 0
  let reconcilingReplacements = false

  const processingCount = computed(() => documents.value.filter(isDocumentProcessing).length)
  const replacementStorageKey = computed(() => `knowledge:replacement:${datasetId.value}`)

  function errorMessage(error: unknown, fallback: string) {
    return error instanceof Error ? error.message : fallback
  }

  function setBusy(id: string, busy: boolean) {
    const next = new Set(busyIds.value)
    if (busy)
      next.add(id)
    else
      next.delete(id)
    busyIds.value = next
  }

  function isBusy(id: string) {
    return busyIds.value.has(id)
  }

  function shouldPoll() {
    return processingCount.value > 0 || replacementJobs.value.length > 0
  }

  const pollScheduler = createDocumentPollScheduler(
    (callback, delay) => setTimeout(callback, delay),
    handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
    () => pageActive && shouldPoll(),
    () => void loadDocuments({ silent: true, polling: true }),
  )

  function stopPolling() {
    pollScheduler.stop()
  }

  function schedulePolling(delay = 2000) {
    pollScheduler.schedule(delay)
  }

  function loadReplacementJobs() {
    try {
      replacementJobs.value = parseReplacementJobs(uni.getStorageSync(replacementStorageKey.value))
    }
    catch {
      replacementJobs.value = []
    }
    if (replacementJobs.value.length)
      reuploadMode.value = true
  }

  function saveReplacementJobs() {
    if (replacementJobs.value.length)
      uni.setStorageSync(replacementStorageKey.value, JSON.stringify(replacementJobs.value))
    else
      uni.removeStorageSync(replacementStorageKey.value)
  }

  function removeReplacementJob(job: ReplacementJob) {
    replacementJobs.value = replacementJobs.value.filter(item => item.newDocumentId !== job.newDocumentId)
    saveReplacementJobs()
  }

  async function reconcileReplacementJobs(snapshot: KnowledgeDocument[]) {
    if (reconcilingReplacements || !replacementJobs.value.length)
      return
    reconcilingReplacements = true
    let reload = false
    try {
      for (const job of [...replacementJobs.value]) {
        const decision = replacementDecision(job, snapshot)
        if (decision === 'WAIT')
          continue
        if (decision === 'MISSING_NEW') {
          if (Date.now() - job.createdAt > 120000) {
            removeReplacementJob(job)
            workflowError.value = `未找到“${job.newName}”的上传结果，旧文档已保留，请重新操作。`
          }
          continue
        }
        if (decision === 'KEEP_OLD') {
          removeReplacementJob(job)
          const replacement = snapshot.find(item => item.id === job.newDocumentId)
          workflowError.value = replacement?.error || `“${job.newName}”解析失败，旧文档已保留。`
          continue
        }
        const oldDocumentExists = snapshot.some(item => item.id === job.oldDocumentId)
        if (oldDocumentExists)
          await deleteDocument(datasetId.value, job.oldDocumentId)
        removeReplacementJob(job)
        workflowError.value = ''
        toast.success(`“${job.oldName}”已使用新切片替换`)
        reload = oldDocumentExists
      }
    }
    catch (error) {
      workflowError.value = errorMessage(error, '新文档已完成，但旧文档删除失败，请重试')
    }
    finally {
      reconcilingReplacements = false
    }
    if (reload && pageActive)
      void loadDocuments({ silent: true })
  }

  async function loadDocuments(options: { silent?: boolean, polling?: boolean } = {}) {
    if (!datasetId.value)
      return
    stopPolling()
    const requestId = loadGate.next()
    if (!options.silent)
      loading.value = true
    try {
      const data = await getDocuments(datasetId.value, { pageSize: 100 })
      if (!pageActive || !loadGate.isLatest(requestId))
        return
      const previous = documents.value
      const next = data.list || []
      documents.value = next
      loadError.value = ''
      pollFailures = 0
      if (selectedDocument.value) {
        selectedDocument.value = next.find(item => item.id === selectedDocument.value?.id) || null
        if (!selectedDocument.value)
          chunks.value = []
      }
      for (const document of completedDocumentTransitions(previous, next)) {
        if (normalizeDocumentStatus(document.run) === 'DONE')
          toast.success(`“${document.name}”解析完成`)
        else
          workflowError.value = document.error || `“${document.name}”${documentStatusText(document)}`
      }
      void reconcileReplacementJobs(next)
    }
    catch (error) {
      if (!pageActive || !loadGate.isLatest(requestId))
        return
      loadError.value = errorMessage(error, '文档状态加载失败')
      if (options.polling)
        pollFailures += 1
      else
        toast.error(loadError.value)
    }
    finally {
      if (loadGate.isLatest(requestId))
        loading.value = false
      if (pageActive)
        schedulePolling(nextDocumentPollDelay(pollFailures))
    }
  }

  function validateFile(file?: SelectedFile) {
    if (!file)
      return '未选择文件'
    const extension = file.name.split('.').pop()?.toLowerCase() || ''
    if (!allowedExtensions.includes(extension))
      return '不支持此文件类型'
    if (file.size > 10 * 1024 * 1024)
      return '文件不能超过 10MB'
    return ''
  }

  function confirmDifferentFileName(document: KnowledgeDocument, file: SelectedFile) {
    if (document.name === file.name)
      return Promise.resolve(true)
    return new Promise<boolean>((resolve) => {
      uni.showModal({
        title: '文件名不同',
        content: `原文档为“${document.name}”，选择的是“${file.name}”。仍要用此文件替换吗？`,
        confirmText: '继续替换',
        success: result => resolve(Boolean(result.confirm)),
        fail: () => resolve(false),
      })
    })
  }

  function chooseFile(document?: KnowledgeDocument) {
    if (uploading.value || (document && isBusy(document.id)))
      return
    const chooser = (uni as any).chooseMessageFile
    if (!chooser) {
      toast.warning('当前平台不支持选择文档')
      return
    }
    chooser({
      count: 1,
      type: 'file',
      success: async (result: { tempFiles: SelectedFile[] }) => {
        const file = result.tempFiles[0]
        const validationError = validateFile(file)
        if (validationError) {
          toast.warning(validationError)
          return
        }
        if (document && !(await confirmDifferentFileName(document, file)))
          return
        await uploadFile(file, document)
      },
    })
  }

  async function uploadFile(file: SelectedFile, replacing?: KnowledgeDocument) {
    const operationId = replacing?.id || '__upload__'
    uploading.value = true
    setBusy(operationId, true)
    workflowError.value = ''
    uni.showLoading({ title: replacing ? '重新切片中' : '上传中', mask: true })
    try {
      const uploaded = await uploadKnowledgeDocument(datasetId.value, file.path, file.name)
      if (!uploaded?.id)
        throw new Error('上传成功但未返回文档 ID')
      if (replacing) {
        replacementJobs.value.push({
          oldDocumentId: replacing.id,
          newDocumentId: uploaded.id,
          oldName: replacing.name,
          newName: uploaded.name || file.name,
          createdAt: Date.now(),
        })
        saveReplacementJobs()
      }
      try {
        await parseDocuments(datasetId.value, [uploaded.id])
        toast.success(replacing ? '新文档已上传，解析完成后将自动替换' : '已提交解析')
      }
      catch (error) {
        workflowError.value = `${errorMessage(error, '解析提交失败')}；旧文档不会删除，可刷新状态后重试。`
      }
      await loadDocuments({ silent: true })
    }
    catch (error) {
      workflowError.value = errorMessage(error, replacing ? '重新切片失败，旧文档已保留' : '上传失败')
      toast.error(workflowError.value)
    }
    finally {
      uni.hideLoading()
      uploading.value = false
      setBusy(operationId, false)
    }
  }

  async function parse(document: KnowledgeDocument) {
    if (isBusy(document.id) || isDocumentProcessing(document))
      return
    setBusy(document.id, true)
    workflowError.value = ''
    try {
      await parseDocuments(datasetId.value, [document.id])
      const current = documents.value.find(item => item.id === document.id)
      if (current)
        current.run = 'RUNNING'
      toast.success('已提交解析，将持续更新状态')
      await loadDocuments({ silent: true })
    }
    catch (error) {
      workflowError.value = errorMessage(error, '解析失败')
      toast.error(workflowError.value)
    }
    finally {
      setBusy(document.id, false)
    }
  }

  async function viewChunks(document: KnowledgeDocument) {
    if (isBusy(document.id))
      return
    const requestId = chunkGate.next()
    setBusy(document.id, true)
    try {
      const data = await getChunks(datasetId.value, document.id, { pageSize: 100 })
      if (!pageActive || !chunkGate.isLatest(requestId))
        return
      selectedDocument.value = document
      chunks.value = data.chunks || []
    }
    catch (error) {
      if (chunkGate.isLatest(requestId))
        toast.error(errorMessage(error, '切片加载失败'))
    }
    finally {
      setBusy(document.id, false)
    }
  }

  function remove(document: KnowledgeDocument) {
    if (isBusy(document.id))
      return
    uni.showModal({
      title: '删除文档',
      content: `确定删除“${document.name}”及其切片？`,
      confirmColor: '#d93025',
      success: async (result) => {
        if (!result.confirm)
          return
        setBusy(document.id, true)
        try {
          await deleteDocument(datasetId.value, document.id)
          if (selectedDocument.value?.id === document.id) {
            selectedDocument.value = null
            chunks.value = []
          }
          await loadDocuments({ silent: true })
        }
        catch (error) {
          toast.error(errorMessage(error, '删除失败'))
        }
        finally {
          setBusy(document.id, false)
        }
      },
    })
  }

  async function retrievalTest() {
    if (retrievalLoading.value)
      return
    const question = retrievalQuestion.value.trim()
    if (!question) {
      toast.warning('请输入召回问题')
      return
    }
    retrievalLoading.value = true
    try {
      const data = await runRetrievalTest(datasetId.value, question)
      retrievalHits.value = data.chunks || []
      if (!retrievalHits.value.length)
        toast.info('没有召回切片')
    }
    catch (error) {
      toast.error(errorMessage(error, '召回测试失败'))
    }
    finally {
      retrievalLoading.value = false
    }
  }

  function initialize(options?: Record<string, string | undefined>) {
    datasetId.value = String(options?.datasetId || '')
    reuploadMode.value = String(options?.reupload || '') === '1'
    if (options?.name)
      uni.setNavigationBarTitle({ title: decodeURIComponent(String(options.name)) })
    loadReplacementJobs()
  }

  function activate() {
    pageActive = true
    void loadDocuments()
  }

  function deactivate() {
    pageActive = false
    stopPolling()
    loadGate.invalidate()
    chunkGate.invalidate()
  }

  return {
    documents,
    loading,
    uploading,
    selectedDocument,
    chunks,
    retrievalQuestion,
    retrievalHits,
    retrievalLoading,
    loadError,
    workflowError,
    reuploadMode,
    processingCount,
    loadDocuments,
    chooseFile,
    parse,
    viewChunks,
    remove,
    retrievalTest,
    isBusy,
    isDocumentProcessing,
    documentProgressPercent,
    documentStatusText,
    normalizeDocumentStatus,
    initialize,
    activate,
    deactivate,
  }
}
