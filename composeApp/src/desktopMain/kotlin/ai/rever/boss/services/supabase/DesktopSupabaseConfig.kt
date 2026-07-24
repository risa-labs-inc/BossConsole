package ai.rever.boss.services.supabase

import ai.rever.boss.config.SupabaseClientConfig

/**
 * Desktop implementation of Supabase URL configuration
 * Uses ConfigLoader which checks:
 * 1. Environment variable
 * 2. System property
 * 3. local.properties file
 * 4. Fallback to production default
 */
actual fun getSupabaseUrl(): String = SupabaseClientConfig.url

/**
 * Desktop implementation of Supabase anonymous key configuration
 * Uses ConfigLoader which checks:
 * 1. Environment variable
 * 2. System property
 * 3. local.properties file
 * 4. Fallback to production default
 */
actual fun getSupabaseAnonKey(): String = SupabaseClientConfig.anonKey

/**
 * Desktop implementation of Supabase Functions URL configuration
 * Uses ConfigLoader which checks:
 * 1. Environment variable
 * 2. System property
 * 3. local.properties file
 * 4. Fallback to production default
 */
actual fun getSupabaseFunctionUrl(): String = SupabaseClientConfig.functionUrl
