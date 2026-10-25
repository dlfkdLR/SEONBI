import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useEffect } from 'react';
import { Toast } from './ui';
export function useNewDecision() {
  const navigate = useNavigate();
  return () => navigate('/decisions/new/setup/1');
}

export function Layout() {
  const create = useNewDecision(), location = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  return <><a className="skip-link" href="#main">본문으로 바로가기</a><header className="site-header"><div className="header-inner"><Link className="brand" to="/" aria-label="선비 홈">선비</Link><nav aria-label="주 메뉴">
    <NavLink to="/" end>공유 결정</NavLink><button onClick={create}>새 결정</button>
  </nav></div></header><Outlet /><Toast /></>;
}
