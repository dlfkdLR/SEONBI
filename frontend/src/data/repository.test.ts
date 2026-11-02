import {beforeEach,describe,expect,it,vi} from 'vitest';
import {api} from '../api/http';
import {fromDto,repository,rebaseDraft,valuesBody,criterionBody} from './repository';
import {blankDecision,criterion} from './examples';
import type {DecisionDto} from '../api/contracts';
vi.mock('../api/http',async importOriginal=>({...await importOriginal<typeof import('../api/http')>(),api:{request:vi.fn()}}));
const request=vi.mocked(api.request);
function fixture():DecisionDto{return{id:'d',title:'전공 선택',description:'목적',category:'career',status:'DRAFT',options:[{id:'o1',name:'컴공',excluded:false,excludedReasons:[]},{id:'o2',name:'디자인',excluded:false,excludedReasons:[]}],criteria:[],values:[],rankings:[],conditions:[],readiness:{canStartQuestions:false,missing:['CRITERIA_LT_2']},createdAt:'2026-10-24T00:00:00Z',updatedAt:'2026-10-24T00:00:00Z'};}
beforeEach(()=>request.mockReset());
describe('B의 서버 DTO와 화면 입력 변환',()=>{
 it('순위 입력을 별도 rankings로 보내고 동순위를 같은 그룹에 둔다',()=>{const d=fromDto(fixture()),c=criterion('흥미','rating');c.inputMode='rank';d.criteria=[c];d.values={o1:{[c.id]:1},o2:{[c.id]:1}};expect(valuesBody(d)).toEqual({items:[],rankings:[{criterionId:c.id,order:[['o1','o2']]}]});});
 it('서버의 동순위와 boolean 원시값을 보존한다',()=>{const dto=fixture();dto.values=[{optionId:'o1',criterionId:'yes',value:false}];dto.rankings=[{criterionId:'rank',order:[['o1','o2']]}];expect(fromDto(dto).values).toEqual({o1:{yes:false,rank:1},o2:{rank:1}});});
 it('boolean은 true_better, 단계형은 higher_better로 전송한다',()=>{expect(criterionBody(criterion('기숙사','boolean'))).toMatchObject({direction:'true_better'});expect(criterionBody(criterion('적성','rating','lower_better'))).toMatchObject({direction:'higher_better'});});
 it('초기 설정 완료는 POST 한 번으로 모두 보내고 서버가 발급한 ID로 바꾼다',async()=>{
  const d=blankDecision('owner');d.title='전공 선택';d.options=[{id:'local1',name:'컴공'},{id:'local2',name:'디자인'}];
  const cost=criterion('비용','numeric','lower_better','만 원'),mood=criterion('흥미','rating');mood.inputMode='rank';d.criteria=[cost,mood];
  d.values={local1:{[cost.id]:50,[mood.id]:1},local2:{[cost.id]:70,[mood.id]:2}};
  d.conditions=[{id:'k1',kind:'threshold',criterionId:cost.id,operator:'<=',value:60},{id:'k2',kind:'required_tag',label:'통학 가능',satisfiedBy:['local1']}];
  const dto={...fixture(),criteria:[{...cost,id:'sc1'},{...mood,id:'sc2'}],values:[{optionId:'o1',criterionId:'sc1',value:50},{optionId:'o2',criterionId:'sc1',value:70}],rankings:[{criterionId:'sc2',order:[['o1'],['o2']]}]};
  request.mockResolvedValueOnce(dto);
  const saved=await repository.complete(d,'key-1');
  expect(saved.id).toBe('d');expect(saved.options.map(o=>o.id)).toEqual(['o1','o2']);
  expect(request).toHaveBeenCalledTimes(1);
  expect(request.mock.calls[0][0]).toBe('completeSetup');
  expect(request.mock.calls[0][1]).toMatchObject({body:{setupKey:'key-1',title:'전공 선택',options:[{ref:'local1',name:'컴공'},{ref:'local2',name:'디자인'}],
   values:[{optionRef:'local1',criterionRef:cost.id,value:50},{optionRef:'local2',criterionRef:cost.id,value:70}],rankings:[{criterionRef:mood.id,order:[['local1'],['local2']]}],
   conditions:[{kind:'threshold',criterionRef:cost.id,operator:'<=',value:60},{kind:'required_tag',label:'통학 가능',satisfiedByRefs:['local1']}]}});
 });
 it('초기 설정 저장이 실패하면 예외를 내고 재시도할 수 있다',async()=>{
  const d=blankDecision('owner');d.title='x';request.mockRejectedValueOnce(new Error('503'));
  await expect(repository.complete(d,'key-2')).rejects.toThrow('503');
  request.mockResolvedValueOnce(fixture());await expect(repository.complete(d,'key-2')).resolves.toMatchObject({id:'d'});
  expect(request.mock.calls.map(c=>(c[1] as unknown as {body:{setupKey:string}}).body.setupKey)).toEqual(['key-2','key-2']);
 });
 it('추가 행·열의 임시 ID를 서버 ID로 치환한 뒤 값과 조건을 저장한다',async()=>{const dto=fixture(),base=fromDto(dto),draft=structuredClone(base);draft.options.push({id:'temp-option',name:'경영'});const c=criterion('비용','numeric');draft.criteria.push(c);draft.values['temp-option']={[c.id]:50};draft.conditions=[{id:'temp-condition',kind:'threshold',criterionId:c.id,operator:'<=',value:60}];
 request.mockImplementation(async(name)=>name==='createOption'?{id:'new-option'}:name==='createCriterion'?{id:'new-criterion'}:name==='getDecision'?dto:{});
 await repository.save(base,draft);expect(request).toHaveBeenCalledWith('saveValues',{params:{decisionId:'d'},body:{items:[{optionId:'new-option',criterionId:'new-criterion',value:50}],rankings:[]}});expect(request).toHaveBeenCalledWith('setConditions',{params:{decisionId:'d'},body:{items:[{kind:'threshold',criterionId:'new-criterion',operator:'<=',value:60}]}});
 });
 it('서버 저장 실패는 예외를 내고 성공 데이터로 대체하지 않는다',async()=>{const base=fromDto(fixture()),draft=structuredClone(base);draft.title='수정';request.mockRejectedValueOnce(new Error('415'));await expect(repository.save(base,draft)).rejects.toThrow('415');expect(base.title).toBe('전공 선택');});
 it('마지막 입력값을 지우면 명시적 null을 서버에 보낸다',async()=>{const dto=fixture();dto.criteria=[{...criterion('비용','numeric'),id:'c1'}];dto.values=[{optionId:'o1',criterionId:'c1',value:10}];const base=fromDto(dto),draft=structuredClone(base);delete draft.values.o1.c1;request.mockImplementation(async(name)=>name==='getDecision'?dto:{});await repository.save(base,draft);expect(request).toHaveBeenCalledWith('saveValues',{params:{decisionId:'d'},body:{items:[{optionId:'o1',criterionId:'c1',value:null}],rankings:[]}});});
 it('기존 순위를 지우면 빈 순위 그룹을 서버에 보낸다',async()=>{const dto=fixture();const c={...criterion('흥미','rating'),id:'c1',inputMode:'rank' as const};dto.criteria=[c];dto.rankings=[{criterionId:'c1',order:[['o1'],['o2']]}];const base=fromDto(dto),draft=structuredClone(base);delete draft.values.o1.c1;delete draft.values.o2.c1;request.mockImplementation(async(name)=>name==='getDecision'?dto:{});await repository.save(base,draft);expect(request).toHaveBeenCalledWith('saveValues',{params:{decisionId:'d'},body:{items:[],rankings:[{criterionId:'c1',order:[]}]}});});
});

it('부분 저장 후 재시도할 때 서버 ID를 이어받고 미저장 값은 보존한다',async()=>{
 const base=fromDto(fixture()),draft=structuredClone(base);draft.options.push({id:'temp',name:'경영'});const c=criterion('시간','numeric');draft.criteria=[c];draft.values.temp={[c.id]:30};
 const current=structuredClone(base);current.options.push({id:'server-option',name:'경영'});current.criteria=[{...c,id:'server-criterion'}];
 const rebased=rebaseDraft(draft,current);expect(rebased.values['server-option']['server-criterion']).toBe(30);
 request.mockImplementation(async(name)=>name==='getDecision'?{...fixture(),options:current.options,criteria:current.criteria}:{});
 await repository.save(current,rebased);expect(request.mock.calls.some(([name])=>name==='createOption'||name==='createCriterion')).toBe(false);
 expect(request).toHaveBeenCalledWith('saveValues',{params:{decisionId:'d'},body:{items:[{optionId:'server-option',criterionId:'server-criterion',value:30}],rankings:[]}});
});
