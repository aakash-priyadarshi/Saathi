type PublicAddress = {
  addressLine1: string;
  locality: string;
  landmark?: string;
  postalCode: string;
  city: string;
  district: string;
  state: string;
  country: string;
};

export function formatPublicAddress(address: PublicAddress) {
  const district =
    address.district.trim().toLocaleLowerCase('en-IN') ===
    address.city.trim().toLocaleLowerCase('en-IN')
      ? []
      : [address.district];
  return [
    address.addressLine1,
    address.locality,
    ...(address.landmark ? [address.landmark] : []),
    `PIN ${address.postalCode}`,
    address.city,
    ...district,
    address.state,
    address.country,
  ]
    .map((part) => part.trim())
    .filter(Boolean)
    .join(', ');
}
