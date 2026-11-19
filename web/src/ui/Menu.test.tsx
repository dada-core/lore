import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Menu, MenuItem } from './Menu';

describe('a menu', () => {
  it('closes with Escape when it was opened with the mouse, the focus left on its button', async () => {
    render(
      <Menu label="Catégorie" trigger="Toutes les catégories">
        <MenuItem onSelect={() => {}}>Runbook</MenuItem>
      </Menu>,
    );
    const browser = userEvent.setup();
    const button = screen.getByRole('button', { name: 'Catégorie' });
    await browser.click(button);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(button).toHaveFocus();

    await browser.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });

  it('closes with Escape when the focus is elsewhere on the page, as Safari leaves it', async () => {
    render(
      <Menu label="Catégorie" trigger="Toutes les catégories">
        <MenuItem onSelect={() => {}}>Runbook</MenuItem>
      </Menu>,
    );
    const browser = userEvent.setup();
    await browser.click(screen.getByRole('button', { name: 'Catégorie' }));
    (document.activeElement as HTMLElement).blur();
    await browser.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
