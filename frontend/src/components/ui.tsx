import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Input } from './input';
import type { InputProps } from './input';
import * as Dialog from '@radix-ui/react-dialog';
import { useEffect, useId } from 'react';
import { useUI } from '../lib/state';

export function Button({ secondary = false, className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { secondary?: boolean }) {
  return <button className={`button ${secondary ? 'button-secondary' : ''} ${className}`} {...props} />;
}
export function TextButton({ className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button className={`text-button ${className}`} {...props} />;
}
export function Field({ label, hint, className = '', ...props }: InputProps & { label: string; hint?: string }) {
  const generated = useId(), id = props.id ?? generated;
  return <div className={`field ${className}`}><label htmlFor={id}>{label}</label><Input {...props} id={id} aria-describedby={hint ? `${id}-hint` : undefined} />{hint && <small id={`${id}-hint`}>{hint}</small>}</div>;
}
export function Modal({ title, description, open, onClose, children, wide = false }: { title: string; description?: string; open: boolean; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return <Dialog.Root open={open} onOpenChange={v => { if (!v) onClose(); }}><Dialog.Portal><Dialog.Overlay className="modal-overlay" /><Dialog.Content className={`modal ${wide ? 'modal-wide' : ''}`}>
    <Dialog.Title className="section-title">{title}</Dialog.Title>{description ? <Dialog.Description className="muted leading-7">{description}</Dialog.Description> : <Dialog.Description className="sr-only">항목을 입력하고 저장하거나 취소하세요.</Dialog.Description>}
    {children}<Dialog.Close className="modal-close" aria-label="닫기">×</Dialog.Close>
  </Dialog.Content></Dialog.Portal></Dialog.Root>;
}
export function PageHeading({ context, title, description }: { context?: string; title: string; description?: string }) {
  return <header className="page-heading">{context && <p className="eyebrow muted">{context}</p>}<h1>{title}</h1>{description && <p className="muted">{description}</p>}</header>;
}
export function Toast() {
  const { toast, dismiss } = useUI();
  useEffect(() => { if (!toast) return; const timer = setTimeout(dismiss, 4500); return () => clearTimeout(timer); }, [toast, dismiss]);
  return toast ? <div className="toast" role="status">{toast}<button onClick={dismiss} aria-label="알림 닫기">×</button></div> : null;
}
export function ErrorNotice({ message }: { message: string }) { return <p role="alert" className="error-notice">{message}</p>; }
export function Loading({ label = '불러오고 있어요.' }: { label?: string }) {
  // 빈 화면 대신 자리만 잡은 막대(스켈레톤)를 보여 줘서 무언가 로딩 중임이 보이게 한다
  return <main className="page" id="main" aria-busy="true"><div className="page-content loading-skeleton"><p className="muted" role="status">{label}</p><div className="skeleton skeleton-title" /><div className="skeleton skeleton-line" /><div className="skeleton skeleton-line short" /><div className="skeleton skeleton-block" /></div></main>;
}
