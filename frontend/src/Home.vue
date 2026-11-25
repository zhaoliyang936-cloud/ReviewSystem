<script setup>
import { onMounted, ref } from 'vue'
import { api, clearCsrf } from './api'
import BaseButton from './components/BaseButton.vue'
import BaseCard from './components/BaseCard.vue'
import BaseField from './components/BaseField.vue'
import EmptyState from './components/EmptyState.vue'
import SkeletonBlock from './components/SkeletonBlock.vue'
import StatusPill from './components/StatusPill.vue'

const username = ref('')
const password = ref('')
const me = ref(null)
const message = ref('')
const register = ref(false)
const authBusy = ref(false)

const dashLoading = ref(false)
const historyState = ref({ items: [], error: '' })
const masteryState = ref({ items: [], error: '' })
const wrongState = ref({ items: [], error: '' })

const masteryLabels = {
  UNASSESSED: '未评估',
  INSUFFICIENT: '证据不足',
  WEAK: '待加强',
  CONSOLIDATE: '巩固',
  STABLE: '稳定'
}

const masteryTones = {
  UNASSESSED: 'neutral',
  INSUFFICIENT: 'info',
  WEAK: 'danger',
  CONSOLIDATE: 'warn',
  STABLE: 'success'
}

let csrf

async function token() {
  const r = await fetch('/api/auth/csrf')
  if (!r.ok) throw Error()
  csrf = await r.json()
}

async function request(path, body) {
  await token()
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body)
  })
}

async function loadDashboard() {
  if (!me.value || me.value.role === 'ADMIN') return
  dashLoading.value = true
  historyState.value = { items: [], error: '' }
  masteryState.value = { items: [], error: '' }
  wrongState.value = { items: [], error: '' }

  const [histRes, mastRes, wrongRes] = await Promise.allSettled([
    api('/api/practices/history?page=0&size=5'),
    api('/api/review/mastery?page=0&size=6'),
    api('/api/wrong-questions?page=0&size=5')
  ])

  if (histRes.status === 'fulfilled') {
    historyState.value = { items: histRes.value?.items || [], error: '' }
  } else {
    historyState.value = { items: [], error: histRes.reason?.message || '最近练习暂时无法加载' }
  }

  if (mastRes.status === 'fulfilled') {
    masteryState.value = { items: mastRes.value?.items || [], error: '' }
  } else {
    masteryState.value = { items: [], error: mastRes.reason?.message || '掌握度概览暂时无法加载' }
  }

  if (wrongRes.status === 'fulfilled') {
    wrongState.value = { items: wrongRes.value?.items || [], error: '' }
  } else {
    wrongState.value = { items: [], error: wrongRes.reason?.message || '错题列表暂时无法加载' }
  }

  dashLoading.value = false
}

async function refresh() {
  const r = await fetch('/api/me')
  me.value = r.ok ? await r.json() : null
  if (me.value && me.value.role !== 'ADMIN') {
    await loadDashboard()
  }
}

async function submit() {
  message.value = ''
  authBusy.value = true
  try {
    const r = await request('/api/auth/' + (register.value ? 'register' : 'login'), {
      username: username.value,
      password: password.value
    })
    if (!r.ok) {
      message.value =
        r.status === 429
          ? '尝试次数过多，请稍后再试'
          : register.value
            ? '注册失败，请检查用户名、密码或是否已注册'
            : '登录失败，请检查账号状态和凭据'
      return
    }
    password.value = ''
    if (register.value) {
      register.value = false
      message.value = '注册成功，请登录'
    } else {
      clearCsrf()
      await refresh()
    }
  } catch {
    message.value = '无法连接服务'
  } finally {
    authBusy.value = false
  }
}

async function logout() {
  try {
    const r = await request('/api/auth/logout', {})
    if (!r.ok) throw Error()
    clearCsrf()
    me.value = null
    message.value = '已退出'
  } catch {
    message.value = '退出失败，请重试'
  }
}

function formatScore(v) {
  return v == null ? '—' : Number(v).toFixed(1)
}

function formatDate(v) {
  return v ? new Date(v).toLocaleDateString() : '—'
}

onMounted(refresh)
</script>

<template>
  <main v-if="me" class="ui-shell ui-shell--wide">
    <header class="ui-topbar">
      <div>
        <span class="ui-kicker">REVIEWSYSTEM · WORKSPACE</span>
        <h1 class="ui-title">你好，{{ me.username }}</h1>
        <p class="ui-subtitle">当前身份：{{ me.role === 'ADMIN' ? '管理员' : '学生' }}</p>
      </div>
      <div class="ui-actions">
        <BaseButton v-if="me.role === 'ADMIN'" to="/admin" variant="primary">进入课程内容工作台</BaseButton>
        <BaseButton variant="secondary" @click="logout">退出登录</BaseButton>
      </div>
    </header>

    <template v-if="me.role === 'ADMIN'">
      <BaseCard>
        <h2 class="home-card-title">管理员工作台</h2>
        <p class="ui-muted">前往课程内容工作台管理章节、知识点、四类题库、课程资料与复核案件。</p>
        <div class="ui-actions ui-mt-16">
          <BaseButton to="/admin" variant="primary">进入课程内容工作台</BaseButton>
        </div>
      </BaseCard>
    </template>

    <template v-else>
      <nav class="home-shortcuts" aria-label="快捷入口">
        <BaseButton to="/practice/new" variant="primary">新建练习</BaseButton>
        <BaseButton to="/review" variant="secondary">复习任务</BaseButton>
        <BaseButton to="/wrong" variant="secondary">错题本</BaseButton>
        <BaseButton to="/mastery" variant="secondary">掌握度</BaseButton>
        <BaseButton to="/practice/history" variant="ghost">练习历史</BaseButton>
      </nav>

      <div class="ui-grid ui-grid--3 home-cards">
        <BaseCard tag="section">
          <div class="home-card-head">
            <h2 class="home-card-title">最近练习</h2>
            <RouterLink class="home-more" to="/practice/history">全部</RouterLink>
          </div>
          <SkeletonBlock v-if="dashLoading" :lines="4" />
          <p v-else-if="historyState.error" class="ui-muted home-degraded">{{ historyState.error }}</p>
          <EmptyState v-else-if="!historyState.items.length" title="暂无练习记录" description="创建一次普通练习开始积累记录。" />
          <ul v-else class="home-list">
            <li v-for="p in historyState.items" :key="p.id" class="home-list__item">
              <div>
                <RouterLink :to="`/practice/${p.id}/result`" class="home-item-link">
                  {{ p.questionCount }} 题练习
                </RouterLink>
                <div class="ui-muted home-item-meta ui-num">{{ formatDate(p.submittedAt) }}</div>
              </div>
              <StatusPill tone="brand">{{ p.status }}</StatusPill>
            </li>
          </ul>
        </BaseCard>

        <BaseCard tag="section">
          <div class="home-card-head">
            <h2 class="home-card-title">掌握度概览</h2>
            <RouterLink class="home-more" to="/mastery">全部</RouterLink>
          </div>
          <SkeletonBlock v-if="dashLoading" :lines="4" />
          <p v-else-if="masteryState.error" class="ui-muted home-degraded">{{ masteryState.error }}</p>
          <EmptyState v-else-if="!masteryState.items.length" title="暂无掌握度数据" description="完成练习后系统会自动生成知识点掌握度。" />
          <ul v-else class="home-list">
            <li v-for="m in masteryState.items" :key="m.knowledgeId" class="home-list__item">
              <div class="home-item-main">
                <RouterLink :to="`/mastery/${m.knowledgeId}`" class="home-item-link" :title="m.knowledgeCode">
                  {{ m.knowledgeName }} <small class="ui-muted">{{ m.knowledgeCode }}</small>
                </RouterLink>
                <div class="ui-muted home-item-meta ui-num">最终分 {{ formatScore(m.finalScore) }}</div>
              </div>
              <StatusPill :tone="masteryTones[m.status] || 'neutral'">
                {{ masteryLabels[m.status] || m.status }}
              </StatusPill>
            </li>
          </ul>
        </BaseCard>

        <BaseCard tag="section">
          <div class="home-card-head">
            <h2 class="home-card-title">错题待复习</h2>
            <RouterLink class="home-more" to="/wrong">全部</RouterLink>
          </div>
          <SkeletonBlock v-if="dashLoading" :lines="4" />
          <p v-else-if="wrongState.error" class="ui-muted home-degraded">{{ wrongState.error }}</p>
          <EmptyState v-else-if="!wrongState.items.length" title="暂无待复习错题" description="答错的题目会自动收录到这里。" />
          <ul v-else class="home-list">
            <li v-for="w in wrongState.items" :key="w.id" class="home-list__item">
              <div class="home-item-main">
                <RouterLink :to="`/wrong/${w.id}`" class="home-item-link home-stem">
                  {{ w.stem || '（无题干）' }}
                </RouterLink>
                <div class="ui-muted home-item-meta" :title="w.knowledgeCode">{{ w.knowledgeName || w.knowledgeCode }} · 错 {{ w.wrongCount }} 次</div>
              </div>
              <StatusPill :tone="w.status === 'MASTERED' ? 'success' : 'warn'">
                {{ w.status === 'MASTERED' ? '已掌握' : '待重做' }}
              </StatusPill>
            </li>
          </ul>
        </BaseCard>
      </div>
    </template>

    <p v-if="message" role="status" aria-live="polite" class="ui-muted ui-mt-16">{{ message }}</p>
  </main>

  <main v-else class="auth-wrap">
    <BaseCard class="auth-card">
      <div class="auth-brand">
        <span class="ui-kicker">DATA STRUCTURES · REVIEW</span>
        <h1 class="ui-title">ReviewSystem</h1>
        <p class="ui-subtitle">{{ register ? '学生注册' : '登录' }}</p>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <BaseField v-slot="{ id, describedBy }" label="用户名">
          <input
            :id="id"
            v-model="username"
            required
            minlength="3"
            maxlength="40"
            pattern="[A-Za-z0-9_]+"
            autocomplete="username"
            :aria-describedby="describedBy"
          />
        </BaseField>

        <BaseField
          v-slot="{ id, describedBy }"
          label="密码"
          hint="用户名使用 3–40 个字母、数字或下划线；密码至少 12 个字符且不超过 72 字节。"
        >
          <input
            :id="id"
            v-model="password"
            type="password"
            required
            minlength="12"
            :autocomplete="register ? 'new-password' : 'current-password'"
            :aria-describedby="describedBy"
          />
        </BaseField>

        <div class="ui-actions ui-mt-16">
          <BaseButton type="submit" variant="primary" :loading="authBusy">
            {{ register ? '注册' : '登录' }}
          </BaseButton>
          <BaseButton
            variant="ghost"
            @click="register = !register; message = ''"
          >
            {{ register ? '返回登录' : '注册学生账号' }}
          </BaseButton>
        </div>
      </form>

      <p role="status" aria-live="polite" class="auth-status">{{ message }}</p>
    </BaseCard>
  </main>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--sp-24) var(--sp-16);
}

.auth-card {
  width: min(440px, 100%);
}

.auth-brand {
  border-left: 3px solid var(--brand-600);
  padding-left: var(--sp-16);
  margin-bottom: var(--sp-24);
}

.auth-form {
  display: grid;
  gap: var(--sp-12);
}

.auth-status {
  margin: var(--sp-16) 0 0;
  min-height: 22px;
  font-size: var(--fs-13);
  color: var(--ink-700);
}

.home-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-12);
  margin-bottom: var(--sp-20);
}

.home-cards {
  align-items: start;
}

.home-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-12);
  margin-bottom: var(--sp-16);
}

.home-card-title {
  margin: 0;
  font-size: var(--fs-18);
  line-height: var(--lh-tight);
  color: var(--ink-900);
}

.home-more {
  font-size: var(--fs-13);
  text-decoration: none;
  color: var(--brand-600);
}

.home-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--sp-12);
}

.home-list__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-12);
  min-width: 0;
  padding-bottom: var(--sp-12);
  border-bottom: 1px solid var(--line);
}

.home-list__item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.home-item-main {
  min-width: 0;
  flex: 1;
}

.home-item-link {
  display: block;
  font-weight: 600;
  color: var(--ink-900);
  text-decoration: none;
}

.home-item-link:hover {
  color: var(--brand-600);
}

.home-stem {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.home-item-meta {
  font-size: var(--fs-12);
  margin-top: var(--sp-4);
}

.home-degraded {
  margin: 0;
  padding: var(--sp-12);
  background: var(--bg-sub);
  border-radius: var(--r-sm);
  font-size: var(--fs-13);
}
</style>
