# Code Signing Setup for BOSS Kotlin Application

This document outlines how to set up code signing certificates and configure GitHub repository secrets for production-ready distribution builds.

## macOS Code Signing Setup

### Prerequisites
1. **Apple Developer Account** with Developer ID Application certificate
2. **Apple ID App-Specific Password** for notarization

### Required GitHub Secrets

#### `MACOS_P12_CERTIFICATE`
- **Format**: Base64-encoded P12 certificate file
- **Description**: Your Developer ID Application certificate exported from Keychain Access
- **How to generate**:
  1. Open Keychain Access on macOS
  2. Find your "Developer ID Application" certificate
  3. Right-click → Export → Save as .p12 file
  4. Convert to base64: `base64 -i certificate.p12 | pbcopy`
  5. Paste the base64 string into this secret

#### `MACOS_P12_PASSWORD`
- **Format**: Plain text password
- **Description**: Password used when exporting the P12 certificate

#### `MACOS_DEVELOPER_ID`
- **Format**: Certificate common name
- **Example**: `"Developer ID Application: Your Name (TEAMID1234)"`
- **How to find**: Check the certificate details in Keychain Access
- **Local builds**: also accepted from `local.properties` (`MACOS_DEVELOPER_ID=...`); when unset, packaging is unsigned

#### `MACOS_APPLE_ID`
- **Format**: Apple ID email
- **Description**: Your Apple Developer account email

#### `MACOS_APP_PASSWORD`
- **Format**: App-specific password
- **Description**: Generate at https://appleid.apple.com/account/manage
- **How to generate**:
  1. Sign in to https://appleid.apple.com/account/manage
  2. Go to "App-Specific Passwords"
  3. Generate new password for "BOSS Notarization"

#### `MACOS_TEAM_ID`
- **Format**: 10-character team identifier
- **Example**: `"TEAMID1234"`
- **How to find**: Apple Developer account → Membership details

#### `MACOS_PROVISIONING_PROFILE_B64`
- **Format**: Base64-encoded `.provisionprofile` file
- **Description**: Apple Developer ID provisioning profile for the branded
  browser's Touch ID keychain entitlement. Not committed to the repo — the
  chromium-branding workflow restores it from this secret. Optional: without
  it, Touch ID signing is skipped.
- **How to generate**: `base64 -i boss-browser.provisionprofile | pbcopy`

## Windows Code Signing Setup

### Prerequisites
1. **DigiCert KeyLocker Account** with code signing certificate
2. **DigiCert KeyLocker API credentials**

### Required GitHub Secrets

#### `DIGICERT_API_KEY`
- **Format**: API key string
- **Description**: DigiCert KeyLocker API key
- **How to generate**: DigiCert console → API Keys → Generate new key

#### `DIGICERT_CLIENT_AUTH_KEY`
- **Format**: Client authentication key
- **Description**: DigiCert KeyLocker client authentication
- **How to generate**: Provided by DigiCert when setting up KeyLocker

#### `DIGICERT_KEYLOCKER_HOST`
- **Format**: Hostname
- **Example**: `"clientauth.one.digicert.com"`
- **Description**: DigiCert KeyLocker service endpoint

#### `DIGICERT_KEYLOCKER_CERTIFICATE_NAME`
- **Format**: Certificate name
- **Description**: Name of your code signing certificate in KeyLocker

## Setting Up GitHub Secrets

1. Go to your GitHub repository
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Add each secret listed above with exact names

## Verification

### Local Testing (macOS)
```bash
# Test certificate availability
security find-identity -v -p codesigning

# Test build with signing
./gradlew packageDmg
```

### Local Testing (Windows)
```bash
# Test DigiCert KeyLocker connection
smksp_registrar.exe list

# Test build with signing
./gradlew packageMsi
```

## Production Release Process

1. **Trigger Release**: Use GitHub Actions workflow_dispatch
2. **Automatic Process**:
   - Version increment
   - Cross-platform builds with code signing
   - macOS notarization
   - Windows MSI signing
   - GitHub release creation
   - Artifact upload

## Security Best Practices

1. **Certificate Protection**:
   - Store certificates securely
   - Use strong passwords
   - Rotate app-specific passwords regularly

2. **Access Control**:
   - Limit repository access
   - Use environment protection rules
   - Enable required status checks

3. **Monitoring**:
   - Monitor signing logs
   - Track certificate expiration
   - Set up alerts for failed builds

## Troubleshooting

### macOS Issues
- **Certificate not found**: Check keychain access and certificate name
- **Notarization fails**: Verify Apple ID and app-specific password
- **Team ID mismatch**: Ensure team ID matches certificate

### Windows Issues
- **KeyLocker connection fails**: Check API credentials and host
- **Certificate not accessible**: Verify KeyLocker setup and permissions
- **MSI signing fails**: Check certificate name and DigiCert service status

## Cost Considerations

### macOS
- **Apple Developer Program**: $99/year
- **Notarization**: Free with developer account

### Windows
- **DigiCert Code Signing**: ~$300-500/year
- **KeyLocker Service**: Additional monthly fee

## Support

For issues with this setup:
1. Check GitHub Actions logs for specific errors
2. Verify all secrets are correctly configured
3. Test certificate access locally first
4. Contact DigiCert support for KeyLocker issues
5. Contact Apple Developer Support for notarization issues