import {expect,test} from '@playwright/test';
import {decisionFixture,mockApi} from './test-api';

test('서버 CRUD·값 PUT·질문·분석·시뮬레이션·확정·파일·복제를 연결한다',async({page})=>{
  const server=await mockApi(page);
  await page.goto('/');await expect(page.locator('.example-label')).toHaveCount(1);
  await page.getByRole('button',{name:'새 결정 만들기 +'}).click();
  await page.getByLabel('결정 제목').fill('내 동아리 결정');await page.getByLabel('어디에, 어떻게 사용할 예정인가요?').fill('활동 비교');
  await page.getByLabel('선택지 1',{exact:true}).fill('개발 동아리');await page.getByLabel('선택지 2',{exact:true}).fill('사진 동아리');
  await page.getByRole('button',{name:'다음: 평가 기준'}).click();await expect(page).toHaveURL(/server-decision-1\/setup\/2/);
  await page.getByRole('button',{name:'기본 기준 제안 보기'}).click();await page.getByRole('checkbox').nth(0).check();await page.getByRole('checkbox').nth(1).check();await page.getByRole('button',{name:'선택한 기준 추가'}).click();
  await page.getByRole('button',{name:'다음: 값 비교'}).click();
  await page.getByLabel('개발 동아리 흥미',{exact:true}).selectOption('3');await page.getByLabel('개발 동아리 성장',{exact:true}).selectOption('1');
  await page.getByLabel('사진 동아리 흥미',{exact:true}).selectOption('1');await page.getByLabel('사진 동아리 성장',{exact:true}).selectOption('3');
  await page.getByRole('button',{name:'다음: 필수 조건'}).click();
  await page.getByRole('button',{name:'+ 필수 조건 추가',exact:true}).click();await page.getByLabel('조건 이름').fill('교내 활동');await page.getByRole('button',{name:'조건 추가',exact:true}).click();
  await page.getByRole('button',{name:'입력 끝내고 질문 시작'}).click();
  await expect(page.getByRole('heading',{level:1})).toHaveText('서버가 고른 첫 질문');await page.locator('.choice-card').first().click();await page.getByRole('button',{name:'이 답변으로 계속'}).click();
  await expect(page.getByRole('heading',{level:1})).toHaveText('서버가 고른 다음 질문');await page.getByRole('slider',{name:'상대 중요도'}).fill('2');await page.getByRole('button',{name:'이 답변으로 계속'}).click();
  await expect(page.getByRole('heading',{name:/지금의 기준에는/})).toContainText('사진 동아리');await expect(page.locator('.metric')).toHaveText('91%');await expect(page.getByText('서버가 계산한 추천 근거',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'기준 바꿔보기'}).click();await page.getByRole('slider',{name:'흥미 중요도'}).fill('80');await expect(page.getByText('45.6점')).toBeVisible();
  await page.getByRole('link',{name:'분석 결과로 돌아가기'}).click();await page.getByRole('button',{name:'이 선택으로 확정',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'이 선택으로 확정',exact:true}).click();await expect(page.locator('.notice')).toContainText('최종 선택을 서버에 기록');
  await page.getByRole('button',{name:'파일로 내보내기'}).click();const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'Markdown 다운로드'}).click();expect((await downloaded).suggestedFilename()).toBe('server-result.md');await page.getByRole('button',{name:'닫기',exact:true}).click();
  await page.getByRole('button',{name:'복제해서 다시 고민하기'}).click();await expect(page.getByLabel('결정 제목')).toHaveValue('내 동아리 결정 (복사본)');
  expect(server.calls.some(c=>c.method==='POST'&&c.path.endsWith('/criteria/recommendations'))).toBe(true);
  expect(server.calls.find(c=>c.method==='PUT'&&c.path.endsWith('/values'))?.body.items).toHaveLength(4);
  expect(server.calls.some(c=>c.method==='PUT'&&c.path.endsWith('/conditions'))).toBe(true);
});

test('내 결정이 있으면 예시를 숨기고 마지막 삭제 후 예시 하나를 다시 표시한다',async({page})=>{
  const d=decisionFixture();const server=await mockApi(page,[d]);await page.goto('/');await expect(page.locator('.example-label')).toHaveCount(0);
  await page.getByRole('button',{name:'서버의 결정 삭제'}).click();await page.getByRole('button',{name:'결정 삭제',exact:true}).click();await expect(page.locator('.example-label')).toHaveCount(1);expect(server.records.size).toBe(0);
  await page.reload();await expect(page.locator('.example-label')).toHaveCount(1);await page.getByRole('link',{name:/새 노트북 고르기/}).click();await expect(page.getByRole('heading',{name:'새 노트북 고르기'})).toBeVisible();expect(server.calls.some(c=>c.method==='POST')).toBe(false);
});

test('501 스텁 확정은 완료 상태로 바꾸지 않는다',async({page})=>{
  const d=decisionFixture();d.status='ANALYZED';await mockApi(page,[d]);
  await page.route('**/final-choice',route=>route.fulfill({status:501,contentType:'application/json',body:JSON.stringify({error:{code:'NOT_IMPLEMENTED',message:'서비스 연결 전입니다.',details:{stub:true}}})}));
  await page.goto(`/decisions/${d.id}/result`);await page.getByRole('button',{name:'이 선택으로 확정',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'이 선택으로 확정',exact:true}).click();await expect(page.getByRole('dialog').getByRole('alert')).toContainText('서비스 연결 전입니다.');await expect(page.getByRole('dialog')).toBeVisible();await expect(page.getByText('최종 선택을 서버에 기록했어요.',{exact:false})).toHaveCount(0);
});

test('서버 연결 실패는 로컬 예시 목록이나 가짜 분석으로 대체하지 않는다',async({page})=>{
  await page.route(/\/api\/(?:v1\/|health)/,route=>route.abort('connectionrefused'));await page.goto('/');await expect(page.getByRole('alert')).toContainText('연결하지 못했어요');await expect(page.locator('.example-label')).toHaveCount(0);await expect(page.getByRole('button',{name:'다시 연결'})).toBeVisible();
});

test('값 저장의 415 오류에서는 다음 단계로 넘어가지 않고 편집값을 보존해 재시도한다',async({page})=>{
  const d=decisionFixture();const server=await mockApi(page,[d]);
  const fail=async(route:import('@playwright/test').Route)=>route.fulfill({status:415,contentType:'application/json',body:JSON.stringify({error:{code:'UNSUPPORTED_MEDIA_TYPE',message:'저장 요청 형식을 확인해주세요.'}})});
  await page.route('**/values',fail);
  await page.goto(`/decisions/${d.id}/setup/3`);await page.getByLabel('개발 동아리 흥미',{exact:true}).selectOption('2');await page.getByRole('button',{name:'다음: 필수 조건'}).click();
  await expect(page.getByRole('status')).toContainText('저장 요청 형식을 확인해주세요.');await expect(page).toHaveURL(/setup\/3/);await expect(page.getByLabel('개발 동아리 흥미',{exact:true})).toHaveValue('2');
  await page.unroute('**/values',fail);await page.getByRole('button',{name:'다음: 필수 조건'}).click();await expect(page).toHaveURL(/setup\/4/);expect(server.records.get(d.id)?.values.find(v=>v.optionId==='o1'&&v.criterionId==='c1')?.value).toBe(2);
});
