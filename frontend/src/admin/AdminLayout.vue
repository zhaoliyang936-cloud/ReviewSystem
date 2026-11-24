<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import BaseButton from '../components/BaseButton.vue'

const me = ref(null)
const drawerOpen = ref(false)
const router = useRouter()
const route = useRoute()

function closeDrawer() {
  drawerOpen.value = false
}

function onKeydown(e) {
  if (e.key === 'Escape' && drawerOpen.value) {
    closeDrawer()
  }
}

watch(() => route.fullPath, closeDrawer)

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    me.value = await api('/api/me')
    if (me.value.role !== 'ADMIN') router.replace('/')
  } catch {
    router.replace('/')
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="admin-shell">
    <div v-if="drawerOpen" class="admin-backdrop" @click="closeDrawer"></div>

    <aside class="admin-sidebar" :class="{ 'is-open': drawerOpen }">
      <div class="admin-sidebar__top">
        <div class="course-mark">
          <span>固定课程</span>
          <strong>数据结构</strong>
          <small>DATA_STRUCTURES</small>
        </div>
        <BaseButton
          variant="ghost"
          size="sm"
          class="admin-drawer-close"
          aria-label="关闭导航菜单"
          @click="closeDrawer"
        >
          ×
        </BaseButton>
      </div>

      <nav class="admin-nav" aria-label="管理导航">
        <RouterLink to="/admin/knowledge">章节与知识点</RouterLink>
        <RouterLink to="/admin/questions">四类题库</RouterLink>
        <RouterLink to="/admin/questions/new">录入题目</RouterLink>
        <RouterLink to="/admin/materials">课程资料</RouterLink>
        <RouterLink to="/admin/materials/search">检索预览</RouterLink>
        <RouterLink to="/admin/coverage">覆盖统计</RouterLink>
        <RouterLink to="/admin/review-cases">复核案件</RouterLink>
      </nav>

      <RouterLink class="admin-back" to="/">← 返回首页</RouterLink>
    </aside>

    <main class="admin-main">
      <header class="admin-topbar">
        <div class="ui-actions">
          <BaseButton
            variant="secondary"
            size="sm"
            class="admin-menu-btn"
            :aria-expanded="drawerOpen ? 'true' : 'false'"
            aria-label="打开导航菜单"
            @click="drawerOpen = true"
          >
            菜单
          </BaseButton>
          <span class="ui-eyebrow admin-brand-eyebrow">M03 · 课程内容工作台</span>
        </div>
        <span v-if="me" class="admin-user">{{ me.username }}</span>
      </header>

      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
}

.admin-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  padding: var(--sp-24) var(--sp-20);
  background: var(--surface);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  z-index: 30;
}

.admin-sidebar__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-8);
}

.course-mark {
  border-left: 3px solid var(--brand-600);
  padding-left: var(--sp-12);
  margin-bottom: var(--sp-32);
}

.course-mark span,
.course-mark small {
  display: block;
  color: var(--ink-500);
  font-size: var(--fs-12);
}

.course-mark strong {
  display: block;
  font-size: var(--fs-22);
  line-height: var(--lh-tight);
  margin: var(--sp-4) 0;
  color: var(--ink-900);
}

.course-mark small {
  font-family: var(--font-mono);
}

.admin-nav {
  display: grid;
  gap: var(--sp-8);
}

.admin-nav a,
.admin-back {
  color: var(--ink-700);
  text-decoration: none;
  padding: var(--sp-12) var(--sp-12);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
  transition: background-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.admin-nav a:hover,
.admin-back:hover {
  background: var(--brand-50);
  color: var(--brand-700);
}

.admin-nav a.router-link-active {
  background: var(--brand-100);
  color: var(--brand-800);
  font-weight: 700;
}

.admin-back {
  margin-top: auto;
}

.admin-main {
  padding: var(--sp-24) var(--sp-40) var(--sp-56);
  min-width: 0;
}

.admin-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-12);
  margin-bottom: var(--sp-20);
}

.admin-brand-eyebrow {
  margin-bottom: 0;
}

.admin-user {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  padding: var(--sp-4) var(--sp-12);
  font-size: var(--fs-13);
  color: var(--ink-700);
}

.admin-menu-btn,
.admin-drawer-close,
.admin-backdrop {
  display: none;
}

@media (max-width: 1024px) {
  .admin-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .admin-menu-btn,
  .admin-drawer-close {
    display: inline-flex;
  }

  .admin-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: var(--overlay);
    z-index: 45;
  }

  .admin-sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 260px;
    max-width: 82vw;
    z-index: 50;
    transform: translateX(-100%);
    transition: transform var(--dur-base) var(--ease);
    box-shadow: var(--sh-3);
  }

  .admin-sidebar.is-open {
    transform: translateX(0);
  }

  .admin-main {
    padding: var(--sp-20) var(--sp-20) var(--sp-40);
  }
}

@media (max-width: 720px) {
  .admin-main {
    padding: var(--sp-16) var(--sp-16) var(--sp-32);
  }
}
</style>
