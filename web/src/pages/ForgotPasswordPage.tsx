import { Link } from 'react-router';
import { useI18n } from '../i18n/i18n';
import { AuthLayout } from '../layouts/AuthLayout';
import { useDocumentTitle } from '../ui/bits';
import { Callout } from '../ui/forms';

/**
 * Without a mail server, an administrator generates the reset link. The page names nobody: a
 * visitor who is not signed in learns neither who administers the organization nor their address.
 */
export function ForgotPasswordPage() {
  const { t } = useI18n();
  useDocumentTitle(t('forgot.docTitle'));
  return (
    <AuthLayout>
      <h1>{t('forgot.title')}</h1>
      <p className="lead">{t('forgot.lead')}</p>
      <div className="auth-panel">
        <div className="form">
          <Callout>{t('forgot.noMail')}</Callout>
          <div className="field">
            <p className="help">{t('forgot.owner')}</p>
            <code className="command">{t('forgot.ownerCommand')}</code>
          </div>
        </div>
      </div>
      <p className="auth-alt"><Link className="link" to="/login">{t('common.backToLogin')}</Link></p>
    </AuthLayout>
  );
}
