import type { SupabaseClient } from "@supabase/supabase-js"

const BUCKET_NAME = 'plugin-jars'

/**
 * Normalize storage URLs to be accessible from outside Docker network.
 * Replaces internal Docker hostnames (kong:8000) with the public Supabase URL.
 */
export function normalizeStorageUrl(url: string): string {
  // Get the public host port from environment (set by local Supabase)
  const hostPort = Deno.env.get("SUPABASE_INTERNAL_HOST_PORT") || "54321"
  // Local stacks only: where clients reach this stack when it is not 127.0.0.1 (e.g. a test cluster
  // behind TLS). Production URLs never match the internal hosts below, so this never applies there.
  const publicUrl = (Deno.env.get("PLUGIN_STORE_PUBLIC_URL") || `http://127.0.0.1:${hostPort}`).replace(/\/+$/, "")

  // Replace internal Docker hostnames with public URL
  // kong:8000 is the internal API gateway in local Supabase
  // For production, these patterns won't match and the URL stays unchanged
  return url
    .replace(/http:\/\/kong:8000/g, publicUrl)
    .replace(/http:\/\/supabase_kong_[^\/]+:8000/g, publicUrl)
}

/**
 * Generate a signed URL for downloading a plugin JAR
 */
export async function getSignedDownloadUrl(
  supabase: SupabaseClient,
  jarPath: string,
  expiresIn: number = 3600, // 1 hour default
  // This function fetching the JAR itself (finalize's hash): the URL as storage issued it, reachable
  // from here. Only URLs handed to clients are rewritten for where clients reach the stack.
  { serverSide = false }: { serverSide?: boolean } = {},
): Promise<string> {
  const { data, error } = await supabase
    .storage
    .from(BUCKET_NAME)
    .createSignedUrl(jarPath, expiresIn)

  if (error) {
    console.error('Error creating signed download URL:', error)
    throw new Error(`Failed to create download URL: ${error.message}`)
  }

  if (!data?.signedUrl) {
    throw new Error('Failed to create download URL: no URL returned')
  }

  return serverSide ? data.signedUrl : normalizeStorageUrl(data.signedUrl)
}

/**
 * Generate a signed URL for uploading a plugin JAR
 */
export async function getSignedUploadUrl(
  supabase: SupabaseClient,
  jarPath: string,
  expiresIn: number = 3600 // 1 hour default
): Promise<string> {
  const { data, error } = await supabase
    .storage
    .from(BUCKET_NAME)
    .createSignedUploadUrl(jarPath)

  if (error) {
    console.error('Error creating signed upload URL:', error)
    throw new Error(`Failed to create upload URL: ${error.message}`)
  }

  if (!data?.signedUrl) {
    throw new Error('Failed to create upload URL: no URL returned')
  }

  return normalizeStorageUrl(data.signedUrl)
}

/**
 * Check if a JAR file exists in storage
 */
export async function jarExists(
  supabase: SupabaseClient,
  jarPath: string
): Promise<boolean> {
  const { data, error } = await supabase
    .storage
    .from(BUCKET_NAME)
    .list(jarPath.split('/').slice(0, -1).join('/'), {
      limit: 1,
      search: jarPath.split('/').pop()
    })

  if (error) {
    console.error('Error checking JAR existence:', error)
    return false
  }

  return data && data.length > 0
}

/**
 * Delete a JAR file from storage
 */
export async function deleteJar(
  supabase: SupabaseClient,
  jarPath: string
): Promise<void> {
  const { error } = await supabase
    .storage
    .from(BUCKET_NAME)
    .remove([jarPath])

  if (error) {
    console.error('Error deleting JAR:', error)
    throw new Error(`Failed to delete JAR: ${error.message}`)
  }
}

/**
 * Generate the storage path for a plugin JAR
 */
export function generateJarPath(pluginId: string, version: string): string {
  // Path format: plugins/{pluginId}/{version}/{pluginId}-{version}.jar
  return `plugins/${pluginId}/${version}/${pluginId}-${version}.jar`
}

/**
 * Get the public URL for a JAR (if bucket is public)
 */
export function getPublicUrl(
  supabase: SupabaseClient,
  jarPath: string
): string {
  const { data } = supabase
    .storage
    .from(BUCKET_NAME)
    .getPublicUrl(jarPath)

  return data.publicUrl
}

/**
 * Upload a JAR file directly to storage
 */
export async function uploadJar(
  supabase: SupabaseClient,
  jarPath: string,
  jarData: ArrayBuffer
): Promise<void> {
  const { error } = await supabase
    .storage
    .from(BUCKET_NAME)
    .upload(jarPath, jarData, {
      contentType: 'application/java-archive',
      upsert: true // Allow overwriting if file exists
    })

  if (error) {
    console.error('Error uploading JAR:', error)
    throw new Error(`Failed to upload JAR: ${error.message}`)
  }
}
