import { useEffect, useRef, useState } from 'react';
import type { InputHTMLAttributes } from 'react';

export type InputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'defaultValue'> & {
  value?: string | number;
  onValueChange?: (value: string) => void;
};

/** Keep keystrokes synchronous and let the browser own an active IME session.
 * The persistence/query cache may notify later; its old value must not be
 * written back into the input while a Korean syllable is still composing.
 */
export function Input({ value = '', onValueChange, onCompositionStart, onCompositionEnd, onBlur, ...props }: InputProps) {
  const external = String(value);
  const [draft, setDraft] = useState(external);
  const composing = useRef(false);
  const lastPublished = useRef(external);

  useEffect(() => {
    if (!composing.current) {
      setDraft(external);
      lastPublished.current = external;
    }
  }, [external]);

  const publish = (next: string) => {
    if (lastPublished.current === next) return;
    lastPublished.current = next;
    onValueChange?.(next);
  };

  return <input {...props} value={draft}
    onChange={event => {
      const next = event.currentTarget.value;
      setDraft(next);
      if (!composing.current && !(event.nativeEvent as InputEvent).isComposing) publish(next);
    }}
    onCompositionStart={event => {
      composing.current = true;
      onCompositionStart?.(event);
    }}
    onCompositionEnd={event => {
      composing.current = false;
      const next = event.currentTarget.value;
      setDraft(next);
      publish(next);
      onCompositionEnd?.(event);
    }}
    onBlur={event => {
      if (composing.current) {
        composing.current = false;
        const next = event.currentTarget.value;
        setDraft(next);
        publish(next);
      }
      onBlur?.(event);
    }}
  />;
}
