import { useState } from 'react';
import { Link } from 'react-router';
import { useDecisions } from '../data/hooks';
import { statusLabels } from '../domain/types';
import type { Status, Decision } from '../domain/types';
import { Button, Loading, PageHeading, ErrorNotice, TextButton } from '../components/ui';
import { useNewDecision } from '../components/layout';

export function decisionPath(d: Decision) {
  return `/decisions/${d.id}/${d.status === 'DRAFT' ? `setup/${d.step}` : d.status === 'QUESTIONING' ? 'questions' : 'result'}`;
}
function dateLabel(date: string) {
  return new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' }).format(new Date(date));
}
export default function HistoryPage() {
  const { data, isPending, error } = useDecisions(), [filter, setFilter] = useState<Status | 'ALL'>('ALL');
  const create = useNewDecision();
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  const decisions = data ?? [], rows = decisions.filter(d => filter === 'ALL' || d.status === filter);
  return <main className="page history-page" id="main"><div className="page-content"><div className="heading-action"><PageHeading title="내 결정" description="고민을 시작하고, 내 기준으로 내린 선택을 모아보세요." /><Button onClick={create}>새 결정 만들기 +</Button></div>
    <div className="status-tabs" role="tablist" aria-label="결정 상태">{(['ALL', 'DRAFT', 'QUESTIONING', 'ANALYZED', 'FINALIZED'] as const).map(s => <button role="tab" aria-selected={filter === s} onClick={() => setFilter(s)} key={s}>{s === 'ALL' ? '전체' : statusLabels[s]} <span>{decisions.filter(d => s === 'ALL' || d.status === s).length}</span></button>)}</div>
    {decisions.length ? <div className="history-table"><div className="history-head"><span>결정</span><span>상태</span><span>비교 대상</span><span>최근 변경</span><span /></div>
      {rows.map(d => <Link to={decisionPath(d)} className="history-row" key={d.id}><strong>{d.title || '제목 없는 결정'}{d.example && <small className="example-label">예시</small>}</strong><span className="ink">{statusLabels[d.status]}</span><span>선택지 {d.options.length}개</span><span>{dateLabel(d.updatedAt)}</span><span>{d.status === 'DRAFT' ? '이어서 작성' : d.status === 'QUESTIONING' ? '이어서 답변' : '결과 보기'} ↗</span></Link>)}
      {!rows.length && <div className="empty-state"><p>이 상태의 결정이 아직 없어요.</p><TextButton onClick={() => setFilter('ALL')}>전체 결정 보기</TextButton></div>}
    </div> : <div className="empty-state"><h2>첫 번째 고민을 적어볼까요?</h2><p className="muted">비교할 대상 두 개와 나만의 기준이면 시작할 수 있어요.</p><Button onClick={create}>첫 결정 만들기</Button></div>}
  </div><footer className="page-footer"><small>총 {decisions.length}개의 결정{decisions.some(d => d.example) ? ' · 예시의 제품 정보와 값은 시연용입니다.' : ''} · 이 브라우저에 저장됨</small></footer></main>;
}
