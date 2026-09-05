import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getToken, clearToken } from './lib/auth';
import Login from './pages/Login';
import Today from './pages/Today';
import OrderDetail from './pages/OrderDetail';
import AddPlant from './pages/AddPlant';
import StockAdjust from './pages/StockAdjust';
import PayoutsAndQuality from './pages/PayoutsAndQuality';

function RequireAuth({ children }: { children: React.ReactNode }) {
  return getToken() ? <>{children}</> : <Navigate to="/login" replace />;
}

export default function App() {
  const { t, i18n } = useTranslation();
  const signedIn = !!getToken();

  return (
    <div className="app">
      {signedIn && (
        <nav className="tabbar">
          <NavLink to="/" end>{t('today')}</NavLink>
          <NavLink to="/add-plant">{t('addPlant')}</NavLink>
          <NavLink to="/stock-adjust">{t('stockAdjust')}</NavLink>
          <NavLink to="/payouts">{t('payouts')}</NavLink>
          <button className="lang-toggle" onClick={() => void i18n.changeLanguage(i18n.language === 'hi' ? 'en' : 'hi')}>
            {i18n.language === 'hi' ? 'EN' : 'हि'}
          </button>
          <button className="lang-toggle" onClick={clearToken}>⏻</button>
        </nav>
      )}

      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<RequireAuth><Today /></RequireAuth>} />
        <Route path="/orders/:orderId" element={<RequireAuth><OrderDetail /></RequireAuth>} />
        <Route path="/add-plant" element={<RequireAuth><AddPlant /></RequireAuth>} />
        <Route path="/stock-adjust" element={<RequireAuth><StockAdjust /></RequireAuth>} />
        <Route path="/payouts" element={<RequireAuth><PayoutsAndQuality /></RequireAuth>} />
      </Routes>
    </div>
  );
}
