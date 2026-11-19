import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Home from './Home.vue'
import AdminLayout from './admin/AdminLayout.vue'
import KnowledgePage from './admin/KnowledgePage.vue'
import QuestionListPage from './admin/QuestionListPage.vue'
import QuestionEditorPage from './admin/QuestionEditorPage.vue'
import CoveragePage from './admin/CoveragePage.vue'
const router = createRouter({ history: createWebHistory(), routes: [
 { path: '/', component: Home },
 { path: '/admin', component: AdminLayout, redirect: '/admin/knowledge', children: [
  { path: 'knowledge', component: KnowledgePage },
  { path: 'questions', component: QuestionListPage },
  { path: 'questions/new', component: QuestionEditorPage },
  { path: 'questions/:id', component: QuestionEditorPage },
  { path: 'coverage', component: CoveragePage }
 ]}
] })
createApp(App).use(createPinia()).use(router).mount('#app')
