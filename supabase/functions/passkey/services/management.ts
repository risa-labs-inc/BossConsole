import type { SupabaseClient } from "@supabase/supabase-js"
import { getUserPasskeys } from "../utils/database.ts"
import { withErrorHandler } from "../utils/error-handler.ts"

/**
 * Lists all active passkeys for a user
 */
export const listUserPasskeys = withErrorHandler(
  async (supabase: SupabaseClient, userId: string) => {
    console.log('📋 Listing passkeys for user:', userId)

    const result = await getUserPasskeys(supabase, userId)

    if (!result.success) {
      return {
        success: false,
        error: result.error || 'Failed to fetch passkeys'
      }
    }

    const passkeys = result.passkeys || []

    // Return only necessary fields (keeping snake_case for consistency with DB)
    const sanitizedPasskeys = passkeys.map(pk => ({
      id: pk.id,
      credential_id: pk.credential_id,
      display_name: pk.display_name,
      created_at: pk.created_at,
      last_used_at: pk.last_used_at,
      transports: pk.transports || []
    }))

    return {
      success: true,
      passkeys: sanitizedPasskeys
    }
  },
  'Failed to list passkeys',
  '📋'
)

/**
 * Deletes a passkey for a user
 */
export const deleteUserPasskey = withErrorHandler(
  async (supabase: SupabaseClient, userId: string, passkeyId: string) => {
    console.log('🗑️ Deleting passkey:', passkeyId, 'for user:', userId)

    // First verify the passkey belongs to the user
    const { data: passkey, error: fetchError } = await supabase
      .from('user_passkeys')
      .select('*')
      .eq('id', passkeyId)
      .eq('user_id', userId)
      .single()

    if (fetchError || !passkey) {
      return {
        success: false,
        error: 'Passkey not found or access denied'
      }
    }

    // Soft delete by marking as inactive
    const { error: deleteError } = await supabase
      .from('user_passkeys')
      .update({ active: false })
      .eq('id', passkeyId)

    if (deleteError) {
      console.error('🗑️ Error deleting passkey:', deleteError)
      return {
        success: false,
        error: 'Failed to delete passkey'
      }
    }

    console.log('✅ Passkey deleted successfully')

    return {
      success: true
    }
  },
  'Failed to delete passkey',
  '🗑️'
)

/**
 * Updates a passkey's display name
 */
export const updatePasskeyDisplayName = withErrorHandler(
  async (
    supabase: SupabaseClient,
    userId: string,
    passkeyId: string,
    displayName: string
  ) => {
    console.log('✏️ Updating passkey display name:', passkeyId)

    // First verify the passkey belongs to the user
    const { data: passkey, error: fetchError } = await supabase
      .from('user_passkeys')
      .select('*')
      .eq('id', passkeyId)
      .eq('user_id', userId)
      .single()

    if (fetchError || !passkey) {
      return {
        success: false,
        error: 'Passkey not found or access denied'
      }
    }

    // Update display name
    const { error: updateError } = await supabase
      .from('user_passkeys')
      .update({ display_name: displayName })
      .eq('id', passkeyId)

    if (updateError) {
      console.error('✏️ Error updating passkey:', updateError)
      return {
        success: false,
        error: 'Failed to update passkey'
      }
    }

    console.log('✅ Passkey updated successfully')

    return {
      success: true
    }
  },
  'Failed to update passkey',
  '✏️'
)
