import { ref } from 'vue'
import { errorText } from '../api'
export function useAsync() {
  const loading = ref(false), error = ref('')
  async function run(action: () => Promise<void>) { if (loading.value) return; loading.value = true; error.value = ''; try { await action() } catch (e) { error.value = errorText(e) } finally { loading.value = false } }
  return { loading, error, run }
}
