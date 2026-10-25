import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Link, Route, Routes } from 'react-router';
import '@fontsource-variable/noto-sans-kr';
import './styles.css';
import { queryClient } from './data/hooks';
import { Layout } from './components/layout';
import HistoryPage from './pages/history';
import HomePage from './pages/home';
import { Button, PageHeading } from './components/ui';
const ExamplePage = lazy(() => import('./pages/example'));
const SetupPage = lazy(() => import('./pages/setup'));
const QuestionsPage = lazy(() => import('./pages/questions'));
const ResultPage = lazy(() => import('./pages/result'));
const SimulationPage = lazy(() => import('./pages/simulation'));

// 어디서든 잡히지 않은 오류와 거절된 Promise를 콘솔에 남긴다 (화면에는 짧은 문구만 나온다)
window.addEventListener('error', event => console.error('[선비 화면 오류]', event.message, event.error ?? ''));
window.addEventListener('unhandledrejection', event => console.error('[선비 처리되지 않은 오류]', event.reason));

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: unknown, info: React.ErrorInfo) { console.error('[선비 화면 오류] 화면을 그리다 실패했어요.', error, info.componentStack); }
  render() {
    return this.state.failed ? <main className="page"><PageHeading title="화면을 불러오지 못했어요." description="새로고침 후 다시 시도해주세요." /><Button onClick={() => location.reload()}>다시 불러오기</Button></main> : this.props.children;
  }
}
function App() {
  return <ErrorBoundary><QueryClientProvider client={queryClient}><BrowserRouter><Suspense fallback={<main className="page"><p role="status" className="muted">화면을 준비하고 있어요.</p></main>}><Routes><Route element={<Layout />}>
    <Route index element={<HomePage />} />
    <Route path="decisions" element={<HistoryPage />} />
    <Route path="example" element={<ExamplePage />} />
    <Route path="decisions/:id/setup/:step" element={<SetupPage />} />
    <Route path="decisions/:id/questions" element={<QuestionsPage />} />
    <Route path="decisions/:id/result" element={<ResultPage />} />
    <Route path="decisions/:id/simulate" element={<SimulationPage />} />
    <Route path="*" element={<main id="main" className="page"><PageHeading title="페이지를 찾을 수 없어요." /><Link to="/">공유 결정으로 돌아가기</Link></main>} />
  </Route></Routes></Suspense></BrowserRouter></QueryClientProvider></ErrorBoundary>;
}
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
