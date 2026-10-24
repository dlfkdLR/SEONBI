import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Link, Route, Routes } from 'react-router';
import '@fontsource-variable/noto-sans-kr';
import './styles.css';
import { queryClient } from './data/hooks';
import { Layout } from './components/layout';
import HistoryPage from './pages/history';
import { Button, PageHeading } from './components/ui';
const SetupPage = lazy(() => import('./pages/setup'));
const QuestionsPage = lazy(() => import('./pages/questions'));
const ResultPage = lazy(() => import('./pages/result'));
const SimulationPage = lazy(() => import('./pages/simulation'));

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <main className="page"><PageHeading title="화면을 불러오지 못했어요." description="브라우저의 저장소 접근 설정을 확인한 뒤 다시 시도해주세요." /><Button onClick={() => location.reload()}>다시 불러오기</Button></main> : this.props.children;
  }
}
function App() {
  return <ErrorBoundary><QueryClientProvider client={queryClient}><BrowserRouter><Suspense fallback={<main className="page"><p role="status" className="muted">화면을 준비하고 있어요.</p></main>}><Routes><Route element={<Layout />}>
    <Route index element={<HistoryPage />} />
    <Route path="decisions/:id/setup/:step" element={<SetupPage />} />
    <Route path="decisions/:id/questions" element={<QuestionsPage />} />
    <Route path="decisions/:id/result" element={<ResultPage />} />
    <Route path="decisions/:id/simulate" element={<SimulationPage />} />
    <Route path="*" element={<main id="main" className="page"><PageHeading title="페이지를 찾을 수 없어요." /><Link to="/">내 결정으로 돌아가기</Link></main>} />
  </Route></Routes></Suspense></BrowserRouter></QueryClientProvider></ErrorBoundary>;
}
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
