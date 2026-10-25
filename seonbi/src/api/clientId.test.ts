import {afterEach,beforeEach,expect,it,vi} from 'vitest';
let getClientId: typeof import('./clientId')['getClientId'];
beforeEach(async()=>{vi.resetModules();getClientId=(await import('./clientId')).getClientId;const data=new Map<string,string>();vi.stubGlobal('localStorage',{getItem:(k:string)=>data.get(k)??null,setItem:(k:string,v:string)=>data.set(k,v)});});
afterEach(()=>vi.unstubAllGlobals());
it('첫 방문에 표준 키에 UUID를 저장한다',()=>{const id=getClientId();expect(id).toMatch(/^[0-9a-f-]{36}$/);expect(localStorage.getItem('seonbi.clientId')).toBe(id);expect(getClientId()).toBe(id);});
it('기존 ID를 재발급하지 않고 표준 키로 이어받는다',()=>{localStorage.setItem('seonbi.client-id','old-id');expect(getClientId()).toBe('old-id');expect(localStorage.getItem('seonbi.clientId')).toBe('old-id');});
it('C에서 사용하던 표준 ID를 다른 ID로 덮어쓰지 않는다',()=>{localStorage.setItem('seonbi.clientId','current-id');localStorage.setItem('seonbi.client-id','legacy-id');expect(getClientId()).toBe('current-id');expect(localStorage.getItem('seonbi.client-id')).toBe('legacy-id');});

it('저장소가 차단되어도 같은 페이지에서는 동일한 임시 ID를 쓴다',()=>{vi.stubGlobal('localStorage',{getItem:()=>{throw new Error('blocked');},setItem:()=>{throw new Error('blocked');}});const id=getClientId();expect(getClientId()).toBe(id);expect(id).toMatch(/^[0-9a-f-]{36}$/);});
