import '@fontsource-variable/inter';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthProvider } from './auth/AuthProvider';
import { ToastProvider } from './components/ui/Toast';
import { I18nProvider } from './i18n/I18nProvider';
import { ThemeProvider } from './theme/ThemeProvider';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <ThemeProvider>
        <ToastProvider>
          <AuthProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </AuthProvider>
        </ToastProvider>
      </ThemeProvider>
    </I18nProvider>
  </StrictMode>,
);
