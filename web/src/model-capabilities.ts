import type { OpenAIModel } from './domain'

export interface ReasoningEffortOption {
  value: string
  label: string
}

export interface ModelCapabilities {
  provider: string
  reasoningEfforts: readonly ReasoningEffortOption[]
  defaultReasoningEffort: string
}

interface CapabilityContext {
  baseUrl: string
  model: OpenAIModel
}

interface ModelCapabilityAdapter {
  matches: (context: CapabilityContext) => boolean
  resolve: () => ModelCapabilities
}

const DEEPSEEK_REASONING_EFFORTS = [
  { value: 'low', label: '低 (low)' },
  { value: 'high', label: '高 (high)' },
  { value: 'max', label: '最高 (max)' },
] as const

function hostnameOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).hostname.toLowerCase()
  } catch {
    return ''
  }
}

const adapters: readonly ModelCapabilityAdapter[] = [
  {
    matches: ({ baseUrl, model }) => {
      const hostname = hostnameOf(baseUrl)
      const owner = model.ownedBy?.toLowerCase() ?? ''
      const modelId = model.id.toLowerCase()
      return owner.includes('deepseek')
        || modelId.startsWith('deepseek-')
        || hostname === 'api.deepseek.com'
        || hostname.endsWith('.deepseek.com')
    },
    resolve: () => ({
      provider: 'DeepSeek',
      reasoningEfforts: DEEPSEEK_REASONING_EFFORTS,
      defaultReasoningEffort: 'high',
    }),
  },
]

export function resolveModelCapabilities(
  baseUrl: string,
  model: OpenAIModel | undefined,
): ModelCapabilities {
  if (model === undefined) {
    return { provider: '未知', reasoningEfforts: [], defaultReasoningEffort: '' }
  }

  const context = { baseUrl, model }
  const adapter = adapters.find(candidate => candidate.matches(context))
  if (adapter !== undefined) return adapter.resolve()

  return {
    provider: model.ownedBy?.trim() || 'OpenAI-compatible',
    reasoningEfforts: [],
    defaultReasoningEffort: '',
  }
}

export function normalizeReasoningEffort(
  capabilities: ModelCapabilities,
  preferred: string,
): string {
  return capabilities.reasoningEfforts.some(option => option.value === preferred)
    ? preferred
    : capabilities.defaultReasoningEffort
}
