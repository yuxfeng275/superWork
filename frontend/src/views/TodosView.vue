<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/utils/api'

type UserTodo = {
  id: number
  title: string
  excerpt?: string | null
  link?: string | null
  status: string
  createdAt?: string
}

const router = useRouter()
const rows = ref<UserTodo[]>([])
const loading = ref(false)
const error = ref('')
const actingId = ref<number>()

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.getTodos()
  } catch (e) {
    rows.value = []
    error.value = e instanceof Error ? e.message : '待办加载失败'
  } finally {
    loading.value = false
  }
}

const complete = async (row: UserTodo) => {
  actingId.value = row.id
  try {
    await api.completeTodo(row.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '完成待办失败'
  } finally {
    actingId.value = undefined
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="todos-page">
    <header>
      <div>
        <h2>待办</h2>
        <p>周进展和周会里被 @ 到的事项会汇到这里。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="loading">加载中…</p>
    <ul v-else-if="rows.length">
      <li v-for="row in rows" :key="row.id">
        <strong>{{ row.title }}</strong>
        <span>{{ row.excerpt || '—' }}</span>
        <div>
          <button v-if="row.link" type="button" @click="router.push(row.link!)">查看</button>
          <button
            v-if="row.status !== 'DONE'"
            type="button"
            :disabled="actingId === row.id"
            @click="complete(row)"
          >
            完成
          </button>
        </div>
      </li>
    </ul>
    <p v-else>暂无被 @ 的待办</p>
  </section>
</template>
