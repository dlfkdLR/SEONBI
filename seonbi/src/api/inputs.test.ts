import {expect,it,vi,afterEach} from 'vitest';
import {retryInputWithClarification} from './inputs';
import {clarifiedInputText} from './inputText';
afterEach(()=>vi.unstubAllGlobals());
it('추가 설명까지 500자를 넘으면 원래 입력을 거절하기 전에 중단한다',async()=>{
 const send=vi.fn();vi.stubGlobal('fetch',send);
 await expect(retryInputWithClarification('d','i',{context:'reason',text:'가'.repeat(480)},'나'.repeat(20))).rejects.toThrow(RangeError);
 expect(send).not.toHaveBeenCalled();
});
it('추가 설명의 공백을 정리하고 확인 문장을 만든다',()=>{expect(clarifiedInputText(' 원문 ',' 추가 설명 ')).toBe('원문\n추가 설명: 추가 설명');});
