import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Line, LineChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api/http';
import type { AnalysisDto, SimulationDto, SensitivityDto } from '../api/contracts';
import { useOwner } from '../data/hooks';
import { redistribute } from '../domain/forms';
import { Button, ErrorNotice, Loading, PageHeading } from '../components/ui';

export default function SimulationPage() {
  const {id=''}=useParams(),owner=useOwner();
  const analysis=useQuery({queryKey:['analysis',owner,id],queryFn:async()=>await api.request('analysis',{params:{decisionId:id}}) as AnalysisDto});
  if(analysis.isPending)return <Loading/>;
  if(analysis.error)return <main className="page" id="main"><ErrorNotice message={analysis.error.message}/></main>;
  if(!analysis.data?.weights?.length)return <main className="page" id="main"><ErrorNotice message="서버의 가중치 응답을 확인해주세요."/></main>;
  return <Simulation key={id} id={id} analysis={analysis.data}/>;
}
function Simulation({id,analysis}:{id:string;analysis:AnalysisDto}) {
  const owner=useOwner(), original=analysis.weights.map(w=>w.mean), [weights,setWeights]=useState(original),[debounced,setDebounced]=useState(weights),[index,setIndex]=useState(0),[showSensitivity,setShowSensitivity]=useState(false);
  useEffect(()=>{const timer=setTimeout(()=>setDebounced(weights),150);return()=>clearTimeout(timer);},[weights]);
  const simulation=useQuery({queryKey:['simulation',owner,id,debounced],queryFn:async({signal})=>await api.request('simulate',{params:{decisionId:id},signal,body:{weights:analysis.weights.map((w,i)=>({criterionId:w.criterionId,weight:debounced[i]}))}}) as SimulationDto});
  const selected=analysis.weights[index];
  const sensitivity=useQuery({queryKey:['sensitivity',owner,id,selected.criterionId],enabled:showSensitivity,queryFn:async({signal})=>await api.request('sensitivity',{params:{decisionId:id},query:{criterionId:selected.criterionId},signal}) as SensitivityDto});
  const curve=sensitivity.data?.items.find(s=>s.criterionId===selected.criterionId);
  const points=curve?.points.map(p=>({weight:p.weight*100,...Object.fromEntries(Object.entries(p.scores).map(([id,score])=>[id,score*100]))}));
  return <main className="page simulation-page" id="main"><div className="page-content"><PageHeading context="결과에서 더 살펴보기" title={"중요도가 달라지면,\n선택도 달라질까요?"} description="각 항목을 얼마나 중요하게 볼지 바꿔보세요. 원래 답변은 그대로 남아요."/>
    <div className="simulation-columns"><section className="weight-controls"><h2 className="section-title">내가 조정한 중요도</h2>{analysis.weights.map((c,i)=><div className="weight-control" key={c.criterionId}><label htmlFor={`weight-${i}`}><span>{c.name}</span><strong>{(weights[i]*100).toFixed(1)}%</strong></label><input id={`weight-${i}`} aria-label={`${c.name} 중요도`} type="range" min={0} max={100} step={.1} value={weights[i]*100} onChange={e=>setWeights(w=>redistribute(w,i,Number(e.target.value)/100))}/><small>원래 중요도 {(original[i]*100).toFixed(1)}%</small></div>)}<p className="caption muted">한 기준을 움직이면 나머지가 비율대로 조정돼요. 합계 100%를 유지해요.</p></section>
    <section className="simulation-outcome"><h2 className="section-title">조정한 기준에서는</h2>{simulation.isFetching&&<p role="status">서버에서 계산 중…</p>}{simulation.error?<ErrorNotice message={simulation.error.message}/>:simulation.data?.rankings.map(r=><div className="simulation-rank" key={r.optionId}><div><strong>{r.rank} {r.name??analysis.rankings.find(v=>v.optionId===r.optionId)?.name??r.optionId}</strong><span>{(r.score*100).toFixed(1)}점</span></div><div className="score-track"><div style={{width:`${r.score*100}%`}}/></div></div>)}</section></div>
    <details className="sensitivity" onToggle={e=>setShowSensitivity(e.currentTarget.open)}><summary>어느 지점에서 선택이 달라지는지 자세히 보기</summary><div className="sensitivity-heading"><h2 className="section-title">원래 추정한 가중치 기준의 민감도</h2><select aria-label="민감도 분석 기준" value={index} onChange={e=>setIndex(Number(e.target.value))}>{analysis.weights.map((c,i)=><option key={c.criterionId} value={i}>{c.name}</option>)}</select></div>{sensitivity.error?<ErrorNotice message={sensitivity.error.message}/>:<div className="chart" role="img" aria-label={`${selected.name} 중요도에 따른 점수 변화`}><ResponsiveContainer width="100%" height="100%"><LineChart data={points}><CartesianGrid vertical={false} stroke="#eee"/><XAxis dataKey="weight" type="number" domain={[0,100]} tickFormatter={v=>`${v}%`}/><YAxis domain={[0,100]}/><Tooltip/>{curve?.crossovers.map((c,i)=><ReferenceLine key={i} x={c.weight*100} stroke="#999" strokeDasharray="3 5"/>)}{analysis.rankings.map((r,i)=><Line key={r.optionId} dataKey={r.optionId} name={r.name} type="linear" dot={false} stroke={i?'#999':'#222'} strokeDasharray={i?'6 5':undefined} isAnimationActive={false}/>)}</LineChart></ResponsiveContainer></div>}</details>
    </div><footer className="page-footer"><Link className="text-button" to={`/decisions/${id}/result`}>‹ 내 결과로 돌아가기</Link><Button onClick={()=>setWeights([...original])}>원래 기준으로 되돌리기</Button></footer></main>;
}
