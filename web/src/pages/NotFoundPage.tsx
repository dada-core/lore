import { Link } from 'react-router';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { useOpenPalette } from '../search/Palette';
import { useDocumentTitle } from '../ui/bits';
import { Icon, Logo } from '../ui/Icon';

/**
 * An address that leads nowhere, or to something the person may not see: the same page in both
 * cases, so that it reveals nothing.
 */
export function NotFoundPage() {
  const { t } = useI18n();
  const openPalette = useOpenPalette();
  useDocumentTitle(t('notFound.docTitle'));
  return (
    <View crumbs={[{ label: t('notFound.docTitle') }]}>
      <div className="empty" style={{ paddingTop: 96 }}>
        <Logo size={34} />
        <h1 className="page-title">{t('notFound.title')}</h1>
        <p>{t('notFound.body')}</p>
        <div className="actions">
          <Link className="btn btn-primary" to="/">{t('notFound.home')}</Link>
          <button type="button" className="btn" onClick={openPalette}><Icon name="search" />{t('notFound.search')}</button>
        </div>
        <p className="help">{t('notFound.why')}</p>
      </div>
    </View>
  );
}
