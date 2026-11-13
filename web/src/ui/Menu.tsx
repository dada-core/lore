import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router';
import { Icon, type IconName } from './Icon';

const CloseMenu = createContext<() => void>(() => {});

interface MenuProps {
  /** Content of the button that opens the menu. */
  trigger: ReactNode;
  /** Accessible name of the button, when its content is only an icon. */
  label?: string;
  triggerClassName?: string;
  align?: 'start' | 'end';
  children: ReactNode;
}

const items = (popover: HTMLElement | null) =>
  Array.from(popover?.querySelectorAll<HTMLElement>('[role="menuitem"], [role="menuitemradio"]') ?? []);

/**
 * A button that opens a list of actions. The list is placed against the viewport, so that a grid
 * that scrolls does not cut it; it closes on a click elsewhere, Escape, Tab or a scroll.
 */
export function Menu({ trigger, label, triggerClassName = 'icon-btn', align = 'start', children }: MenuProps) {
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<{ top: number; left?: number; right?: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const focusFirst = useRef(false);
  const menuId = useId();
  const triggerId = useId();

  const close = useCallback((returnFocus = true) => {
    setOpen(false);
    setPosition(null);
    if (returnFocus) triggerRef.current?.focus();
  }, []);

  const toggle = (byKeyboard: boolean) => {
    if (open) {
      close(false);
      return;
    }
    const rect = triggerRef.current?.getBoundingClientRect();
    if (!rect) return;
    focusFirst.current = byKeyboard;
    setPosition(align === 'end'
      ? { top: rect.bottom + 6, right: window.innerWidth - rect.right }
      : { top: rect.bottom + 6, left: rect.left });
    setOpen(true);
  };

  // Opens upwards when the list would leave the viewport at the bottom.
  useLayoutEffect(() => {
    const popover = popoverRef.current;
    const rect = triggerRef.current?.getBoundingClientRect();
    if (!open || !popover || !rect) return;
    const height = popover.offsetHeight;
    if (rect.bottom + 6 + height > window.innerHeight && rect.top - 6 - height > 0) {
      popover.style.top = `${rect.top - 6 - height}px`;
    }
    if (focusFirst.current) items(popover)[0]?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!popoverRef.current?.contains(target) && !triggerRef.current?.contains(target)) close(false);
    };
    // A scroll that moves the button closes the list, which would otherwise float away from it.
    const openedAt = triggerRef.current?.getBoundingClientRect();
    const onScroll = (event: Event) => {
      if (event.target instanceof Node && popoverRef.current?.contains(event.target)) return;
      const now = triggerRef.current?.getBoundingClientRect();
      if (!openedAt || !now || Math.abs(now.top - openedAt.top) > 1 || Math.abs(now.left - openedAt.left) > 1) close(false);
    };
    const onResize = () => close(false);
    document.addEventListener('pointerdown', onPointer);
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onResize);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onResize);
    };
  }, [open, close]);

  const onKeyDown = (event: ReactKeyboardEvent) => {
    const list = items(popoverRef.current);
    const index = list.indexOf(document.activeElement as HTMLElement);
    const move = (to: number) => {
      event.preventDefault();
      list[(to + list.length) % list.length]?.focus();
    };
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
    } else if (event.key === 'Tab') {
      close(false);
    } else if (event.key === 'ArrowDown') move(index + 1);
    else if (event.key === 'ArrowUp') move(index - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(list.length - 1);
  };

  return (
    <div className="menu">
      <button
        ref={triggerRef}
        id={triggerId}
        type="button"
        className={triggerClassName}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={label}
        onClick={(event) => toggle(event.detail === 0)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' && !open) {
            event.preventDefault();
            toggle(true);
          }
        }}
      >
        {trigger}
      </button>
      {open && position && createPortal(
        <div
          ref={popoverRef}
          id={menuId}
          className="popover"
          role="menu"
          aria-label={label}
          aria-labelledby={label ? undefined : triggerId}
          style={{ top: position.top, left: position.left, right: position.right }}
          onKeyDown={onKeyDown}
        >
          <CloseMenu value={() => close()}>{children}</CloseMenu>
        </div>,
        document.body,
      )}
    </div>
  );
}

interface ItemProps {
  icon?: IconName;
  danger?: boolean;
  children: ReactNode;
}

export function MenuItem({ icon, danger, children, onSelect, checked }: ItemProps & { onSelect: () => void; checked?: boolean }) {
  const close = useContext(CloseMenu);
  return (
    <button
      type="button"
      role={checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={checked}
      tabIndex={-1}
      className={danger ? 'menu-item danger' : 'menu-item'}
      onClick={() => {
        close();
        onSelect();
      }}
    >
      {icon && <Icon name={icon} />}
      {children}
    </button>
  );
}

export function MenuLink({ icon, children, to }: ItemProps & { to: string }) {
  const close = useContext(CloseMenu);
  return (
    <Link role="menuitem" tabIndex={-1} className="menu-item" to={to} onClick={() => close()}>
      {icon && <Icon name={icon} />}
      {children}
    </Link>
  );
}

export function MenuSeparator() {
  return <div className="menu-sep" role="separator" />;
}
