import { useState } from 'react';
import { Link } from 'react-router';
import type { DecisionItem, Status } from '../api/contracts';
import { useDecisions, useAction } from '../data/hooks';
import { repository } from '../data/repository';
import { statusLabels } from '../domain/types';
import { Button, Loading, PageHeading, ErrorNotice, Modal, TextButton } from '../components/ui';
import { useNewDecision } from '../components/layout';
function decisionPath(d: DecisionItem) { return `/decisions/${d.id}/${d.status === 'DRAFT' ? 'setup/1' : d.status === 'QUESTIONING' ? 'questions' : 'result'}`; }
export default function HistoryPage() {
  const result = useDecisions(), [filter, setFilter] = useState<Status | 'ALL'>('ALL'), [deleting, setDeleting] = useState<DecisionItem | null>(null);
  const { busy, run, error: actionError } = useAction(), create = useNewDecision();
  if (result.isPending) return <Loading />;
  if (result.error) return <main className="page" id="main"><PageHeading title="내 기록에 연결하지 못했어요." /><ErrorNotice message={result.error.message} /><Button onClick={() => void result.refetch()}>다시 연결</Button></main>;
  const items = result.data.pages.flatMap(p => p.items), rows = items.filter(d => filter === 'ALL' || d.status === filter);
  return <main className="page history-page" id="main"><div className="page-content"><PageHeading title="내가 남긴 선택." description="고민하던 내용부터 이어갈 수도, 새로운 결정을 시작할 수도 있어요." /><div className="records-toolbar"><span className="muted">내 결정 {items.length}개</span><Button onClick={create}>새 결정 만들기</Button></div>
    {items.length > 1 && <div className="status-tabs" role="tablist" aria-label="결정 상태">{(['ALL', 'DRAFT', 'QUESTIONING', 'ANALYZED', 'FINALIZED'] as const).map(s => <button role="tab" aria-selected={filter === s} onClick={() => setFilter(s)} key={s}>{s === 'ALL' ? '전체' : statusLabels[s]} <span>{items.filter(d => s === 'ALL' || d.status === s).length}</span></button>)}</div>}
    <div className="record-list">{rows.map(d => <article className="record-card" key={d.id}><div className="record-top"><div><span className="eyebrow">{statusLabels[d.status]}</span><h2>{d.title}</h2><p className="muted">{d.finalChoice?.name ? `${d.finalChoice.name}으로 결정했어요.` : d.status === 'DRAFT' ? '입력하던 내용부터 이어가세요.' : d.status === 'QUESTIONING' ? '몇 가지 질문으로 내 기준을 알아보고 있어요.' : '나에게 맞는 선택을 확인해보세요.'}</p><small>후보 {d.optionCount}개 · {new Date(d.updatedAt).toLocaleDateString('ko-KR')}</small></div><Link className="button button-secondary" to={decisionPath(d)}>{d.status === 'DRAFT' ? '이어서 작성하기' : d.status === 'QUESTIONING' ? '이어서 답하기' : '결과 다시 보기'}</Link></div><div className="record-bottom"><small>같은 브라우저에서 이어볼 수 있는 기록이에요.</small><TextButton onClick={() => setDeleting(d)} aria-label={`${d.title} 삭제`}>삭제</TextButton></div></article>)}</div>
    {!items.length && <div className="empty-state"><h2>첫 번째 고민을 남겨볼까요?</h2><p>비교할 후보가 두 개만 있어도 시작할 수 있어요.</p><Link className="text-button example-label" to="/example">새 노트북 고르기 · 예시 살펴보기 ›</Link></div>}{items.length > 0 && !rows.length && <p className="empty-state">이 상태의 결정이 아직 없어요.</p>}
    {result.hasNextPage && <Button secondary disabled={result.isFetchingNextPage} onClick={() => void result.fetchNextPage()}>결정 더 불러오기</Button>}<p className="caption records-note">내 결정을 모두 지우면, 처음 화면에서 예시를 다시 볼 수 있어요.</p></div>
    {deleting && <Modal open onClose={() => setDeleting(null)} title="이 결정을 지울까요?" description={`‘${deleting.title}’의 입력 내용과 결과를 삭제해요. 되돌릴 수 없어요.`}>{actionError && <ErrorNotice message={actionError} />}<div className="modal-actions"><Button secondary disabled={busy} onClick={() => setDeleting(null)}>돌아가기</Button><Button disabled={busy} onClick={() => void run(() => repository.remove(deleting.id), () => setDeleting(null))}>결정 삭제</Button></div></Modal>}
  </main>;
}
