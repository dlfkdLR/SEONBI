import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/http';
import { getClientId, setClientId } from '../api/clientId';
import { moveNewDecisionDraft } from '../data/new-decision-draft';
import { Button, ErrorNotice, Field, Modal } from './ui';

interface Issued { code: string; expiresAt: string; expiresInSeconds: number }
interface Claimed { clientId: string; movedDecisions: number; decisionCount: number }

/**
 * 로그인 없이 기기 사이로 기록을 옮기는 창 (기획서 49-1·49-2번).
 * 옛 기기: 10분짜리 코드를 받는다. 새 기기: 그 코드를 입력하면 옛 기기의 ID를 이어받고, 이 기기에 있던 결정도 함께 합쳐진다.
 */
export function TransferDialog({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState<'send' | 'receive'>('send');
  return <Modal open onClose={onClose} title="다른 기기로 기록 옮기기" description="로그인이 없어서, 코드로 이 브라우저의 기록을 다른 기기에서 이어서 볼 수 있어요." wide>
    <div className="status-tabs" role="tablist" aria-label="기록 옮기기 방법">
      <button role="tab" aria-selected={tab === 'send'} onClick={() => setTab('send')}>이 기기의 기록 보내기</button>
      <button role="tab" aria-selected={tab === 'receive'} onClick={() => setTab('receive')}>코드로 기록 받기</button>
    </div>
    {tab === 'send' ? <Send /> : <Receive />}
  </Modal>;
}

function Send() {
  const [issued, setIssued] = useState<Issued>(), [error, setError] = useState(''), [busy, setBusy] = useState(false), [left, setLeft] = useState(0);
  useEffect(() => {
    if (!issued) return;
    const tick = () => setLeft(Math.max(0, Math.round((Date.parse(issued.expiresAt) - Date.now()) / 1000)));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [issued]);
  const issue = async () => {
    setBusy(true); setError('');
    try { setIssued(await api.request('issueTransferCode', {}) as Issued); }
    catch (e) { setError(e instanceof Error ? e.message : '코드를 만들지 못했어요.'); }
    finally { setBusy(false); }
  };
  const expired = !!issued && left === 0;
  return <div className="transfer-panel">
    <p>다른 기기에서 <strong>‘코드로 기록 받기’</strong>를 열고 아래 코드를 입력하세요. 코드는 10분 동안 한 번만 쓸 수 있어요.</p>
    {error && <ErrorNotice message={error} />}
    {issued && !expired && <div className="transfer-code" role="status"><strong aria-label={`이동 코드 ${issued.code}`}>{issued.code}</strong><small>{Math.floor(left / 60)}분 {String(left % 60).padStart(2, '0')}초 남음</small></div>}
    {expired && <p role="status">코드가 만료됐어요. 새 코드를 받아주세요.</p>}
    <Button disabled={busy} onClick={() => void issue()}>{issued ? '새 코드 받기' : '이동 코드 받기'}</Button>
  </div>;
}

function Receive() {
  const [code, setCode] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [done, setDone] = useState<Claimed>();
  const claim = async () => {
    setBusy(true); setError('');
    try {
      const before = getClientId();
      const result = await api.request('claimTransferCode', { body: { code: code.trim() } }) as Claimed;
      moveNewDecisionDraft(before, result.clientId); // 이 기기에 저장해 둔 작성 중 임시 저장도 함께 옮긴다
      setClientId(result.clientId);
      setDone(result);
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'TRANSFER_CODE_INVALID' ? '코드가 올바르지 않거나 만료됐어요. 옛 기기에서 새 코드를 받아주세요.'
        : e instanceof Error ? e.message : '기록을 받지 못했어요.');
    } finally { setBusy(false); }
  };
  if (done) return <div className="transfer-panel"><p role="status">기록 {done.decisionCount}개를 이어받았어요.{done.movedDecisions > 0 ? ` (이 기기에 있던 ${done.movedDecisions}개도 함께 합쳤어요.)` : ''}</p>
    <Button onClick={() => window.location.reload()}>기록 보러 가기</Button></div>;
  return <div className="transfer-panel">
    <p>옛 기기의 <strong>‘이동 코드 받기’</strong>로 받은 코드를 입력하세요. 이 기기에 이미 있는 결정은 지워지지 않고 함께 합쳐져요.</p>
    {error && <ErrorNotice message={error} />}
    <Field label="이동 코드" hint="예: K7M2Q-9XA4B" value={code} autoComplete="off" autoCapitalize="characters" maxLength={16} onValueChange={setCode} />
    <Button disabled={busy || code.trim().length < 10} onClick={() => void claim()}>기록 받기</Button>
  </div>;
}
