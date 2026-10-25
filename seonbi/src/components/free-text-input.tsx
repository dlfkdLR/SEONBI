import { useState } from 'react';
import { extractInput, confirmInput, rejectInput, retryInputWithClarification, type ExtractedItem, type ExtractInputResponse } from '../api/inputs';
import { useAction, useDecision } from '../data/hooks';
import { Modal, Field, Button, TextButton, ErrorNotice } from './ui';

export function FreeTextInput({decisionId,questionId,onClose}:{decisionId:string;questionId?:string;onClose:()=>void}) {
  const [text,setText]=useState(''),[result,setResult]=useState<ExtractInputResponse>(),[items,setItems]=useState<ExtractedItem[]>([]),[clarification,setClarification]=useState('');
  const {busy,run,error}=useAction(), input={context:'reason' as const,questionId,text}, decision=useDecision(decisionId);
  const receive=(value:ExtractInputResponse)=>{setResult(value);setItems(value.extracted);};
  const label=(item:ExtractedItem)=>item.type==='preference'?`기준 간 중요도 ${item.value}`:item.type==='constraint'?item.kind==='required_tag'?item.label:`조건 ${item.operator} ${item.value}`:item.name;
  return <Modal open onClose={onClose} title="이유와 조건을 덧붙여요." description="서버가 해석한 내용을 확인한 뒤 반영해주세요."><div className="form-stack">{error&&<ErrorNotice message={error}/>}
    {!result?<><Field label="이유 또는 조건" value={text} onValueChange={setText} maxLength={500}/><Button disabled={busy||!text.trim()} onClick={()=>void run(()=>extractInput(decisionId,input),receive)}>입력 해석하기</Button></>:result.needsClarification?<><p>{result.needsClarification}</p><Field label="추가 설명" value={clarification} onValueChange={setClarification}/><Button disabled={busy||!clarification.trim()} onClick={()=>void run(()=>retryInputWithClarification(decisionId,result.id,input,clarification).catch(e=>{if(!(e instanceof RangeError)){setText(text+'\n추가 설명: '+clarification);setResult(undefined);}throw e;}),receive)}>설명 추가해서 다시 확인</Button></>:<><p className="muted">반영할 항목만 남겨주세요. 조건의 충족 선택지는 설정 화면에서 확인할 수 있어요.</p>{items.map((item,i)=><div className="check-row" key={i}><div><span>{label(item)}</span>{item.type==='constraint'&&item.kind==='required_tag'&&<div className="condition-checks">{decision.data?.options.map(o=><label key={o.id}><input type="checkbox" checked={item.satisfiedBy?.includes(o.id)??false} onChange={e=>setItems(previous=>previous.map((entry,n)=>n===i&&entry.type==='constraint'?{...entry,satisfiedBy:e.target.checked?[...entry.satisfiedBy??[],o.id]:(entry.satisfiedBy??[]).filter(id=>id!==o.id)}:entry))}/>{o.name}</label>)}</div>}</div><TextButton onClick={()=>setItems(v=>v.filter((_,n)=>n!==i))}>제외</TextButton></div>)}<Button disabled={busy||!items.length} onClick={()=>void run(()=>confirmInput(decisionId,result.id,items),onClose)}>확인한 내용 반영</Button></>}
    {result&&<TextButton disabled={busy} onClick={()=>void run(()=>rejectInput(decisionId,result.id),onClose)}>해석 결과 취소</TextButton>}
  </div></Modal>;
}
