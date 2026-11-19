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
import PracticeNewPage from './practice/PracticeNewPage.vue'
import PracticePage from './practice/PracticePage.vue'
import PracticeResultPage from './practice/PracticeResultPage.vue'
import PracticeHistoryPage from './practice/PracticeHistoryPage.vue'
import './practice/practice.css'
const router = createRouter({ history: createWebHistory(), routes: [
 { path: '/', component: Home },
 { path: '/practice/new', component: PracticeNewPage },
 { path: '/practice/history', component: PracticeHistoryPage },
 { path: '/practice/:id/result', component: PracticeResultPage },
 { path: '/practice/:id', component: PracticePage },
 { path: '/admin', component: AdminLayout, redirect: '/admin/knowledge', children: [
  { path: 'knowledge', component: KnowledgePage },
  { path: 'questions', component: QuestionListPage },
  { path: 'questions/new', component: QuestionEditorPage },
  { path: 'questions/:id', component: QuestionEditorPage },
  { path: 'coverage', component: CoveragePage }
 ]}
] })
createApp(App).use(createPinia()).use(router).mount('#app')
