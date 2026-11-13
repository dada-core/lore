/* Applies the theme chosen in My account before the page is drawn, to avoid a flash of the
   other theme. The interface keeps it in sync afterwards (src/ui/theme.ts). */
(function () {
  try {
    var theme = localStorage.getItem('lore.theme');
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.setAttribute('data-theme', theme);
    }
  } catch (e) {
    /* Storage unavailable: the system theme applies. */
  }
})();
