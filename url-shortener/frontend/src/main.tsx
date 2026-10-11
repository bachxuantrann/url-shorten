import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth'
import './styles.css'

// HashRouter: toàn bộ giao diện nằm dưới "/#/...", nên không chiếm đường dẫn nào ở gốc domain
// (mỗi đường dẫn một đoạn như /abc123 là short code của hệ thống).
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </HashRouter>
  </StrictMode>,
)
