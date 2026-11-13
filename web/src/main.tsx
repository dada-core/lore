import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { setUnauthenticatedHandler } from './api/client';
import { queryClient } from './api/queryClient';
import { createRouter } from './router';
import { expireSession } from './session/session';
import './styles/tokens.css';
import './styles/app.css';
import { ToastProvider } from './ui/Toast';

// A 401 from the server: the session has expired; the screens then lead back to the sign-in page.
setUnauthenticatedHandler(expireSession);

const container = document.getElementById('root');
if (!container) throw new Error('Missing #root');

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={createRouter()} />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
