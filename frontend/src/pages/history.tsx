import { useState } from 'react';
import { Link } from 'react-router';
import type { DecisionItem, Status } from '../api/contracts';
import { useDecisions, useAction, useOwner } from '../data/hooks';
import { repository } from '../data/repository';
import { statusLabels } from '../domain/types';
import { Button, Loading, PageHeading, ErrorNotice, Modal, TextButton } from '../components/ui';
import { clearNewDecisionDraft, useSavedNewDecisionDraft } from '../data/new-decision-draft';
import { useNewDecision } from '../components/layout';
function decisionPath(d: DecisionItem) { return `/decisions/${d.id}/${d.status === 'DRAFT' ? 'setup/1' : d.status === 'QUESTIONING' ? 'questions' : 'result'}`; }
export default function HistoryPage() {
  const owner = useOwner(), draft = useSavedNewDecisionDraft(owner);
  const result = useDecisions(), [filter, setFilter] = useState<Status | 'ALL'>('ALL'), [deleting, setDeleting] = useState<DecisionItem | null>(null);
  const { busy, run, error: actionError } = useAction(), create = useNewDecision();
  if (result.isPending && !draft) return <Loading />;
  if (result.error && !draft) return <main className="page" id="main"><PageHeading title="서버에 연결하지 못했어요." /><ErrorNotice message={result.error.message} /><Button onClick={() => void result.refetch()}>다시 연결</Button></main>;
  const items = result.data?.pages.flatMap(p => p.items) ?? [], rows = items.filter(d => filter === 'ALL' || d.status === filter);
  return <main className="page history-page" id="main"><div className="page-content"><PageHeading title="공유 결정" description="모든 방문자가 같은 결정을 보고 수정할 수 있어요. 이어서 고민하거나 새로 시작해보세요." /><div className="records-toolbar"><span className="muted">총 {items.length + (draft ? 1 : 0)}개</span><Button onClick={create}>새 결정 만들기</Button></div>
    {items.length + (draft ? 1 : 0) > 1 && <div className="status-tabs" role="tablist" aria-label="결정 상태">{(['ALL', 'DRAFT', 'QUESTIONING', 'ANALYZED', 'FINALIZED'] as const).map(s => <button role="tab" aria-selected={filter === s} onClick={() => setFilter(s)} key={s}>{s === 'ALL' ? '전체' : statusLabels[s]} <span>{items.filter(d => s === 'ALL' || d.status === s).length + (draft && (s === 'ALL' || s === 'DRAFT') ? 1 : 0)}</span></button>)}</div>}
    {result.error && <ErrorNotice message="서버 기록에 연결하지 못했어요. 이 브라우저의 임시 저장은 이어 쓸 수 있어요." />}
    <div className="record-list">{draft && (filter === 'ALL' || filter === 'DRAFT') && <article className="record-card local-draft-card"><div className="record-top"><div><span className="eyebrow">임시 저장 · 이 브라우저</span><h2>{draft.title}</h2><small>후보 {draft.options.filter(o => o.name.trim()).length}개 · 제목·후보 작성 중</small></div><Link className="button button-secondary" to={`/decisions/new/setup/${draft.step}`}>이어서 작성하기</Link></div><div className="record-bottom"><small>아직 서버에 만들지 않은 결정이에요.</small><TextButton onClick={() => clearNewDecisionDraft(owner)} aria-label={`${draft.title} 임시 저장 삭제`}>삭제</TextButton></div></article>}{rows.map(d => <article className="record-card" key={d.id}><div className="record-top"><div><span className="eyebrow">{statusLabels[d.status]}</span><h2>{d.title}</h2>{d.finalChoice?.name && <p className="muted">{d.finalChoice.name}</p>}<small>후보 {d.optionCount}개 · {new Date(d.updatedAt).toLocaleDateString('ko-KR')}</small></div><Link className="button button-secondary" to={decisionPath(d)}>{d.status === 'DRAFT' ? '이어서 작성하기' : d.status === 'QUESTIONING' ? '이어서 답하기' : '결과 다시 보기'}</Link></div><div className="record-bottom"><small>서버에 저장됨</small><TextButton onClick={() => setDeleting(d)} aria-label={`${d.title} 삭제`}>삭제</TextButton></div></article>)}</div>
    {!items.length && !draft && <div className="empty-state"><h2>첫 번째 고민을 남겨볼까요?</h2><p>비교할 후보가 두 개만 있어도 시작할 수 있어요.</p><Link className="text-button example-label" to="/example">새 노트북 고르기 · 예시 살펴보기 ›</Link></div>}{items.length > 0 && !rows.length && !(draft && filter === 'DRAFT') && <p className="empty-state">이 상태의 결정이 아직 없어요.</p>}
    {result.hasNextPage && <Button secondary disabled={result.isFetchingNextPage} onClick={() => void result.fetchNextPage()}>결정 더 불러오기</Button>}</div>
    {deleting && <Modal open onClose={() => setDeleting(null)} title="이 결정을 지울까요?" description={`‘${deleting.title}’의 입력 내용과 결과를 삭제해요. 되돌릴 수 없어요.`}>{actionError && <ErrorNotice message={actionError} />}<div className="modal-actions"><Button secondary disabled={busy} onClick={() => setDeleting(null)}>돌아가기</Button><Button disabled={busy} onClick={() => void run(() => repository.remove(deleting.id), () => setDeleting(null))}>결정 삭제</Button></div></Modal>}
  </main>;
}
