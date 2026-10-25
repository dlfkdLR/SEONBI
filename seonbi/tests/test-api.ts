import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';
import type { DecisionDto, AnalysisDto, QuestionDto } from '../src/api/contracts';
export function decisionFixture(): DecisionDto {
  return {id:'019a0000-0000-7000-8000-000000000001',title:'서버의 결정',description:'서버 저장 내용',category:'activity',status:'DRAFT',
    options:[{id:'o1',name:'개발 동아리',excluded:false,excludedReasons:[]},{id:'o2',name:'사진 동아리',excluded:false,excludedReasons:[]}],
    criteria:[{id:'c1',name:'흥미',type:'rating',direction:'higher_better',inputMode:'score',levels:['낮음','보통','높음']},{id:'c2',name:'성장',type:'rating',direction:'higher_better',inputMode:'score',levels:['낮음','보통','높음']}],
    values:[{optionId:'o1',criterionId:'c1',value:3},{optionId:'o1',criterionId:'c2',value:1},{optionId:'o2',criterionId:'c1',value:1},{optionId:'o2',criterionId:'c2',value:3}],rankings:[],conditions:[],readiness:{canStartQuestions:true,missing:[]},createdAt:'2026-10-24T00:00:00Z',updatedAt:'2026-10-24T00:00:00Z'};
}
export function analysisFixture(d: DecisionDto):AnalysisDto {
  return {provisional:false,recommendation:{optionId:'o2',name:d.options[1]?.name??'서버 추천',topProbability:.913},rankings:[{rank:1,optionId:'o2',name:d.options[1]?.name??'서버 추천',score:.731,topProbability:.913},{rank:2,optionId:'o1',name:d.options[0]?.name??'다른 선택',score:.269,topProbability:.087}],
    weights:d.criteria.map((c,i)=>({criterionId:c.id,name:c.name,mean:i?.7:.3,interval:i?[.6,.8]:[.2,.4]})),stability:{level:'HIGH',score:.913,localPerturbationKeepRate:.86,text:'서버 안정성 설명'},changeConditions:[{type:'weight',criterionId:'c1',threshold:.6,text:'서버에서 받은 전환 조건'}],hiddenPreferences:[{text:'서버에서 받은 숨은 선호'}],excludedOptions:[],explanation:{summary:'서버가 계산한 추천 근거',reasons:['서버 판단 이유'],caveats:[],source:'template'},lowConfidence:false,computedAt:'2026-10-24T00:00:00Z'};
}
export async function mockApi(page:Page,initial:DecisionDto[]=[]) {
  const records=new Map(initial.map(d=>[d.id,structuredClone(d)])),calls:{method:string;path:string;body:any;clientId:string|null}[]=[];
  let answered=0,counter=0;
  function question():QuestionDto {
    return {id:`q${answered}`,seq:answered+1,type:answered===1?'slider_pair':'ab_criteria',criteria:['c1','c2'],title:answered===0?'서버가 고른 첫 질문':'서버가 고른 다음 질문',allowSkip:true,allowFreeText:true,payload:answered===1?{left:{criterionId:'c1',label:'흥미',range:'낮음~높음'},right:{criterionId:'c2',label:'성장',range:'낮음~높음'},min:-5,max:5}:{optionA:{criterionId:'c1',label:'흥미'},optionB:{criterionId:'c2',label:'성장'}}};
  }
  await page.route(/\/api\/(?:v1\/|health)/,async route=>{
    const request=route.request(),url=new URL(request.url()),method=request.method(),path=url.pathname,body=request.postDataJSON();
    const clientId=request.headers()['x-client-id']??null;calls.push({method,path,body,clientId});
    expect(clientId).toMatch(/^[0-9a-f-]{36}$/);
    if(body!==null) expect(request.headers()['content-type']).toBe('application/json');
    const send=(json:unknown,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(json)});
    if(path==='/api/v1/decisions'){
      if(method==='GET')return send({items:[...records.values()].map(d=>({id:d.id,title:d.title,status:d.status,optionCount:d.options.length,updatedAt:d.updatedAt})),nextCursor:null});
      if(method==='POST'){
        expect(Object.keys(body).sort()).toEqual(['category','description','options','title']);
        const d=decisionFixture();d.id='server-decision-'+(++counter);d.title=body.title;d.description=body.description;d.category=body.category;d.options=body.options.map((o:any,i:number)=>({id:`o${i+1}`,name:o.name,excluded:false,excludedReasons:[]}));d.criteria=[];d.values=[];d.readiness={canStartQuestions:false,missing:['CRITERIA_LT_2']};records.set(d.id,d);
        return send({id:d.id,title:d.title,description:d.description,category:d.category,status:d.status,options:d.options,createdAt:d.createdAt,updatedAt:d.updatedAt},201);
      }
    }
    const match=path.match(/^\/api\/v1\/decisions\/([^/]+)(.*)$/),d=match&&records.get(match[1]),tail=match?.[2];
    if(!d)return send({error:{code:'NOT_FOUND',message:'결정 없음'}},404);
    if(!tail){if(method==='GET')return send(d);if(method==='PATCH'){Object.assign(d,body);return send(d);}if(method==='DELETE'){records.delete(d.id);return route.fulfill({status:204});}}
    if(tail==='/criteria/recommendations'&&method==='POST')return send({source:'template',items:[{name:'흥미',type:'rating',direction:'higher_better',inputMode:'score',levels:['낮음','보통','높음']},{name:'성장',type:'rating',direction:'higher_better',inputMode:'score',levels:['낮음','보통','높음']}]});
    if(tail==='/criteria'&&method==='POST'){const c={...body,id:'c'+(d.criteria.length+1)};d.criteria.push(c);d.readiness={canStartQuestions:d.criteria.length>=2,missing:[]};return send(c,201);}
    if(tail==='/values'&&method==='PUT'){d.values=body.items;d.rankings=body.rankings;return send({items:d.values,rankings:d.rankings,missing:[]});}
    if(tail==='/conditions'&&method==='PUT'){d.conditions=body.items.map((c:any,i:number)=>({...c,id:'k'+i}));return send({items:d.conditions,excludedOptions:[],warning:null});}
    if(tail==='/questions/start'&&method==='POST'){d.status='QUESTIONING';return send({status:d.status,round:'initial',question:question(),progress:{answered,expectedRange:[1,2]}});}
    if(tail==='/questions/next'&&method==='GET')return send(d.status==='ANALYZED'?{done:true,reason:'MAX_QUESTIONS'}:{done:false,question:question()});
    if(tail==='/questions/stop'&&method==='POST'){d.status='ANALYZED';return send({reason:'USER_STOPPED',lowConfidence:false});}
    if(tail==='/answers'&&method==='POST'){expect(body.questionId).toBe('q'+answered);answered++;if(answered>=2)d.status='ANALYZED';return send({answer:{id:'answer'+answered},estimate:{weights:[],topProbability:.913},next:answered>=2?{done:true,reason:'MAX_QUESTIONS'}:{done:false,question:question()}},201);}
    if(tail==='/analysis'&&method==='GET')return send(analysisFixture(d));
    if(tail==='/simulations'&&method==='POST')return send({normalizedWeights:body.weights,rankings:[{rank:1,optionId:'o1',score:.456},{rank:2,optionId:'o2',score:.321}],changedFromCurrent:true});
    if(tail==='/sensitivity'&&method==='GET')return send({items:[{criterionId:url.searchParams.get('criterionId'),currentWeight:.3,points:[{weight:0,scores:{o1:.2,o2:.8}},{weight:1,scores:{o1:.8,o2:.2}}],crossovers:[]}]});
    if(tail==='/final-choice'&&method==='POST'){d.status='FINALIZED';return send({decisionId:d.id,optionId:body.optionId,matchedRecommendation:true,finalizedAt:d.updatedAt,status:d.status},201);}
    if(tail==='/export'&&method==='GET')return route.fulfill({status:200,contentType:'text/markdown',headers:{'Content-Disposition':'attachment; filename="server-result.md"'},body:'# 서버에서 내보낸 결과'});
    if(tail==='/duplicate'&&method==='POST'){const copy=structuredClone(d);copy.id='copy-'+d.id;copy.status='DRAFT';copy.title+=' (복사본)';records.set(copy.id,copy);return send(copy,201);}
    return send({error:{code:'TEST_UNHANDLED',message:`미처리 계약 요청: ${method} ${tail}`}},501);
  });
  return {records,calls};
}
