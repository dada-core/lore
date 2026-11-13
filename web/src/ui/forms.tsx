import { useEffect, useId, type InputHTMLAttributes, type ReactNode, type Ref, type RefObject, type SelectHTMLAttributes } from 'react';
import { ApiError } from '../api/client';
import { fieldMessage } from '../i18n/errors';
import type { I18n } from '../i18n/i18n';
import { Icon } from './Icon';

/** A field to correct, found in the browser or sent by the server. */
export interface Problem {
  code: string;
  params?: Record<string, string | number>;
}
export type Problems = Partial<Record<string, Problem>>;

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/** The same checks as the server, so that most mistakes are shown before anything is sent. */
export const checks = {
  required(value: string, max = 120): Problem | null {
    if (!value.trim()) return { code: 'required' };
    return value.trim().length > max ? { code: 'too_long', params: { max } } : null;
  },
  email(value: string): Problem | null {
    if (!value.trim()) return { code: 'required' };
    return EMAIL.test(value.trim()) ? null : { code: 'invalid_email' };
  },
  url(value: string): Problem | null {
    if (!value.trim()) return null;
    try {
      const url = new URL(value.trim());
      return (url.protocol === 'http:' || url.protocol === 'https:') && !url.search && !url.hash && !url.username
        ? null
        : { code: 'invalid_url' };
    } catch {
      return { code: 'invalid_url' };
    }
  },
  password(value: string, min: number): Problem | null {
    if (!value) return { code: 'required' };
    if (value.length < min) return { code: 'too_short', params: { min } };
    return value.length > 128 ? { code: 'too_long', params: { max: 128 } } : null;
  },
  confirmation(value: string, password: string): Problem | null {
    if (!value) return { code: 'required' };
    return value === password ? null : { code: 'passwords_differ' };
  },
};

/** Keeps the problems found, field by field: validate({ email: checks.email(email) }). */
export function validate(found: Record<string, Problem | null>): Problems {
  const problems: Problems = {};
  for (const [field, problem] of Object.entries(found)) {
    if (problem) problems[field] = problem;
  }
  return problems;
}

export function hasProblems(problems: Problems): boolean {
  return Object.keys(problems).length > 0;
}

/** The field errors of a 400 response, by field name. */
export function problemsOf(error: unknown): Problems {
  const problems: Problems = {};
  if (error instanceof ApiError) {
    for (const fieldError of error.fieldErrors) {
      problems[fieldError.field] ??= { code: fieldError.code, params: fieldError.params };
    }
  }
  return problems;
}

/** Moves the focus to the first field to correct, after a refused submission. */
export function useFocusFirstProblem(form: RefObject<HTMLElement | null>, problems: Problems): void {
  useEffect(() => {
    if (!hasProblems(problems)) return;
    const invalid = form.current?.querySelector<HTMLElement>('[aria-invalid="true"]');
    invalid?.focus();
  }, [form, problems]);
}

export function message(i18n: I18n, problem: Problem | undefined): string | null {
  return problem ? fieldMessage(i18n, { code: problem.code, params: problem.params ?? {} }) : null;
}

function describedBy(...ids: (string | false | undefined | null)[]): string | undefined {
  const value = ids.filter(Boolean).join(' ');
  return value || undefined;
}

export function FieldError({ id, children }: { id: string; children: ReactNode }) {
  return (
    <p className="field-error" id={id}>
      <Icon name="alert" />
      <span>{children}</span>
    </p>
  );
}

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  label: string;
  help?: ReactNode;
  error?: string | null;
  mono?: boolean;
  /** Shown at the end of the label line, for example a "Forgot your password?" link. */
  aside?: ReactNode;
  ref?: Ref<HTMLInputElement>;
}

export function TextField({ id, label, help: givenHelp, error, mono, aside, ref, ...input }: TextFieldProps) {
  const generated = useId();
  const inputId = id ?? generated;
  const errorId = `${inputId}-error`;
  const helpId = `${inputId}-help`;
  // "At least 12 characters" once is enough, when the error says just that.
  const help = givenHelp === error ? null : givenHelp;
  return (
    <div className="field">
      {aside ? (
        <div className="label-row">
          <label className="field-label" htmlFor={inputId}>{label}</label>
          {aside}
        </div>
      ) : (
        <label htmlFor={inputId}>{label}</label>
      )}
      <input
        ref={ref}
        id={inputId}
        className={mono ? 'input input-mono' : 'input'}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(error ? errorId : null, help ? helpId : null)}
        {...input}
      />
      {error && <FieldError id={errorId}>{error}</FieldError>}
      {help && <p className="help" id={helpId}>{help}</p>}
    </div>
  );
}

interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className'> {
  label: string;
  help?: ReactNode;
  error?: string | null;
}

export function SelectField({ id, label, help, error, children, ...select }: SelectFieldProps) {
  const generated = useId();
  const selectId = id ?? generated;
  return (
    <div className="field">
      <label htmlFor={selectId}>{label}</label>
      <select
        id={selectId}
        className="select"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(error ? `${selectId}-error` : null, help ? `${selectId}-help` : null)}
        {...select}
      >
        {children}
      </select>
      {error && <FieldError id={`${selectId}-error`}>{error}</FieldError>}
      {help && <p className="help" id={`${selectId}-help`}>{help}</p>}
    </div>
  );
}

/**
 * A line of a settings box: the label and its help on the left, the control on the right.
 * The control receives the identifiers it must carry.
 */
export function SettingRow({ label, help: givenHelp, error, children }: {
  label: string;
  help?: ReactNode;
  error?: string | null;
  children: (ids: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: true }) => ReactNode;
}) {
  const id = useId();
  const help = givenHelp === error ? null : givenHelp;
  return (
    <div className="set-row">
      <div className="lbl">
        <label htmlFor={id}>{label}</label>
        {help && <p className="help" id={`${id}-help`}>{help}</p>}
      </div>
      <div className="ctl">
        {children({
          id,
          'aria-describedby': describedBy(error ? `${id}-error` : null, help ? `${id}-help` : null),
          'aria-invalid': error ? true : undefined,
        })}
        {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
      </div>
    </div>
  );
}

export function Callout({ tone = 'info', children, role }: { tone?: 'info' | 'warn' | 'error' | 'ok'; children: ReactNode; role?: 'alert' | 'status' }) {
  const icon = tone === 'info' ? 'info' : tone === 'ok' ? 'check-circle' : 'alert';
  return (
    <div className={tone === 'info' ? 'callout' : `callout callout-${tone}`} role={role}>
      <Icon name={icon} />
      <div>{children}</div>
    </div>
  );
}
