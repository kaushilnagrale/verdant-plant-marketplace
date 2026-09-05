import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchMyListings, adjustStock, type MyListing } from '../lib/api';

const REASONS = ['RESTOCK', 'MORTALITY', 'CORRECTION', 'OFFLINE_SALE'] as const;

/** "One tap decrement with a reason" (56.1's #4 screen) - reason is a real, fixed choice from inventory_adjustments' own CHECK-constrained vocabulary (Week 4/10), not free text. */
export default function StockAdjust() {
  const { t } = useTranslation();
  const [listings, setListings] = useState<MyListing[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [delta, setDelta] = useState('1');
  const [reason, setReason] = useState<(typeof REASONS)[number]>('RESTOCK');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void fetchMyListings().then(setListings);
  }, []);

  async function apply(sign: 1 | -1) {
    if (!selected) return;
    setSaving(true);
    try {
      await adjustStock(selected, sign * Math.abs(parseInt(delta, 10) || 0), reason);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="screen">
      <h1>{t('stockAdjust')}</h1>
      <label>
        Plant
        <select value={selected} onChange={(e) => setSelected(e.target.value)}>
          <option value="">Choose…</option>
          {listings.map((l) => (
            <option key={l.id} value={l.id}>{l.rawTitle}</option>
          ))}
        </select>
      </label>
      <label>
        Amount
        <input value={delta} onChange={(e) => setDelta(e.target.value)} inputMode="numeric" />
      </label>
      <label>
        Reason
        <select value={reason} onChange={(e) => setReason(e.target.value as (typeof REASONS)[number])}>
          {REASONS.map((r) => (
            <option key={r} value={r}>{r}</option>
          ))}
        </select>
      </label>
      <div className="button-row">
        <button className="big-button" disabled={saving || !selected} onClick={() => void apply(1)}>+ Add</button>
        <button className="big-button" disabled={saving || !selected} onClick={() => void apply(-1)}>− Remove</button>
      </div>
    </div>
  );
}
