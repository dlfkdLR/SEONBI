import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { useEffect } from 'react';
import { Toast } from './ui';
export function useNewDecision() {
  const navigate = useNavigate();
  return () => navigate('/decisions/new/setup/1');
}
export function Layout() {
  const location = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  return <><a className="skip-link" href="#main">본문으로 바로가기</a><header className="site-header"><div className="header-inner"><Link className="brand" to="/" aria-label="선비 홈">선비</Link><nav aria-label="주 메뉴"><Link to="/decisions" aria-current={location.pathname === '/decisions' ? 'page' : undefined}>내 결정</Link></nav></div></header><Outlet /><Toast /></>;
}
