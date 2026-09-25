import { createContext } from 'react';
import type { MutableRefObject, RefObject } from 'react';

export type DesktopSearchHandlers = {
  change: (keyword: string) => void;
  submit: (keyword: string) => void;
  clear: () => void;
  compositionStart: () => void;
  compositionEnd: (keyword: string) => void;
};

export type DesktopSearchContextValue = {
  keyword: string;
  setKeyword: (keyword: string) => void;
  handlers: MutableRefObject<DesktopSearchHandlers | null>;
  inputRef: RefObject<HTMLInputElement | null>;
  setHandlers: (handlers: DesktopSearchHandlers | null) => void;
};

export const DesktopSearchContext = createContext<DesktopSearchContextValue | null>(null);
