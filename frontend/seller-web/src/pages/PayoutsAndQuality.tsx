import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchMySellerId, fetchQuality, setVacationMode, type SellerQualityView } from '../lib/api';

/** "Statement per cycle, score with the component breakdown" (56.1's #5 screen) plus vacation mode. Payout statement fetching is left out of this first cut - it needs the specific cycle id PayoutStatementController (Week 8) is keyed on, which nothing in this app's own flow surfaces yet; the quality half (the genuinely new Week 10 feature) is real and complete. */
export default function PayoutsAndQuality() {
  const { t } = useTranslation();
  const [sellerId, setSellerId] = useState<string | null>(null);
  const [quality, setQuality] = useState<SellerQualityView | null>(null);
  const [onVacation, setOnVacation] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchMySellerId().then(async (id) => {
      setSellerId(id);
      try {
        setQuality(await fetchQuality(id));
      } catch {
        setError('No quality score computed yet - this needs at least one real dispatch or delivery on record.');
      }
    });
  }, []);

  async function toggleVacation() {
    if (!sellerId) return;
    const next = !onVacation;
    await setVacationMode(sellerId, next);
    setOnVacation(next);
  }

  return (
    <div className="screen">
      <h1>{t('payouts')} / {t('quality')}</h1>

      <section>
        <button className="big-button" onClick={() => void toggleVacation()}>
          {t('vacationMode')}: {onVacation ? 'ON' : 'OFF'}
        </button>
      </section>

      <section>
        <h2>{t('quality')}</h2>
        {error && <p className="status">{error}</p>}
        {quality && (
          <>
            <p className="tier-badge">{quality.tier} — {(quality.score * 100).toFixed(0)}%</p>
            {quality.isProvisional && <p className="status">Still building your real record ({quality.orderCount} orders so far).</p>}
            <ul>
              {Object.entries(quality.components).map(([name, c]) => (
                <li key={name}>
                  <strong>{name}</strong>: {c.numerator} of {c.denominator} — {c.description}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </div>
  );
}
