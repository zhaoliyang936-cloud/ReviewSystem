import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Home from './Home.vue'
import AdminLayout from './admin/AdminLayout.vue'
import KnowledgePage from './admin/KnowledgePage.vue'
import QuestionListPage from './admin/QuestionListPage.vue'
import QuestionEditorPage from './admin/QuestionEditorPage.vue'
import MaterialListPage from './admin/MaterialListPage.vue'
import MaterialEditorPage from './admin/MaterialEditorPage.vue'
import MaterialSearchCheckPage from './admin/MaterialSearchCheckPage.vue'
import CoveragePage from './admin/CoveragePage.vue'
import PracticeNewPage from './practice/PracticeNewPage.vue'
import PracticePage from './practice/PracticePage.vue'
import PracticeResultPage from './practice/PracticeResultPage.vue'
import PracticeHistoryPage from './practice/PracticeHistoryPage.vue'
import MasteryOverviewPage from './mastery/MasteryOverviewPage.vue'
import MasteryDetailPage from './mastery/MasteryDetailPage.vue'
import RecommendationPage from './review/RecommendationPage.vue'
import ReviewTaskPage from './review/ReviewTaskPage.vue'
import ReviewTaskResultPage from './review/ReviewTaskResultPage.vue'
import './practice/practice.css'
const router = createRouter({ history: createWebHistory(), routes: [
 { path: '/', component: Home },
 { path: '/practice/new', component: PracticeNewPage },
 { path: '/practice/history', component: PracticeHistoryPage },
 { path: '/mastery', component: MasteryOverviewPage },
 { path: '/mastery/:knowledgeId', component: MasteryDetailPage },
 { path: '/review', component: RecommendationPage },
 { path: '/review/tasks/:id/result', component: ReviewTaskResultPage },
 { path: '/review/tasks/:id', component: ReviewTaskPage },
 { path: '/practice/:id/result', component: PracticeResultPage },
 { path: '/practice/:id', component: PracticePage },
 { path: '/admin', component: AdminLayout, redirect: '/admin/knowledge', children: [
  { path: 'knowledge', component: KnowledgePage },
  { path: 'questions', component: QuestionListPage },
  { path: 'questions/new', component: QuestionEditorPage },
  { path: 'questions/:id', component: QuestionEditorPage },
  { path: 'materials', component: MaterialListPage },
  { path: 'materials/new', component: MaterialEditorPage },
  { path: 'materials/search', component: MaterialSearchCheckPage },
  { path: 'materials/:id', component: MaterialEditorPage },
  { path: 'coverage', component: CoveragePage }
 ]}
] })
createApp(App).use(createPinia()).use(router).mount('#app')
