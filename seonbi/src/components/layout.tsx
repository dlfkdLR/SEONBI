import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useEffect } from 'react';
import { useOwner } from '../data/hooks';
import { blankDecision } from '../data/examples';
import { repository } from '../data/local-repository';
import { queryClient } from '../data/hooks';
import { useUI } from '../lib/state';
import { Toast } from './ui';

export function useNewDecision() {
  const owner = useOwner(), navigate = useNavigate(), notify = useUI(s => s.notify);
  return () => {
    try { const d = blankDecision(owner); repository.save(owner, d); void queryClient.invalidateQueries({ queryKey: ['decisions', owner] }); navigate(`/decisions/${d.id}/setup/1`); }
    catch (e) { notify(e instanceof Error ? e.message : '결정을 만들지 못했어요.'); }
  };
}
export function Layout() {
  const create = useNewDecision(), location = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  return <><a className="skip-link" href="#main">본문으로 바로가기</a><header className="site-header"><div className="header-inner"><Link className="brand" to="/" aria-label="선비 홈">선비</Link><nav aria-label="주 메뉴">
    <NavLink to="/" end>내 결정</NavLink><button onClick={create}>새 결정</button>
  </nav></div></header><Outlet /><Toast /></>;
}
