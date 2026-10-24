import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/http';
import type { AnalysisDto } from '../api/contracts';
import { finalizeChoice, downloadDecisionMarkdown } from '../api/results';
import { repository } from '../data/repository';
import { useDecision, useOwner, useAction } from '../data/hooks';
import { Button, ErrorNotice, Field, Loading, Modal, TextButton } from '../components/ui';
import { formatValue } from '../domain/forms';

export default function ResultPage() {
  const {id=''}=useParams(), owner=useOwner(), decision=useDecision(id), navigate=useNavigate(), {busy,run,error:actionError}=useAction();
  const result=useQuery({queryKey:['analysis',owner,id],queryFn:async()=>await api.request('analysis',{params:{decisionId:id}}) as AnalysisDto});
  const [dialog,setDialog]=useState<'finalize'|'export'|'edit'|null>(null),[optionId,setOption]=useState(''),[memo,setMemo]=useState(''),[title,setTitle]=useState(''),[description,setDescription]=useState('');
  if(decision.isPending||result.isPending)return <Loading/>;
  const error=decision.error??result.error;
  if(error)return <main className="page" id="main"><ErrorNotice message={error.message}/><Link to={`/decisions/${id}/setup/3`}>입력 내용 확인</Link><Button onClick={()=>{void decision.refetch();void result.refetch();}}>다시 시도</Button></main>;
  const d=decision.data, a=result.data;
  if(!d||!a?.recommendation||!a.stability||!Array.isArray(a.weights)||!Array.isArray(a.rankings))return <main className="page" id="main"><ErrorNotice message="서버의 분석 응답 형식을 확인해주세요."/></main>;
  const locked=d.status==='FINALIZED';
  return <main className="page result-page" id="main"><div className="page-content"><div className="result-context"><p className="eyebrow muted">{d.title} / {locked?'최종 결정':'분석 결과'}</p>{!locked&&<TextButton onClick={()=>{setTitle(d.title);setDescription(d.description);setDialog('edit');}}>결정 수정</TextButton>}</div>
    {a.provisional&&<div className="notice">질문이 진행 중인 잠정 결과예요. <Link to={`/decisions/${id}/questions`}>이어서 답변하기 ↗</Link></div>}
    {locked&&<div className="notice">최종 선택을 서버에 기록했어요. 변경하려면 복제해주세요.</div>}
    {a.lowConfidence&&<div className="notice">아직 선택을 좁히기 어려워요. 추가 질문이나 입력값 점검으로 더 확인할 수 있어요.</div>}
    <section className="result-hero"><div><p className="eyebrow muted">현재의 추천</p><h1>지금의 기준에는<br/>{a.recommendation.name}.</h1></div><div className="confidence"><p className="caption muted">1위가 될 확률</p><p className="metric">{Math.round(a.recommendation.topProbability*100)}<span>%</span></p><p>결정 안정성 · {{HIGH:'높음',MEDIUM:'보통',LOW:'낮음'}[a.stability.level]}</p></div></section>
    <section className="editorial-section"><h2>01 판단의 근거</h2><div><p>{a.explanation?.summary}</p>{a.explanation?.reasons?.map((text,i)=><p key={i}>{text}</p>)}{a.explanation?.caveats?.map((text,i)=><p className="muted" key={i}>{text}</p>)}</div></section>
    <section className="editorial-section"><h2>02 추천이 바뀌는 조건</h2><div>{a.changeConditions?.map((c,i)=><p key={i}>{c.text}</p>)}<p className="muted">{a.stability.text}</p></div></section>
    {!!a.hiddenPreferences?.length&&<section className="editorial-section"><h2>03 답변에서 발견한 선호</h2><div>{a.hiddenPreferences.map((p,i)=><p key={i}>{p.text}</p>)}</div></section>}
    <div className="result-columns"><section><h2 className="section-title">선택지 비교</h2>{a.rankings.map(r=><div className="ranking-row" key={r.optionId}><span>{r.rank} {r.name??d.options.find(o=>o.id===r.optionId)?.name}</span><span>{(r.score*100).toFixed(1)}점</span></div>)}{a.excludedOptions?.map(o=><p key={o.optionId} className="caption muted">{d.options.find(x=>x.id===o.optionId)?.name} 제외 · {o.reasons.join(', ')}</p>)}</section><section><h2 className="section-title">답변에서 드러난 중요도</h2><div className="weight-bars">{a.weights.map(w=><div className="weight-row" key={w.criterionId}><span>{w.name??d.criteria.find(c=>c.id===w.criterionId)?.name}</span><div className="weight-track"><div style={{width:`${w.mean*100}%`}}/><span className="weight-interval" style={{left:`${w.interval[0]*100}%`,width:`${(w.interval[1]-w.interval[0])*100}%`}}/></div><strong>{Math.round(w.mean*100)}%</strong></div>)}</div><small className="muted caption">가는 선은 중요도의 80% 추정 구간이에요.</small></section></div>
    <details className="result-values"><summary>입력한 비교 값과 기준 확인</summary><div className="matrix-scroll"><table className="summary-table"><thead><tr><th>선택지</th>{d.criteria.map(c=><th key={c.id}>{c.name}</th>)}</tr></thead><tbody>{d.options.map(o=><tr key={o.id}><th>{o.name}</th>{d.criteria.map(c=><td key={c.id}>{formatValue(d.values[o.id]?.[c.id],c)}</td>)}</tr>)}</tbody></table></div><Link to={`/decisions/${id}/setup/3`}>{locked?'구성 내용 보기':'입력값 수정'} ↗</Link></details>
    <p className="caption muted source-note">서버 분석 · {a.computedAt?new Date(a.computedAt).toLocaleString('ko-KR'):''}</p>
    </div><footer className="page-footer result-actions">{locked?<Button disabled={busy} onClick={()=>void run(()=>repository.duplicate(id),copy=>navigate(`/decisions/${copy.id}/setup/1`))}>복제해서 다시 고민하기</Button>:<Button disabled={busy||d.status!=='ANALYZED'} onClick={()=>{setOption(a.recommendation.optionId);setDialog('finalize');}}>이 선택으로 확정</Button>}<Button secondary onClick={()=>navigate(`/decisions/${id}/simulate`)}>기준 바꿔보기</Button>{!locked&&<TextButton disabled={busy} onClick={()=>void run(()=>api.request('startQuestions',{params:{decisionId:id}}),()=>navigate(`/decisions/${id}/questions`))}>질문 더 하기</TextButton>}<TextButton onClick={()=>setDialog('export')}>파일로 내보내기</TextButton></footer>
    {dialog==='finalize'&&<Modal open onClose={()=>setDialog(null)} title="어떤 선택으로 남길까요?" description="확정 후 입력과 답변을 수정할 수 없어요."><div className="form-stack">{actionError&&<ErrorNotice message={actionError}/>}<label className="field">최종 선택<select value={optionId} onChange={e=>setOption(e.target.value)}>{a.rankings.map(r=><option key={r.optionId} value={r.optionId}>{r.name??d.options.find(o=>o.id===r.optionId)?.name}</option>)}</select></label><Field label="결정 메모 · 선택" value={memo} onValueChange={setMemo} maxLength={500}/><Button disabled={busy} onClick={()=>void run(()=>finalizeChoice(id,{optionId,memo}),()=>setDialog(null))}>이 선택으로 확정</Button></div></Modal>}
    {dialog==='export'&&<Modal open onClose={()=>setDialog(null)} title="결과를 파일로 남길까요?"><div className="form-stack">{actionError&&<ErrorNotice message={actionError}/>}<Button disabled={busy} onClick={()=>void run(()=>downloadDecisionMarkdown(id))}>Markdown 다운로드</Button><Button secondary onClick={()=>{setDialog(null);setTimeout(()=>window.print(),150);}}>인쇄 / PDF로 저장</Button></div></Modal>}
    {dialog==='edit'&&<Modal open onClose={()=>setDialog(null)} title="결정 정보를 수정해요."><div className="form-stack">{actionError&&<ErrorNotice message={actionError}/>}<Field label="결정 제목" value={title} onValueChange={setTitle} maxLength={100}/><Field label="설명" value={description} onValueChange={setDescription} maxLength={1000}/><Button disabled={busy||!title.trim()} onClick={()=>void run(()=>api.request('updateDecision',{params:{decisionId:id},body:{title:title.trim(),description}}),()=>setDialog(null))}>변경사항 저장</Button></div></Modal>}
  </main>;
}
