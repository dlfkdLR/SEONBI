import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/http';
import type { AnswerDto, AnswerResponse, NextQuestion, QuestionDto } from '../api/contracts';
import { useAction, useOwner } from '../data/hooks';
import { Button, ErrorNotice, Field, Loading, Modal, PageHeading, TextButton } from '../components/ui';
import { FreeTextInput } from '../components/free-text-input';

export default function QuestionsPage() {
  const {id=''}=useParams(), owner=useOwner(), navigate=useNavigate(), {busy,run,error:actionError}=useAction();
  const next=useQuery({queryKey:['question',owner,id],queryFn:async()=>await api.request('nextQuestion',{params:{decisionId:id}}) as NextQuestion});
  const [response,setResponse]=useState<AnswerResponse|null>(null),[history,setHistory]=useState(false),[editing,setEditing]=useState<AnswerDto|null>(null),[reason,setReason]=useState(false);
  const answers=useQuery({queryKey:['answers',owner,id],queryFn:async()=>await api.request('listAnswers',{params:{decisionId:id}}) as {items:AnswerDto[]},enabled:history});
  const q=next.data&&!next.data.done?next.data.question:undefined;
  useEffect(()=>setResponse(null),[q?.id]);
  if(next.isPending)return <Loading/>;
  if(next.error)return <main className="page" id="main"><ErrorNotice message={next.error.message}/><Button onClick={()=>void next.refetch()}>다시 시도</Button><Link to={`/decisions/${id}/setup/3`}>입력 확인</Link></main>;
  if(next.data?.done)return <Navigate to={`/decisions/${id}/result`} replace/>;
  if(!q||!q.id||!q.title||!q.payload||typeof q.payload!=='object')return <main className="page" id="main"><ErrorNotice message="서버의 질문 응답 형식을 확인해주세요."/></main>;
  const submit=()=>void run(async()=>await api.request('submitAnswer',{params:{decisionId:id},body:{questionId:q.id,response:response as never}}),()=>{setResponse(null);});
  return <main className="page question-page" id="main"><div className="page-content"><PageHeading context={`질문 ${q.seq}`} title={q.title} description="실제로 고를 것 같은 쪽을 선택해주세요."/>
    <QuestionInput key={q.id} question={q} response={response} onChange={setResponse}/>
    <div className="question-help">{q.allowSkip&&<TextButton onClick={()=>setResponse({skip:true})}>{response?.skip?'✓ 잘 모르겠어요 · 선택됨':'잘 모르겠어요'}</TextButton>}{q.allowFreeText&&<TextButton onClick={()=>setReason(true)}>이유 추가</TextButton>}</div>
    <div className="question-meta"><Link to={`/decisions/${id}/setup/3`}>비교 내용 확인 ↗</Link></div></div>
    <footer className="page-footer"><Button secondary disabled={busy} onClick={()=>setHistory(true)}>이전 답변 수정</Button><TextButton disabled={busy} onClick={()=>void run(()=>api.request('stopQuestions',{params:{decisionId:id}}),()=>navigate(`/decisions/${id}/result`))}>지금 결과 보기</TextButton><Button disabled={!response||busy} onClick={submit}>{busy?'반영 중…':'이 답변으로 계속'}</Button></footer>
    {reason&&<FreeTextInput decisionId={id} questionId={q.id} onClose={()=>setReason(false)}/>}
    {history&&<Modal open onClose={()=>setHistory(false)} title="이전 답변을 확인해요." wide>{answers.isPending?<p>불러오는 중…</p>:answers.error?<ErrorNotice message={answers.error.message}/>:answers.data?.items.map(a=><div className="answer-row" key={a.id}><p>{a.question.title}{!a.isValid?' · 기준 변경으로 제외됨':''}</p><TextButton disabled={!a.isValid} onClick={()=>{setEditing(a);setHistory(false);}}>수정</TextButton></div>)}</Modal>}
    {editing&&<Modal open onClose={()=>setEditing(null)} title="이전 답변을 수정해요." description={editing.question.title} wide>{actionError&&<ErrorNotice message={actionError}/>}<QuestionInput question={editing.question} response={editing.response} onChange={r=>setEditing(a=>a?{...a,response:r}:a)}/>{editing.question.allowSkip&&<TextButton onClick={()=>setEditing(a=>a?{...a,response:{skip:true}}:a)}>잘 모르겠어요</TextButton>}<Button disabled={busy} onClick={()=>void run(()=>api.request('updateAnswer',{params:{decisionId:id,answerId:editing.id},body:{response:editing.response as never}}),()=>setEditing(null))}>수정한 답변 저장</Button></Modal>}
  </main>;
}
export function QuestionInput({question:q,response,onChange}:{question:QuestionDto;response:AnswerResponse|null;onChange:(r:AnswerResponse)=>void}) {
  if(q.type==='slider_pair')return <div className="preference-slider"><div className="flex justify-between"><strong>{q.payload.left?.label} ↔ {q.payload.right?.label}</strong><span>{response?.value??'직접 움직여주세요'}</span></div><input type="range" aria-label="상대 중요도" min={q.payload.min??-5} max={q.payload.max??5} step={1} value={response?.value??0} onChange={e=>onChange({value:Number(e.target.value)})}/><div className="range-labels"><span>{q.payload.right?.label}이 더 중요해요<br/>{q.payload.right?.range}</span><button onClick={()=>onChange({value:0})}>비슷해요</button><span>{q.payload.left?.label}이 더 중요해요<br/>{q.payload.left?.range}</span></div></div>;
  if(q.type==='numeric')return <Field label={q.title} type="number" min={-5} max={5} step={1} value={response?.value??''} onValueChange={v=>{if(v!==''&&Number.isInteger(Number(v))&&Number(v)>=-5&&Number(v)<=5)onChange({value:Number(v)});}}/>;
  if(q.type==='free_text')return <ErrorNotice message="자유 입력 질문의 직접 답변 형식은 서버 계약 확정이 필요해요. ‘이유 추가’로 조건을 확인해 반영하거나 건너뛸 수 있어요."/>;
  if(q.type!=='ab_criteria'&&q.type!=='ab_scenario')return <ErrorNotice message="지원하지 않는 서버 질문 유형이에요."/>;
  return <div className="choice-cards" role="group" aria-label="질문 선택지">{(['A','B'] as const).map(choice=>{const option=choice==='A'?q.payload.optionA:q.payload.optionB;return <button key={choice} className={`choice-card ${response?.choice===choice?'selected':''}`} aria-pressed={response?.choice===choice} onClick={()=>onChange({choice})}><small>{choice}</small><h2>{option?.label}</h2>{option?.description&&<p>{option.description}</p>}{option?.deltas?.map((d,i)=><p key={i}>{d.display}</p>)}</button>;})}</div>;
}
