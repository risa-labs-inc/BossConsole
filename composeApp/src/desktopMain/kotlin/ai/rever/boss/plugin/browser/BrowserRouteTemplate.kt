package ai.rever.boss.plugin.browser

/**
 * Reduces a page's path to a route template, so telemetry can say *where in a site* a user
 * was without saying anything about who or what they were looking at.
 *
 * Split out of [BrowserAnalytics] for size, not for separation of concern: this is part of
 * the same privacy boundary and is called from [BrowserAnalytics.pageViewed] before a
 * [ai.rever.boss.plugin.api.BrowserEvent] exists. Nothing else may call it - a caller that
 * templated a route and put it somewhere other than through that function would be routing
 * around the boundary rather than using it.
 */
internal object BrowserRouteTemplate {
    /** A templated route and whether every one of its segments was recognised. */
    internal data class RouteTemplate(
        val value: String,
        val known: Boolean,
    )

    /**
     * Reduce a page's path to a route template: `/claims/8837261/detail` becomes
     * `claims>:num>detail`, and `/accounts/john-smith` becomes `accounts>:word`.
     *
     * **A segment survives literally only if it is in [ROUTE_WORDS].** Everything else
     * becomes a placeholder describing its shape. That is the whole design, and it is
     * inverted from the obvious approach: the obvious one placeholders what *looks like* an
     * identifier and keeps the rest, which ships every path segment nobody thought to
     * pattern-match. A person's name, an account handle, an email local-part and a document
     * slug are all just words, and no regex separates them from `settings`. Recognising the
     * finite set of structural words instead means an unfamiliar segment fails safe.
     *
     * This is the same failure [sanitizeFieldName] documents and does *not* solve for field
     * names — there, refusing unfamiliar values would cost the signal on every legitimate
     * form, because field names are an open vocabulary. Route words are not: a site has a
     * handful of them and they repeat on every visit, so the closed-vocabulary trade that is
     * wrong for `name=` is right here.
     *
     * **Separated with `>`, never `/`.** The downstream scrubber drops any value containing
     * a slash followed by two characters, so a `/`-joined route would arrive as an absent
     * property — delivered, and empty, which is the failure mode that already cost this
     * event family two properties (see the plugin's `fieldName`/`elementPath` renames).
     * `>` also matches the alphabet [sanitizePath] already uses for element paths.
     *
     * Query string and fragment are cut before anything else: they are the single most
     * identifier-dense part of a URL (`?account=123`, `#token=…`) and nothing here is meant
     * to look at them.
     */
    @Suppress("ReturnCount") // Guard clauses: blank, root, and two refusals, each of which must stop here.
    fun template(raw: String?): RouteTemplate? {
        // Accepts a whole URL as readily as a bare path, deliberately — [registrableDomain]
        // is defensive in the same way and for the same reason. A caller holding a URL
        // should not have to split it first, because the split is exactly the step where a
        // query string gets carried along by accident.
        var trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (BrowserAnalytics.LEADING_SCHEME.containsMatchIn(trimmed.lowercase())) {
            trimmed = trimmed.substring(trimmed.indexOf("://") + 3)
            val slash = trimmed.indexOf('/')
            trimmed = if (slash >= 0) trimmed.substring(slash) else "/"
        }
        val path =
            trimmed
                .substringBefore('?')
                .substringBefore('#')
                .takeIf { it.isNotEmpty() }
                ?: return RouteTemplate(ROOT_ROUTE, known = true)
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return RouteTemplate(ROOT_ROUTE, known = true)

        // Deep paths are truncated rather than refused: the first few segments carry the
        // structure worth counting, and a 30-segment path is still a real page view.
        val truncated = segments.size > MAX_ROUTE_SEGMENTS
        val classified = segments.take(MAX_ROUTE_SEGMENTS).map(::classifySegment)
        val value = classified.joinToString(">")

        // Refuse rather than trim, as everywhere else in this file: a route that needed
        // cleaning was built from something other than the classifier's own output.
        if (value.length > MAX_ROUTE_LENGTH || !ROUTE_SHAPE.matches(value)) return null
        return RouteTemplate(
            value = value,
            known = !truncated && classified.none { it.startsWith(PLACEHOLDER_PREFIX) },
        )
    }

    /**
     * Classify one path segment by shape, falling back to [SEGMENT_WORD].
     *
     * Order matters: the numeric test runs before the date test so `20260910` reads as a
     * number rather than a date, and the digit-bearing test runs late so that a recognised
     * word containing a digit (`v2`, `oauth2`) is not thrown away as a generated id.
     */
    private fun classifySegment(raw: String): String {
        val segment = raw.lowercase()
        return when {
            segment.all { it in '0'..'9' } -> {
                SEGMENT_NUM
            }

            UUID_SHAPE.matches(segment) -> {
                SEGMENT_UUID
            }

            DATE_SHAPE.matches(segment) -> {
                SEGMENT_DATE
            }

            segment.length >= MIN_HEX_LENGTH && segment.all { it in '0'..'9' || it in 'a'..'f' } -> {
                SEGMENT_HEX
            }

            segment in ROUTE_WORDS -> {
                segment
            }

            segment.length > MAX_LITERAL_LENGTH || segment.any { it in '0'..'9' } -> {
                SEGMENT_SLUG
            }

            // Anything outside the vocabulary's own alphabet was never a route word, so it
            // is an identifier of some kind rather than an unfamiliar noun. `jane.doe@ex.com`
            // has no digit and is under the length cap, so without this it read as `:word` —
            // safe, but it labelled an email address as an unrecognised English word.
            !segment.all { it in 'a'..'z' || it == '-' } -> {
                SEGMENT_SLUG
            }

            else -> {
                SEGMENT_WORD
            }
        }
    }

    private const val MAX_ROUTE_LENGTH = 120
    private const val MAX_ROUTE_SEGMENTS = 8

    /**
     * Longest a segment may be and still be considered for the vocabulary.
     *
     * Nothing in [ROUTE_WORDS] is close to this; the cap exists so a long unfamiliar
     * segment is classified as a slug rather than walking the whole set to say "no".
     */
    private const val MAX_LITERAL_LENGTH = 24

    /**
     * Shortest all-hex run treated as an identifier rather than a word.
     *
     * Below eight, ordinary words collide with the hex alphabet — `added`, `faced`,
     * `decade` and `beef` are all valid hex — and calling those identifiers would placehold
     * real route words. Eight is the shortest length where a hex run is far more likely to
     * be a truncated hash than English.
     */
    private const val MIN_HEX_LENGTH = 8

    /** Route emitted for a site's root, so `/` is countable rather than absent. */
    private const val ROOT_ROUTE = "root"

    private const val PLACEHOLDER_PREFIX = ":"
    private const val SEGMENT_NUM = ":num"
    private const val SEGMENT_UUID = ":uuid"
    private const val SEGMENT_HEX = ":hex"
    private const val SEGMENT_DATE = ":date"
    private const val SEGMENT_SLUG = ":slug"
    private const val SEGMENT_WORD = ":word"

    private val UUID_SHAPE = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    private val DATE_SHAPE = Regex("""\d{4}(-\d{1,2}){0,2}""")
    private val ROUTE_SHAPE = Regex("""(:[a-z]+|[a-z0-9-]+)(>(:[a-z]+|[a-z0-9-]+))*""")

    /**
     * Structural words a path segment may keep verbatim.
     *
     * Deliberately generic — these are the words sites build navigation out of, not any one
     * vertical's vocabulary. A segment that is not here becomes a placeholder, so the cost
     * of an omission is a less specific route and never a disclosure. That asymmetry is why
     * the list can be grown casually but must never be grown to include anything that could
     * name a person, an account, a document, or a record.
     */
    private val ROUTE_WORDS =
        setOf(
            // structure and navigation
            "about",
            "account",
            "accounts",
            "admin",
            "all",
            "api",
            "app",
            "apps",
            "archive",
            "assets",
            "auth",
            "billing",
            "blog",
            "calendar",
            "cart",
            "categories",
            "category",
            "changelog",
            "chat",
            "checkout",
            "claims",
            "comments",
            "community",
            "compare",
            "contact",
            "content",
            "dashboard",
            "details",
            "detail",
            "developers",
            "docs",
            "download",
            "downloads",
            "edit",
            "editor",
            "explore",
            "faq",
            "feed",
            "files",
            "forgot",
            "forum",
            "gallery",
            "getting-started",
            "groups",
            "help",
            "history",
            "home",
            "images",
            "inbox",
            "index",
            "invoices",
            "issues",
            "jobs",
            "join",
            "library",
            "list",
            "login",
            "logout",
            "main",
            "manage",
            "media",
            "members",
            "messages",
            "new",
            "news",
            "notifications",
            "onboarding",
            "orders",
            "overview",
            "pages",
            "password",
            "payments",
            "plans",
            "policy",
            "portal",
            "posts",
            "preview",
            "pricing",
            "privacy",
            "products",
            "profile",
            "projects",
            "public",
            "purchase",
            "patients",
            "payers",
            "people",
            "queue",
            "recent",
            "register",
            "releases",
            "reports",
            "requests",
            "reset",
            "results",
            "reviews",
            "search",
            "security",
            "services",
            "settings",
            "setup",
            "shop",
            "signin",
            "signup",
            "sitemap",
            "sources",
            "static",
            "stats",
            "status",
            "store",
            "subscription",
            "support",
            "tags",
            "tasks",
            "team",
            "teams",
            "terms",
            "tickets",
            "tools",
            "topics",
            "tour",
            "trending",
            "updates",
            "upload",
            "usage",
            "users",
            "verify",
            "video",
            "videos",
            "view",
            "welcome",
            "workspace",
            // versions and locales — high-cardinality-free and useful for grouping
            "v1",
            "v2",
            "v3",
            "beta",
            "en",
            "en-us",
            "en-gb",
            "fr",
            "de",
            "es",
            "pt",
            "ja",
        )
}
