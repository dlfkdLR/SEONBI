import { useState } from 'react';
import { Link } from 'react-router';
import type { DecisionItem, Status } from '../api/contracts';
import { useDecisions, useAction } from '../data/hooks';
import { repository } from '../data/repository';
import { statusLabels } from '../domain/types';
import { Button, Loading, PageHeading, ErrorNotice, Modal } from '../components/ui';
import { useNewDecision } from '../components/layout';

function decisionPath(d: DecisionItem) { return `/decisions/${d.id}/${d.status === 'DRAFT' ? 'setup/1' : d.status === 'QUESTIONING' ? 'questions' : 'result'}`; }
export default function HistoryPage() {
  const result = useDecisions(), [filter,setFilter] = useState<Status|'ALL'>('ALL'), [deleting,setDeleting] = useState<DecisionItem|null>(null);
  const { busy, run, error: actionError } = useAction(), create = useNewDecision();
  if (result.isPending) return <Loading />;
  if (result.error) return <main className="page" id="main"><PageHeading title="내 결정" /><ErrorNotice message={result.error.message} /><Button onClick={() => void result.refetch()}>다시 연결</Button></main>;
  const items = result.data.pages.flatMap(p => p.items), rows = items.filter(d => filter === 'ALL' || d.status === filter);
  return <main className="page history-page" id="main"><div className="page-content"><div className="heading-action"><PageHeading title="내 결정" description="고민을 시작하고, 내 기준으로 내린 선택을 모아보세요." /><Button onClick={create}>새 결정 만들기 +</Button></div>
    <div className="status-tabs" role="tablist" aria-label="결정 상태">{(['ALL','DRAFT','QUESTIONING','ANALYZED','FINALIZED'] as const).map(s => <button role="tab" aria-selected={filter===s} onClick={()=>setFilter(s)} key={s}>{s==='ALL'?'전체':statusLabels[s]} <span>{items.filter(d=>s==='ALL'||d.status===s).length}</span></button>)}</div>
    <div className="history-table"><div className="history-head"><span>결정</span><span>상태</span><span>비교 대상</span><span>최근 변경</span><span /></div>
    {rows.map(d => <div className="history-row" key={d.id}><Link to={decisionPath(d)}><strong>{d.title}</strong></Link><span>{statusLabels[d.status]}</span><span>선택지 {d.optionCount}개</span><span>{new Date(d.updatedAt).toLocaleDateString('ko-KR')}</span><button className="text-button" onClick={()=>setDeleting(d)} aria-label={`${d.title} 삭제`}>삭제</button></div>)}
    {!items.length && <Link className="history-row" to="/example"><strong>새 노트북 고르기 <small className="example-label">예시</small></strong><span>입력 예시</span><span>선택지 3개</span><span>—</span><span>예시 보기 ↗</span></Link>}
    {items.length>0&&!rows.length&&<p className="empty-state">이 상태의 결정이 아직 없어요.</p>}</div>
    {result.hasNextPage&&<Button secondary disabled={result.isFetchingNextPage} onClick={()=>void result.fetchNextPage()}>결정 더 불러오기</Button>}
    </div><footer className="page-footer"><small>불러온 결정 {items.length}개 · 이 브라우저의 식별자로 서버에 저장됨</small></footer>
    {deleting&&<Modal open onClose={()=>setDeleting(null)} title="이 결정을 삭제할까요?" description="입력 내용과 결과를 삭제해요. 되돌릴 수 없어요."><>{actionError&&<ErrorNotice message={actionError}/>}<div className="modal-actions"><Button secondary onClick={()=>setDeleting(null)}>취소</Button><Button disabled={busy} onClick={()=>void run(()=>repository.remove(deleting.id),()=>setDeleting(null))}>결정 삭제</Button></div></></Modal>}
  </main>;
}
