import { Link } from 'react-router';
import { PageHeading, Button } from '../components/ui';
import { useNewDecision } from '../components/layout';
export default function ExamplePage() {
  const create=useNewDecision();
  return <main className="page" id="main"><div className="page-content"><PageHeading context="입력 예시" title="새 노트북 고르기" description="선택지와 비교 기준을 이렇게 입력할 수 있어요. 제품 정보와 수치는 시연용이에요." />
    <table className="value-table"><thead><tr>{['선택지','가격 · 만원','성능 · 5단계','무게 · kg','배터리 · 시간'].map(t=><th key={t}>{t}</th>)}</tr></thead><tbody>{[['갤럭시북5 프로',170,'매우 좋음',1.23,14],['LG 그램 16',150,'좋음',1.19,16],['맥북 에어 M4',160,'매우 좋음',1.24,18]].map(row=><tr key={row[0]}>{row.map((v,i)=><td key={i}>{v}</td>)}</tr>)}</tbody></table>
    <p className="caption muted">이 예시는 서버에 저장되는 내 결정에 포함되지 않아요. 추천·확률·가중치는 실제 입력과 답변 후 서버에서 받아 표시해요.</p></div>
    <footer className="page-footer"><Link className="button button-secondary" to="/">내 결정</Link><Button onClick={create}>내 결정 만들기</Button></footer></main>;
}
