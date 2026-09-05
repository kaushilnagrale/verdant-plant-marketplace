import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createListing, adjustStock } from '../lib/api';

/**
 * "Under sixty seconds end to end, or she will not add plants" (56.1's
 * own hard target) - two fields (name, price) plus an initial stock
 * count, one save button. Real photo upload already exists on the
 * backend (ListingImageService/S3, Week 3) but is deliberately left out
 * of this first cut: camera capture + upload UX is a real, separate
 * chunk of work on its own, and every screen here is scoped to the real
 * API calls this session had time to wire and verify - adding it later
 * doesn't change this screen's own flow.
 */
export default function AddPlant() {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  const [stock, setStock] = useState('1');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  async function save() {
    setSaving(true);
    try {
      const listing = await createListing(name, price);
      const initialStock = parseInt(stock, 10);
      if (initialStock > 0) {
        await adjustStock(listing.id, initialStock, 'RESTOCK');
      }
      setSaved(true);
      setName('');
      setPrice('');
      setStock('1');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="screen">
      <h1>{t('addPlant')}</h1>
      <label>
        Plant name
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Money Plant" />
      </label>
      <label>
        Price (₹)
        <input value={price} onChange={(e) => setPrice(e.target.value)} inputMode="decimal" placeholder="349.00" />
      </label>
      <label>
        Stock
        <input value={stock} onChange={(e) => setStock(e.target.value)} inputMode="numeric" />
      </label>
      <button className="big-button" disabled={saving || !name || !price} onClick={() => void save()}>
        {t('save')}
      </button>
      {saved && <p className="status">✓ Added — it will be reviewed and go live shortly.</p>}
    </div>
  );
}
