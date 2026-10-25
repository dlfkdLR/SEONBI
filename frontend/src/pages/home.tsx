import { Link } from 'react-router';
import { useDecisions } from '../data/hooks';
import { useNewDecision } from '../components/layout';
import { Button, ErrorNotice, TextButton } from '../components/ui';
export default function HomePage() {
  const create = useNewDecision(), decisions = useDecisions();
  const empty = decisions.isSuccess && decisions.data.pages.every(p => p.items.length === 0);
  return <main className="page home-page" id="main"><div className="home-intro"><p className="eyebrow">나답게 고르는 힘, 선비</p><h1>고민은 가볍게.<br />선택은 나답게.</h1><p className="home-description">후보를 적고, 몇 가지 질문에 답해보세요.<br />내가 중요하게 생각하는 것부터 선택의 이유까지 정리해드려요.</p><div className="home-actions"><Button onClick={create}>내 결정 시작하기</Button>{empty ? <Link className="text-button example-label" to="/example">예시로 살펴보기 <span aria-hidden="true">›</span></Link> : decisions.isSuccess ? <Link className="text-button" to="/decisions">내 결정 이어보기 ›</Link> : null}</div><p className="caption">로그인 없이 바로 시작해요. 저장한 결정은 모든 방문자가 함께 볼 수 있어요.</p></div>
    {empty && <section className="home-preview" aria-label="노트북 비교 입력 예시"><div className="preview-card"><strong>갤럭시북5 프로</strong><p>성능을 더 중요하게.</p><small>170만 원 · 1.23kg</small></div><div className="preview-card preview-featured"><span className="selection-mark" aria-hidden="true">✓</span><strong>LG 그램 16</strong><p>가볍게, 오래.</p><small>내 기준으로 비교하는 선택</small></div><div className="preview-card"><strong>맥북 에어 M4</strong><p>익숙한 사용 경험.</p><small>160만 원 · 1.24kg</small></div></section>}
    {decisions.error && <div className="home-connection"><ErrorNotice message="서버에 연결하지 못했어요. 서버 실행 상태를 확인하고 다시 연결해주세요." /><TextButton onClick={() => void decisions.refetch()}>다시 연결</TextButton></div>}
    <p className="caption home-note">한 번에 완벽히 정하지 않아도 괜찮아요. 하나씩 물어볼게요.</p></main>;
}
