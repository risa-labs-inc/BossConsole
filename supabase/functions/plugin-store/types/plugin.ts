/**
 * Plugin type definitions
 */

export type PluginType = 'panel' | 'tab' | 'hybrid'

export interface Plugin {
  id: string
  pluginId: string
  displayName: string
  description: string
  authorId: string | null
  authorName: string
  homepageUrl: string
  iconUrl: string
  type: PluginType
  apiVersion: string
  verified: boolean
  published: boolean
  createdAt: string
  updatedAt: string
}

export interface PluginVersion {
  id: string
  pluginId: string
  version: string
  changelog: string
  minBossVersion: string
  minIpcVersion: string
  /** Minimum boss-plugin-api (runtime API layer) version; '' = no requirement. */
  minApiVersion: string
  jarPath: string
  jarSize: number
  sha256: string
  /** Base64 store signature over the canonical anchor `pluginId|version|sha256` (see utils/signing.ts versionAnchor); null pre-signing. */
  signature?: string | null
  dependencies: PluginDependency[]
  publishedAt: string
  downloadCount?: number
  /** Release channel (stable | beta | test). Absent on rows read before the channel migration: treat as stable. */
  channel?: ReleaseChannel
  /** approved | pending_review | rejected. Absent: treat as approved (the column default). */
  reviewStatus?: ReviewStatus
  /** The reviewer's note on a rejected version (only shown to callers with channel access). */
  reviewNote?: string | null
  /** What a reviewer is shown for a Max app version (services/max-app.ts MaxAppReviewMeta). */
  reviewMeta?: Record<string, unknown> | null
}

/** plugins.kind */
export type PluginKind = 'plugin' | 'max-app'
/** plugin_versions.channel, lowest first: a channel includes the ones below it. */
export const RELEASE_CHANNELS = ['stable', 'beta', 'test'] as const
export type ReleaseChannel = typeof RELEASE_CHANNELS[number]
export type ReviewStatus = 'approved' | 'pending_review' | 'rejected'

/** The channels a caller asking for `channel` gets: stable for stable, stable+beta for beta, all three for test. */
export function channelsUpTo(channel: ReleaseChannel): ReleaseChannel[] {
  return RELEASE_CHANNELS.slice(0, RELEASE_CHANNELS.indexOf(channel) + 1)
}

export interface PluginDependency {
  pluginId: string
  versionRange: string
}

export interface PluginWithStats extends Plugin {
  latestVersion: string | null
  latestVersionId: string | null
  avgRating: number
  ratingCount: number
  downloadCount: number
  tags: string[]
  screenshots: PluginScreenshot[]
  /** Permissions a user must hold to install/use this plugin. Empty = open. */
  requiredPermissions: string[]
}

export interface PluginScreenshot {
  url: string
  caption: string
}

export interface PluginListItem {
  id: string
  pluginId: string
  displayName: string
  description: string
  author: string
  type: PluginType
  apiVersion: string
  verified: boolean
  iconUrl: string
  url: string
  version: string | null
  rating: number
  ratingCount: number
  downloadCount: number
  tags: string[]
  updatedAt: string
  /** Permissions a user must hold to install/use this plugin. Empty = open. */
  requiredPermissions: string[]
}

export interface PluginSearchResult {
  plugins: PluginListItem[]
  totalCount: number
  page: number
  pageSize: number
}

export interface PluginRating {
  id: string
  pluginId: string
  userId: string
  rating: number
  review: string
  createdAt: string
  updatedAt: string
}

export interface DownloadInfo {
  downloadUrl: string
  sha256: string
  version: string
  size: number
  versionId: string
}


/**
 * Plugin manifest from plugin.json inside JAR
 * Matches the structure in META-INF/boss-plugin/plugin.json
 */
export interface PluginManifest {
  manifestVersion?: number
  pluginId: string
  displayName: string
  version: string
  apiVersion: string
  mainClass: string
  type?: PluginType | "mixed" | "service"
  description?: string
  author?: string
  url?: string
  homepageUrl?: string  // Alternative to url
  iconUrl?: string
  tags?: string[]
  minBossVersion?: string
  minIpcVersion?: string
  minApiVersion?: string
  dependencies?: PluginDependency[]
  sandbox?: {
    maxThreads?: number
    maxMemoryMb?: number
    enableSandbox?: boolean
  }
  panel?: {
    icon?: string
    location?: string
    order?: number
  }
  requiresAdmin?: boolean
  // Effective permissions the user must hold to use the plugin (may reference
  // existing system permissions). Gated host-side; not stored by the store.
  requiredPermissions?: string[]
  // NEW permissions this plugin introduces. Auto-registered into the catalog
  // (non-system, ungranted) at publish time via register_plugin_permission.
  definedPermissions?: { name: string; description?: string }[]
}
