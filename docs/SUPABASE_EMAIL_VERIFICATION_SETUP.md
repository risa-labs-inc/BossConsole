# Supabase Email Verification Setup for BOSS Desktop App

This guide explains how to configure Supabase to redirect email verification links to your BOSS desktop application.

## 1. Configure Redirect URL in Supabase Dashboard

1. Go to your Supabase project dashboard
2. Navigate to **Authentication** → **URL Configuration**
3. Add the following to **Redirect URLs**:
   ```
   boss://auth/verify
   ```

## 2. Update Email Templates

1. Go to **Authentication** → **Email Templates**
2. Click on **Confirm signup** template
3. Update the template to use your custom URL scheme:

```html
<h2>Confirm your email</h2>

<p>Follow this link to confirm your email:</p>
<p><a href="{{ .SiteURL }}/auth/v1/verify?token={{ .Token }}&type=email&redirect_to=boss://auth/verify?token={{ .Token }}">Confirm your email address</a></p>

<p>Or open BOSS and enter this verification code:</p>
<p><strong>{{ .Token }}</strong></p>
```

## 3. How It Works

1. When a user signs up, they receive an email with a verification link
2. The link includes `redirect_to=boss://auth/verify?token=xxx`
3. When clicked, it:
   - First goes to Supabase to verify the email
   - Then redirects to `boss://auth/verify?token=xxx`
   - The BOSS app intercepts this URL and processes the verification

## 4. Alternative: Manual Token Entry

If the deep link doesn't work (e.g., on some systems), users can:
1. Copy the verification token from the email
2. Open BOSS app
3. Click "Verify Email" button
4. Paste the token manually

## 5. Testing

1. Sign up with a new email
2. Check your email for the verification link
3. Click the link - it should:
   - Open your default browser briefly
   - Redirect to the BOSS app
   - Automatically verify your email

## 6. Troubleshooting

### Deep links not working on macOS:
- Make sure the app is installed (not just running from IDE)
- Check that the Info.plist includes the URL scheme configuration
- Try running: `open boss://auth/verify?token=test` in Terminal

### Email not being sent:
- Check Supabase email settings
- Verify SMTP configuration if using custom SMTP
- Check spam folder

### Verification failing:
- Ensure the token hasn't expired (default: 1 hour)
- Check that the redirect URL is properly encoded
- Verify the Supabase project URL is correct