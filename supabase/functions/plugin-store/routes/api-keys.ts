import { createRoute, OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"
import { ErrorResponseSchema } from "../types/schemas.ts"
import { getUserFromToken } from "../utils/auth.ts"
import {
  generateApiKey,
  hashApiKey,
  getKeyPrefix,
  areValidScopes,
  VALID_API_KEY_SCOPES,
} from "../utils/api-key.ts"

const apiKeys = new OpenAPIHono<{ Variables: PluginStoreContext }>()

// Rate limit: Maximum API keys per user (configurable via environment variable)
const MAX_API_KEYS_PER_USER = parseInt(
  Deno.env.get("MAX_API_KEYS_PER_USER") || "10",
  10
)

// ============================================================================
// Schemas
// ============================================================================

const CreateApiKeyRequestSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required")
    .max(100, "Name must be 100 characters or less")
    .describe("A friendly name to identify this API key"),
  scopes: z
    .array(z.enum(["publish", "version", "finalize"]))
    .min(1, "At least one scope is required")
    .default(["publish", "version", "finalize"])
    .describe("Permissions for this API key"),
  expiresInDays: z
    .number()
    .int()
    .positive()
    .nullable()
    .optional()
    .describe("Optional: Number of days until the key expires (null = never)"),
})

const CreateApiKeyResponseSchema = z.object({
  success: z.boolean(),
  apiKey: z.string().describe("The full API key - ONLY shown once"),
  keyInfo: z.object({
    id: z.string().uuid(),
    name: z.string(),
    keyPrefix: z.string().describe("First 16 chars for identification"),
    scopes: z.array(z.string()),
    createdAt: z.string(),
    expiresAt: z.string().nullable(),
  }),
})

const ListApiKeysResponseSchema = z.object({
  success: z.boolean(),
  keys: z.array(
    z.object({
      id: z.string().uuid(),
      name: z.string(),
      keyPrefix: z.string(),
      scopes: z.array(z.string()),
      createdAt: z.string(),
      lastUsedAt: z.string().nullable(),
      expiresAt: z.string().nullable(),
      isExpired: z.boolean(),
    })
  ),
})

const DeleteApiKeyResponseSchema = z.object({
  success: z.boolean(),
  message: z.string().optional(),
})

// ============================================================================
// POST /api-keys - Create a new API key
// ============================================================================

const createApiKeyRoute = createRoute({
  method: "post",
  path: "/api-keys",
  tags: ["API Keys"],
  summary: "Create a new API key",
  description:
    "Create a new API key for CI/CD publishing. The full key is ONLY returned once. Requires JWT authentication.",
  request: {
    body: {
      content: {
        "application/json": {
          schema: CreateApiKeyRequestSchema,
        },
      },
    },
  },
  responses: {
    201: {
      description: "API key created successfully",
      content: {
        "application/json": {
          schema: CreateApiKeyResponseSchema,
        },
      },
    },
    400: {
      description: "Invalid request",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    401: {
      description: "Authentication required (JWT only, no API keys)",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    409: {
      description: "A key with this name already exists",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    429: {
      description: "API key limit exceeded (max 10 per user)",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    500: {
      description: "Internal server error",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
  },
})

apiKeys.openapi(createApiKeyRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const body = ctx.req.valid("json")

    // IMPORTANT: Only JWT auth allowed for creating API keys (not API keys)
    const authHeader = ctx.req.header("Authorization")
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json(
        { success: false, error: "JWT authentication required" },
        401
      )
    }

    // Validate scopes
    if (!areValidScopes(body.scopes)) {
      return ctx.json(
        {
          success: false,
          error: `Invalid scopes. Allowed: ${VALID_API_KEY_SCOPES.join(", ")}`,
        },
        400
      )
    }

    // Check rate limit: max API keys per user
    const { data: countData, error: countError } = await supabase.rpc(
      "get_user_api_key_count",
      { p_user_id: user.userId }
    )

    if (countError) {
      console.error("Error checking API key count:", countError)
      return ctx.json({ success: false, error: "Failed to check API key limit" }, 500)
    }

    if (countData >= MAX_API_KEYS_PER_USER) {
      return ctx.json(
        {
          success: false,
          error: `API key limit exceeded. Maximum ${MAX_API_KEYS_PER_USER} active keys per user allowed.`,
        },
        429
      )
    }

    // Generate the API key
    const fullApiKey = generateApiKey()
    const keyHash = await hashApiKey(fullApiKey)
    const keyPrefix = getKeyPrefix(fullApiKey)

    // Calculate expiration date if provided
    const expiresAt = body.expiresInDays
      ? new Date(Date.now() + body.expiresInDays * 24 * 60 * 60 * 1000).toISOString()
      : null

    // Insert into database
    const { data, error } = await supabase
      .from("plugin_api_keys")
      .insert({
        user_id: user.userId,
        name: body.name,
        key_prefix: keyPrefix,
        key_hash: keyHash,
        scopes: body.scopes,
        expires_at: expiresAt,
      })
      .select()
      .single()

    if (error) {
      // Check for unique constraint violation
      if (error.code === "23505") {
        return ctx.json(
          { success: false, error: "A key with this name already exists" },
          409
        )
      }
      console.error("Error creating API key:", error)
      return ctx.json({ success: false, error: error.message }, 500)
    }

    return ctx.json(
      {
        success: true,
        apiKey: fullApiKey, // ONLY time the full key is returned
        keyInfo: {
          id: data.id,
          name: data.name,
          keyPrefix: data.key_prefix,
          scopes: data.scopes,
          createdAt: data.created_at,
          expiresAt: data.expires_at,
        },
      },
      201
    )
  } catch (error) {
    console.error("Error creating API key:", error)
    return ctx.json({ success: false, error: (error as Error).message }, 500)
  }
})

// ============================================================================
// GET /api-keys - List user's API keys
// ============================================================================

const listApiKeysRoute = createRoute({
  method: "get",
  path: "/api-keys",
  tags: ["API Keys"],
  summary: "List your API keys",
  description:
    "List all API keys for the authenticated user. Keys are shown with masked values (prefix only). Requires JWT authentication.",
  responses: {
    200: {
      description: "List of API keys",
      content: {
        "application/json": {
          schema: ListApiKeysResponseSchema,
        },
      },
    },
    401: {
      description: "Authentication required (JWT only)",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    500: {
      description: "Internal server error",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
  },
})

apiKeys.openapi(listApiKeysRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")

    // IMPORTANT: Only JWT auth allowed for listing API keys
    const authHeader = ctx.req.header("Authorization")
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json(
        { success: false, error: "JWT authentication required" },
        401
      )
    }

    // Get all non-revoked keys for this user
    const { data, error } = await supabase
      .from("plugin_api_keys")
      .select("id, name, key_prefix, scopes, created_at, last_used_at, expires_at")
      .eq("user_id", user.userId)
      .is("revoked_at", null)
      .order("created_at", { ascending: false })

    if (error) {
      console.error("Error listing API keys:", error)
      return ctx.json({ success: false, error: error.message }, 500)
    }

    const now = new Date()
    const keys = (data || []).map((key) => ({
      id: key.id,
      name: key.name,
      keyPrefix: key.key_prefix,
      scopes: key.scopes,
      createdAt: key.created_at,
      lastUsedAt: key.last_used_at,
      expiresAt: key.expires_at,
      isExpired: key.expires_at ? new Date(key.expires_at) < now : false,
    }))

    return ctx.json({ success: true, keys }, 200)
  } catch (error) {
    console.error("Error listing API keys:", error)
    return ctx.json({ success: false, error: (error as Error).message }, 500)
  }
})

// ============================================================================
// DELETE /api-keys/:keyId - Revoke an API key
// ============================================================================

const deleteApiKeyRoute = createRoute({
  method: "delete",
  path: "/api-keys/{keyId}",
  tags: ["API Keys"],
  summary: "Revoke an API key",
  description:
    "Revoke an API key. The key will no longer be usable. Requires JWT authentication.",
  request: {
    params: z.object({
      keyId: z.string().uuid(),
    }),
  },
  responses: {
    200: {
      description: "API key revoked successfully",
      content: {
        "application/json": {
          schema: DeleteApiKeyResponseSchema,
        },
      },
    },
    401: {
      description: "Authentication required (JWT only)",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    403: {
      description: "Not authorized to revoke this key",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    404: {
      description: "API key not found",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
    500: {
      description: "Internal server error",
      content: {
        "application/json": {
          schema: ErrorResponseSchema,
        },
      },
    },
  },
})

apiKeys.openapi(deleteApiKeyRoute, async (ctx) => {
  try {
    const supabase = ctx.get("supabase")
    const { keyId } = ctx.req.valid("param")

    // IMPORTANT: Only JWT auth allowed for revoking API keys
    const authHeader = ctx.req.header("Authorization")
    const user = await getUserFromToken(supabase, authHeader)

    if (!user) {
      return ctx.json(
        { success: false, error: "JWT authentication required" },
        401
      )
    }

    // Get the key to verify ownership
    const { data: existingKey, error: fetchError } = await supabase
      .from("plugin_api_keys")
      .select("id, user_id, revoked_at")
      .eq("id", keyId)
      .single()

    if (fetchError || !existingKey) {
      return ctx.json({ success: false, error: "API key not found" }, 404)
    }

    // Verify ownership
    if (existingKey.user_id !== user.userId) {
      return ctx.json(
        { success: false, error: "Not authorized to revoke this key" },
        403
      )
    }

    // Check if already revoked
    if (existingKey.revoked_at) {
      return ctx.json(
        { success: true, message: "API key was already revoked" },
        200
      )
    }

    // Revoke the key (soft delete)
    const { error: updateError } = await supabase
      .from("plugin_api_keys")
      .update({ revoked_at: new Date().toISOString() })
      .eq("id", keyId)

    if (updateError) {
      console.error("Error revoking API key:", updateError)
      return ctx.json({ success: false, error: updateError.message }, 500)
    }

    return ctx.json(
      { success: true, message: "API key revoked successfully" },
      200
    )
  } catch (error) {
    console.error("Error revoking API key:", error)
    return ctx.json({ success: false, error: (error as Error).message }, 500)
  }
})

export default apiKeys
