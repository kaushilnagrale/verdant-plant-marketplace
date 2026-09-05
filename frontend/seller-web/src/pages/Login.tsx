import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { setToken } from '../lib/auth';

/** See lib/auth.ts's own comment - a real token pasted here, not a real OIDC flow, which is genuinely separate, larger work this scope doesn't cover. */
export default function Login() {
  const { t } = useTranslation();
  const [token, setTokenValue] = useState('');
  const navigate = useNavigate();

  function signIn() {
    setToken(token.trim());
    navigate('/');
  }

  return (
    <div className="screen">
      <h1>Verdant Seller</h1>
      <label>
        Access token
        <textarea rows={4} value={token} onChange={(e) => setTokenValue(e.target.value)}
          placeholder="Paste the token from scripts/token.sh seller-a" />
      </label>
      <button className="big-button" disabled={!token.trim()} onClick={signIn}>
        {t('signIn')}
      </button>
    </div>
  );
}
