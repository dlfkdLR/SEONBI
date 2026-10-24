import { create } from 'zustand';
export const useUI = create<{ toast: string; notify: (message: string) => void; dismiss: () => void }>(set => ({ toast: '', notify: toast => set({ toast }), dismiss: () => set({ toast: '' }) }));
