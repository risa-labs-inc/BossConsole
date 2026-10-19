package ai.rever.boss.sharing

internal fun appSharingFailureStatus(error: Throwable): String =
    when ((error as? AppSharingException)?.reason) {
        "service_not_deployed" -> {
            "Application sharing is not deployed on the configured server. Contact your administrator."
        }

        "relay_disabled" -> {
            "Enable Use media relay in Window → Sharing Settings to share BossConsole."
        }

        "sign_in_required", "unauthorized", "account_changed" -> {
            "Sign in to BossConsole to share or connect."
        }

        "sfu_not_configured" -> {
            "The application media relay has not been configured on the server."
        }

        "approval_required" -> {
            "Automatic access is disabled for this account."
        }

        "conflict" -> {
            "Sharing settings changed elsewhere. Refresh and try again."
        }

        "connection_lost" -> {
            "Sharing stopped because the account service could not be reached."
        }

        "media_unavailable" -> {
            "Sharing stopped because encrypted media could not be established."
        }

        else -> {
            (error as? AppCaptureUnavailableException)?.message
                ?: "Application sharing is unavailable. Check capture permission and the sharing service."
        }
    }

internal fun appMediaFailureStatus(reason: String?): String =
    when (reason) {
        "media_connection_failed" -> {
            "Sharing stopped because the media relay connection failed. Start sharing again."
        }

        "media_connection_timeout", "media_ice_timeout" -> {
            "The media relay connection timed out. Start sharing again."
        }

        "authority_unavailable" -> {
            "Sharing stopped because session access could not be verified. Start sharing again."
        }

        "native_frame_failed" -> {
            "Sharing stopped because a captured window frame could not be streamed. " +
                "Start sharing again."
        }

        else -> {
            "Sharing stopped because encrypted media could not be established."
        }
    }
