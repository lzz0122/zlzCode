const OPENAI_API_KEY_STORAGE_KEY = 'zlz-code-agent-openai-api-key-v1'

export function loadOpenAIApiKey(): string {
  try {
    return localStorage.getItem(OPENAI_API_KEY_STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

export function saveOpenAIApiKey(apiKey: string): void {
  try {
    if (apiKey) {
      localStorage.setItem(OPENAI_API_KEY_STORAGE_KEY, apiKey)
    } else {
      localStorage.removeItem(OPENAI_API_KEY_STORAGE_KEY)
    }
  } catch {
    // Keep the current in-memory value when browser storage is unavailable.
  }
}

export function clearOpenAIApiKey(): void {
  saveOpenAIApiKey('')
}
