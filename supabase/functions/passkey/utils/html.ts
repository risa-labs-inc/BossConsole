/**
 * HTML Template Generation for Mobile WebAuthn Flows
 * Templates embedded as template literals for Edge Runtime compatibility
 */

const REGISTRATION_TEMPLATE = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Add BOSS Passkey</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            background-color: #1a1a1a;
            color: #F2F2F2;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .container {
            width: 100%;
            max-width: 480px;
            background-color: #2B2B2B;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
            overflow: hidden;
        }

        .header {
            padding: 40px 40px 30px;
            text-align: center;
            border-bottom: 1px solid #4D4D4D;
        }

        .logo {
            font-size: 28px;
            font-weight: 600;
            color: #F2F2F2;
            letter-spacing: -0.5px;
            margin-bottom: 8px;
        }

        .subtitle {
            color: #AAAAAA;
            font-size: 14px;
            letter-spacing: 0.5px;
        }

        .content {
            padding: 40px;
        }

        .title {
            font-size: 24px;
            font-weight: 600;
            color: #F2F2F2;
            margin-bottom: 8px;
        }

        .description {
            color: #AAAAAA;
            font-size: 16px;
            line-height: 1.5;
            margin-bottom: 24px;
        }

        .email-badge {
            background-color: #3C3F41;
            padding: 12px 16px;
            border-radius: 6px;
            text-align: center;
            margin-bottom: 32px;
            border-left: 3px solid #3592C4;
        }

        .email-badge .label {
            color: #AAAAAA;
            font-size: 12px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 4px;
        }

        .email-badge .value {
            color: #F2F2F2;
            font-size: 16px;
            font-weight: 500;
        }

        .button {
            width: 100%;
            padding: 16px 48px;
            background-color: #3592C4;
            color: #FFFFFF;
            border: none;
            border-radius: 6px;
            font-size: 16px;
            font-weight: 600;
            letter-spacing: 0.3px;
            cursor: pointer;
            box-shadow: 0 2px 8px rgba(53, 146, 196, 0.3);
            transition: all 0.2s ease;
        }

        .button:hover:not(:disabled) {
            background-color: #2d7ba8;
            box-shadow: 0 4px 12px rgba(53, 146, 196, 0.4);
            transform: translateY(-1px);
        }

        .button:active:not(:disabled) {
            transform: translateY(0);
        }

        .button:disabled {
            background-color: #4D4D4D;
            cursor: not-allowed;
            box-shadow: none;
            opacity: 0.6;
        }

        .status-container {
            margin-top: 24px;
            min-height: 80px;
        }

        .status {
            padding: 16px 20px;
            border-radius: 6px;
            font-size: 14px;
            line-height: 1.5;
        }

        .status.success {
            background-color: #3C3F41;
            border-left: 3px solid #43A047;
            color: #F2F2F2;
        }

        .status.success .icon {
            color: #4CAF50;
        }

        .status.error {
            background-color: #3C3F41;
            border-left: 3px solid #E53935;
            color: #F2F2F2;
        }

        .status.error .icon {
            color: #E53935;
        }

        .status .icon {
            font-size: 18px;
            font-weight: 600;
            margin-right: 8px;
        }

        .footer {
            padding: 30px 40px;
            text-align: center;
            border-top: 1px solid #4D4D4D;
            background-color: #2B2B2B;
        }

        .footer-text {
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }

        .security-info {
            background-color: #3C3F41;
            padding: 12px 16px;
            border-radius: 6px;
            border-left: 3px solid #43A047;
            margin-top: 24px;
        }

        .security-info .title {
            color: #F2F2F2;
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 4px;
        }

        .security-info .text {
            color: #AAAAAA;
            font-size: 12px;
            line-height: 1.4;
        }

        @media (max-width: 600px) {
            .header, .content, .footer {
                padding: 24px;
            }

            .logo {
                font-size: 24px;
            }

            .title {
                font-size: 20px;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">BOSS Console</div>
            <div class="subtitle">Business Operating System as Service</div>
        </div>

        <div class="content">
            <div class="title">Add Passkey</div>
            <div class="description">
                Secure your BOSS account with biometric authentication. You'll use Touch ID, Face ID, or your device's security to sign in.
            </div>

            <div class="email-badge">
                <div class="label">Account</div>
                <div class="value">{{EMAIL}}</div>
            </div>

            <button id="registerBtn" class="button">
                🔐 Add Passkey
            </button>

            <div class="status-container">
                <div id="status"></div>
            </div>

            <div class="security-info">
                <div class="title">🔒 Secure Authentication</div>
                <div class="text">
                    Your passkey is stored securely on this device and protected by your biometric data. BOSS never has access to your biometric information.
                </div>
            </div>
        </div>

        <div class="footer">
            <div class="footer-text">
                © 2025 BOSS Console. All rights reserved.
            </div>
        </div>
    </div>

    <script>
        const challenge = '{{CHALLENGE}}';
        const userId = '{{USER_ID}}';
        const email = '{{EMAIL}}';
        const sessionId = '{{SESSION_ID}}';
        const rpId = '{{RP_ID}}';
        const rpName = '{{RP_NAME}}';
        const anonKey = '{{ANON_KEY}}';

        function base64urlToBuffer(base64url) {
            try {
                let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
                while (base64.length % 4) {
                    base64 += '=';
                }
                const binary = atob(base64);
                const buffer = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) {
                    buffer[i] = binary.charCodeAt(i);
                }
                return buffer.buffer;
            } catch (error) {
                console.error('Error converting base64url to buffer:', error);
                throw error;
            }
        }

        function bufferToBase64url(buffer) {
            const bytes = new Uint8Array(buffer);
            let binary = '';
            for (let i = 0; i < bytes.length; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary).replace(/[+]/g, '-').replace(/[/]/g, '_').replace(/=/g, '');
        }

        document.getElementById('registerBtn').addEventListener('click', async () => {
            const button = document.getElementById('registerBtn');
            const status = document.getElementById('status');

            button.disabled = true;
            button.textContent = '⏳ Creating Passkey...';
            status.innerHTML = '';

            try {
                const userIdBuffer = new TextEncoder().encode(userId);
                const challengeBuffer = base64urlToBuffer(challenge);

                const publicKeyCredentialCreationOptions = {
                    challenge: challengeBuffer,
                    rp: { id: rpId, name: rpName },
                    user: {
                        id: userIdBuffer,
                        name: email,
                        displayName: email
                    },
                    pubKeyCredParams: [
                        { alg: -7, type: "public-key" },
                        { alg: -257, type: "public-key" }
                    ],
                    authenticatorSelection: {
                        userVerification: "preferred",
                        residentKey: "preferred"
                    },
                    timeout: 300000,
                    attestation: "none"
                };

                const credential = await navigator.credentials.create({
                    publicKey: publicKeyCredentialCreationOptions
                });

                if (!credential || !credential.id || !credential.response) {
                    throw new Error('Invalid credential created');
                }

                const registrationData = {
                    userId: userId,
                    credential: {
                        id: credential.id,
                        rawId: bufferToBase64url(credential.rawId),
                        type: credential.type,
                        response: {
                            clientDataJSON: bufferToBase64url(credential.response.clientDataJSON),
                            attestationObject: bufferToBase64url(credential.response.attestationObject)
                        }
                    },
                    challenge: challenge,
                    displayName: email
                };

                const response = await fetch('/functions/v1/passkey/register/complete', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'apikey': anonKey
                    },
                    body: JSON.stringify(registrationData)
                });

                const result = await response.json();

                if (result.success) {
                    status.className = 'status success';
                    status.innerHTML = '<span class="icon">✅</span><strong>Passkey added successfully!</strong><br>Redirecting to BOSS Console...';
                    button.textContent = '✓ Complete';

                    // Redirect to BOSS app via deep link after 1.5 seconds
                    setTimeout(() => {
                        window.location.href = 'boss://passkey/registered?sessionId=' + sessionId;
                    }, 1500);
                } else {
                    throw new Error(result.error || 'Registration failed');
                }

            } catch (error) {
                console.error('Registration error:', error);
                status.className = 'status error';
                status.innerHTML = '<span class="icon">❌</span><strong>Failed to add passkey</strong><br>' + error.message;
                button.disabled = false;
                button.textContent = '🔐 Try Again';
            }
        });

        // Auto-click the register button on page load for seamless embedded browser experience
        document.addEventListener('DOMContentLoaded', () => {
            // Slight delay to ensure all event listeners are attached
            setTimeout(() => {
                const button = document.getElementById('registerBtn');
                if (button && !button.disabled) {
                    console.log('Auto-clicking registration button...');
                    button.click();
                }
            }, 100);  // 100ms to ensure DOM and listeners are ready
        });
    </script>
</body>
</html>
`;

const AUTHENTICATION_TEMPLATE = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>BOSS Passkey Authentication</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            background-color: #1a1a1a;
            color: #F2F2F2;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .container {
            width: 100%;
            max-width: 480px;
            background-color: #2B2B2B;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
            overflow: hidden;
        }

        .header {
            padding: 40px 40px 30px;
            text-align: center;
            border-bottom: 1px solid #4D4D4D;
        }

        .logo {
            font-size: 28px;
            font-weight: 600;
            color: #F2F2F2;
            letter-spacing: -0.5px;
            margin-bottom: 8px;
        }

        .subtitle {
            color: #AAAAAA;
            font-size: 14px;
            letter-spacing: 0.5px;
        }

        .content {
            padding: 40px;
        }

        .title {
            font-size: 24px;
            font-weight: 600;
            color: #F2F2F2;
            margin-bottom: 8px;
        }

        .description {
            color: #AAAAAA;
            font-size: 16px;
            line-height: 1.5;
            margin-bottom: 24px;
        }

        .account-info {
            background-color: #3C3F41;
            padding: 16px;
            border-radius: 6px;
            margin-bottom: 16px;
            border-left: 3px solid #3592C4;
        }

        .account-info .label {
            color: #AAAAAA;
            font-size: 12px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 6px;
        }

        .account-info .value {
            color: #F2F2F2;
            font-size: 16px;
            font-weight: 500;
            margin-bottom: 12px;
        }

        .credential-details {
            background-color: #3C3F41;
            padding: 12px 16px;
            border-radius: 6px;
            margin-bottom: 32px;
        }

        .credential-details .row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 6px 0;
        }

        .credential-details .row:not(:last-child) {
            border-bottom: 1px solid #4D4D4D;
        }

        .credential-details .label {
            color: #AAAAAA;
            font-size: 13px;
        }

        .credential-details .value {
            color: #F2F2F2;
            font-size: 13px;
            font-weight: 500;
        }

        .button {
            width: 100%;
            padding: 16px 48px;
            background-color: #3592C4;
            color: #FFFFFF;
            border: none;
            border-radius: 6px;
            font-size: 16px;
            font-weight: 600;
            letter-spacing: 0.3px;
            cursor: pointer;
            box-shadow: 0 2px 8px rgba(53, 146, 196, 0.3);
            transition: all 0.2s ease;
        }

        .button:hover:not(:disabled) {
            background-color: #2d7ba8;
            box-shadow: 0 4px 12px rgba(53, 146, 196, 0.4);
            transform: translateY(-1px);
        }

        .button:active:not(:disabled) {
            transform: translateY(0);
        }

        .button:disabled {
            background-color: #4D4D4D;
            cursor: not-allowed;
            box-shadow: none;
            opacity: 0.6;
        }

        .status-container {
            margin-top: 24px;
            min-height: 80px;
        }

        .status {
            padding: 16px 20px;
            border-radius: 6px;
            font-size: 14px;
            line-height: 1.5;
        }

        .status.loading {
            background-color: #3C3F41;
            border-left: 3px solid #3592C4;
            color: #F2F2F2;
        }

        .status.loading .icon {
            color: #3592C4;
        }

        .status.success {
            background-color: #3C3F41;
            border-left: 3px solid #43A047;
            color: #F2F2F2;
        }

        .status.success .icon {
            color: #4CAF50;
        }

        .status.error {
            background-color: #3C3F41;
            border-left: 3px solid #E53935;
            color: #F2F2F2;
        }

        .status.error .icon {
            color: #E53935;
        }

        .status .icon {
            font-size: 18px;
            font-weight: 600;
            margin-right: 8px;
        }

        .footer {
            padding: 30px 40px;
            text-align: center;
            border-top: 1px solid #4D4D4D;
            background-color: #2B2B2B;
        }

        .footer-text {
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }

        .security-info {
            background-color: #3C3F41;
            padding: 12px 16px;
            border-radius: 6px;
            border-left: 3px solid #43A047;
            margin-top: 24px;
        }

        .security-info .title {
            color: #F2F2F2;
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 4px;
        }

        .security-info .text {
            color: #AAAAAA;
            font-size: 12px;
            line-height: 1.4;
        }

        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }

        .loading .icon {
            display: inline-block;
            animation: spin 1s linear infinite;
        }

        @media (max-width: 600px) {
            .header, .content, .footer {
                padding: 24px;
            }

            .logo {
                font-size: 24px;
            }

            .title {
                font-size: 20px;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">BOSS Console</div>
            <div class="subtitle">Business Operating System as Service</div>
        </div>

        <div class="content">
            <div class="title">Sign In with Passkey</div>
            <div class="description">
                Use your biometric authentication to securely sign in to BOSS Console.
            </div>

            <div class="account-info">
                <div class="label">Account</div>
                <div class="value">{{EMAIL}}</div>

                <div class="credential-details">
                    <div class="row">
                        <span class="label">Credential</span>
                        <span class="value">{{CREDENTIAL_DISPLAY_NAME}}</span>
                    </div>
                    <div class="row">
                        <span class="label">Created</span>
                        <span class="value">{{CREDENTIAL_CREATED_AT}}</span>
                    </div>
                </div>
            </div>

            <button id="authButton" class="button">
                🔐 Authenticate with Passkey
            </button>

            <div class="status-container">
                <div id="status"></div>
            </div>

            <div class="security-info">
                <div class="title">🔒 Secure Authentication</div>
                <div class="text">
                    Your authentication is protected by device biometrics. BOSS never stores or has access to your biometric data.
                </div>
            </div>
        </div>

        <div class="footer">
            <div class="footer-text">
                © 2025 BOSS Console. All rights reserved.
            </div>
        </div>
    </div>

    <script>
        const challenge = '{{CHALLENGE}}';
        const email = '{{EMAIL}}';
        const credentialId = '{{CREDENTIAL_ID}}';
        const sessionId = '{{SESSION_ID}}';
        const rpId = '{{RP_ID}}';
        const anonKey = '{{ANON_KEY}}';

        function base64urlToBuffer(base64url) {
            let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
            while (base64.length % 4) {
                base64 += '=';
            }
            const binary = atob(base64);
            const buffer = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                buffer[i] = binary.charCodeAt(i);
            }
            return buffer.buffer;
        }

        function bufferToBase64url(buffer) {
            const bytes = new Uint8Array(buffer);
            let binary = '';
            for (let i = 0; i < bytes.length; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary).replace(/[+]/g, '-').replace(/[/]/g, '_').replace(/=/g, '');
        }

        document.getElementById('authButton').addEventListener('click', async () => {
            const button = document.getElementById('authButton');
            const status = document.getElementById('status');

            button.disabled = true;
            button.textContent = '⏳ Authenticating...';
            status.className = 'status loading';
            status.innerHTML = '<span class="icon">🔐</span><strong>Waiting for biometric authentication...</strong><br>Please use Touch ID, Face ID, or your device security.';

            try {
                const publicKeyCredentialRequestOptions = {
                    challenge: base64urlToBuffer(challenge),
                    rpId: rpId,
                    allowCredentials: [{
                        id: base64urlToBuffer(credentialId),
                        type: 'public-key',
                        transports: ['internal', 'hybrid']
                    }],
                    userVerification: 'preferred',
                    timeout: 300000
                };

                const credential = await navigator.credentials.get({
                    publicKey: publicKeyCredentialRequestOptions
                });

                if (!credential || !credential.response) {
                    throw new Error('Authentication failed');
                }

                const authData = {
                    credential: {
                        id: credential.id,
                        rawId: bufferToBase64url(credential.rawId),
                        type: credential.type,
                        response: {
                            clientDataJSON: bufferToBase64url(credential.response.clientDataJSON),
                            authenticatorData: bufferToBase64url(credential.response.authenticatorData),
                            signature: bufferToBase64url(credential.response.signature),
                            userHandle: credential.response.userHandle ? bufferToBase64url(credential.response.userHandle) : null
                        }
                    },
                    challenge: challenge
                };

                const response = await fetch('/functions/v1/passkey/auth/complete', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'apikey': anonKey
                    },
                    body: JSON.stringify(authData)
                });

                const result = await response.json();

                if (result.success) {
                    status.className = 'status success';
                    status.innerHTML = '<span class="icon">✅</span><strong>Authentication successful!</strong><br>Redirecting to BOSS Console...';
                    button.textContent = '✓ Complete';

                    // Redirect to BOSS app via deep link after 1.5 seconds
                    setTimeout(() => {
                        window.location.href = 'boss://passkey/authenticated?sessionId=' + sessionId;
                    }, 1500);
                } else {
                    throw new Error(result.error || 'Authentication failed');
                }

            } catch (error) {
                console.error('Authentication error:', error);
                status.className = 'status error';
                status.innerHTML = '<span class="icon">❌</span><strong>Authentication failed</strong><br>' + error.message;
                button.disabled = false;
                button.textContent = '🔐 Try Again';
            }
        });

        // Auto-click the auth button on page load for seamless embedded browser experience
        document.addEventListener('DOMContentLoaded', () => {
            // Slight delay to ensure all event listeners are attached
            setTimeout(() => {
                const button = document.getElementById('authButton');
                if (button && !button.disabled) {
                    console.log('Auto-clicking authentication button...');
                    button.click();
                }
            }, 100);  // 100ms to ensure DOM and listeners are ready
        });
    </script>
</body>
</html>
`;

const ERROR_TEMPLATE = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>BOSS Passkey Error</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            background-color: #1a1a1a;
            color: #F2F2F2;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .container {
            width: 100%;
            max-width: 480px;
            background-color: #2B2B2B;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
            overflow: hidden;
        }

        .header {
            padding: 40px 40px 30px;
            text-align: center;
            border-bottom: 1px solid #4D4D4D;
        }

        .logo {
            font-size: 28px;
            font-weight: 600;
            color: #F2F2F2;
            letter-spacing: -0.5px;
            margin-bottom: 8px;
        }

        .subtitle {
            color: #AAAAAA;
            font-size: 14px;
            letter-spacing: 0.5px;
        }

        .content {
            padding: 40px;
        }

        .error-container {
            text-align: center;
            padding: 32px 0;
        }

        .error-icon {
            font-size: 64px;
            margin-bottom: 24px;
            opacity: 0.9;
        }

        .error-title {
            font-size: 24px;
            font-weight: 600;
            color: #F2F2F2;
            margin-bottom: 16px;
        }

        .error-message {
            background-color: #3C3F41;
            padding: 20px;
            border-radius: 6px;
            border-left: 3px solid #E53935;
            text-align: left;
            margin-bottom: 24px;
        }

        .error-message .title {
            color: #E53935;
            font-size: 14px;
            font-weight: 600;
            margin-bottom: 8px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        .error-message .message {
            color: #F2F2F2;
            font-size: 15px;
            line-height: 1.6;
        }

        .actions {
            margin-top: 32px;
        }

        .button {
            width: 100%;
            padding: 16px 48px;
            background-color: #4D4D4D;
            color: #FFFFFF;
            border: none;
            border-radius: 6px;
            font-size: 16px;
            font-weight: 600;
            letter-spacing: 0.3px;
            cursor: pointer;
            transition: all 0.2s ease;
            text-decoration: none;
            display: inline-block;
            text-align: center;
        }

        .button:hover {
            background-color: #5a5a5a;
            transform: translateY(-1px);
        }

        .button:active {
            transform: translateY(0);
        }

        .help-text {
            margin-top: 24px;
            padding: 16px;
            background-color: #3C3F41;
            border-radius: 6px;
            border-left: 3px solid #3592C4;
        }

        .help-text .title {
            color: #F2F2F2;
            font-size: 13px;
            font-weight: 600;
            margin-bottom: 8px;
        }

        .help-text .text {
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }

        .help-text ul {
            margin: 12px 0 0 20px;
            padding: 0;
        }

        .help-text li {
            color: #AAAAAA;
            font-size: 13px;
            margin-bottom: 6px;
            line-height: 1.4;
        }

        .footer {
            padding: 30px 40px;
            text-align: center;
            border-top: 1px solid #4D4D4D;
            background-color: #2B2B2B;
        }

        .footer-text {
            color: #AAAAAA;
            font-size: 13px;
            line-height: 1.5;
        }

        @media (max-width: 600px) {
            .header, .content, .footer {
                padding: 24px;
            }

            .logo {
                font-size: 24px;
            }

            .error-title {
                font-size: 20px;
            }

            .error-icon {
                font-size: 48px;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">BOSS Console</div>
            <div class="subtitle">Business Operating System as Service</div>
        </div>

        <div class="content">
            <div class="error-container">
                <div class="error-icon">⚠️</div>
                <div class="error-title">Authentication Error</div>

                <div class="error-message">
                    <div class="title">Error Details</div>
                    <div class="message">{{MESSAGE}}</div>
                </div>

                <div class="actions">
                    <button class="button" onclick="window.close()">
                        Close Window
                    </button>
                </div>

                <div class="help-text">
                    <div class="title">💡 What can you do?</div>
                    <div class="text">
                        <ul>
                            <li>Close this page and try authenticating again from BOSS Console</li>
                            <li>Make sure you're using a supported device with biometric authentication</li>
                            <li>Check your internet connection and try again</li>
                            <li>Contact support if the problem persists</li>
                        </ul>
                    </div>
                </div>
            </div>
        </div>

        <div class="footer">
            <div class="footer-text">
                © 2025 BOSS Console. All rights reserved.
            </div>
        </div>
    </div>

    <script>
        // Auto-close after 30 seconds if window.close() is supported
        setTimeout(() => {
            try {
                window.close();
            } catch (e) {
                console.log('Auto-close not supported');
            }
        }, 30000);
    </script>
</body>
</html>
`;

export async function getMobileRegistrationHTML(
  challenge: string,
  userId: string,
  email: string,
  sessionId: string,
  rpId: string,
  rpName: string
): Promise<string> {
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") || "";
  
  return REGISTRATION_TEMPLATE
    .replace(/{{CHALLENGE}}/g, challenge)
    .replace(/{{USER_ID}}/g, userId)
    .replace(/{{EMAIL}}/g, email)
    .replace(/{{SESSION_ID}}/g, sessionId)
    .replace(/{{RP_ID}}/g, rpId)
    .replace(/{{RP_NAME}}/g, rpName)
    .replace(/{{ANON_KEY}}/g, anonKey);
}

export async function getMobileAuthenticationHTML(
  challenge: string,
  email: string,
  sessionId: string,
  rpId: string,
  credentialId: string,
  credentialDisplayName: string,
  credentialCreatedAt: number
): Promise<string> {
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") || "";
  const createdAtFormatted = new Date(credentialCreatedAt).toLocaleDateString();
  
  return AUTHENTICATION_TEMPLATE
    .replace(/{{CHALLENGE}}/g, challenge)
    .replace(/{{EMAIL}}/g, email)
    .replace(/{{SESSION_ID}}/g, sessionId)
    .replace(/{{RP_ID}}/g, rpId)
    .replace(/{{CREDENTIAL_ID}}/g, credentialId)
    .replace(/{{CREDENTIAL_DISPLAY_NAME}}/g, credentialDisplayName)
    .replace(/{{CREDENTIAL_CREATED_AT}}/g, createdAtFormatted)
    .replace(/{{ANON_KEY}}/g, anonKey);
}

export async function getMobileErrorHTML(message: string): Promise<string> {
  return ERROR_TEMPLATE.replace(/{{MESSAGE}}/g, message);
}
