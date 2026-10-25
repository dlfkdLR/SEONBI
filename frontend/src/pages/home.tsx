import { Link } from 'react-router';
import { useDecisions, useOwner } from '../data/hooks';
import { useSavedNewDecisionDraft } from '../data/new-decision-draft';
import { useNewDecision } from '../components/layout';
import { Button, ErrorNotice, TextButton } from '../components/ui';
import { statusLabels } from '../domain/types';

export default function HomePage() {
  const draft = useSavedNewDecisionDraft(useOwner());
  const create = useNewDecision(), decisions = useDecisions();
  const empty = !draft && decisions.isSuccess && decisions.data.pages.every(p => p.items.length === 0);
  const recent = [
    ...(decisions.data?.pages.flatMap(p => p.items) ?? []).map(d => ({
      key: `server-${d.id}`, title: d.title, status: d.status, label: statusLabels[d.status], updatedAt: d.updatedAt,
      to: `/decisions/${d.id}/${d.status === 'DRAFT' ? 'setup/1' : d.status === 'QUESTIONING' ? 'questions' : 'result'}`,
      action: d.status === 'DRAFT' ? '이어서 작성하기' : d.status === 'QUESTIONING' ? '질문 이어가기' : '결과 살펴보기',
    })),
    ...(draft ? [{ key: 'browser-draft', title: draft.title, status: 'DRAFT', label: '임시 저장', updatedAt: draft.savedAt ?? '',
      to: `/decisions/new/setup/${draft.step}`, action: '이어서 작성하기' }] : []),
  ].sort((a, b) => (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0)).slice(0, 3);
  return <main className="page home-page" id="main"><div className="home-hero"><div className="home-intro"><p className="eyebrow">선택의 비결, 선비</p><h1>고민은 가볍게.<br />선택은 나답게.</h1><p className="home-description">후보와 기준을 적으면,<br />나에게 맞는 선택을 함께 정리해요.</p><div className="home-actions"><Button onClick={create}>내 결정 시작하기</Button>{draft ? <Link className="text-button" to={`/decisions/new/setup/${draft.step}`}>저장한 고민 이어쓰기 ›</Link> : empty ? <Link className="text-button example-label" to="/example">예시로 살펴보기 <span aria-hidden="true">›</span></Link> : decisions.isSuccess ? <Link className="text-button" to="/decisions">내 결정 이어보기 ›</Link> : null}</div><p className="caption">로그인 없이 시작하고, 나중에 이어 써도 괜찮아요.</p></div><aside className="home-guide" aria-label="선비 이용 순서"><p className="eyebrow">복잡한 고민, 세 번의 정리</p><ol><li><span>01</span><div><strong>후보 적기</strong><p>고민 중인 선택지부터.</p></div></li><li><span>02</span><div><strong>내 기준 찾기</strong><p>무엇이 더 중요한지.</p></div></li><li><span>03</span><div><strong>선택 비교하기</strong><p>나에게 맞는 이유까지.</p></div></li></ol></aside></div>
    {empty && <section className="home-preview" aria-label="노트북 비교 입력 예시"><div className="preview-card"><strong>갤럭시북5 프로</strong><p>성능을 더 중요하게.</p><small>170만 원 · 1.23kg</small></div><div className="preview-card preview-featured"><span className="selection-mark" aria-hidden="true">✓</span><strong>LG 그램 16</strong><p>가볍게, 오래.</p><small>내 기준으로 비교하는 선택</small></div><div className="preview-card"><strong>맥북 에어 M4</strong><p>익숙한 사용 경험.</p><small>160만 원 · 1.24kg</small></div></section>}
    {recent.length > 0 && <section className="home-recent" aria-labelledby="home-recent-title">
      <div className="home-recent-heading"><h2 id="home-recent-title">최근 고민 이어보기</h2><Link className="text-button" to="/decisions">전체 보기 <span aria-hidden="true">›</span></Link></div>
      <div className="home-recent-grid">{recent.map(d => <article key={d.key}>
        <Link className="home-recent-card" to={d.to} aria-label={`${d.title} · ${d.action}`}>
          <span className={`home-status home-status-${d.status.toLowerCase()}`}>{d.label}</span>
          <h3>{d.title}</h3>
          <span className="home-recent-action">{d.action}<span aria-hidden="true">›</span></span>
        </Link>
      </article>)}</div>
    </section>}
    {decisions.error && <div className="home-connection"><ErrorNotice message="서버에 연결하지 못했어요. 서버 실행 상태를 확인하고 다시 연결해주세요." /><TextButton onClick={() => void decisions.refetch()}>다시 연결</TextButton></div>}
    <footer className="home-footer"><span>선비</span><small>선택의 비결, 선비</small></footer>
    </main>;
}
