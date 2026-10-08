'use client';
import { useEffect, useState } from 'react';
import { api } from '../lib/api';

type PostalResult = { valid: boolean; place?: string; district?: string; state?: string };

export function IndiaAddressFields() {
  const [postalCode, setPostalCode] = useState('');
  const [city, setCity] = useState('');
  const [district, setDistrict] = useState('');
  const [state, setState] = useState('');
  const [lookupStatus, setLookupStatus] = useState('');

  useEffect(() => {
    if (postalCode.length !== 6) {
      setLookupStatus('');
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setLookupStatus('Looking up PIN code…');
      try {
        const result = await api<PostalResult>(
          `/public/postal-codes/${encodeURIComponent(postalCode)}`,
          { signal: controller.signal },
        );
        if (controller.signal.aborted) return;
        if (!result.valid) {
          setLookupStatus('No match found. Enter the city, district and state manually.');
          return;
        }
        setCity(result.place ?? '');
        setDistrict(result.district ?? '');
        setState(result.state ?? '');
        setLookupStatus(
          'Area filled from PIN data. Please verify it; a PIN can cover multiple localities.',
        );
      } catch {
        if (!controller.signal.aborted)
          setLookupStatus(
            'PIN lookup is unavailable. Enter the city, district and state manually.',
          );
      }
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [postalCode]);

  return (
    <>
      <label>
        PIN code
        <input
          name="postalCode"
          inputMode="numeric"
          autoComplete="postal-code"
          pattern="[0-9]{6}"
          maxLength={6}
          value={postalCode}
          onChange={(event) => setPostalCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="6-digit PIN"
          required
        />
      </label>
      {lookupStatus && (
        <p className="form-hint address-lookup-status" role="status">
          {lookupStatus}
        </p>
      )}
      <label>
        City / town
        <input
          name="city"
          autoComplete="address-level2"
          value={city}
          onChange={(event) => setCity(event.target.value)}
          required
        />
      </label>
      <label>
        District
        <input
          name="district"
          value={district}
          onChange={(event) => setDistrict(event.target.value)}
          required
        />
      </label>
      <label>
        State / union territory
        <input
          name="state"
          autoComplete="address-level1"
          value={state}
          onChange={(event) => setState(event.target.value)}
          required
        />
      </label>
      <label>
        Country
        <input name="country" autoComplete="country-name" value="India" readOnly />
      </label>
      <p className="form-hint address-attribution">
        PIN suggestions: GeoNames data (CC BY 4.0) via{' '}
        <a href="https://github.com/ikarthikng/postalcodes-india" target="_blank" rel="noreferrer">
          postalcodes-india
        </a>
        . Check the suggested locality before saving.
      </p>
    </>
  );
}
