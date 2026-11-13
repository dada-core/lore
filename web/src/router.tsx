import { createBrowserRouter, Navigate, Outlet, type RouteObject } from 'react-router';
import { AppShell } from './layouts/AppShell';
import { AccountPage } from './pages/AccountPage';
import { GeneralPage } from './pages/admin/GeneralPage';
import { MembersPage } from './pages/admin/MembersPage';
import { SecurityPage } from './pages/admin/SecurityPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { HomePage } from './pages/HomePage';
import { JoinPage } from './pages/JoinPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ResetPasswordPage } from './pages/ResetPasswordPage';
import { SetupPage } from './pages/SetupPage';
import { CrashPage, Root } from './Root';
import { GuestOnly, RequireAdmin, RequireUser } from './session/guards';

/**
 * The addresses of the interface, all in English whatever the language of the page. The server
 * answers every one of them with index.html; the links it generates point to /join and
 * /reset-password.
 */
export const routes: RouteObject[] = [
  {
    element: <Root />,
    errorElement: <CrashPage />,
    children: [
      { path: 'setup', element: <SetupPage /> },
      { path: 'login', element: <GuestOnly><LoginPage /></GuestOnly> },
      { path: 'forgot-password', element: <GuestOnly><ForgotPasswordPage /></GuestOnly> },
      { path: 'reset-password', element: <ResetPasswordPage /> },
      { path: 'join', element: <JoinPage /> },
      {
        element: <RequireUser><AppShell /></RequireUser>,
        children: [
          { index: true, element: <HomePage /> },
          { path: 'account', element: <AccountPage /> },
          {
            path: 'admin',
            element: <RequireAdmin><Outlet /></RequireAdmin>,
            children: [
              { index: true, element: <Navigate to="/admin/general" replace /> },
              { path: 'general', element: <GeneralPage /> },
              { path: 'members', element: <MembersPage /> },
              { path: 'security', element: <SecurityPage /> },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export function createRouter() {
  return createBrowserRouter(routes);
}
