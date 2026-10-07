#!/bin/sh
# Builds CJP Swarm with the paid team and uploads it to TestFlight, signed through an App Store Connect Team API key.
#   ASC_KEY_ID=... ASC_ISSUER_ID=... APPLE_TEAM_ID=... apps/ios/scripts/testflight.sh [upload]
# The key must be at ~/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8. "upload" reuses the last archive.
set -eu
: "${ASC_KEY_ID:?}" "${ASC_ISSUER_ID:?}" "${APPLE_TEAM_ID:?}"
cd "$(dirname "$0")/../App"
KEY="$HOME/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8"
AUTH="-allowProvisioningUpdates -authenticationKeyPath $KEY -authenticationKeyID $ASC_KEY_ID -authenticationKeyIssuerID $ASC_ISSUER_ID"
OUT=build/testflight
if [ "${1:-}" != upload ]; then
  xcodegen generate
  rm -rf "$OUT/Swarm.xcarchive"
  # A new bundle ID: org.cjp.swarm.staging is held by the free personal team.
  xcodebuild archive -project Swarm.xcodeproj -scheme Swarm -destination generic/platform=iOS -archivePath "$OUT/Swarm.xcarchive" $AUTH \
    DEVELOPMENT_TEAM="$APPLE_TEAM_ID" PRODUCT_BUNDLE_IDENTIFIER=org.cockroachjantaparty.swarm \
    CODE_SIGN_ENTITLEMENTS=Release.entitlements CURRENT_PROJECT_VERSION="$(date +%y%m%d%H%M)"
fi
cat > "$OUT/export.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
PLIST
xcodebuild -exportArchive -archivePath "$OUT/Swarm.xcarchive" -exportOptionsPlist "$OUT/export.plist" -exportPath "$OUT" $AUTH
