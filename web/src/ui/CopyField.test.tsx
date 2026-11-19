import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../i18n/i18n';
import { CopyField } from './CopyField';
import { ToastProvider } from './Toast';

function show() {
  render(
    <I18nProvider locale="fr" timeZone="UTC">
      <ToastProvider>
        <CopyField label="Lien d’invitation" value="https://lore.example.com/join#jeton" />
      </ToastProvider>
    </I18nProvider>,
  );
}

describe('a link to copy', () => {
  it('is copied, and says so', async () => {
    show();
    const browser = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, 'writeText');
    await browser.click(screen.getByRole('button', { name: 'Copier' }));
    expect(write).toHaveBeenCalledWith('https://lore.example.com/join#jeton');
    expect(await screen.findByText('Lien copié')).toBeInTheDocument();
  });

  it('is selected, to copy by hand, when the browser refuses the clipboard', async () => {
    show();
    const browser = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new DOMException('Refused', 'NotAllowedError'));
    await browser.click(screen.getByRole('button', { name: 'Copier' }));
    expect(await screen.findByText('Sélectionnez le lien, puis copiez-le.')).toBeInTheDocument();
    const field = screen.getByRole('textbox', { name: 'Lien d’invitation' });
    expect(field).toHaveFocus();
    expect((field as HTMLInputElement).selectionStart).toBe(0);
    expect((field as HTMLInputElement).selectionEnd).toBe('https://lore.example.com/join#jeton'.length);
  });
});
