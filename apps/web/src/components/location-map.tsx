import { useEffect, useRef, useState } from 'react';

export function openStreetMapUrl(latitude: number, longitude: number) {
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=18/${latitude}/${longitude}`;
}

export function openStreetMapDirectionsUrl(latitude: number, longitude: number) {
  return `https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=;${latitude},${longitude}`;
}

export function openStreetMapSearchUrl(query: string) {
  return `https://www.openstreetmap.org/search?query=${encodeURIComponent(query)}`;
}

export function LocationPicker({
  latitude,
  longitude,
  onSelect,
  focusVersion = 0,
}: {
  latitude: number | null;
  longitude: number | null;
  onSelect: (latitude: number, longitude: number) => void;
  focusVersion?: number;
}) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<import('leaflet').Map | null>(null);
  const marker = useRef<import('leaflet').Marker | null>(null);
  const placeMarker = useRef<((latitude: number, longitude: number) => void) | null>(null);
  const onSelectRef = useRef(onSelect);
  const locationRef = useRef({ latitude, longitude });
  const [ready, setReady] = useState(false);
  onSelectRef.current = onSelect;
  locationRef.current = { latitude, longitude };

  useEffect(() => {
    let disposed = false;
    let createdMap: import('leaflet').Map | undefined;
    void import('leaflet').then((leaflet) => {
      if (disposed || !element.current) return;
      const L = leaflet.default;
      const selected = locationRef.current;
      const hasPin = selected.latitude !== null && selected.longitude !== null;
      const initialLatitude = selected.latitude ?? 22.5;
      const initialLongitude = selected.longitude ?? 79;
      createdMap = L.map(element.current, { scrollWheelZoom: false, zoomControl: true }).setView(
        hasPin ? [initialLatitude, initialLongitude] : [22.5, 79],
        hasPin ? 16 : 5,
      );
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>',
      }).addTo(createdMap);
      map.current = createdMap;
      placeMarker.current = (lat, lon) => {
        if (!map.current) return;
        if (!marker.current) {
          marker.current = L.marker([lat, lon], {
            draggable: true,
            icon: L.divIcon({
              className: 'swarm-map-marker',
              html: '<span class="swarm-map-pin" aria-hidden="true"></span>',
              iconSize: [28, 28],
              iconAnchor: [14, 14],
            }),
          }).addTo(map.current);
          marker.current.on('dragend', () => {
            const selected = marker.current?.getLatLng();
            if (selected) onSelectRef.current(selected.lat, selected.lng);
          });
        } else marker.current.setLatLng([lat, lon]);
        onSelectRef.current(lat, lon);
      };
      createdMap.on('click', (event) => {
        placeMarker.current?.(event.latlng.lat, event.latlng.lng);
      });
      if (hasPin) placeMarker.current(initialLatitude, initialLongitude);
      setReady(true);
    });
    return () => {
      disposed = true;
      createdMap?.remove();
      map.current = null;
      marker.current = null;
      placeMarker.current = null;
    };
    // The picker initializes once; subsequent selected points are applied below.
  }, []);

  useEffect(() => {
    if (!ready || latitude === null || longitude === null) return;
    placeMarker.current?.(latitude, longitude);
    // Applying a selected point must not zoom the map away from the area the user tapped.
  }, [latitude, longitude, ready]);

  useEffect(() => {
    const selected = locationRef.current;
    if (!ready || !map.current || selected.latitude === null || selected.longitude === null) return;
    map.current.setView([selected.latitude, selected.longitude], 16);
  }, [focusVersion, ready]);

  return (
    <div className="location-picker-shell">
      {!ready && <p className="form-hint">Loading map…</p>}
      <div
        className="location-picker"
        ref={element}
        aria-label="Interactive map. Tap to place a marker, or drag the marker to adjust it."
      />
    </div>
  );
}

export function LocationMap({
  latitude,
  longitude,
  label = 'Selected point',
}: {
  latitude: number;
  longitude: number;
  label?: string;
}) {
  const span = 0.003;
  const params = new URLSearchParams({
    bbox: [longitude - span, latitude - span, longitude + span, latitude + span].join(','),
    layer: 'mapnik',
    marker: `${latitude},${longitude}`,
  });
  return (
    <div className="location-map">
      <iframe
        title={`OpenStreetMap location at ${latitude.toFixed(6)}, ${longitude.toFixed(6)}`}
        src={`https://www.openstreetmap.org/export/embed.html?${params}`}
        loading="lazy"
        referrerPolicy="origin"
      />
      <div className="location-map-caption">
        <span>
          {label} · {latitude.toFixed(6)}, {longitude.toFixed(6)}
        </span>
        <div className="map-links">
          <a href={openStreetMapUrl(latitude, longitude)} target="_blank" rel="noreferrer">
            View map
          </a>
          <a
            href={openStreetMapDirectionsUrl(latitude, longitude)}
            target="_blank"
            rel="noreferrer"
          >
            Get directions
          </a>
        </div>
      </div>
    </div>
  );
}
