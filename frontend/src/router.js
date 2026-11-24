import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./Home.vue') },
    { path: '/practice/new', component: () => import('./practice/PracticeNewPage.vue') },
    { path: '/practice/history', component: () => import('./practice/PracticeHistoryPage.vue') },
    { path: '/mastery', component: () => import('./mastery/MasteryOverviewPage.vue') },
    { path: '/mastery/:knowledgeId', component: () => import('./mastery/MasteryDetailPage.vue') },
    { path: '/review', component: () => import('./review/RecommendationPage.vue') },
    { path: '/review/tasks/:id/result', component: () => import('./review/ReviewTaskResultPage.vue') },
    { path: '/review/tasks/:id', component: () => import('./review/ReviewTaskPage.vue') },
    { path: '/practice/:id/result', component: () => import('./practice/PracticeResultPage.vue') },
    { path: '/practice/:id', component: () => import('./practice/PracticePage.vue') },
    { path: '/wrong', component: () => import('./wrong/WrongQuestionListPage.vue') },
    { path: '/wrong/:id', component: () => import('./wrong/WrongQuestionDetailPage.vue') },
    {
      path: '/admin',
      component: () => import('./admin/AdminLayout.vue'),
      redirect: '/admin/knowledge',
      children: [
        { path: 'knowledge', component: () => import('./admin/KnowledgePage.vue') },
        { path: 'questions', component: () => import('./admin/QuestionListPage.vue') },
        { path: 'questions/new', component: () => import('./admin/QuestionEditorPage.vue') },
        { path: 'questions/:id', component: () => import('./admin/QuestionEditorPage.vue') },
        { path: 'materials', component: () => import('./admin/MaterialListPage.vue') },
        { path: 'materials/new', component: () => import('./admin/MaterialEditorPage.vue') },
        { path: 'materials/search', component: () => import('./admin/MaterialSearchCheckPage.vue') },
        { path: 'materials/:id', component: () => import('./admin/MaterialEditorPage.vue') },
        { path: 'coverage', component: () => import('./admin/CoveragePage.vue') },
        { path: 'review-cases', component: () => import('./admin/ReviewCaseListPage.vue') }
      ]
    }
  ]
})
